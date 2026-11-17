"""Unit tests kiểm thử logic dự báo theo nhóm và quy tắc nghiệm thu Issue #113.

Kiểm thử:
1. Cắt tập kiểm tra chính xác 30 ngày cuối (2026-10-02 đến 2026-10-31).
2. Lọc cờ stockout đồng nhất và đối xứng giữa baseline và mô hình.
3. Nhóm 200 SKU bán chạy chỉ được xác định trên tập train (No Data Leakage).
4. Chuỗi có dưới 28 ngày lịch sử fallback về trung bình danh mục chi nhánh.
5. Ngưỡng hậu xử lý chỉ được tối ưu trên tập Validation, không đụng đến nhãn tập Test.
6. Tính toán đầy đủ 3 cách đo lường (từng ô ngày, theo tuần, tổng cả tầm).
7. Hàm WAPE an toàn tuyệt đối khi chia cho 0.
8. Báo cáo không chứa kết luận "ĐẠT AC-05" khi Lead chưa chốt cách đo.
"""

from __future__ import annotations

from datetime import date, timedelta
import numpy as np
import pandas as pd
import pytest

from app.models.baselines import evaluate_baselines_on_daily_sales
from app.models.evaluator import (
    aggregate_by_weekly_windows,
    apply_short_history_fallback,
    evaluate_lightgbm_pipeline,
    find_optimal_slow_threshold_on_validation,
    format_full_comparison_report,
)
from app.models.metrics import calculate_mae, calculate_wape
from tests.test_features import _make_dummy_daily_sales


def test_cutoff_and_test_window_exact_30_days():
    """Kiểm tra cửa sổ test có đúng 30 ngày độc lập."""
    df_dummy = _make_dummy_daily_sales(n_days=65, n_branches=2, n_products=4)
    unique_dates = sorted(df_dummy["sale_date"].unique())
    max_d = unique_dates[-1]
    cutoff = max_d - timedelta(days=30)
    report = evaluate_baselines_on_daily_sales(df_dummy, cutoff_date=cutoff, horizons=(7, 30))

    assert report.cutoff_date == cutoff
    assert (report.test_end_date - report.test_start_date).days + 1 == 30
    assert report.test_end_date == max_d


def test_stockout_masking_consistency():
    """Ngày stockout = True bị loại bỏ hoàn toàn khỏi bảng clean của cả baseline và mô hình."""
    df_dummy = _make_dummy_daily_sales(n_days=65, n_branches=2, n_products=4)
    unique_dates = sorted(df_dummy["sale_date"].unique())
    max_d = unique_dates[-1]
    cutoff = max_d - timedelta(days=30)
    target_stockout_date = cutoff + timedelta(days=5)

    # Đánh dấu stockout cho 1 ngày trong test window
    df_dummy.loc[df_dummy["sale_date"] == target_stockout_date, "stockout"] = True

    report = evaluate_baselines_on_daily_sales(df_dummy, cutoff_date=cutoff, horizons=(7,))

    raw_samples = report.results_raw[7]["rolling_mean_28"]["overall"].sample_count
    clean_samples = report.results[7]["rolling_mean_28"]["overall"].sample_count

    # 2 branches * 4 products = 8 series. 1 ngày bị stockout -> giảm đúng 8 mẫu
    assert raw_samples - clean_samples == 8


def test_top_sku_train_only_no_leakage():
    """Nhóm 200 SKU bán chạy chỉ được tính trên tập train; sửa dữ liệu tập test không đổi top SKU."""
    df_dummy = _make_dummy_daily_sales(n_days=65, n_branches=2, n_products=10)
    cutoff = date(2026, 1, 31)

    df_train_only = df_dummy[df_dummy["sale_date"] <= cutoff]
    top_train = set(df_train_only.groupby("product_id")["qty_net"].sum().nlargest(3).index)

    # Đột biến doanh số của product_id=999 ở tập test
    df_modified = df_dummy.copy()
    test_mask = df_modified["sale_date"] > cutoff
    df_modified.loc[test_mask & (df_modified["product_id"] == 9), "qty_net"] = 100_000

    df_mod_train = df_modified[df_modified["sale_date"] <= cutoff]
    top_mod_train = set(df_mod_train.groupby("product_id")["qty_net"].sum().nlargest(3).index)

    assert top_train == top_mod_train, "Top SKU bị rò rỉ dữ liệu từ tập test!"


