"""Script trích xuất số liệu đánh giá chi tiết theo phân nhóm (Chi nhánh, Ngành hàng, Pareto)
phục vụ báo cáo đánh giá mô hình cuối cùng AC-05 (Task #120).

Tuân thủ chặt chẽ:
- Giao thức thực nghiệm của evaluator.py (Cutoff: 2026-10-01, Test: 02/10 - 31/10/2026).
- Lọc bỏ ngày hết hàng (stockout = True).
- Nạp trực tiếp mô hình LightGBM artifacts (H7 & H30), áp dụng ngưỡng 0.4 cho slow-moving.
- Tính toán đầy đủ cho cả Mô hình và Baseline tốt nhất (Rolling mean 28 ngày), kèm Weekly Naive & Zero.
- Phân rã theo 3 Chi nhánh và toàn bộ các Ngành hàng thực tế trong dữ liệu (đếm động).
"""

from __future__ import annotations

import argparse
from datetime import date, timedelta
import json
from pathlib import Path
import sys
from typing import Any, Dict, Optional, Set

import numpy as np
import pandas as pd

# Thiết lập đường dẫn import
_SCRIPT_DIR = Path(__file__).resolve().parent
_SERVICE_DIR = _SCRIPT_DIR.parent
if str(_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(_SERVICE_DIR))

from app.models.baselines import BaselineMetrics, _compare_metrics_for_best
from app.models.evaluator import (
    DEFAULT_MIN_HISTORY_DAYS,
    DEFAULT_MODEL_VERSION,
    aggregate_by_weekly_windows,
    apply_short_history_fallback,
    build_postprocess_version,
    calculate_mae,
    calculate_wape,
    calculate_zero_ratio,
)
from app.models.features import FeatureMatrixBuilder
from app.models.lightgbm_model import LightGBMDemandModel


def _load_thresholds_from_metrics_json(
    artifacts_dir: Path,
    horizons: tuple[int, ...],
    override_threshold: Optional[float] = None,
) -> Dict[int, float]:
    """Đọc ngưỡng bán chậm theo horizon từ evaluation_metrics.json (hoặc fallback 0.4)."""
    if override_threshold is not None:
        return {H: float(override_threshold) for H in horizons}
    metrics_file = artifacts_dir / "evaluation_metrics.json"
    if metrics_file.exists():
        try:
            payload = json.loads(metrics_file.read_text(encoding="utf-8"))
            raw_th = payload.get("optimal_slow_thresholds", {})
            if raw_th:
                return {H: float(raw_th.get(str(H), 0.4)) for H in horizons}
        except Exception:
            pass
    return {H: 0.4 for H in horizons}


def _compute_view_comparison(
    y_act: np.ndarray,
    y_postproc: np.ndarray,
    y_raw: np.ndarray,
    y_ma28: np.ndarray,
    y_naive: np.ndarray,
    horizon: int,
    aggregation: str,
    stockout_excluded: bool,
) -> Dict[str, Any]:
    """Tính toán đồng nhất các chỉ số model (raw + postproc) và baseline trên cùng ma trận."""
    mae_ma28 = calculate_mae(y_act, y_ma28)
    wape_ma28 = calculate_wape(y_act, y_ma28, as_percent=False)

    mae_naive = calculate_mae(y_act, y_naive)
    wape_naive = calculate_wape(y_act, y_naive, as_percent=False)

    best_b_name = _compare_metrics_for_best(
        BaselineMetrics(mae=mae_naive, wape=wape_naive, sample_count=len(y_act), total_actual=float(np.sum(y_act))),
        BaselineMetrics(mae=mae_ma28, wape=wape_ma28, sample_count=len(y_act), total_actual=float(np.sum(y_act))),
    )
    best_b_wape = wape_naive if best_b_name == "weekly_naive" else wape_ma28

    mae_post = calculate_mae(y_act, y_postproc)
    wape_post = calculate_wape(y_act, y_postproc, as_percent=False)
    zero_ratio_post = calculate_zero_ratio(y_postproc)

    mae_raw = calculate_mae(y_act, y_raw)
    wape_raw = calculate_wape(y_act, y_raw, as_percent=False)
    zero_ratio_raw = calculate_zero_ratio(y_raw)

    win_post = (wape_post < best_b_wape) if (wape_post is not None and best_b_wape is not None) else False
    wape_diff = (wape_post - best_b_wape) if (wape_post is not None and best_b_wape is not None) else None

    return {
        "horizon": horizon,
        "aggregation": aggregation,
        "stockout_excluded": stockout_excluded,
        "sample_count": int(len(y_act)),
        "best_baseline_name": best_b_name,
        "model": {"mae": mae_post, "wape": wape_post, "zero_pred_ratio": zero_ratio_post},
        "model_raw": {"mae": mae_raw, "wape": wape_raw, "zero_pred_ratio": zero_ratio_raw},
        "baseline_rolling_mean_28": {"mae": mae_ma28, "wape": wape_ma28},
        "baseline_weekly_naive": {"mae": mae_naive, "wape": wape_naive},
        "wape_diff": wape_diff,
        "win": win_post,
    }


