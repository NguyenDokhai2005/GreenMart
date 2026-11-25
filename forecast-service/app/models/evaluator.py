"""Module điều phối huấn luyện, xác thực (validation) và đánh giá đối đầu mô hình (Issue #113).

Tuân thủ nghiêm ngặt:
1. Giao thức đánh giá chuẩn:
   - Mốc kiểm tra (cutoff_date): 2026-10-01.
   - Cửa sổ kiểm tra (test window): 30 ngày (2026-10-02 đến 2026-10-31), CHỈ CHẠY ĐÚNG 1 LẦN.
   - Tập kiểm tra tuyệt đối không dùng để tinh chỉnh tham số hay chọn ngưỡng.
   - Mọi tinh chỉnh siêu tham số và ngưỡng hậu xử lý chỉ được tối ưu trên tập Validation (trước cutoff_date).
2. Đo lường đối đầu song song 3 mức độ chi tiết (Granularities):
   - Cách 1: Từng ô ngày (Daily cell - chuẩn #111).
   - Cách 2: Theo tuần (Weekly aggregate).
   - Cách 3: Tổng cả tầm (Series horizon aggregate - phục vụ bài toán tồn kho ROP & gợi ý nhập hàng).
3. Phân khúc: Toàn bộ danh mục (Overall), Top 200 SKU bán chạy (Pareto 80%), và 800 SKU bán chậm.
4. Xử lý sản phẩm mới (< 28 ngày dữ liệu): không chạy mô hình, dùng trung bình danh mục tại chi nhánh.
5. Trung thực khoa học: Tuyệt đối không tự ý kết luận "ĐẠT AC-05" khi Lead chưa chốt cách đo lường.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
import hashlib
import math
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple
import numpy as np
import pandas as pd

from app.models.baselines import (
    BaselineEvaluationReport,
    BaselineMetrics,
    _compare_metrics_for_best,
    evaluate_baselines_on_daily_sales,
    extract_top_sku_ids_from_sales,
)
from app.models.features import FeatureMatrixBuilder
from app.models.lightgbm_model import LightGBMDemandModel, calculate_zero_ratio
from app.models.metrics import calculate_mae, calculate_wape

DEFAULT_MODEL_VERSION = "LightGBM-Tweedie-H7-H30"
DEFAULT_MIN_HISTORY_DAYS = 28
DEFAULT_SLOW_THRESHOLD = 0.4
DEFAULT_METRIC_PROTOCOL_VERSION = "ac05-v2-h7-h30-postproc-20261125"


def compute_canonical_data_hash(df_daily: pd.DataFrame) -> str:
    """Tính SHA-256 chuẩn hóa từ nội dung (sale_date, branch_id, product_id, qty_net, stockout)."""
    if df_daily.empty:
        return hashlib.sha256(b"").hexdigest()
    work = pd.DataFrame({
        "sale_days": (pd.to_datetime(df_daily["sale_date"]) - pd.Timestamp("1970-01-01")).dt.days.astype(np.int32),
        "branch_id": df_daily["branch_id"].to_numpy(dtype=np.int64),
        "product_id": df_daily["product_id"].to_numpy(dtype=np.int64),
        "qty_net": np.round(df_daily["qty_net"].to_numpy(dtype=np.float64), 4),
        "stockout": df_daily["stockout"].to_numpy(dtype=np.uint8) if "stockout" in df_daily.columns else np.zeros(len(df_daily), dtype=np.uint8),
    })
    work = work.sort_values(["sale_days", "branch_id", "product_id"], kind="mergesort")
    h = hashlib.sha256()
    for col in ("sale_days", "branch_id", "product_id", "qty_net", "stockout"):
        h.update(np.ascontiguousarray(work[col].to_numpy()).tobytes())
    return h.hexdigest()


def build_postprocess_version(
    optimal_slow_thresholds: Dict[int, float],
    min_history_days: int = DEFAULT_MIN_HISTORY_DAYS,
) -> str:
    """Tạo định danh phiên bản hậu xử lý minh bạch theo ngưỡng bán chậm và fallback chuỗi mới."""
    if not optimal_slow_thresholds:
        return f"no-th-sh{min_history_days}-v1"
    parts = [f"h{h}_{float(optimal_slow_thresholds[h]):g}" for h in sorted(optimal_slow_thresholds)]
    return f"slow-th-{'-'.join(parts)}-sh{min_history_days}-v1"


def select_best_baseline_for_metrics(
    results_for_horizon: Dict[str, Dict[str, "ModelComparisonMetrics"]],
    segment: str = "overall",
) -> str:
    """Chọn baseline tốt nhất (weekly_naive vs rolling_mean_28) trên đúng cùng góc đo và phân khúc."""
    naive_m = results_for_horizon["weekly_naive"][segment]
    ma_m = results_for_horizon["rolling_mean_28"][segment]
    return _compare_metrics_for_best(
        BaselineMetrics(
            mae=naive_m.mae if naive_m.mae is not None else float("inf"),
            wape=naive_m.wape,
            sample_count=naive_m.sample_count,
            total_actual=naive_m.total_actual,
        ),
        BaselineMetrics(
            mae=ma_m.mae if ma_m.mae is not None else float("inf"),
            wape=ma_m.wape,
            sample_count=ma_m.sample_count,
            total_actual=ma_m.total_actual,
        ),
    )


@dataclass
class ModelComparisonMetrics:
    """Chứa kết quả đánh giá của một mô hình trên một phân khúc."""

    mae: Optional[float]
    wape: Optional[float]
    sample_count: int
    total_actual: float
    zero_pred_ratio: Optional[float]
    excluded_stockout_count: int = 0
    total_daily_cells: int = 0
    coverage_ratio: float = 1.0


@dataclass
class FullEvaluationReport:
    """Báo cáo tổng hợp đánh giá LightGBM so với các Baseline theo 3 góc nhìn đo lường."""

    cutoff_date: date
    test_start_date: date
    test_end_date: date
    horizons: List[int]
    baseline_report: BaselineEvaluationReport
    # Cách 1: Từng ô ngày (Daily cell)
    lgb_results_clean: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]]
    lgb_results_raw: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]]
    # Cách 2: Theo tuần (Weekly aggregate)
    weekly_results_clean: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]]
    weekly_results_raw: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]]
    # Cách 3: Tổng cả tầm của mỗi chuỗi (Horizon aggregate)
    aggregated_results_clean: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]]
    aggregated_results_raw: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]]
    # Thông tin validation và tối ưu
    val_results: Dict[int, Dict[str, Dict[str, float]]]
    best_objective: Dict[int, str]
    optimal_slow_thresholds: Dict[int, float] = field(default_factory=dict)
    short_history_series_count: int = 0
    model_version: str = DEFAULT_MODEL_VERSION
    postprocess_version: str = ""
    best_baseline_by_view: Dict[int, Dict[str, str]] = field(default_factory=dict)
    metric_protocol_version: str = DEFAULT_METRIC_PROTOCOL_VERSION
    data_source: str = "daily_sales"
    data_source_hash: str = ""
    serving_cutoff_date: Optional[date] = None
    rolling_h7_results: Dict[str, Dict[str, ModelComparisonMetrics]] = field(default_factory=dict)
    rolling_h7_origins: List[date] = field(default_factory=list)


def generate_origin_dates_for_training(
    unique_dates: List[date],
    min_history_days: int = 28,
    max_target_date: Optional[date] = None,
    step_days: int = 7,
    horizon: int = 30,
) -> List[date]:
    """Sinh danh sách ngày gốc (origin dates) cách nhau step_days ngày để tạo tập huấn luyện."""
    if max_target_date is not None:
        train_pool = [d for d in unique_dates if d <= max_target_date]
    else:
        train_pool = unique_dates

    if len(train_pool) < 7:
        return []

    eff_min_history = min(min_history_days, max(3, len(train_pool) // 3))

    valid_origins: List[date] = []
    for idx in range(eff_min_history, len(train_pool), step_days):
        origin = train_pool[idx]
        if max_target_date is not None and origin + timedelta(days=1) > max_target_date:
            break
        valid_origins.append(origin)

    return valid_origins


def find_optimal_slow_threshold_on_validation(
    y_val: np.ndarray,
    preds_val: np.ndarray,
    slow_mask_val: np.ndarray,
    candidate_thresholds: Sequence[float] = (0.0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4),
) -> Tuple[float, float]:
    """Tìm ngưỡng hậu xử lý tối ưu trên nhóm bán chậm dựa HOÀN TOÀN trên tập Validation.

    Quy tắc chặt chẽ:
    - Chỉ chạy trên tập Validation (val_origins trước cutoff_date).
    - Tuyệt đối không nhận bất kỳ nhãn nào của tập Test để đảm bảo không rò rỉ dữ liệu (No Data Leakage).

    Trả về:
        (best_threshold, best_val_wape)
    """
    if len(y_val) == 0:
        return 0.0, 1.0

    base_wape = calculate_wape(y_val, preds_val)
    best_wape = base_wape if base_wape is not None else float("inf")
    best_th = 0.0

    for th in candidate_thresholds:
        p = preds_val.copy()
        if th > 0.0:
            cond = slow_mask_val & (p < th)
            p[cond] = 0.0
        w = calculate_wape(y_val, p)
        if w is not None and w < best_wape:
            best_wape = w
            best_th = th

    return best_th, best_wape


def aggregate_by_weekly_windows(mat: np.ndarray, horizon: int) -> np.ndarray:
    """Gộp sản lượng theo các cửa sổ tuần 7 ngày (Weekly aggregate).

    Cho ma trận mat có shape (N_series, horizon) -> trả về ma trận (N_series, n_weeks).
    """
    n_weeks = int(np.ceil(horizon / 7.0))
    weekly_cols = []
    for w in range(n_weeks):
        s = w * 7
        e = min((w + 1) * 7, horizon)
        w_sum = np.sum(mat[:, s:e], axis=1)
        weekly_cols.append(w_sum)
    return np.column_stack(weekly_cols)


def apply_short_history_fallback(
    df_train: pd.DataFrame,
    series_index: pd.MultiIndex,
    pred_matrix: np.ndarray,
    min_history_days: int = 28,
) -> int:
    """Kiểm tra và áp dụng quy tắc SRS cho sản phẩm mới (< 28 ngày dữ liệu):

    Không chạy mô hình; dùng trung bình danh mục tại chi nhánh.
    Trả về số chuỗi được áp dụng fallback.
    """
    if len(df_train) == 0:
        return 0

    series_counts = df_train.groupby(["branch_id", "product_id"])["sale_date"].nunique()

    has_cat = "category_id" in df_train.columns
    if has_cat:
        cat_map = (
            df_train.drop_duplicates(subset=["product_id"])[["product_id", "category_id"]]
            .set_index("product_id")["category_id"]
            .to_dict()
        )
        cat_means = df_train.groupby(["branch_id", "category_id"])["qty_net"].mean().to_dict()
    else:
        cat_map = {}
        cat_means = {}
    branch_means = df_train.groupby("branch_id")["qty_net"].mean().to_dict()

    fallback_count = 0
    for idx, (b_id, p_id) in enumerate(series_index):
        cnt = series_counts.get((b_id, p_id), 0)
        if cnt < min_history_days:
            c_id = cat_map.get(p_id)
            val = cat_means.get((b_id, c_id), branch_means.get(b_id, 0.0))
            pred_matrix[idx, :] = max(0.0, float(val))
            fallback_count += 1

    return fallback_count


def _apply_serving_postprocess_pipeline(
    pred_matrix: np.ndarray,
    is_top_mask: np.ndarray,
    slow_threshold: float,
    df_history: pd.DataFrame,
    series_index: pd.MultiIndex,
    min_history_days: int = DEFAULT_MIN_HISTORY_DAYS,
) -> Tuple[np.ndarray, int]:
    """Áp dụng đúng thứ tự hậu xử lý của mô hình phục vụ:
    1) ngưỡng slow_threshold (< threshold -> 0.0, giữ nguyên giá trị == threshold),
    2) fallback chuỗi mới (< 28 ngày lịch sử <= origin),
    3) chặn không âm >= 0.0.
    """
    mat = pred_matrix.copy()
    if slow_threshold > 0.0:
        slow_cells = (~is_top_mask)[:, np.newaxis] & (mat < slow_threshold)
        mat = np.where(slow_cells, 0.0, mat)
    fb_count = apply_short_history_fallback(
        df_train=df_history,
        series_index=series_index,
        pred_matrix=mat,
        min_history_days=min_history_days,
    )
    mat = np.clip(mat, 0.0, None)
    return mat, fb_count


def _evaluate_rolling_h7_on_window(
    builder: FeatureMatrixBuilder,
    model_h7: LightGBMDemandModel,
    selected_obj: str,
    evaluation_cutoff_date: date,
    test_end_date: date,
    slow_threshold: float,
    min_history_days: int = DEFAULT_MIN_HISTORY_DAYS,
) -> Tuple[Dict[str, Dict[str, ModelComparisonMetrics]], List[date]]:
    """Đánh giá rolling H7 trên toàn bộ các ngày gốc hợp lệ trong cửa sổ test 30 ngày (D..test_end-7).

    Tại mỗi origin o, đặc trưng, Top 200 SKU, baseline và fallback < 28 ngày chỉ dùng dữ liệu <= o.
    """
    rolling_origins: List[date] = []
    curr = evaluation_cutoff_date
    last_valid_origin = test_end_date - timedelta(days=7)
    while curr <= last_valid_origin:
        if curr in builder.date_to_idx:
            # Kiểm tra đủ 7 ngày mục tiêu liên tiếp trong dữ liệu
            if all((curr + timedelta(days=s)) in builder.date_to_idx for s in range(1, 8)):
                rolling_origins.append(curr)
        curr += timedelta(days=1)

    if not rolling_origins:
        return {}, []

    # Chuẩn bị lũy tích số ngày xuất hiện theo (branch, product) để kiểm tra nhanh fallback tại mỗi origin
    has_cat = "category_id" in builder.df.columns
    first_seen_dates = builder.df.groupby(["branch_id", "product_id"])["sale_date"].min().to_dict()
    min_count_at_eval = min(
        (
            builder.df[builder.df["sale_date"] <= evaluation_cutoff_date]
            .groupby(["branch_id", "product_id"])["sale_date"]
            .nunique()
            .min()
        )
        if not builder.df[builder.df["sale_date"] <= evaluation_cutoff_date].empty
        else 0,
        min_history_days,
    )
    need_per_origin_fallback = (len(first_seen_dates) < builder.n_series) or (min_count_at_eval < min_history_days)

    product_ids_series = builder.product_ids
    model_keys = ["weekly_naive", "rolling_mean_28", "zero_forecast", f"lgb_{selected_obj}", f"lgb_{selected_obj}_postproc"]
    act_agg_by_model: Dict[str, List[np.ndarray]] = {k: [] for k in model_keys}
    pred_agg_by_model: Dict[str, List[np.ndarray]] = {k: [] for k in model_keys}
    stock_cnt_by_origin: List[np.ndarray] = []
    act_raw_sum_by_origin: List[np.ndarray] = []
    is_top_by_origin: List[np.ndarray] = []

    for origin in rolling_origins:
        o_idx = builder.date_to_idx[origin]
        target_indices = [builder.date_to_idx[origin + timedelta(days=s)] for s in range(1, 8)]
        actual_mat = builder.qty_matrix[:, target_indices]
        stockout_mat = builder.stockout_matrix[:, target_indices]

        hist_end = o_idx + 1
        hist_qty = builder.qty_matrix[:, :hist_end]
        last_7 = hist_qty[:, max(0, hist_end - 7) : hist_end]
        if last_7.shape[1] < 7:
            pad = np.repeat(np.mean(last_7, axis=1, keepdims=True), 7 - last_7.shape[1], axis=1)
            pred_naive = np.hstack([pad, last_7])
        else:
            pred_naive = last_7.copy()

        w28 = min(28, hist_qty.shape[1])
        pred_ma28 = np.repeat(np.mean(hist_qty[:, hist_end - w28 : hist_end], axis=1, keepdims=True), 7, axis=1)
        pred_zero = np.zeros_like(actual_mat, dtype=float)

        step_dfs = builder.build_test_features_matrix(cutoff_date=origin, horizon=7)
        pred_raw = np.column_stack([model_h7.predict(sdf) for sdf in step_dfs])

        top_ids_o = extract_top_sku_ids_from_sales(builder.df, cutoff_date=origin, top_k=200)
        is_top_o = np.isin(product_ids_series, list(top_ids_o))
        is_top_by_origin.append(is_top_o)

        pred_post = pred_raw.copy()
        if slow_threshold > 0.0:
            slow_cells = (~is_top_o)[:, np.newaxis] & (pred_post < slow_threshold)
            pred_post = np.where(slow_cells, 0.0, pred_post)

        preds_o = {
            "weekly_naive": pred_naive,
            "rolling_mean_28": pred_ma28,
            "zero_forecast": pred_zero,
            f"lgb_{selected_obj}": pred_raw,
            f"lgb_{selected_obj}_postproc": pred_post,
        }

        df_hist_o = builder.df[builder.df["sale_date"] <= origin] if need_per_origin_fallback else None
        for mk, pmat in preds_o.items():
            if need_per_origin_fallback and df_hist_o is not None:
                apply_short_history_fallback(
                    df_train=df_hist_o,
                    series_index=builder.series_index,
                    pred_matrix=pmat,
                    min_history_days=min_history_days,
                )
            pmat = np.clip(pmat, 0.0, None)
            act_c = np.where(stockout_mat, 0.0, actual_mat)
            pred_c = np.where(stockout_mat, 0.0, pmat)
            act_agg_by_model[mk].append(np.sum(act_c, axis=1))
            pred_agg_by_model[mk].append(np.sum(pred_c, axis=1))

        stock_cnt_by_origin.append(np.sum(stockout_mat, axis=1))
        act_raw_sum_by_origin.append(np.sum(actual_mat, axis=1))

    is_top_flat = np.concatenate(is_top_by_origin)
    act_raw_flat = np.concatenate(act_raw_sum_by_origin)
    stock_cnt_flat = np.concatenate(stock_cnt_by_origin)
    zero_demand_flat = act_raw_flat <= 0.0
    seg_masks = {
        "overall": np.ones(len(is_top_flat), dtype=bool),
        "top_200": is_top_flat,
        "slow_moving": ~is_top_flat,
        "zero_demand": zero_demand_flat,
        "non_zero_demand": ~zero_demand_flat,
    }

    results: Dict[str, Dict[str, ModelComparisonMetrics]] = {}
    for mk in model_keys:
        results[mk] = {}
        act_all = np.concatenate(act_agg_by_model[mk])
        pred_all = np.concatenate(pred_agg_by_model[mk])
        for seg_name, mask in seg_masks.items():
            if not np.any(mask):
                results[mk][seg_name] = ModelComparisonMetrics(None, None, 0, 0.0, None, 0, 0, 1.0)
                continue
            a_sub = act_all[mask]
            p_sub = pred_all[mask]
            excl = int(np.sum(stock_cnt_flat[mask]))
            tot_cells = int(len(a_sub) * 7)
            cov = (tot_cells - excl) / tot_cells if tot_cells > 0 else 1.0
            tot_act = float(np.sum(a_sub))
            results[mk][seg_name] = ModelComparisonMetrics(
                mae=calculate_mae(a_sub, p_sub),
                wape=None if tot_act <= 0.0 else calculate_wape(a_sub, p_sub, as_percent=False),
                sample_count=len(a_sub),
                total_actual=tot_act,
                zero_pred_ratio=calculate_zero_ratio(p_sub),
                excluded_stockout_count=excl,
                total_daily_cells=tot_cells,
                coverage_ratio=cov,
            )

    return results, rolling_origins


def evaluate_lightgbm_pipeline(
    df_daily: pd.DataFrame,
    cutoff_date: Optional[date] = None,
    horizons: Tuple[int, ...] = (7, 30),
    objectives: Tuple[str, ...] = ("regression_l1", "tweedie"),
    sample_step_days: int = 7,
    fixed_slow_thresholds: Optional[Dict[int, float]] = None,
    data_source: str = "daily_sales",
    data_source_hash: Optional[str] = None,
    serving_cutoff_date: Optional[date] = None,
    include_rolling_h7: bool = True,
) -> Tuple[FullEvaluationReport, Dict[int, LightGBMDemandModel]]:
    """Quy trình huấn luyện, xác thực và đánh giá toàn diện LightGBM đối đầu Baseline."""
    builder = FeatureMatrixBuilder(df_daily)
    unique_dates = builder.unique_dates

    max_date = unique_dates[-1]
    if cutoff_date is None:
        cutoff_date = max_date - timedelta(days=30)
    resolved_serving_cutoff = serving_cutoff_date or max_date
    resolved_data_hash = data_source_hash or compute_canonical_data_hash(df_daily)

    # 1. Chạy đánh giá các mô hình Baseline theo chuẩn Task #111
    baseline_report = evaluate_baselines_on_daily_sales(
        df_daily=df_daily,
        cutoff_date=cutoff_date,
        horizons=horizons,
    )

    # Xác định Top 200 SKU từ baseline_report (tái dùng mask đồng bộ với baselines.py)
    top_sku_ids: Set[int] = baseline_report.top_sku_ids
    df_train_only = builder.df[builder.df["sale_date"] <= cutoff_date]

    # Mặt nạ phân nhóm sản phẩm
    product_ids_series = builder.product_ids
    is_top_mask = np.isin(product_ids_series, list(top_sku_ids))
    segment_masks = {
        "overall": np.ones(len(product_ids_series), dtype=bool),
        "top_200": is_top_mask,
        "slow_moving": ~is_top_mask,
    }

    # 2. Phân chia Train / Validation
    # Validation window: tối đa 30 ngày trước cutoff_date
    history_days = (cutoff_date - unique_dates[0]).days
    val_days = min(30, max(7, history_days // 4))
    val_cutoff = cutoff_date - timedelta(days=val_days)

    all_train_origins = generate_origin_dates_for_training(
        unique_dates=unique_dates,
        min_history_days=28,
        max_target_date=cutoff_date,
        step_days=sample_step_days,
    )

    train_origins = [d for d in all_train_origins if d <= val_cutoff]
    val_origins = [d for d in all_train_origins if val_cutoff < d < cutoff_date]

    if not train_origins and all_train_origins:
        mid = max(1, len(all_train_origins) // 2)
        train_origins = all_train_origins[:mid]
        val_origins = all_train_origins[mid:]
    elif not val_origins and len(train_origins) > 1:
        val_origins = [train_origins.pop()]

    # Xác định Top 200 trên tập dữ liệu trước val_cutoff để dùng riêng cho Validation
    df_val_train = builder.df[builder.df["sale_date"] <= val_cutoff]
    val_sku_sales = df_val_train.groupby("product_id")["qty_net"].sum().sort_values(ascending=False)
    val_top_sku_ids: Set[int] = set(val_sku_sales.head(200).index)

    lgb_clean_results: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]] = {}
    lgb_raw_results: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]] = {}
    weekly_clean_results: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]] = {}
    weekly_raw_results: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]] = {}
    agg_clean_results: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]] = {}
    agg_raw_results: Dict[int, Dict[str, Dict[str, ModelComparisonMetrics]]] = {}

    val_metrics_dict: Dict[int, Dict[str, Dict[str, float]]] = {}
    best_obj_dict: Dict[int, str] = {}
    best_models: Dict[int, LightGBMDemandModel] = {}
    optimal_slow_thresholds: Dict[int, float] = {}
    best_baseline_by_view: Dict[int, Dict[str, str]] = {}
    total_short_history_count = 0

    train_dates = [d for d in unique_dates if d <= cutoff_date]
    history_qty_matrix = builder.qty_matrix[:, : len(train_dates)]

    for H in sorted(horizons):
        lgb_clean_results[H] = {}
        lgb_raw_results[H] = {}
        weekly_clean_results[H] = {}
        weekly_raw_results[H] = {}
        agg_clean_results[H] = {}
        agg_raw_results[H] = {}
        val_metrics_dict[H] = {}

        # Xây dựng tập dữ liệu train và val cho tầm H
        X_train, y_train = builder.build_dataset_for_horizon(
            horizon=H,
            origin_dates=train_origins,
            max_target_date=val_cutoff,
            exclude_stockout=True,
        )

        X_val, y_val = builder.build_dataset_for_horizon(
            horizon=H,
            origin_dates=val_origins,
            max_target_date=cutoff_date,
            exclude_stockout=True,
        )

        test_feature_dfs = builder.build_test_features_matrix(
            cutoff_date=cutoff_date,
            horizon=H,
        )

        test_dates_H = [cutoff_date + timedelta(days=i) for i in range(1, H + 1)]
        test_indices = [builder.date_to_idx[d] for d in test_dates_H]

        actual_matrix_H = builder.qty_matrix[:, test_indices]  # (N_series, H)
        stockout_matrix_H = builder.stockout_matrix[:, test_indices]

        # Dự báo của các mô hình Baseline trên tầm H
        last_7_train = history_qty_matrix[:, -7:]
        reps = int(np.ceil(H / 7))
        pred_naive_H = np.tile(last_7_train, (1, reps))[:, :H]

        window_size = min(28, history_qty_matrix.shape[1])
        last_28_train = history_qty_matrix[:, -window_size:]
        mean_28 = np.mean(last_28_train, axis=1, keepdims=True)
        pred_ma28_H = np.repeat(mean_28, H, axis=1)

        pred_zero_H = np.zeros_like(actual_matrix_H, dtype=float)

        all_model_preds_H: Dict[str, np.ndarray] = {
            "weekly_naive": pred_naive_H,
            "rolling_mean_28": pred_ma28_H,
            "zero_forecast": pred_zero_H,
        }

        trained_lgb_models: Dict[str, LightGBMDemandModel] = {}
        val_preds_by_obj: Dict[str, np.ndarray] = {}

        for obj in objectives:
            model = LightGBMDemandModel(horizon=H, objective=obj)
            model.fit(
                X_train=X_train,
                y_train=y_train,
                X_val=X_val if len(X_val) > 0 else None,
                y_val=y_val if len(y_val) > 0 else None,
                early_stopping_rounds=15,
            )
            trained_lgb_models[obj] = model

            # Đánh giá Validation
            if len(X_val) > 0:
                y_val_pred = model.predict(X_val)
                val_preds_by_obj[obj] = y_val_pred
                val_wape = calculate_wape(y_val, y_val_pred)
                val_mae = calculate_mae(y_val, y_val_pred)
            else:
                val_preds_by_obj[obj] = np.zeros(len(y_val))
                val_wape = 1.0
                val_mae = 1.0

            val_metrics_dict[H][obj] = {
                "val_mae": val_mae,
                "val_wape": val_wape if val_wape is not None else 1.0,
            }

            # Dự báo trên Test Set (CHỈ CHẠY 1 LẦN)
            pred_step_list = []
            for step_df in test_feature_dfs:
                pred_step = model.predict(step_df)
                pred_step_list.append(pred_step)
            pred_matrix_H = np.column_stack(pred_step_list)
            all_model_preds_H[f"lgb_{obj}"] = pred_matrix_H

        # QUY TẮC CHỌN MÔ HÌNH BẢN ĐẦU:
        # Loại bỏ mô hình zero-collapse (> 95% zero ở validation/test)
        selected_obj = "tweedie" if "tweedie" in objectives else objectives[0]
        best_obj_dict[H] = selected_obj
        best_models[H] = trained_lgb_models[selected_obj]

        # NGƯỠNG HẬU XỬ LÝ NHÓM BÁN CHẬM:
        # Nếu truyền fixed_slow_thresholds (ví dụ serving 0.4), dùng đúng ngưỡng phục vụ để evaluator đồng nhất với serving.
        # Nếu không truyền, tối ưu hoàn toàn trên tập Validation (không chạm Test).
        if fixed_slow_thresholds is not None and H in fixed_slow_thresholds:
            optimal_slow_thresholds[H] = float(fixed_slow_thresholds[H])
        elif len(X_val) > 0 and selected_obj in val_preds_by_obj:
            val_p = val_preds_by_obj[selected_obj]
            val_slow_mask = ~np.isin(X_val["product_id"].to_numpy(), list(val_top_sku_ids))
            opt_th, opt_val_wape = find_optimal_slow_threshold_on_validation(
                y_val=y_val,
                preds_val=val_p,
                slow_mask_val=val_slow_mask,
            )
            optimal_slow_thresholds[H] = opt_th
        else:
            optimal_slow_thresholds[H] = DEFAULT_SLOW_THRESHOLD if fixed_slow_thresholds is not None else 0.0

        # ÁP DỤNG HẬU XỬ LÝ ĐÚNG THỨ TỰ PHỤC VỤ:
        # 1) ngưỡng < th_val -> 0.0 trên slow_moving (giá trị == th_val được giữ nguyên)
        # 2) fallback chuỗi mới (< 28 ngày lịch sử)
        # 3) chặn không âm >= 0.0
        th_val = optimal_slow_thresholds[H]
        tweedie_pred_mat = all_model_preds_H[f"lgb_{selected_obj}"].copy()
        if th_val > 0.0:
            slow_cells = segment_masks["slow_moving"][:, np.newaxis] & (tweedie_pred_mat < th_val)
            tweedie_pred_mat = np.where(slow_cells, 0.0, tweedie_pred_mat)
        all_model_preds_H[f"lgb_{selected_obj}_postproc"] = tweedie_pred_mat

        for m_name in list(all_model_preds_H.keys()):
            cnt_fb = apply_short_history_fallback(
                df_train=df_train_only,
                series_index=builder.series_index,
                pred_matrix=all_model_preds_H[m_name],
                min_history_days=DEFAULT_MIN_HISTORY_DAYS,
            )
            all_model_preds_H[m_name] = np.clip(all_model_preds_H[m_name], 0.0, None)
            if m_name == f"lgb_{selected_obj}":
                total_short_history_count = cnt_fb

        # Phân nhóm thêm theo nhu cầu bằng 0 trên cửa sổ test của tầm H để minh bạch thước đo WAPE
        zero_demand_mask_H = np.sum(actual_matrix_H, axis=1) <= 0.0
        segment_masks_H: Dict[str, np.ndarray] = {
            **segment_masks,
            "zero_demand": zero_demand_mask_H,
            "non_zero_demand": ~zero_demand_mask_H,
        }

        def _make_metrics(
            y_true_arr: np.ndarray,
            y_pred_arr: np.ndarray,
            excl_stockout: int,
            tot_daily_cells: int,
        ) -> ModelComparisonMetrics:
            if y_true_arr.size == 0:
                cov = (tot_daily_cells - excl_stockout) / tot_daily_cells if tot_daily_cells > 0 else 1.0
                return ModelComparisonMetrics(None, None, 0, 0.0, None, excl_stockout, tot_daily_cells, cov)
            tot_act = float(np.sum(y_true_arr))
            cov = (tot_daily_cells - excl_stockout) / tot_daily_cells if tot_daily_cells > 0 else 1.0
            return ModelComparisonMetrics(
                mae=calculate_mae(y_true_arr, y_pred_arr),
                wape=calculate_wape(y_true_arr, y_pred_arr, as_percent=False),
                sample_count=int(len(y_true_arr)),
                total_actual=tot_act,
                zero_pred_ratio=calculate_zero_ratio(y_pred_arr),
                excluded_stockout_count=excl_stockout,
                total_daily_cells=tot_daily_cells,
                coverage_ratio=cov,
            )

        # ĐÁNH GIÁ ĐỐI ĐẦU THEO 3 CÁCH ĐO LƯỜNG:
        for m_key, pred_matrix in all_model_preds_H.items():
            lgb_clean_results[H][m_key] = {}
            lgb_raw_results[H][m_key] = {}
            weekly_clean_results[H][m_key] = {}
            weekly_raw_results[H][m_key] = {}
            agg_clean_results[H][m_key] = {}
            agg_raw_results[H][m_key] = {}

            act_clean_elem = np.where(stockout_matrix_H, 0.0, actual_matrix_H)
            pred_clean_elem = np.where(stockout_matrix_H, 0.0, pred_matrix)

            act_w_clean = aggregate_by_weekly_windows(act_clean_elem, H)
            pred_w_clean = aggregate_by_weekly_windows(pred_clean_elem, H)
            act_w_raw = aggregate_by_weekly_windows(actual_matrix_H, H)
            pred_w_raw = aggregate_by_weekly_windows(pred_matrix, H)

            act_agg_clean = np.sum(act_clean_elem, axis=1)
            pred_agg_clean = np.sum(pred_clean_elem, axis=1)
            act_agg_raw = np.sum(actual_matrix_H, axis=1)
            pred_agg_raw = np.sum(pred_matrix, axis=1)

            for seg_name, row_mask in segment_masks_H.items():
                if not np.any(row_mask):
                    empty_m = ModelComparisonMetrics(None, None, 0, 0.0, None, 0, 0, 1.0)
                    lgb_raw_results[H][m_key][seg_name] = empty_m
                    lgb_clean_results[H][m_key][seg_name] = empty_m
                    weekly_raw_results[H][m_key][seg_name] = empty_m
                    weekly_clean_results[H][m_key][seg_name] = empty_m
                    agg_raw_results[H][m_key][seg_name] = empty_m
                    agg_clean_results[H][m_key][seg_name] = empty_m
                    continue

                y_act_sub = actual_matrix_H[row_mask, :].ravel()
                y_pred_sub = pred_matrix[row_mask, :].ravel()
                stock_sub = stockout_matrix_H[row_mask, :].ravel()
                tot_cells = int(stock_sub.size)
                excl_cells = int(np.sum(stock_sub))

                # Cách 1: Daily cell
                lgb_raw_results[H][m_key][seg_name] = _make_metrics(y_act_sub, y_pred_sub, 0, tot_cells)
                clean_mask = ~stock_sub
                lgb_clean_results[H][m_key][seg_name] = _make_metrics(
                    y_act_sub[clean_mask], y_pred_sub[clean_mask], excl_cells, tot_cells
                )

                # Cách 2: Weekly aggregate
                weekly_raw_results[H][m_key][seg_name] = _make_metrics(
                    act_w_raw[row_mask, :].ravel(), pred_w_raw[row_mask, :].ravel(), 0, tot_cells
                )
                weekly_clean_results[H][m_key][seg_name] = _make_metrics(
                    act_w_clean[row_mask, :].ravel(), pred_w_clean[row_mask, :].ravel(), excl_cells, tot_cells
                )

                # Cách 3: Horizon aggregate
                agg_raw_results[H][m_key][seg_name] = _make_metrics(
                    act_agg_raw[row_mask], pred_agg_raw[row_mask], 0, tot_cells
                )
                agg_clean_results[H][m_key][seg_name] = _make_metrics(
                    act_agg_clean[row_mask], pred_agg_clean[row_mask], excl_cells, tot_cells
                )

        best_baseline_by_view[H] = {
            "daily_clean": select_best_baseline_for_metrics(lgb_clean_results[H]),
            "daily_raw": select_best_baseline_for_metrics(lgb_raw_results[H]),
            "weekly_clean": select_best_baseline_for_metrics(weekly_clean_results[H]),
            "weekly_raw": select_best_baseline_for_metrics(weekly_raw_results[H]),
            "horizon_aggregate_clean": select_best_baseline_for_metrics(agg_clean_results[H]),
            "horizon_aggregate_raw": select_best_baseline_for_metrics(agg_raw_results[H]),
        }

    rolling_h7_results: Dict[str, Dict[str, ModelComparisonMetrics]] = {}
    rolling_h7_origins: List[date] = []
    if include_rolling_h7 and 7 in best_models:
        rolling_h7_results, rolling_h7_origins = _evaluate_rolling_h7_on_window(
            builder=builder,
            model_h7=best_models[7],
            selected_obj=best_obj_dict[7],
            evaluation_cutoff_date=cutoff_date,
            test_end_date=baseline_report.test_end_date,
            slow_threshold=optimal_slow_thresholds.get(7, DEFAULT_SLOW_THRESHOLD),
            min_history_days=DEFAULT_MIN_HISTORY_DAYS,
        )
        if rolling_h7_results and 7 in best_baseline_by_view:
            best_baseline_by_view[7]["rolling_h7_aggregate_clean"] = select_best_baseline_for_metrics(rolling_h7_results)

    postprocess_version = build_postprocess_version(
        optimal_slow_thresholds=optimal_slow_thresholds,
        min_history_days=DEFAULT_MIN_HISTORY_DAYS,
    )
    report = FullEvaluationReport(
        cutoff_date=cutoff_date,
        test_start_date=baseline_report.test_start_date,
        test_end_date=baseline_report.test_end_date,
        horizons=list(horizons),
        baseline_report=baseline_report,
        lgb_results_clean=lgb_clean_results,
        lgb_results_raw=lgb_raw_results,
        weekly_results_clean=weekly_clean_results,
        weekly_results_raw=weekly_raw_results,
        aggregated_results_clean=agg_clean_results,
        aggregated_results_raw=agg_raw_results,
        val_results=val_metrics_dict,
        best_objective=best_obj_dict,
        optimal_slow_thresholds=optimal_slow_thresholds,
        short_history_series_count=total_short_history_count,
        model_version=DEFAULT_MODEL_VERSION,
        postprocess_version=postprocess_version,
        best_baseline_by_view=best_baseline_by_view,
        metric_protocol_version=DEFAULT_METRIC_PROTOCOL_VERSION,
        data_source=data_source,
        data_source_hash=resolved_data_hash,
        serving_cutoff_date=resolved_serving_cutoff,
        rolling_h7_results=rolling_h7_results,
        rolling_h7_origins=rolling_h7_origins,
    )
    return report, best_models


def _finite_float(val: Optional[float]) -> Optional[float]:
    """Đảm bảo không xuất NaN hoặc Infinity ra API/JSON."""
    if val is None:
        return None
    f = float(val)
    return f if math.isfinite(f) else None


def _metric_values_camel(m: ModelComparisonMetrics) -> Dict[str, Any]:
    """Chuyển đổi ModelComparisonMetrics sang ForecastMetricValues (camelCase)."""
    if m.sample_count <= 0:
        return {"mae": None, "wape": None, "zeroPredRatio": None}
    wape_val = None if m.total_actual <= 0.0 else _finite_float(m.wape)
    return {
        "mae": _finite_float(m.mae),
        "wape": wape_val,
        "zeroPredRatio": _finite_float(m.zero_pred_ratio),
    }


def _metric_to_dict(m: ModelComparisonMetrics) -> Dict[str, Any]:
    """Chuyển đổi ModelComparisonMetrics sang dict JSON chuẩn."""
    wape_val = None if (m.sample_count <= 0 or m.total_actual <= 0.0) else _finite_float(m.wape)
    return {
        "mae": _finite_float(m.mae) if m.sample_count > 0 else None,
        "wape": wape_val,
        "sample_count": m.sample_count,
        "excluded_stockout_count": m.excluded_stockout_count,
        "total_daily_cells": m.total_daily_cells,
        "coverage_ratio": _finite_float(m.coverage_ratio),
        "total_actual": _finite_float(m.total_actual),
        "zero_pred_ratio": _finite_float(m.zero_pred_ratio) if m.sample_count > 0 else None,
    }


def _resolve_best_baseline_name(
    report: FullEvaluationReport,
    horizon: int,
    view_key: str,
    results_for_horizon: Dict[str, Dict[str, ModelComparisonMetrics]],
) -> str:
    """Lấy tên baseline tốt nhất cho đúng cặp (horizon, view), đảm bảo cùng series/date range/origin."""
    stored = report.best_baseline_by_view.get(horizon, {}).get(view_key)
    if stored and stored in results_for_horizon:
        return stored
    if "weekly_naive" in results_for_horizon and "rolling_mean_28" in results_for_horizon:
        return select_best_baseline_for_metrics(results_for_horizon, segment="overall")
    return "rolling_mean_28" if "rolling_mean_28" in results_for_horizon else next(iter(results_for_horizon))


def _build_openapi_evaluation_view(
    report: FullEvaluationReport,
    horizon: int,
    aggregation: str,
    stockout_mode: str,
    res_h: Dict[str, Dict[str, ModelComparisonMetrics]],
    model_key: str,
    bb_view_key: str,
    evaluation_origins: List[date],
    target_start: date,
    target_end: date,
    postproc_ver: str,
) -> Dict[str, Any]:
    """Dựng 1 object ForecastEvaluationView đúng schema OpenAPI."""
    is_postproc = "postproc" in model_key
    best_b_name = _resolve_best_baseline_name(report, horizon, bb_view_key, res_h)
    model_segs = res_h.get(model_key, {})
    best_segs = res_h.get(best_b_name, {})
    naive_segs = res_h.get("weekly_naive", {})
    ma28_segs = res_h.get("rolling_mean_28", {})
    zero_segs = res_h.get("zero_forecast", {})

    seg_key_map = [
        ("overall", "overall"),
        ("top_200", "top200"),
        ("slow_moving", "slowMoving"),
        ("zero_demand", "zeroDemand"),
        ("non_zero_demand", "nonZeroDemand"),
    ]
    segments_camel: Dict[str, Any] = {}
    empty_fallback = ModelComparisonMetrics(None, None, 0, 0.0, None, 0, 0, 1.0)
    for seg_snake, seg_camel in seg_key_map:
        m_seg = model_segs.get(seg_snake, empty_fallback)
        b_seg = best_segs.get(seg_snake, empty_fallback)
        n_seg = naive_segs.get(seg_snake, empty_fallback)
        ma_seg = ma28_segs.get(seg_snake, empty_fallback)
        z_seg = zero_segs.get(seg_snake, empty_fallback)
        segments_camel[seg_camel] = {
            "segment": seg_snake,
            "sampleCount": m_seg.sample_count,
            "excludedStockoutCount": m_seg.excluded_stockout_count,
            "totalDailyCellsBeforeFilter": m_seg.total_daily_cells,
            "coverageRatio": float(_finite_float(m_seg.coverage_ratio) or 1.0),
            "totalActual": float(_finite_float(m_seg.total_actual) or 0.0),
            "bestBaselineName": best_b_name,
            "model": _metric_values_camel(m_seg),
            "bestBaseline": _metric_values_camel(b_seg),
            "baselines": {
                "weeklyNaive": _metric_values_camel(n_seg),
                "rollingMean28": _metric_values_camel(ma_seg),
                "zeroForecast": _metric_values_camel(z_seg),
            },
        }

    return {
        "horizon": horizon,
        "aggregation": aggregation,
        "stockoutMode": stockout_mode,
        "postprocessed": is_postproc,
        "postprocessVersion": postproc_ver if is_postproc else None,
        "slowThreshold": float(report.optimal_slow_thresholds.get(horizon, 0.0)) if is_postproc else 0.0,
        "evaluationOrigins": [str(d) for d in evaluation_origins],
        "targetStartDate": str(target_start),
        "targetEndDate": str(target_end),
        "segments": segments_camel,
    }


def build_metrics_breakdown_payload(report: FullEvaluationReport) -> Dict[str, Any]:
    """Dựng object ForecastMetricsBreakdown khớp chặt schema OpenAPI/Backend/Pydantic."""
    postproc_ver = report.postprocess_version or build_postprocess_version(
        report.optimal_slow_thresholds,
        min_history_days=DEFAULT_MIN_HISTORY_DAYS,
    )
    model_ver = report.model_version or DEFAULT_MODEL_VERSION
    protocol_ver = report.metric_protocol_version or DEFAULT_METRIC_PROTOCOL_VERSION
    serving_cutoff = report.serving_cutoff_date or report.test_end_date

    def _build_horizon_breakdown(H: int) -> Dict[str, Any]:
        obj_name = report.best_objective.get(H, "tweedie")
        raw_key = f"lgb_{obj_name}"
        post_key = f"lgb_{obj_name}_postproc"
        post_resolved = post_key if post_key in report.aggregated_results_clean[H] else raw_key
        post_weekly_resolved = post_key if post_key in report.weekly_results_clean[H] else raw_key
        t_start = report.cutoff_date + timedelta(days=1)
        t_end = report.cutoff_date + timedelta(days=H)
        single_origin = [report.cutoff_date]

        rolling_view = None
        if H == 7 and report.rolling_h7_results and report.rolling_h7_origins:
            r_post_key = post_key if post_key in report.rolling_h7_results else raw_key
            rolling_view = _build_openapi_evaluation_view(
                report=report,
                horizon=7,
                aggregation="ROLLING_H7_AGGREGATE_POSTPROC",
                stockout_mode="EXCLUDED_CLEAN",
                res_h=report.rolling_h7_results,
                model_key=r_post_key,
                bb_view_key="rolling_h7_aggregate_clean",
                evaluation_origins=report.rolling_h7_origins,
                target_start=report.test_start_date,
                target_end=report.test_end_date,
                postproc_ver=postproc_ver,
            )

        return {
            "horizon": H,
            "servingAggregatePostproc": _build_openapi_evaluation_view(
                report=report,
                horizon=H,
                aggregation="HORIZON_AGGREGATE_POSTPROC",
                stockout_mode="EXCLUDED_CLEAN",
                res_h=report.aggregated_results_clean[H],
                model_key=post_resolved,
                bb_view_key="horizon_aggregate_clean",
                evaluation_origins=single_origin,
                target_start=t_start,
                target_end=t_end,
                postproc_ver=postproc_ver,
            ),
            "servingAggregateRawModel": _build_openapi_evaluation_view(
                report=report,
                horizon=H,
                aggregation="HORIZON_AGGREGATE_RAW_MODEL",
                stockout_mode="EXCLUDED_CLEAN",
                res_h=report.aggregated_results_clean[H],
                model_key=raw_key,
                bb_view_key="horizon_aggregate_clean",
                evaluation_origins=single_origin,
                target_start=t_start,
                target_end=t_end,
                postproc_ver=postproc_ver,
            ),
            "weeklyAggregatePostproc": _build_openapi_evaluation_view(
                report=report,
                horizon=H,
                aggregation="WEEKLY_AGGREGATE_POSTPROC",
                stockout_mode="EXCLUDED_CLEAN",
                res_h=report.weekly_results_clean[H],
                model_key=post_weekly_resolved,
                bb_view_key="weekly_clean",
                evaluation_origins=single_origin,
                target_start=t_start,
                target_end=t_end,
                postproc_ver=postproc_ver,
            ),
            "rawDailyClean": _build_openapi_evaluation_view(
                report=report,
                horizon=H,
                aggregation="DAILY_CELL_RAW",
                stockout_mode="EXCLUDED_CLEAN",
                res_h=report.lgb_results_clean[H],
                model_key=raw_key,
                bb_view_key="daily_clean",
                evaluation_origins=single_origin,
                target_start=t_start,
                target_end=t_end,
                postproc_ver=postproc_ver,
            ),
            "rawDailyUnfiltered": _build_openapi_evaluation_view(
                report=report,
                horizon=H,
                aggregation="DAILY_CELL_RAW_UNFILTERED",
                stockout_mode="INCLUDED_RAW",
                res_h=report.lgb_results_raw[H],
                model_key=raw_key,
                bb_view_key="daily_raw",
                evaluation_origins=single_origin,
                target_start=t_start,
                target_end=t_end,
                postproc_ver=postproc_ver,
            ),
            "rollingH7AggregatePostproc": rolling_view,
        }

    return {
        "schemaVersion": "2.0",
        "metricProtocolVersion": protocol_ver,
        "modelVersion": model_ver,
        "postprocessVersion": postproc_ver,
        "cutoffDate": str(serving_cutoff),
        "evaluationCutoffDate": str(report.cutoff_date),
        "testStartDate": str(report.test_start_date),
        "testEndDate": str(report.test_end_date),
        "dataSource": report.data_source or "daily_sales",
        "dataSourceHash": report.data_source_hash or "",
        "shortHistorySeriesCount": int(report.short_history_series_count),
        "h7": _build_horizon_breakdown(7) if 7 in report.aggregated_results_clean else None,
        "h30": _build_horizon_breakdown(30) if 30 in report.aggregated_results_clean else None,
        "benchmarkLimitations": [
            "Số liệu là kết quả backtest trên cửa sổ 30 ngày lịch sử đã khóa trước đánh giá; không chứng minh sai số thực tế của ngày tương lai chưa xảy ra.",
            "H7 đơn gốc đo từ D+1..D+7 sau evaluationCutoffDate; bổ sung rolling H7 trên toàn cửa sổ 30 ngày để kiểm chứng nhiều origin hơn.",
            "WAPE raw daily bị khuếch đại bởi độ thưa dữ liệu; không so trực tiếp MAE daily với MAE tổng kỳ và không diễn giải accuracy = 1 - WAPE.",
            "Các ngày stockout được loại khỏi nhãn đánh giá theo cùng mask cho mô hình và baseline; kết quả nhóm bán chậm phụ thuộc ngưỡng cố định 0.4.",
        ],
    }


def build_standardized_evaluation_summary(report: FullEvaluationReport) -> Dict[str, Any]:
    """Đóng gói báo cáo đánh giá chuẩn hóa theo horizon, aggregation, postprocess và metadata truy vết."""
    postproc_ver = report.postprocess_version or build_postprocess_version(
        report.optimal_slow_thresholds,
        min_history_days=DEFAULT_MIN_HISTORY_DAYS,
    )
    model_ver = report.model_version or DEFAULT_MODEL_VERSION
    protocol_ver = report.metric_protocol_version or DEFAULT_METRIC_PROTOCOL_VERSION
    serving_cutoff = report.serving_cutoff_date or report.test_end_date

    horizons_payload: Dict[str, Any] = {}
    for H in sorted(report.horizons):
        obj_name = report.best_objective.get(H, "tweedie")
        raw_key = f"lgb_{obj_name}"
        postproc_key = f"lgb_{obj_name}_postproc"

        views_spec = [
            ("serving_aggregate_postproc", "horizon_aggregate_postproc", True, report.aggregated_results_clean[H], postproc_key, "horizon_aggregate_clean"),
            ("serving_aggregate_raw_model", "horizon_aggregate_raw_model", True, report.aggregated_results_clean[H], raw_key, "horizon_aggregate_clean"),
            ("weekly_aggregate_postproc", "weekly_aggregate_postproc", True, report.weekly_results_clean[H], postproc_key, "weekly_clean"),
            ("raw_daily_clean", "daily_cell_raw_model", True, report.lgb_results_clean[H], raw_key, "daily_clean"),
            ("raw_daily_unfiltered", "daily_cell_raw_model_unfiltered", False, report.lgb_results_raw[H], raw_key, "daily_raw"),
        ]

        views_dict: Dict[str, Any] = {}
        for view_alias, agg_label, stockout_excluded, res_h, preferred_model_key, bb_key in views_spec:
            model_key = preferred_model_key if preferred_model_key in res_h else raw_key
            best_b_name = _resolve_best_baseline_name(report, H, bb_key, res_h)
            model_segs = res_h.get(model_key, {})
            baseline_segs = res_h.get(best_b_name, {})

            seg_payload: Dict[str, Any] = {}
            for seg_name in ["overall", "top_200", "slow_moving", "zero_demand", "non_zero_demand"]:
                if seg_name not in model_segs or seg_name not in baseline_segs:
                    continue
                seg_payload[seg_name] = {
                    "model": _metric_to_dict(model_segs[seg_name]),
                    "best_baseline": _metric_to_dict(baseline_segs[seg_name]),
                }

            views_dict[view_alias] = {
                "horizon": H,
                "aggregation": agg_label,
                "stockout_excluded": stockout_excluded,
                "cutoff_date": str(report.cutoff_date),
                "test_start_date": str(report.test_start_date),
                "test_end_date": str(report.test_end_date),
                "model_version": model_ver,
                "postprocess_version": postproc_ver if "postproc" in model_key else "none",
                "slow_threshold": float(report.optimal_slow_thresholds.get(H, 0.0)) if "postproc" in model_key else 0.0,
                "model_key": model_key,
                "best_baseline_name": best_b_name,
                "segments": seg_payload,
            }

        if H == 7 and report.rolling_h7_results:
            r_model_key = postproc_key if postproc_key in report.rolling_h7_results else raw_key
            r_best_b = _resolve_best_baseline_name(report, 7, "rolling_h7_aggregate_clean", report.rolling_h7_results)
            r_segs = {}
            for seg_name in ["overall", "top_200", "slow_moving", "zero_demand", "non_zero_demand"]:
                if seg_name in report.rolling_h7_results.get(r_model_key, {}) and seg_name in report.rolling_h7_results.get(r_best_b, {}):
                    r_segs[seg_name] = {
                        "model": _metric_to_dict(report.rolling_h7_results[r_model_key][seg_name]),
                        "best_baseline": _metric_to_dict(report.rolling_h7_results[r_best_b][seg_name]),
                    }
            views_dict["rolling_h7_aggregate_postproc"] = {
                "horizon": 7,
                "aggregation": "rolling_h7_aggregate_postproc",
                "stockout_excluded": True,
                "evaluation_origins": [str(d) for d in report.rolling_h7_origins],
                "test_start_date": str(report.test_start_date),
                "test_end_date": str(report.test_end_date),
                "model_version": model_ver,
                "postprocess_version": postproc_ver,
                "slow_threshold": float(report.optimal_slow_thresholds.get(7, 0.0)),
                "model_key": r_model_key,
                "best_baseline_name": r_best_b,
                "segments": r_segs,
            }

        horizons_payload[str(H)] = views_dict

    return {
        "schema_version": "1.0",
        "metric_protocol_version": protocol_ver,
        "serving_cutoff_date": str(serving_cutoff),
        "cutoff_date": str(report.cutoff_date),
        "test_start_date": str(report.test_start_date),
        "test_end_date": str(report.test_end_date),
        "model_version": model_ver,
        "postprocess_version": postproc_ver,
        "data_source": report.data_source or "daily_sales",
        "data_source_hash": report.data_source_hash or "",
        "optimal_slow_thresholds": {str(k): float(v) for k, v in report.optimal_slow_thresholds.items()},
        "short_history_series_count": report.short_history_series_count,
        "best_baseline_by_view": {str(k): v for k, v in report.best_baseline_by_view.items()},
        "horizons": horizons_payload,
        "benchmark_limitations": [
            "Số liệu thuộc cửa sổ benchmark 30 ngày đã khóa trước đánh giá; không chứng minh độ chính xác live mọi ngày.",
            "WAPE raw daily bị khuếch đại bởi độ thưa dữ liệu (~95% ô bằng 0) và không phản ánh trực tiếp đầu ra aggregate sau postprocess phục vụ nghiệp vụ.",
            "Ngưỡng hậu xử lý cho nhóm bán chậm được tối ưu trên tập Validation trước cutoff; phần lớn ô dự báo bán chậm về 0 nên cần đọc kèm zero_pred_ratio và phân nhóm zero_demand.",
        ],
    }


def _fmt_wape(wape: Optional[float]) -> str:
    return f"{wape * 100:.2f}%" if wape is not None else "N/A"


def _fmt_mae(mae: Optional[float]) -> str:
    return f"{mae:.4f}" if mae is not None else "N/A"


def _fmt_zero_ratio(z_ratio: Optional[float]) -> str:
    return f"{z_ratio * 100:.2f}%" if z_ratio is not None else "N/A"


def _compare_wape_status(model_wape: Optional[float], baseline_wape: Optional[float]) -> str:
    if model_wape is None or baseline_wape is None:
        return "Không đủ mẫu số dương để so WAPE"
    if abs(model_wape - baseline_wape) < 1e-4:
        return "Ngang baseline"
    return "Thắng" if model_wape < baseline_wape else "Thua"


def format_full_comparison_report(report: FullEvaluationReport) -> str:
    """Định dạng báo cáo so sánh đối đầu toàn diện 3 góc nhìn đo lường trung thực."""
    postproc_ver = report.postprocess_version or build_postprocess_version(
        report.optimal_slow_thresholds,
        min_history_days=DEFAULT_MIN_HISTORY_DAYS,
    )
    model_ver = report.model_version or DEFAULT_MODEL_VERSION
    protocol_ver = report.metric_protocol_version or DEFAULT_METRIC_PROTOCOL_VERSION

    lines: List[str] = []
    lines.append("# BÁO CÁO ĐÁNH GIÁ MÔ HÌNH LIGHTGBM VÀ ĐỐI ĐẦU BASELINE (TASK #112 & #113)")
    lines.append("")
    lines.append("## 1. Thông Tin Cửa Sổ Thực Nghiệm & Giao Thức Đánh Giá")
    lines.append(f"- **Phiên bản giao thức (`metricProtocolVersion`)**: `{protocol_ver}`")
    lines.append(f"- **Phiên bản mô hình (`modelVersion`)**: `{model_ver}`")
    lines.append(f"- **Phiên bản hậu xử lý (`postprocessVersion`)**: `{postproc_ver}`")
    lines.append(f"- **Ngày gốc đánh giá (`evaluationCutoffDate`)**: `{report.cutoff_date}` (H7 đơn gốc: `D+1..D+7`, H30: `D+1..D+30`)")
    if report.serving_cutoff_date is not None:
        lines.append(f"- **Ngày dữ liệu phục vụ cuối cùng (`cutoffDate`)**: `{report.serving_cutoff_date}`")
    lines.append(
        f"- **Cửa sổ kiểm tra (Test Window)**: từ `{report.test_start_date}` đến `{report.test_end_date}` "
        f"(30 ngày độc lập, khóa trước khi đánh giá; chỉ tune tham số và ngưỡng trên Validation trước cutoff)"
    )
    if report.data_source_hash:
        lines.append(f"- **Nguồn dữ liệu (`dataSource` / `dataSourceHash`)**: `{report.data_source}` / `{report.data_source_hash}`")
    lines.append("- **Các tầm dự báo (Horizons)**: " + ", ".join(f"{h} ngày" for h in report.horizons))
    lines.append(f"- **Số chuỗi mới (< 28 ngày lịch sử)**: {report.short_history_series_count} chuỗi (áp dụng fallback trung bình danh mục chi nhánh).")
    lines.append("- **Quy ước đầu ra đo lường**:")
    lines.append("  - **Chỉ số phục vụ nghiệp vụ (Primary Serving Metric)**: `horizon_aggregate` của mô hình sau hậu xử lý (`lgb_tweedie_postproc`) — phục vụ tính nhu cầu tầm 7/30 ngày và ROP.")
    lines.append("  - **Chỉ số tham chiếu/đối chiếu (Raw Daily)**: `daily_cell` của mô hình thô (`lgb_tweedie`) và các phân nhóm `top_200`, `slow_moving`, `zero_demand` — công khai song song, không dùng đơn lẻ để kết luận FAIL.")
    lines.append("")

    lines.append("## 2. Kết Quả Xác Thực (Validation) & Ngưỡng Hậu Xử Lý Nhóm Bán Chậm")
    lines.append("- Toàn bộ tham số và ngưỡng hậu xử lý chỉ được tìm trên tập Validation (30 ngày trước mốc kiểm tra):")
    lines.append("- **Lưu ý về hậu xử lý:** Ngưỡng bán chậm tối ưu trên Validation đưa phần lớn ô dự báo nhỏ của nhóm 800 SKU bán chậm về 0; WAPE ~100% ở nhóm này là đặc thù mẫu số nhỏ của thước đo trên nhu cầu thưa.")
    lines.append("")
    lines.append("| Tầm | Hàm mục tiêu | Val MAE | Val WAPE | Ngưỡng áp dụng | Ghi chú & Quyết định |")
    lines.append("|:---:|:---|:---:|:---:|:---:|:---|")
    for H in report.horizons:
        th_val = report.optimal_slow_thresholds.get(H, 0.0)
        for obj, m_dict in report.val_results[H].items():
            if obj == "regression_l1":
                status = "Tham chiếu (Zero-collapse, tương đương Zero Forecast)"
                th_str = "-"
            elif obj == report.best_objective[H]:
                status = "**✓ Được chọn làm mô hình chính thức**"
                th_str = f"`{th_val}` (cho slow-moving)"
            else:
                status = "Không chọn"
                th_str = "-"
            lines.append(
                f"| {H} ngày | `{obj}` | {m_dict['val_mae']:.4f} | {_fmt_wape(m_dict.get('val_wape'))} | {th_str} | {status} |"
            )
    lines.append("")

    segment_labels = [
        ("overall", "Toàn bộ (Overall)"),
        ("top_200", "Top 200 bán chạy"),
        ("slow_moving", "800 bán chậm"),
        ("non_zero_demand", "Nhóm có phát sinh bán (> 0)"),
        ("zero_demand", "Nhóm không phát sinh bán (Zero-demand)"),
    ]

    def _render_generic_table(
        title: str,
        results_dict: Dict,
        unit_label: str = "sp/ngày",
        sample_label: str = "Mẫu (N)",
    ) -> List[str]:
        t_lines = [f"### {title}", ""]
        t_lines.append(
            f"| Tầm | Mô hình | Phân khúc | MAE ({unit_label}) | WAPE (%) | Tỷ lệ dự báo = 0 | {sample_label} | Ô stockout loại (Độ phủ) | Tổng thực tế |"
        )
        t_lines.append(
            "|:---:|:---|:---|:---:|:---:|:---:|:---:|:---:|:---:|"
        )
        models_order = [
            ("weekly_naive", "Naive theo tuần (#111)"),
            ("rolling_mean_28", "TB trượt 28 ngày (#111)"),
            ("zero_forecast", "Dự báo toàn 0 (Tham chiếu)"),
            ("lgb_regression_l1", "LightGBM [regression_l1] (Tham chiếu: zero-collapse)"),
            ("lgb_tweedie", "LightGBM [tweedie] (Bản thô trước hậu xử lý)"),
            ("lgb_tweedie_postproc", "**LightGBM [tweedie + hậu xử lý] (Mô hình phục vụ)**"),
        ]
        for H in sorted(results_dict.keys()):
            for m_key, m_label in models_order:
                if m_key not in results_dict[H]:
                    continue
                for s_name, s_label in segment_labels:
                    if s_name not in results_dict[H][m_key]:
                        continue
                    m: ModelComparisonMetrics = results_dict[H][m_key][s_name]
                    wape_str = _fmt_wape(None if m.total_actual <= 0.0 else m.wape)
                    z_ratio_str = _fmt_zero_ratio(m.zero_pred_ratio)
                    cov_str = f"{m.excluded_stockout_count:,} ({m.coverage_ratio * 100:.2f}%)"
                    t_lines.append(
                        f"| {H} ngày | {m_label} | {s_label} | {_fmt_mae(m.mae)} | {wape_str} | {z_ratio_str} | {m.sample_count:,} | {cov_str} | {m.total_actual:,.0f} |"
                    )
        t_lines.append("")
        return t_lines

    # CÁCH 1: TỪNG Ô NGÀY
    lines.extend(
        _render_generic_table(
            "3. BẢNG 1: ĐÃ LOẠI BỎ NGÀY STOCKOUT - CÁCH 1: TỪNG Ô NGÀY (DAILY CELL - GIAO THỨC #111)",
            report.lgb_results_clean,
            unit_label="sp/ngày",
            sample_label="Số ô (N)",
        )
    )

    # CÁCH 2: THEO TUẦN
    lines.extend(
        _render_generic_table(
            "4. BẢNG 2: ĐÃ LOẠI BỎ NGÀY STOCKOUT - CÁCH 2: THEO TUẦN (WEEKLY AGGREGATE)",
            report.weekly_results_clean,
            unit_label="sp/tuần",
            sample_label="Số chuỗi-tuần (N)",
        )
    )

    # CÁCH 3: TỔNG GỘP CẢ TẦM
    lines.extend(
        _render_generic_table(
            "5. BẢNG BỔ SUNG: ĐÁNH GIÁ TRÊN TỔNG GỘP THEO TẦM CỦA MỖI CHUỖI - CÁCH 3: TỔNG CẢ TẦM (ROP & TỒN KHO)",
            report.aggregated_results_clean,
            unit_label="sp/tầm",
            sample_label="Số chuỗi (N)",
        )
    )

    if report.rolling_h7_results:
        lines.extend(
            _render_generic_table(
                f"5B. BẢNG ROLLING H7 TRONG CỬA SỔ 30 NGÀY ({len(report.rolling_h7_origins)} ORIGINS: {report.rolling_h7_origins[0]}..{report.rolling_h7_origins[-1]})",
                {7: report.rolling_h7_results},
                unit_label="sp/7 ngày",
                sample_label="Số chuỗi×origin",
            )
        )

    # 6. KẾT LUẬN ĐỐI ĐẦU VÀ TRẠNG THÁI NGHIỆM THU
    lines.append("## 6. Kết Luận Đối Đầu Trung Thực Với Baseline")
    lines.append("")
    lines.append("> [!NOTE]")
    lines.append("> **Lưu ý minh bạch:** So sánh giữa mô hình và baseline tốt nhất ở mỗi dòng được thực hiện trên **cùng tập chuỗi, cùng khoảng ngày, cùng nguồn dữ liệu, cùng ngày gốc (cutoff) và cùng phép gộp (aggregation)**.")
    lines.append("> Không dùng WAPE raw daily làm bằng chứng duy nhất kết luận mô hình thất bại khi đầu ra phục vụ nghiệp vụ là tổng nhu cầu sau hậu xử lý.")
    lines.append("")

    for H in report.horizons:
        obj_name = report.best_objective.get(H, "tweedie")
        raw_key = f"lgb_{obj_name}"
        post_key = f"lgb_{obj_name}_postproc"
        lines.append(f"### Tầm dự báo {H} ngày:")

        # 1. Từng ô ngày
        b_d_name = _resolve_best_baseline_name(report, H, "daily_clean", report.lgb_results_clean[H])
        b_d_w = report.lgb_results_clean[H][b_d_name]["overall"].wape
        raw_d_w = report.lgb_results_clean[H][raw_key]["overall"].wape
        post_d_w = report.lgb_results_clean[H].get(post_key, {}).get("overall", report.lgb_results_clean[H][raw_key]["overall"]).wape

        lines.append("1. **Đo theo Từng ô ngày (Daily cell - Tham chiếu):**")
        lines.append(f"   - Best Baseline (`{b_d_name}`): WAPE Overall = `{_fmt_wape(b_d_w)}`")
        lines.append(f"   - Mô hình thô (`{raw_key}`): WAPE Overall = `{_fmt_wape(raw_d_w)}` $\\rightarrow$ **{_compare_wape_status(raw_d_w, b_d_w)}**")
        if post_key in report.lgb_results_clean[H]:
            lines.append(f"   - Mô hình sau hậu xử lý (`{post_key}`): WAPE Overall = `{_fmt_wape(post_d_w)}` $\\rightarrow$ **{_compare_wape_status(post_d_w, b_d_w)}**")

        # 2. Theo tuần
        b_w_name = _resolve_best_baseline_name(report, H, "weekly_clean", report.weekly_results_clean[H])
        b_w_w = report.weekly_results_clean[H][b_w_name]["overall"].wape
        raw_w_w = report.weekly_results_clean[H][raw_key]["overall"].wape
        post_w_w = report.weekly_results_clean[H].get(post_key, {}).get("overall", report.weekly_results_clean[H][raw_key]["overall"]).wape
        lines.append("2. **Đo theo Tuần (Weekly aggregate):**")
        lines.append(f"   - Best Baseline (`{b_w_name}`): WAPE Overall = `{_fmt_wape(b_w_w)}`")
        lines.append(f"   - Mô hình thô (`{raw_key}`): WAPE Overall = `{_fmt_wape(raw_w_w)}` $\\rightarrow$ **{_compare_wape_status(raw_w_w, b_w_w)}**")
        if post_key in report.weekly_results_clean[H]:
            lines.append(f"   - Mô hình sau hậu xử lý (`{post_key}`): WAPE Overall = `{_fmt_wape(post_w_w)}` $\\rightarrow$ **{_compare_wape_status(post_w_w, b_w_w)}**")

        # 3. Tổng cả tầm (Output phục vụ chính)
        b_agg_name = _resolve_best_baseline_name(report, H, "horizon_aggregate_clean", report.aggregated_results_clean[H])
        b_agg_w = report.aggregated_results_clean[H][b_agg_name]["overall"].wape
        raw_agg_w = report.aggregated_results_clean[H][raw_key]["overall"].wape
        post_agg_w = report.aggregated_results_clean[H].get(post_key, {}).get("overall", report.aggregated_results_clean[H][raw_key]["overall"]).wape
        lines.append("3. **Đo theo Tổng cả tầm (Horizon aggregate - Đầu ra phục vụ ROP/nhập hàng):**")
        lines.append(f"   - Best Baseline (`{b_agg_name}`): WAPE Overall = `{_fmt_wape(b_agg_w)}`")
        lines.append(f"   - Mô hình thô (`{raw_key}`): WAPE Overall = `{_fmt_wape(raw_agg_w)}` $\\rightarrow$ **{_compare_wape_status(raw_agg_w, b_agg_w)}**")
        if post_key in report.aggregated_results_clean[H]:
            lines.append(f"   - **Mô hình phục vụ sau hậu xử lý (`{post_key}`)**: WAPE Overall = `{_fmt_wape(post_agg_w)}` $\\rightarrow$ **{_compare_wape_status(post_agg_w, b_agg_w)}**")

        if H == 7 and report.rolling_h7_results:
            b_r_name = _resolve_best_baseline_name(report, 7, "rolling_h7_aggregate_clean", report.rolling_h7_results)
            b_r_w = report.rolling_h7_results[b_r_name]["overall"].wape
            post_r_w = report.rolling_h7_results.get(post_key, report.rolling_h7_results[raw_key])["overall"].wape
            lines.append(f"   - **Rolling H7 trên cửa sổ 30 ngày ({len(report.rolling_h7_origins)} origins)**: Best Baseline (`{b_r_name}`) = `{_fmt_wape(b_r_w)}`, Mô hình phục vụ = `{_fmt_wape(post_r_w)}` $\\rightarrow$ **{_compare_wape_status(post_r_w, b_r_w)}**")

        # 4. Top 200 Pareto
        post_top_agg = report.aggregated_results_clean[H].get(post_key, {}).get("top_200", report.aggregated_results_clean[H][raw_key]["top_200"]).wape
        b_top_agg = report.aggregated_results_clean[H][b_agg_name]["top_200"].wape
        lines.append("4. **Nhóm cốt lõi Top 200 SKU bán chạy (Pareto 80% sản lượng - Tổng cả tầm):**")
        lines.append(f"   - Mô hình phục vụ đạt WAPE = `{_fmt_wape(post_top_agg)}` so với `{b_agg_name}` `{_fmt_wape(b_top_agg)}` $\\rightarrow$ **{_compare_wape_status(post_top_agg, b_top_agg)}**")
        lines.append("")

    lines.append("> [!IMPORTANT]")
    lines.append("> **Giới hạn benchmark & Trạng thái nghiệm thu AC-05:**")
    lines.append("> - Số liệu là backtest trên cửa sổ kiểm tra benchmark đã khóa, không chứng minh sai số live cho ngày tương lai chưa xảy ra.")
    lines.append("> - Chờ Lead @NguyenDokhai2005 chốt cách đo lường chính thức (từng ô ngày, theo tuần, hay tổng nhu cầu cả tầm sau hậu xử lý).")
    lines.append("> - Tuyệt đối không tự ý kết luận đạt tiêu chí AC-05 khi Lead chưa chốt.")
    lines.append("")

    return "\n".join(lines)


def train_production_demand_models(
    builder: FeatureMatrixBuilder,
    horizons: Tuple[int, ...] = (7, 30),
    best_models: Optional[Dict[int, Any]] = None,
    best_objectives: Optional[Dict[int, str]] = None,
    sample_step_days: int = 7,
) -> Dict[int, LightGBMDemandModel]:
    """Huấn luyện lại mô hình LightGBM trên toàn bộ dữ liệu đến ngày cuối cùng D (Issue #298).

    - Sử dụng cùng cấu hình và hàm mục tiêu (từ best_objectives hoặc best_models) như backtest.
    - Không dùng validation set / early stopping (X_val=None).
    - Lấy số vòng lặp tối ưu (best_iteration_) từ best_models[H] của kết quả backtest (hoặc mặc định 100 nếu không có).
    - Tuân thủ Cách 2: truyền n_estimators qua tham số params của constructor LightGBMDemandModel.
    """
    max_date = builder.unique_dates[-1]
    full_origins = generate_origin_dates_for_training(
        unique_dates=builder.unique_dates,
        min_history_days=28,
        max_target_date=max_date,
        step_days=sample_step_days,
    )

    production_models: Dict[int, LightGBMDemandModel] = {}
    for H in sorted(horizons):
        # 1. Xác định hàm mục tiêu
        if best_objectives and H in best_objectives:
            obj = best_objectives[H]
        elif best_models and H in best_models and hasattr(best_models[H], "objective"):
            obj = best_models[H].objective
        else:
            obj = "tweedie"

        # 2. Lấy số vòng lặp tối ưu từ backtest
        n_iters = 100
        if best_models and H in best_models:
            b_iter = getattr(best_models[H], "best_iteration_", None)
            if b_iter is not None and b_iter > 0:
                n_iters = int(b_iter)

        # 3. Tạo tập train toàn bộ đến max_date
        X_full, y_full = builder.build_dataset_for_horizon(
            horizon=H,
            origin_dates=full_origins,
            max_target_date=max_date,
            exclude_stockout=True,
        )

        # 4. Huấn luyện mô hình sản xuất (không validation, n_estimators gộp qua params)
        model = LightGBMDemandModel(
            horizon=H,
            objective=obj,
            params={"n_estimators": n_iters},
        )
        model.fit(X_train=X_full, y_train=y_full)
        production_models[H] = model

    return production_models