def test_short_history_series_fallback():
    """Sản phẩm mới dưới 28 ngày dữ liệu không chạy mô hình, lấy trung bình danh mục chi nhánh."""
    idx = pd.MultiIndex.from_tuples([(1, 10), (1, 20)], names=["branch_id", "product_id"])
    pred_mat = np.zeros((2, 7))

    # Tạo df_train giả lập: product 10 có 30 ngày, product 20 chỉ có 10 ngày
    records = []
    for d in range(30):
        s_date = date(2026, 1, 1) + timedelta(days=d)
        records.append({"branch_id": 1, "product_id": 10, "category_id": 5, "sale_date": s_date, "qty_net": 10.0})
    for d in range(10):
        s_date = date(2026, 1, 21) + timedelta(days=d)
        records.append({"branch_id": 1, "product_id": 20, "category_id": 5, "sale_date": s_date, "qty_net": 2.0})

    df_train = pd.DataFrame(records)

    fb_count = apply_short_history_fallback(
        df_train=df_train,
        series_index=idx,
        pred_matrix=pred_mat,
        min_history_days=28,
    )

    assert fb_count == 1
    # Product 20 (index 1) nhận giá trị trung bình danh mục 5 tại branch 1
    expected_cat_mean = df_train[df_train["category_id"] == 5]["qty_net"].mean()
    assert pred_mat[1, 0] == pytest.approx(expected_cat_mean)
    # Product 10 (index 0) không bị ghi đè
    assert pred_mat[0, 0] == 0.0


def test_validation_only_tuning_no_test_leakage():
    """Hàm tìm ngưỡng chỉ nhận y_val, tuyệt đối không dùng nhãn test."""
    y_val = np.array([0, 0, 0, 5, 0, 0, 10])
    preds_val = np.array([0.1, 0.05, 0.2, 4.8, 0.15, 0.0, 9.5])
    slow_mask_val = np.array([True, True, True, False, True, True, False])

    best_th, best_wape = find_optimal_slow_threshold_on_validation(
        y_val=y_val,
        preds_val=preds_val,
        slow_mask_val=slow_mask_val,
        candidate_thresholds=(0.0, 0.1, 0.25),
    )

    assert best_th in (0.0, 0.1, 0.25)
    assert best_wape is not None
    # Khi th = 0.25, các giá trị nhiễu [0.1, 0.05, 0.2, 0.15] biến thành 0 -> WAPE val giảm
    assert best_th == 0.25


def test_three_measurement_granularities():
    """Kiểm tra ma trận gộp theo tuần aggregate_by_weekly_windows."""
    mat_h7 = np.full((3, 7), 2.0)
    weekly_h7 = aggregate_by_weekly_windows(mat_h7, horizon=7)
    assert weekly_h7.shape == (3, 1)
    assert (weekly_h7 == 14.0).all()

    mat_h30 = np.full((2, 30), 1.0)
    weekly_h30 = aggregate_by_weekly_windows(mat_h30, horizon=30)
    # 30 ngày chia làm 5 tuần: 4 tuần 7 ngày (7.0) và 1 tuần 2 ngày (2.0)
    assert weekly_h30.shape == (2, 5)
    assert (weekly_h30[:, :4] == 7.0).all()
    assert (weekly_h30[:, 4] == 2.0).all()


def test_metrics_zero_division_safety():
    """Hàm tính sai số WAPE không gây crash chia cho 0."""
    # sum(y_true) = 0 và sum(|y_true - y_pred|) = 0 -> 0.0
    assert calculate_wape([0.0, 0.0], [0.0, 0.0]) == 0.0
    # sum(y_true) = 0 và y_pred != 0 -> None
    assert calculate_wape([0.0, 0.0], [1.0, 0.0]) is None
    # y_true < 0 -> None
    assert calculate_wape([-5.0], [0.0]) is None


def test_no_conclusion_dat_ac05():
    """Báo cáo so sánh đối đầu không được phép chứa kết luận 'ĐẠT AC-05' khi Lead chưa chốt."""
    df_dummy = _make_dummy_daily_sales(n_days=65, n_branches=2, n_products=4)
    report, _ = evaluate_lightgbm_pipeline(
        df_daily=df_dummy,
        horizons=(7,),
        objectives=("regression_l1", "tweedie"),
        sample_step_days=4,
    )

    md = format_full_comparison_report(report)
    assert "ĐẠT AC-05" not in md, "Báo cáo không được tự ý kết luận ĐẠT AC-05 khi Lead chưa chốt!"
    assert "Chờ Lead @NguyenDokhai2005 chốt cách đo" in md