def run_segment_evaluation(
    data_path: Path | None = None,
    artifacts_dir: Path | None = None,
    output_json_path: Path | None = None,
    cutoff_date: Optional[date] = None,
    slow_threshold: Optional[float] = None,
) -> Dict[str, Any]:
    if data_path is None:
        data_path = _SERVICE_DIR / "data" / "daily_sales.parquet"
    if artifacts_dir is None:
        artifacts_dir = _SERVICE_DIR / "artifacts"
    if output_json_path is None:
        output_json_path = artifacts_dir / "segment_metrics.json"

    print("=" * 80)
    print("TRÍCH XUẤT SỐ LIỆU ĐÁNH GIÁ MÔ HÌNH THEO PHÂN NHÓM (TASK #120 & #423)")
    print(f"- Dữ liệu: {data_path}")
    print(f"- Thư mục artifacts: {artifacts_dir}")
    print("=" * 80)

    if not data_path.exists():
        raise FileNotFoundError(f"Không tìm thấy file dữ liệu tại {data_path}")

    # 1. Nạp dữ liệu
    print("[1/4] Đang nạp daily_sales...")
    df_daily = pd.read_parquet(data_path)
    if pd.api.types.is_datetime64_any_dtype(df_daily["sale_date"]):
        df_daily["sale_date"] = pd.to_datetime(df_daily["sale_date"]).dt.date

    builder = FeatureMatrixBuilder(df_daily)
    unique_dates = builder.unique_dates
    max_date = unique_dates[-1]
    resolved_cutoff = cutoff_date or (max_date - timedelta(days=30))
    test_dates = [d for d in unique_dates if d > resolved_cutoff]
    if len(test_dates) != 30:
        raise ValueError(
            f"Cửa sổ kiểm tra phải có đúng 30 ngày sau cutoff {resolved_cutoff}, thực tế có {len(test_dates)} ngày."
        )
    test_start_date = test_dates[0]
    test_end_date = test_dates[-1]

    print(
        f"      Tổng số dòng: {len(df_daily):,}, Chuỗi: {builder.n_series:,}, "
        f"Cutoff: {resolved_cutoff}, Test: {test_start_date}..{test_end_date}"
    )

    # 2. Xác định Top 200 SKU trên tập dữ liệu train (<= cutoff_date)
    df_train = builder.df[builder.df["sale_date"] <= resolved_cutoff]
    top_sku_ids: Set[int] = set(
        df_train.groupby("product_id")["qty_net"].sum().nlargest(200).index
    )
    product_ids_series = builder.product_ids
    branch_ids_series = builder.branch_ids
    is_top_mask = np.isin(product_ids_series, list(top_sku_ids))

    # Lấy thông tin chi nhánh và ngành hàng thực tế (hỗ trợ cả DataFrame rút gọn không có cột tên)
    if {"branch_id", "branch_code", "branch_name"}.issubset(df_daily.columns):
        branches_df = df_daily[["branch_id", "branch_code", "branch_name"]].drop_duplicates().sort_values("branch_id")
        branch_info = branches_df.to_dict("records")
    else:
        branch_info = [
            {"branch_id": int(b), "branch_code": f"BR-{int(b)}", "branch_name": f"Branch {int(b)}"}
            for b in sorted(df_daily["branch_id"].unique())
        ]
    print(f"      Số chi nhánh thực tế: {len(branch_info)}")

    if {"category_id", "category_name"}.issubset(df_daily.columns):
        cat_df = df_daily[["category_id", "category_name"]].drop_duplicates().sort_values("category_id")
        cat_info = cat_df.to_dict("records")
        prod_to_cat = df_daily.drop_duplicates("product_id").set_index("product_id")["category_id"].to_dict()
        category_ids_series = np.array([prod_to_cat.get(pid, -1) for pid in product_ids_series])
    else:
        cat_info = []
        category_ids_series = np.full(len(product_ids_series), -1)
    print(f"      Số ngành hàng thực tế: {len(cat_info)}")

    train_dates = [d for d in unique_dates if d <= resolved_cutoff]
    history_qty_matrix = builder.qty_matrix[:, : len(train_dates)]

    # 3. Nạp mô hình LightGBM và ngưỡng postprocess cho 2 tầm 7 và 30 ngày
    print("[2/4] Nạp mô hình LightGBM từ artifacts...")
    horizons = (7, 30)
    thresholds_by_h = _load_thresholds_from_metrics_json(
        artifacts_dir=artifacts_dir,
        horizons=horizons,
        override_threshold=slow_threshold,
    )
    postprocess_version = build_postprocess_version(
        thresholds_by_h,
        min_history_days=DEFAULT_MIN_HISTORY_DAYS,
    )
    models: Dict[int, LightGBMDemandModel] = {}
    for H in horizons:
        model_file = artifacts_dir / f"lgbm_demand_h{H}.txt"
        if not model_file.exists():
            raise FileNotFoundError(f"Không tìm thấy file mô hình {model_file}!")
        m = LightGBMDemandModel(horizon=H, objective="tweedie")
        m.load_model(model_file)
        models[H] = m
        print(f"      Đã nạp thành công mô hình tầm {H} ngày từ {model_file.name}")

    # 4. Dự báo và đánh giá
    print(f"[3/4] Dự báo và tính toán MAE/WAPE trên tập kiểm tra ({test_start_date} - {test_end_date})...")

    results: Dict[str, Any] = {
        "schema_version": "1.0",
        "cutoff_date": str(resolved_cutoff),
        "test_start_date": str(test_start_date),
        "test_end_date": str(test_end_date),
        "model_version": DEFAULT_MODEL_VERSION,
        "postprocess_version": postprocess_version,
        "slow_moving_thresholds": {str(h): float(v) for h, v in thresholds_by_h.items()},
        "slow_moving_threshold": float(thresholds_by_h.get(7, 0.4)),
        "branches": branch_info,
        "categories": cat_info,
        "metrics": {},
    }

    for H in horizons:
        th_h = thresholds_by_h[H]
        test_dates_H = [resolved_cutoff + timedelta(days=i) for i in range(1, H + 1)]
        test_indices = [builder.date_to_idx[d] for d in test_dates_H]
        actual_matrix_H = builder.qty_matrix[:, test_indices]
        stockout_matrix_H = builder.stockout_matrix[:, test_indices]

        # Baseline 1: Naive theo tuần
        last_7_train = history_qty_matrix[:, -7:]
        reps = int(np.ceil(H / 7))
        pred_naive = np.tile(last_7_train, (1, reps))[:, :H]

        # Baseline 2: TB trượt 28 ngày
        w_size = min(28, history_qty_matrix.shape[1])
        last_28_train = history_qty_matrix[:, -w_size:]
        mean_28 = np.mean(last_28_train, axis=1, keepdims=True)
        pred_ma28 = np.repeat(mean_28, H, axis=1)

        # Mô hình LightGBM Tweedie (raw + postproc)
        test_feature_dfs = builder.build_test_features_matrix(cutoff_date=resolved_cutoff, horizon=H)
        step_preds = [models[H].predict(sdf) for sdf in test_feature_dfs]
        pred_lgb_raw = np.column_stack(step_preds)

        slow_cells = (~is_top_mask)[:, np.newaxis] & (pred_lgb_raw < th_h) if th_h > 0.0 else np.zeros_like(pred_lgb_raw, dtype=bool)
        pred_lgb_postproc = np.where(slow_cells, 0.0, pred_lgb_raw)

        # Áp dụng đồng nhất fallback chuỗi mới (< 28 ngày) và clip >= 0 theo đúng thứ tự phục vụ
        for arr in (pred_naive, pred_ma28, pred_lgb_raw, pred_lgb_postproc):
            apply_short_history_fallback(df_train, builder.series_index, arr, DEFAULT_MIN_HISTORY_DAYS)
            np.clip(arr, 0.0, None, out=arr)

        # Ma trận đã lọc stockout
        act_clean = np.where(stockout_matrix_H, 0.0, actual_matrix_H)
        pred_naive_clean = np.where(stockout_matrix_H, 0.0, pred_naive)
        pred_ma28_clean = np.where(stockout_matrix_H, 0.0, pred_ma28)
        pred_lgb_raw_clean = np.where(stockout_matrix_H, 0.0, pred_lgb_raw)
        pred_lgb_clean = np.where(stockout_matrix_H, 0.0, pred_lgb_postproc)

        # Weekly aggregate
        act_w_clean = aggregate_by_weekly_windows(act_clean, H)
        pred_naive_w_clean = aggregate_by_weekly_windows(pred_naive_clean, H)
        pred_ma28_w_clean = aggregate_by_weekly_windows(pred_ma28_clean, H)
        pred_lgb_raw_w_clean = aggregate_by_weekly_windows(pred_lgb_raw_clean, H)
        pred_lgb_w_clean = aggregate_by_weekly_windows(pred_lgb_clean, H)

        # Horizon aggregate
        act_agg_clean = np.sum(act_clean, axis=1)
        pred_naive_agg_clean = np.sum(pred_naive_clean, axis=1)
        pred_ma28_agg_clean = np.sum(pred_ma28_clean, axis=1)
        pred_lgb_raw_agg_clean = np.sum(pred_lgb_raw_clean, axis=1)
        pred_lgb_agg_clean = np.sum(pred_lgb_clean, axis=1)

        zero_demand_mask_H = np.sum(actual_matrix_H, axis=1) <= 0.0
        segment_masks: Dict[str, np.ndarray] = {
            "overall": np.ones(builder.n_series, dtype=bool),
            "top_200": is_top_mask,
            "slow_moving": ~is_top_mask,
            "zero_demand": zero_demand_mask_H,
            "non_zero_demand": ~zero_demand_mask_H,
        }

        for b in branch_info:
            b_id = b["branch_id"]
            segment_masks[f"branch_{b_id}"] = (branch_ids_series == b_id)

        for c in cat_info:
            c_id = c["category_id"]
            segment_masks[f"category_{c_id}"] = (category_ids_series == c_id)

        results["metrics"][str(H)] = {}

        for seg_key, mask in segment_masks.items():
            if not np.any(mask):
                continue

            # 1. Từng ô ngày (Daily cell - đã lọc stockout)
            y_act = actual_matrix_H[mask, :].ravel()
            valid = ~stockout_matrix_H[mask, :].ravel()
            if not np.any(valid):
                continue

            y_act_v = y_act[valid]
            daily_comp = _compute_view_comparison(
                y_act=y_act_v,
                y_postproc=pred_lgb_postproc[mask, :].ravel()[valid],
                y_raw=pred_lgb_raw[mask, :].ravel()[valid],
                y_ma28=pred_ma28[mask, :].ravel()[valid],
                y_naive=pred_naive[mask, :].ravel()[valid],
                horizon=H,
                aggregation="daily_cell",
                stockout_excluded=True,
            )

            weekly_comp = _compute_view_comparison(
                y_act=act_w_clean[mask, :].ravel(),
                y_postproc=pred_lgb_w_clean[mask, :].ravel(),
                y_raw=pred_lgb_raw_w_clean[mask, :].ravel(),
                y_ma28=pred_ma28_w_clean[mask, :].ravel(),
                y_naive=pred_naive_w_clean[mask, :].ravel(),
                horizon=H,
                aggregation="weekly_aggregate",
                stockout_excluded=True,
            )

            horizon_comp = _compute_view_comparison(
                y_act=act_agg_clean[mask],
                y_postproc=pred_lgb_agg_clean[mask],
                y_raw=pred_lgb_raw_agg_clean[mask],
                y_ma28=pred_ma28_agg_clean[mask],
                y_naive=pred_naive_agg_clean[mask],
                horizon=H,
                aggregation="horizon_aggregate",
                stockout_excluded=True,
            )

            results["metrics"][str(H)][seg_key] = {
                "sample_count_series": int(np.sum(mask)),
                "total_actual_clean": float(np.sum(y_act_v)),
                "daily_cell": daily_comp,
                "weekly_aggregate": weekly_comp,
                "horizon_aggregate": horizon_comp,
            }

    # 5. Lưu ra JSON
    print(f"[4/4] Ghi kết quả vào {output_json_path}...")
    output_json_path.parent.mkdir(parents=True, exist_ok=True)
    output_json_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    print("      Đã lưu metrics phân nhóm thành công!")

    # In tóm tắt kết quả
    def _pct(val: Optional[float]) -> str:
        return f"{val * 100:>8.2f}%" if val is not None else f"{'N/A':>9}"

    def _diff_pct(val: Optional[float]) -> str:
        return f"{val * 100:>+9.2f}%" if val is not None else f"{'N/A':>10}"

    print("\n" + "=" * 80)
    print("BẢNG TỔNG HỢP THEO CHI NHÁNH (CÁCH ĐO GỘP TUẦN - WEEKLY AGGREGATE):")
    print(f"{'Tầm':<8} | {'Chi nhánh':<24} | {'Thực tế':<8} | {'BestBL WAPE':<11} | {'Mô hình WAPE':<12} | {'Chênh lệch':<11} | {'Kết quả':<8}")
    print("-" * 92)
    for H in horizons:
        for b in branch_info:
            b_id = b["branch_id"]
            b_name = f"{b['branch_code']} ({b['branch_name']})"
            m = results["metrics"][str(H)][f"branch_{b_id}"]["weekly_aggregate"]
            bl_key = "baseline_weekly_naive" if m["best_baseline_name"] == "weekly_naive" else "baseline_rolling_mean_28"
            bl_w = m[bl_key]["wape"]
            md_w = m["model"]["wape"]
            res = "Thắng" if m["win"] else "Thua"
            tot = int(results["metrics"][str(H)][f"branch_{b_id}"]["total_actual_clean"])
            print(f"{H} ngày   | {b_name:<24} | {tot:<8} | {_pct(bl_w):<11} | {_pct(md_w):<12} | {_diff_pct(m['wape_diff']):<11} | {res:<8}")

    if cat_info:
        print("\n" + "=" * 80)
        print("BẢNG TỔNG HỢP THEO NGÀNH HÀNG (CÁCH ĐO GỘP TUẦN - WEEKLY AGGREGATE):")
        print(f"{'Tầm':<8} | {'Ngành hàng':<32} | {'Thực tế':<8} | {'BestBL WAPE':<11} | {'Mô hình WAPE':<12} | {'Chênh lệch':<11} | {'Kết quả':<8}")
        print("-" * 102)
        for H in horizons:
            for c in cat_info:
                c_id = c["category_id"]
                c_name = f"[{c_id}] {c['category_name']}"
                m = results["metrics"][str(H)][f"category_{c_id}"]["weekly_aggregate"]
                bl_key = "baseline_weekly_naive" if m["best_baseline_name"] == "weekly_naive" else "baseline_rolling_mean_28"
                bl_w = m[bl_key]["wape"]
                md_w = m["model"]["wape"]
                res = "Thắng" if m["win"] else "Thua"
                tot = int(results["metrics"][str(H)][f"category_{c_id}"]["total_actual_clean"])
                print(f"{H} ngày   | {c_name:<32} | {tot:<8} | {_pct(bl_w):<11} | {_pct(md_w):<12} | {_diff_pct(m['wape_diff']):<11} | {res:<8}")

    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Trích xuất metrics phân nhóm cho forecast-service.")
    parser.add_argument("--data-path", type=str, default=None, help="Đường dẫn file parquet daily_sales")
    parser.add_argument("--cutoff-date", type=str, default=None, help="Ngày gốc cutoff YYYY-MM-DD (mặc định max_date - 30)")
    parser.add_argument("--slow-threshold", type=float, default=None, help="Ghi đè ngưỡng postprocess bán chậm")
    args = parser.parse_args()

    run_segment_evaluation(
        data_path=Path(args.data_path) if args.data_path else None,
        cutoff_date=date.fromisoformat(args.cutoff_date) if args.cutoff_date else None,
        slow_threshold=args.slow_threshold,
    )
