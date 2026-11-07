"""Module triển khai hai mô hình Baseline và quy trình đánh giá sai số (Task #111).

Bao gồm:
1. Baseline 1 (Weekly Naive): Lặp lại chu kỳ 7 ngày gần nhất trước điểm gốc (D = D - 7).
2. Baseline 2 (Rolling Mean 28D): Trung bình trượt 28 ngày gần nhất trước điểm gốc (flat forecast).
3. Hàm thuần `evaluate_baselines_on_daily_sales`: Tính toán ma trận vectorized (chuỗi × ngày)
   bằng pandas và numpy, tránh vòng lặp Python trên 3.000 chuỗi bán lẻ.
4. Xử lý phân nhóm: Overall, Top 200 SKU bán chạy (tính từ train set), và 800 SKU còn lại.
5. Mặt nạ stockout: Báo cả 2 bảng (số chính đã loại stockout và bảng gộp tham chiếu).
6. Hàm chọn baseline tốt nhất `select_best_baseline` và định dạng báo cáo `format_report`.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any, Dict, List, Optional, Set, Tuple
import numpy as np
import pandas as pd

from app.models.metrics import calculate_mae, calculate_wape


@dataclass(frozen=True)
class BaselineMetrics:
    """Chứa các chỉ số sai số của một mô hình baseline trên một phân khúc."""

    mae: float
    wape: Optional[float]  # Lưu dưới dạng tỷ lệ 0.0 - 1.0 (ví dụ 0.1167)
    sample_count: int
    total_actual: float


@dataclass
class BaselineEvaluationReport:
    """Báo cáo tổng hợp đánh giá các baseline trên tập kiểm tra."""

    cutoff_date: date
    test_start_date: date
    test_end_date: date
    horizons: List[int]
    results: Dict[int, Dict[str, Dict[str, BaselineMetrics]]]  # Đã loại bỏ ngày stockout (số chính)
    results_raw: Dict[int, Dict[str, Dict[str, BaselineMetrics]]]  # Gộp toàn bộ ngày (tham chiếu)
    best_baseline: Dict[int, str]  # [horizon] -> "weekly_naive" hoặc "rolling_mean_28"


class Baseline1WeeklyNaive:
    """Baseline 1: Naive theo tuần (Same-Day-Last-Week).

    Dự báo ngày D bằng thực tế của cùng thứ trong tuần gần nhất đã biết trước điểm gốc (D = D - 7).
    Lặp lại chu kỳ 7 ngày gần nhất cho toàn bộ chân trời dự báo H (7 hoặc 30 ngày).
    """

    @staticmethod
    def predict(history_series: pd.Series, horizon: int = 7) -> np.ndarray:
        """Dự báo horizon ngày tiếp theo từ lịch sử chuỗi thời gian đơn lẻ.

        history_series phải có độ dài >= 7 ngày.
        """
        if len(history_series) < 7:
            raise ValueError(
                f"Weekly naive yêu cầu chuỗi lịch sử có độ dài >= 7 ngày, nhận được {len(history_series)}."
            )

        last_7_days = np.asarray(history_series.iloc[-7:], dtype=float)
        reps = int(np.ceil(horizon / 7))
        tiled = np.tile(last_7_days, reps)
        return tiled[:horizon]


class Baseline2RollingMean28:
    """Baseline 2: Trung bình trượt 28 ngày gần nhất (Rolling Mean 28D).

    Dự báo mọi ngày trong tầm bằng trung bình cộng qty_net của tối đa 28 ngày
    gần nhất trước điểm gốc (flat forecast).
    """

    @staticmethod
    def predict(history_series: pd.Series, horizon: int = 7) -> np.ndarray:
        """Dự báo horizon ngày tiếp theo bằng trung bình cộng 28 ngày gần nhất."""
        if len(history_series) == 0:
            raise ValueError("Chuỗi lịch sử không được rỗng.")

        window_size = min(28, len(history_series))
        mean_val = float(history_series.iloc[-window_size:].mean())
        return np.full(horizon, mean_val, dtype=float)


def _compare_metrics_for_best(
    naive_m: BaselineMetrics,
    ma_m: BaselineMetrics,
) -> str:
    """So sánh hai baseline theo luật hòa:

    1. WAPE có giá trị số (float) ưu tiên hơn WAPE=None.
    2. Cả hai cùng có WAPE: WAPE thấp hơn thắng.
    3. WAPE bằng nhau (hoặc cả hai đều None): MAE thấp hơn thắng.
    4. Cả WAPE và MAE bằng nhau: Ưu tiên 'weekly_naive'.
    """
    w_naive = naive_m.wape
    w_ma = ma_m.wape

    # Trường hợp xử lý None
    if w_naive is not None and w_ma is None:
        return "weekly_naive"
    if w_naive is None and w_ma is not None:
        return "rolling_mean_28"

    if w_naive is not None and w_ma is not None:
        if abs(w_naive - w_ma) > 1e-9:
            return "weekly_naive" if w_naive < w_ma else "rolling_mean_28"

    # WAPE bằng nhau hoặc cả hai đều None -> so sánh MAE
    if abs(naive_m.mae - ma_m.mae) > 1e-9:
        return "weekly_naive" if naive_m.mae < ma_m.mae else "rolling_mean_28"

    # Hòa tuyệt đối -> ưu tiên naive theo tuần
    return "weekly_naive"


def select_best_baseline(
    report: BaselineEvaluationReport,
    horizon: int = 7,
    segment: str = "overall",
) -> Tuple[str, BaselineMetrics]:
    """Chọn mô hình baseline tốt nhất cho chân trời và phân khúc chỉ định.

    Áp dụng luật giải quyết hòa: WAPE -> MAE -> weekly_naive.
    """
    if horizon not in report.results:
        raise ValueError(f"Không tìm thấy horizon {horizon} trong kết quả báo cáo.")

    h_results = report.results[horizon]
    if "weekly_naive" not in h_results or "rolling_mean_28" not in h_results:
        raise ValueError("Kết quả báo cáo thiếu mô hình weekly_naive hoặc rolling_mean_28.")

    naive_metrics = h_results["weekly_naive"][segment]
    ma_metrics = h_results["rolling_mean_28"][segment]

    best_name = _compare_metrics_for_best(naive_metrics, ma_metrics)
    best_metrics = naive_metrics if best_name == "weekly_naive" else ma_metrics
    return best_name, best_metrics


def evaluate_baselines_on_daily_sales(
    df_daily: pd.DataFrame,
    cutoff_date: Optional[date] = None,
    horizons: Tuple[int, ...] = (7, 30),
    exclude_stockout: bool = True,
    top_sku_ids: Optional[Set[int]] = None,
) -> BaselineEvaluationReport:
    """Hàm thuần đánh giá hai mô hình baseline trên bảng dữ liệu daily_sales.

    Tính toán hoàn toàn bằng ma trận vectorized (chuỗi × ngày) qua numpy và pandas,
    tối ưu cho 3.000 chuỗi bán lẻ.

    Quy trình:
    1. Xác định điểm gốc (cutoff_date): Mặc định max(sale_date) - 30 ngày.
    2. Kiểm tra cửa sổ kiểm tra (test window): Bắt buộc đủ chính xác 30 ngày.
    3. Xác định nhóm Top 200 SKU bán chạy từ qty_net của tập train (<= cutoff_date).
    4. Pivot về ma trận (chuỗi × ngày), dự báo vectorized cho từng baseline.
    5. Tính MAE và WAPE gộp (pooled) trên mọi dòng theo 3 phân nhóm:
       - overall: Toàn bộ danh mục
       - top_200: Nhóm 200 SKU bán chạy
       - slow_moving: 800 SKU còn lại
    6. Tạo cả 2 bảng kết quả: đã loại ngày stockout (số chính) và gộp toàn bộ ngày.
    """
    df = df_daily.copy()

    # Chuẩn hóa cột sale_date về datetime.date
    if pd.api.types.is_datetime64_any_dtype(df["sale_date"]):
        df["sale_date"] = df["sale_date"].dt.date

    unique_dates = sorted(df["sale_date"].unique())
    if not unique_dates:
        raise ValueError("Dữ liệu daily_sales rỗng, không có ngày bán nào.")

    max_date = unique_dates[-1]

    if cutoff_date is None:
        cutoff_date = max_date - timedelta(days=30)

    test_dates = [d for d in unique_dates if d > cutoff_date]
    if len(test_dates) != 30:
        raise ValueError(
            f"Cửa sổ kiểm tra phải có đúng 30 ngày, thực tế có {len(test_dates)} ngày "
            f"(từ {test_dates[0] if test_dates else None} đến {test_dates[-1] if test_dates else None})."
        )

    train_dates = [d for d in unique_dates if d <= cutoff_date]
    if len(train_dates) < 7:
        raise ValueError(
            f"Tập lịch sử huấn luyện cần tối thiểu 7 ngày, thực tế chỉ có {len(train_dates)} ngày."
        )

    test_start_date = test_dates[0]
    test_end_date = test_dates[-1]

    # Xác định Top 200 SKU từ dữ liệu bán ròng tập train (không dùng cột is_top_sku)
    if top_sku_ids is None:
        df_train_only = df[df["sale_date"] <= cutoff_date]
        sku_sales = (
            df_train_only.groupby("product_id")["qty_net"].sum().sort_values(ascending=False)
        )
        top_sku_ids = set(sku_sales.head(200).index)

    # Đảm bảo cột stockout tồn tại
    if "stockout" not in df.columns:
        df["stockout"] = False

    # Pivot về ma trận (branch_id, product_id) × sale_date
    # Vectorized: unstack đảm bảo tốc độ cực cao
    indexed_df = df.set_index(["branch_id", "product_id", "sale_date"])

    pivot_qty = (
        indexed_df["qty_net"]
        .unstack(level="sale_date", fill_value=0)
        .reindex(columns=unique_dates, fill_value=0)
    )

    pivot_stockout = (
        indexed_df["stockout"]
        .unstack(level="sale_date", fill_value=False)
        .reindex(columns=unique_dates, fill_value=False)
    )

    # Chuyển đổi sang numpy arrays
    history_qty_matrix = pivot_qty[train_dates].to_numpy(dtype=float)  # (N_series, N_train_days)
    test_qty_matrix = pivot_qty[test_dates].to_numpy(dtype=float)  # (N_series, 30)
    test_stockout_matrix = pivot_stockout[test_dates].to_numpy(dtype=bool)  # (N_series, 30)

    # Mặt nạ phân nhóm sản phẩm
    product_ids_series = pivot_qty.index.get_level_values("product_id").to_numpy()
    is_top_mask = np.isin(product_ids_series, list(top_sku_ids))

    segment_row_masks: Dict[str, np.ndarray] = {
        "overall": np.ones(len(product_ids_series), dtype=bool),
        "top_200": is_top_mask,
        "slow_moving": ~is_top_mask,
    }

    results_clean: Dict[int, Dict[str, Dict[str, BaselineMetrics]]] = {}
    results_raw: Dict[int, Dict[str, Dict[str, BaselineMetrics]]] = {}
    best_baselines: Dict[int, str] = {}

    for H in sorted(horizons):
        if H > 30:
            raise ValueError(f"Horizon {H} vượt quá cửa sổ kiểm tra 30 ngày.")

        # 1. Dự báo Baseline 1 (Weekly Naive) cho tầm H
        last_7_train = history_qty_matrix[:, -7:]
        reps = int(np.ceil(H / 7))
        pred_naive_H = np.tile(last_7_train, (1, reps))[:, :H]

        # 2. Dự báo Baseline 2 (Rolling Mean 28D) cho tầm H
        window_size = min(28, history_qty_matrix.shape[1])
        last_28_train = history_qty_matrix[:, -window_size:]
        mean_28 = np.mean(last_28_train, axis=1, keepdims=True)
        pred_ma28_H = np.repeat(mean_28, H, axis=1)

        actual_H = test_qty_matrix[:, :H]
        stockout_H = test_stockout_matrix[:, :H]

        # 3. Mốc kiểm tra tham chiếu: Dự báo toàn 0 (Zero Forecast)
        pred_zero_H = np.zeros_like(actual_H, dtype=float)

        model_preds = {
            "weekly_naive": pred_naive_H,
            "rolling_mean_28": pred_ma28_H,
            "zero_forecast": pred_zero_H,
        }

        results_clean[H] = {}
        results_raw[H] = {}

        for model_name, pred_matrix in model_preds.items():
            results_clean[H][model_name] = {}
            results_raw[H][model_name] = {}

            for seg_name, row_mask in segment_row_masks.items():
                if not np.any(row_mask):
                    empty_m = BaselineMetrics(
                        mae=0.0,
                        wape=None,
                        sample_count=0,
                        total_actual=0.0,
                    )
                    results_raw[H][model_name][seg_name] = empty_m
                    results_clean[H][model_name][seg_name] = empty_m
                    continue

                y_act_sub = actual_H[row_mask, :].ravel()
                y_pred_sub = pred_matrix[row_mask, :].ravel()
                stockout_sub = stockout_H[row_mask, :].ravel()

                # A. Kết quả gộp toàn bộ ngày (Raw)
                mae_raw = calculate_mae(y_act_sub, y_pred_sub)
                wape_raw = calculate_wape(y_act_sub, y_pred_sub, as_percent=False)
                results_raw[H][model_name][seg_name] = BaselineMetrics(
                    mae=mae_raw,
                    wape=wape_raw,
                    sample_count=len(y_act_sub),
                    total_actual=float(np.sum(y_act_sub)),
                )

                # B. Kết quả đã loại bỏ ngày stockout (Clean - Số chính)
                clean_mask = ~stockout_sub
                if np.any(clean_mask):
                    y_act_clean = y_act_sub[clean_mask]
                    y_pred_clean = y_pred_sub[clean_mask]
                    mae_clean = calculate_mae(y_act_clean, y_pred_clean)
                    wape_clean = calculate_wape(y_act_clean, y_pred_clean, as_percent=False)
                    sample_cnt = len(y_act_clean)
                    tot_actual = float(np.sum(y_act_clean))
                else:
                    mae_clean = 0.0
                    wape_clean = None
                    sample_cnt = 0
                    tot_actual = 0.0

                results_clean[H][model_name][seg_name] = BaselineMetrics(
                    mae=mae_clean,
                    wape=wape_clean,
                    sample_count=sample_cnt,
                    total_actual=tot_actual,
                )

        # Xác định baseline tốt nhất trên phân khúc overall của kết quả clean
        best_b = _compare_metrics_for_best(
            results_clean[H]["weekly_naive"]["overall"],
            results_clean[H]["rolling_mean_28"]["overall"],
        )
        best_baselines[H] = best_b

    return BaselineEvaluationReport(
        cutoff_date=cutoff_date,
        test_start_date=test_start_date,
        test_end_date=test_end_date,
        horizons=list(horizons),
        results=results_clean,
        results_raw=results_raw,
        best_baseline=best_baselines,
    )


def format_report(report: BaselineEvaluationReport) -> str:
    """Định dạng báo cáo đánh giá Baseline thành bảng Markdown đẹp mắt.

    Hiển thị cả 2 bảng:
    - BẢNG 1: Đã loại ngày stockout (số chính nghiệm thu AC-05).
    - BẢNG 2: Gộp toàn bộ ngày (tham chiếu).
    WAPE hiển thị dưới dạng phần trăm (%).
    """
    lines = []
    lines.append("=" * 86)
    lines.append("BÁO CÁO ĐÁNH GIÁ MÔ HÌNH BASELINE & CHỈ SỐ MAE/WAPE (TASK #111)")
    lines.append("=" * 86)
    lines.append(f"- Điểm gốc dự báo (Cutoff Date): {report.cutoff_date.strftime('%Y-%m-%d')}")
    lines.append(
        f"- Cửa sổ kiểm tra (Test Window): {report.test_start_date.strftime('%Y-%m-%d')} "
        f"đến {report.test_end_date.strftime('%Y-%m-%d')} (30 ngày)"
    )
    for h in report.horizons:
        lines.append(
            f"- Baseline tốt nhất tầm {h} ngày: {report.best_baseline[h]} "
            f"(WAPE = {report.results[h][report.best_baseline[h]]['overall'].wape * 100:.2f}%)"
        )
    lines.append("")
    lines.append(
        "> Ghi chú: WAPE trên 100% là do dữ liệu rất thưa (~95% ô bằng 0); dự báo toàn 0 cho WAPE đúng 100%."
    )
    lines.append(
        "> Mốc 'zero_forecast' chỉ là mốc kiểm tra đặc trưng dữ liệu thưa, không tham gia vào việc chọn baseline tốt nhất."
    )
    lines.append("")

    def _render_table(
        title: str, results_dict: Dict[int, Dict[str, Dict[str, BaselineMetrics]]]
    ) -> List[str]:
        t_lines = []
        t_lines.append(f"### {title}")
        t_lines.append(
            "| Tầm (ngày) | Mô hình Baseline | Phân nhóm SKU | MAE (sp/ngày) | WAPE (%) | Mẫu (quan sát) | Tổng bán ròng |"
        )
        t_lines.append(
            "|:---|:---|:---|:---:|:---:|:---:|:---:|"
        )
        for h in report.horizons:
            for b_name in ["weekly_naive", "rolling_mean_28", "zero_forecast"]:
                if b_name == "weekly_naive":
                    b_label = f"Naive theo tuần ({b_name})"
                elif b_name == "rolling_mean_28":
                    b_label = f"TB trượt 28 ngày ({b_name})"
                else:
                    b_label = f"Dự báo toàn 0 ({b_name}) [Tham chiếu]"
                for s_name in ["overall", "top_200", "slow_moving"]:
                    s_label = (
                        f"Toàn bộ (Overall - {s_name})"
                        if s_name == "overall"
                        else (
                            f"Top 200 bán chạy ({s_name})"
                            if s_name == "top_200"
                            else f"800 bán chậm ({s_name})"
                        )
                    )
                    m = results_dict[h][b_name][s_name]
                    wape_str = f"{m.wape * 100:.2f}%" if m.wape is not None else "N/A"
                    t_lines.append(
                        f"| {h} ngày | {b_label} | {s_label} | {m.mae:.4f} | {wape_str} | {m.sample_count:,} | {m.total_actual:,.0f} |"
                    )
        t_lines.append("")
        return t_lines

    lines.extend(
        _render_table(
            "BẢNG 1: ĐÃ LOẠI BỎ NGÀY STOCKOUT (SỐ CHÍNH ĐỂ NGHIỆM THU AC-05)",
            report.results,
        )
    )
    lines.extend(
        _render_table(
            "BẢNG 2: GỘP TOÀN BỘ NGÀY (THAM CHIẾU - KỂ CẢ NGÀY STOCKOUT)",
            report.results_raw,
        )
    )
    return "\n".join(lines)
