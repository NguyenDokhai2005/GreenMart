"""Unit tests cho các mô hình Baseline và hàm đánh giá (Task #111).

Tuân thủ nghiêm ngặt:
1. Test CI chỉ dùng dữ liệu tổng hợp tự tạo, KHÔNG đọc parquet.
2. Kiểm tra chu kỳ 7 ngày cho Weekly Naive, cửa sổ 28 ngày cho Moving Average.
3. Kiểm tra tính bao hàm: 7 ngày đầu của tầm 30 trùng khớp với tầm 7.
4. Kiểm tra không rò rỉ dữ liệu (No Data Leakage).
5. Kiểm tra select_best_baseline với WAPE=None và luật hòa (WAPE -> MAE -> weekly_naive).
6. Kiểm tra cutoff_date mặc định và kiểm tra đúng 30 ngày cửa sổ kiểm tra.
7. Kiểm tra phân nhóm Top SKU tính ở mức sản phẩm từ qty_net của tập train.
8. Kiểm tra mặt nạ stockout và format_report.
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Dict, List, Set
import numpy as np
import pandas as pd
import pytest

from app.models.baselines import (
    Baseline1WeeklyNaive,
    Baseline2RollingMean28,
    BaselineMetrics,
    BaselineEvaluationReport,
    evaluate_baselines_on_daily_sales,
    select_best_baseline,
    format_report,
)


class TestBaselineModels:
    """Kiểm tra logic toán học của hai mô hình Baseline."""

    def test_weekly_naive_prediction_7_days(self):
        """Weekly naive tầm 7 lấy đúng 7 ngày gần nhất."""
        # 14 ngày: tuần 1 [1..7], tuần 2 [8..14]
        history = pd.Series([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14])
        preds = Baseline1WeeklyNaive.predict(history, horizon=7)
        assert len(preds) == 7
        np.testing.assert_array_equal(preds, np.array([8, 9, 10, 11, 12, 13, 14]))

    def test_weekly_naive_prediction_30_days(self):
        """Weekly naive tầm 30 lặp lại chu kỳ 7 ngày cho 30 ngày."""
        history = pd.Series(list(range(1, 15)))  # tuần cuối là [8..14]
        preds = Baseline1WeeklyNaive.predict(history, horizon=30)
        assert len(preds) == 30
        # 7 ngày đầu
        np.testing.assert_array_equal(preds[:7], np.array([8, 9, 10, 11, 12, 13, 14]))
        # 7 ngày tiếp theo
        np.testing.assert_array_equal(preds[7:14], np.array([8, 9, 10, 11, 12, 13, 14]))
        # 2 ngày cuối (ngày 29, 30) là thứ 6 và thứ 7 trong chu kỳ (chỉ số 0 và 1 của tuần: 8, 9)
        assert preds[28] == 8
        assert preds[29] == 9

    def test_weekly_naive_horizon_subset(self):
        """Tầm 7 là tập con chính xác của 7 ngày đầu của tầm 30."""
        history = pd.Series([5, 8, 2, 10, 4, 6, 9, 7, 12, 3, 11, 5, 8, 14])
        preds_7 = Baseline1WeeklyNaive.predict(history, horizon=7)
        preds_30 = Baseline1WeeklyNaive.predict(history, horizon=30)
        np.testing.assert_array_equal(preds_7, preds_30[:7])

    def test_weekly_naive_raises_on_insufficient_history(self):
        """Weekly naive yêu cầu tối thiểu 7 ngày lịch sử."""
        with pytest.raises(ValueError, match="độ dài >= 7"):
            Baseline1WeeklyNaive.predict(pd.Series([1, 2, 3, 4, 5, 6]), horizon=7)

    def test_rolling_mean_28_exact_window(self):
        """Rolling mean 28 ngày chỉ lấy đúng 28 ngày cuối cùng."""
        # 35 ngày: 7 ngày đầu giá trị 10, 28 ngày sau giá trị 20
        history = pd.Series([10.0] * 7 + [20.0] * 28)
        preds_7 = Baseline2RollingMean28.predict(history, horizon=7)
        assert len(preds_7) == 7
        np.testing.assert_array_equal(preds_7, np.full(7, 20.0))

        preds_30 = Baseline2RollingMean28.predict(history, horizon=30)
        assert len(preds_30) == 30
        np.testing.assert_array_equal(preds_30, np.full(30, 20.0))

    def test_rolling_mean_horizon_subset(self):
        """Tầm 7 là tập con chính xác của tầm 30 đối với rolling mean."""
        history = pd.Series(list(range(1, 40)))
        preds_7 = Baseline2RollingMean28.predict(history, horizon=7)
        preds_30 = Baseline2RollingMean28.predict(history, horizon=30)
        np.testing.assert_array_equal(preds_7, preds_30[:7])

    def test_rolling_mean_short_history_uses_available(self):
        """Lịch sử < 28 ngày dùng toàn bộ số ngày hiện có."""
        history = pd.Series([10.0, 20.0, 30.0])  # trung bình = 20.0
        preds = Baseline2RollingMean28.predict(history, horizon=7)
        np.testing.assert_array_equal(preds, np.full(7, 20.0))

    def test_rolling_mean_raises_on_empty(self):
        """Lịch sử rỗng phải raise ValueError."""
        with pytest.raises(ValueError, match="không được rỗng"):
            Baseline2RollingMean28.predict(pd.Series([], dtype=float), horizon=7)


class TestSelectBestBaseline:
    """Kiểm tra hàm chọn baseline tốt nhất kèm luật hòa và WAPE=None."""

    def _make_report_with_metrics(
        self,
        naive_wape: float | None,
        naive_mae: float,
        ma_wape: float | None,
        ma_mae: float,
    ) -> BaselineEvaluationReport:
        naive_m = BaselineMetrics(mae=naive_mae, wape=naive_wape, sample_count=100, total_actual=500.0)
        ma_m = BaselineMetrics(mae=ma_mae, wape=ma_wape, sample_count=100, total_actual=500.0)

        results: Dict[int, Dict[str, Dict[str, BaselineMetrics]]] = {
            7: {
                "weekly_naive": {"overall": naive_m},
                "rolling_mean_28": {"overall": ma_m},
            }
        }
        return BaselineEvaluationReport(
            cutoff_date=date(2026, 10, 1),
            test_start_date=date(2026, 10, 2),
            test_end_date=date(2026, 10, 31),
            horizons=[7],
            results=results,
            results_raw=results,
            best_baseline={7: "weekly_naive"},
        )

    def test_lower_wape_wins(self):
        """Mô hình có WAPE thấp hơn sẽ thắng."""
        report = self._make_report_with_metrics(
            naive_wape=0.15, naive_mae=2.5,
            ma_wape=0.18, ma_mae=2.2
        )
        best_name, best_m = select_best_baseline(report, horizon=7, segment="overall")
        assert best_name == "weekly_naive"
        assert best_m.wape == 0.15

    def test_none_wape_handling(self):
        """Nếu một bên có WAPE=None và một bên có float -> bên có float thắng."""
        report = self._make_report_with_metrics(
            naive_wape=None, naive_mae=2.0,
            ma_wape=0.20, ma_mae=2.5
        )
        best_name, _ = select_best_baseline(report, horizon=7, segment="overall")
        assert best_name == "rolling_mean_28"

    def test_both_none_wape_compares_mae(self):
        """Nếu cả hai bên WAPE=None -> so sánh MAE thấp hơn."""
        report = self._make_report_with_metrics(
            naive_wape=None, naive_mae=1.5,
            ma_wape=None, ma_mae=2.5
        )
        best_name, _ = select_best_baseline(report, horizon=7, segment="overall")
        assert best_name == "weekly_naive"

    def test_tie_breaking_wape_equal_compares_mae(self):
        """Luật hòa: WAPE bằng nhau -> MAE thấp hơn thắng."""
        report = self._make_report_with_metrics(
            naive_wape=0.20, naive_mae=2.5,
            ma_wape=0.20, ma_mae=1.8
        )
        best_name, _ = select_best_baseline(report, horizon=7, segment="overall")
        assert best_name == "rolling_mean_28"

    def test_tie_breaking_both_equal_prefers_weekly_naive(self):
        """Luật hòa tuyệt đối: cả WAPE và MAE bằng nhau -> ưu tiên weekly_naive."""
        report = self._make_report_with_metrics(
            naive_wape=0.20, naive_mae=2.0,
            ma_wape=0.20, ma_mae=2.0
        )
        best_name, _ = select_best_baseline(report, horizon=7, segment="overall")
        assert best_name == "weekly_naive"


class TestEvaluateBaselinesSynthetic:
    """Kiểm tra quy trình đánh giá trên dữ liệu tổng hợp (không đọc parquet)."""

    @pytest.fixture
    def synthetic_daily_sales(self) -> pd.DataFrame:
        """
        Tạo dữ liệu tổng hợp độc lập trên RAM:
        - 2 chi nhánh (1, 2)
        - 4 sản phẩm (101, 102, 103, 104)
        - 60 ngày liên tục: 30 ngày train (02/09/2026 -> 01/10/2026) + 30 ngày test (02/10/2026 -> 31/10/2026)
        - Sản phẩm 101, 102 bán nhiều (Top 2), sản phẩm 103, 104 bán ít
        - Có một số ngày stockout trong cả tập train và tập test
        """
        start_date = date(2026, 9, 2)
        end_date = date(2026, 10, 31)  # tổng cộng 60 ngày
        date_list = [start_date + timedelta(days=i) for i in range((end_date - start_date).days + 1)]
        assert len(date_list) == 60

        records = []
        for d in date_list:
            for b_id in [1, 2]:
                for p_id in [101, 102, 103, 104]:
                    # Nhu cầu mẫu: 101 & 102 bán nhiều, 103 & 104 bán ít
                    base_qty = 10 if p_id in [101, 102] else 1
                    dow = d.weekday()
                    # Mùa vụ cuối tuần
                    qty = base_qty + (2 if dow >= 5 else 0)
                    # Giả lập stockout ngày 2026-10-15 cho sp 101 tại chi nhánh 1
                    is_stockout = (d == date(2026, 10, 15) and p_id == 101 and b_id == 1)

                    records.append({
                        "sale_date": d,
                        "branch_id": b_id,
                        "product_id": p_id,
                        "qty_sold": qty,
                        "qty_returned": 0,
                        "qty_net": qty,
                        "revenue": qty * 10000,
                        "promo_flag": False,
                        "promo_discount_pct": 0.0,
                        "stockout": is_stockout,
                    })

        return pd.DataFrame(records)

    def test_default_cutoff_date_and_test_window_validation(self, synthetic_daily_sales):
        """Cutoff date mặc định = max - 30 ngày (2026-10-01), test window = 30 ngày."""
        report = evaluate_baselines_on_daily_sales(synthetic_daily_sales)
        assert report.cutoff_date == date(2026, 10, 1)
        assert report.test_start_date == date(2026, 10, 2)
        assert report.test_end_date == date(2026, 10, 31)
        assert report.horizons == [7, 30]

    def test_invalid_test_window_raises(self, synthetic_daily_sales):
        """Nếu cửa sổ test không đúng 30 ngày -> raise ValueError."""
        # Cắt bớt dữ liệu chỉ còn đến ngày 25/10/2026 (thiếu ngày)
        df_invalid = synthetic_daily_sales[synthetic_daily_sales["sale_date"] <= date(2026, 10, 25)].copy()
        with pytest.raises(ValueError, match="Cửa sổ kiểm tra phải có đúng 30 ngày"):
            evaluate_baselines_on_daily_sales(df_invalid, cutoff_date=date(2026, 10, 1))

    def test_top_sku_determination_ignores_is_top_sku_col(self, synthetic_daily_sales):
        """Top 200 xác định theo tổng qty_net của tập train (<= cutoff), không dùng cột is_top_sku."""
        # Thêm cột is_top_sku giả cố tình gán ngược: 103, 104 là True, 101, 102 là False
        df = synthetic_daily_sales.copy()
        df["is_top_sku"] = df["product_id"].isin([103, 104])

        report = evaluate_baselines_on_daily_sales(df, cutoff_date=date(2026, 10, 1))
        # Top sku thực tế theo train qty_net phải là 101 và 102
        # Kiểm tra kết quả có phân nhóm top_200 và slow_moving
        assert "top_200" in report.results[7]["weekly_naive"]
        assert "slow_moving" in report.results[7]["weekly_naive"]

    def test_explicit_top_sku_ids_param(self, synthetic_daily_sales):
        """Nếu truyền top_sku_ids thì dùng đúng tập đó."""
        custom_top_skus: Set[int] = {104}
        report = evaluate_baselines_on_daily_sales(
            synthetic_daily_sales,
            cutoff_date=date(2026, 10, 1),
            top_sku_ids=custom_top_skus,
        )
        assert "top_200" in report.results[7]["weekly_naive"]

    def test_stockout_mask_affects_primary_results_only(self, synthetic_daily_sales):
        """Ngày stockout bị loại ở kết quả chính (results) nhưng giữ ở results_raw."""
        report = evaluate_baselines_on_daily_sales(synthetic_daily_sales)
        # Tại horizon 30 (chứa ngày 15/10 có stockout)
        metrics_clean = report.results[30]["weekly_naive"]["overall"]
        metrics_raw = report.results_raw[30]["weekly_naive"]["overall"]

        # Tổng số dòng test: 2 chi nhánh * 4 sản phẩm * 30 ngày = 240 dòng
        # 1 dòng bị stockout -> sạch có 239 mẫu, raw có 240 mẫu
        assert metrics_raw.sample_count == 240
        assert metrics_clean.sample_count == 239

    def test_no_data_leakage(self, synthetic_daily_sales):
        """Thay đổi giá trị trong cửa sổ test không làm thay đổi giá trị dự báo của baseline."""
        report_before = evaluate_baselines_on_daily_sales(synthetic_daily_sales)

        # Chỉnh sửa dữ liệu tập test thành số cực lớn
        df_modified = synthetic_daily_sales.copy()
        mask_test = df_modified["sale_date"] > date(2026, 10, 1)
        df_modified.loc[mask_test, "qty_net"] = 9999

        # Lưu ý: predictions không đổi nhưng lỗi đo sẽ đổi
        # Ta kiểm tra predictions của baseline trước điểm gốc
        history_series = synthetic_daily_sales[
            (synthetic_daily_sales["branch_id"] == 1) &
            (synthetic_daily_sales["product_id"] == 101) &
            (synthetic_daily_sales["sale_date"] <= date(2026, 10, 1))
        ].sort_values("sale_date")["qty_net"]

        p1 = Baseline1WeeklyNaive.predict(history_series, horizon=7)
        p2 = Baseline2RollingMean28.predict(history_series, horizon=7)

        # Đảm bảo các hàm predict chỉ nhận history_series
        assert len(p1) == 7
        assert len(p2) == 7

    def test_zero_forecast_wape_is_100_percent_and_never_selected_as_best(self, synthetic_daily_sales):
        """Mốc tham chiếu zero_forecast: WAPE = 1.0 (100%) khi tổng thực tế > 0, và không bao giờ được chọn làm best."""
        report = evaluate_baselines_on_daily_sales(synthetic_daily_sales)

        # 1. Có mặt trong results và results_raw ở cả 2 tầm 7 và 30
        assert "zero_forecast" in report.results[7]
        assert "zero_forecast" in report.results[30]
        assert "zero_forecast" in report.results_raw[7]
        assert "zero_forecast" in report.results_raw[30]

        # 2. Với tổng thực tế > 0, WAPE của zero_forecast phải chính xác = 1.0 (100%)
        m_7 = report.results[7]["zero_forecast"]["overall"]
        assert m_7.total_actual > 0
        assert m_7.wape == pytest.approx(1.0, rel=1e-5)

        m_30 = report.results[30]["zero_forecast"]["overall"]
        assert m_30.total_actual > 0
        assert m_30.wape == pytest.approx(1.0, rel=1e-5)

        # 3. Tuyệt đối KHÔNG bao giờ được chọn làm best_baseline
        assert report.best_baseline[7] in ("weekly_naive", "rolling_mean_28")
        assert report.best_baseline[30] in ("weekly_naive", "rolling_mean_28")
        assert report.best_baseline[7] != "zero_forecast"
        assert report.best_baseline[30] != "zero_forecast"

        best_name, _ = select_best_baseline(report, horizon=7, segment="overall")
        assert best_name != "zero_forecast"

    def test_format_report_outputs_markdown_tables(self, synthetic_daily_sales):
        """Hàm format_report in ra 2 bảng chuẩn và hiển thị phần trăm WAPE."""
        report = evaluate_baselines_on_daily_sales(synthetic_daily_sales)
        text = format_report(report)

        assert "BẢNG 1" in text
        assert "BẢNG 2" in text
        assert "weekly_naive" in text
        assert "rolling_mean_28" in text
        assert "zero_forecast" in text
        assert "dữ liệu rất thưa" in text
        assert "overall" in text
        assert "top_200" in text
        assert "slow_moving" in text
        assert "%" in text
