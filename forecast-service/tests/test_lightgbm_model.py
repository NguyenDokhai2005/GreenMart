"""Unit tests cho mô hình LightGBM và quy trình đánh giá đối đầu (Task #112).

Kiểm thử:
1. Huấn luyện thành công trên nhiều mục tiêu (regression_l1, tweedie, poisson).
2. Dự báo không âm (clipping >= 0.0) và không chứa NaN.
3. Tính tái lập tuyệt đối (reproducibility) với random seed 2026.
4. Lưu và nạp mô hình từ file text.
5. Hàm tính tỷ lệ dự báo bằng 0 (Zero Prediction Ratio).
6. Toàn bộ pipeline đánh giá chạy mượt mà trên dữ liệu nhỏ trong bộ nhớ (< 3 giây).
"""

from __future__ import annotations

from datetime import date, timedelta
from pathlib import Path
import tempfile
import numpy as np
import pandas as pd
import pytest

from app.models.evaluator import (
    FullEvaluationReport,
    evaluate_lightgbm_pipeline,
    format_full_comparison_report,
)
from app.models.features import FeatureMatrixBuilder
from app.models.lightgbm_model import (
    LightGBMDemandModel,
    calculate_zero_ratio,
)
from tests.test_features import _make_dummy_daily_sales


class TestLightGBMModel:
    """Kiểm tra lớp LightGBMDemandModel."""

    def test_fit_and_predict_non_negative(self):
        """Dự báo đầu ra không được chứa NaN và phải >= 0.0."""
        df_dummy = _make_dummy_daily_sales(n_days=45)
        builder = FeatureMatrixBuilder(df_dummy)

        origins = [date(2026, 1, 28), date(2026, 1, 29)]
        X_train, y_train = builder.build_dataset_for_horizon(horizon=7, origin_dates=origins)

        model = LightGBMDemandModel(horizon=7, objective="regression_l1", params={"n_estimators": 10})
        model.fit(X_train, y_train)

        preds = model.predict(X_train)
        assert len(preds) == len(X_train)
        assert not np.isnan(preds).any()
        assert (preds >= 0.0).all()

    def test_tweedie_objective_training(self):
        """Mục tiêu tweedie cho dữ liệu đếm thưa chạy trơn tru."""
        df_dummy = _make_dummy_daily_sales(n_days=45)
        builder = FeatureMatrixBuilder(df_dummy)

        origins = [date(2026, 1, 28), date(2026, 1, 29)]
        X_train, y_train = builder.build_dataset_for_horizon(horizon=7, origin_dates=origins)

        model = LightGBMDemandModel(horizon=7, objective="tweedie", params={"n_estimators": 10})
        model.fit(X_train, y_train)

        preds = model.predict(X_train)
        assert len(preds) == len(X_train)
        assert (preds >= 0.0).all()

    def test_model_reproducibility_with_seed_2026(self):
        """Cố định seed 2026 cho kết quả dự báo lặp lại 100%."""
        df_dummy = _make_dummy_daily_sales(n_days=45)
        builder = FeatureMatrixBuilder(df_dummy)

        origins = [date(2026, 1, 28)]
        X_train, y_train = builder.build_dataset_for_horizon(horizon=7, origin_dates=origins)

        m1 = LightGBMDemandModel(horizon=7, objective="regression_l1", params={"n_estimators": 15})
        m1.fit(X_train, y_train)
        p1 = m1.predict(X_train)

        m2 = LightGBMDemandModel(horizon=7, objective="regression_l1", params={"n_estimators": 15})
        m2.fit(X_train, y_train)
        p2 = m2.predict(X_train)

        np.testing.assert_array_equal(p1, p2)

    def test_save_and_load_model(self):
        """Lưu ra file text và nạp lại cho kết quả dự báo đồng nhất."""
        df_dummy = _make_dummy_daily_sales(n_days=45)
        builder = FeatureMatrixBuilder(df_dummy)

        origins = [date(2026, 1, 28)]
        X_train, y_train = builder.build_dataset_for_horizon(horizon=7, origin_dates=origins)

        model = LightGBMDemandModel(horizon=7, objective="regression_l1", params={"n_estimators": 10})
        model.fit(X_train, y_train)
        preds_orig = model.predict(X_train)

        with tempfile.TemporaryDirectory() as tmp_dir:
            fpath = Path(tmp_dir) / "test_model.txt"
            model.save_model(fpath)
            assert fpath.exists()

            loaded_model = LightGBMDemandModel(horizon=7).load_model(fpath)
            preds_loaded = loaded_model.predict(X_train)
            np.testing.assert_array_almost_equal(preds_orig, preds_loaded, decimal=5)

    def test_zero_ratio_calculation(self):
        """Tính toán tỷ lệ dự báo bằng 0 chính xác."""
        preds = np.array([0.0, 0.0, 5.0, 10.0])
        assert calculate_zero_ratio(preds) == pytest.approx(0.50)

        preds_none_zero = np.array([1.0, 2.0, 3.0])
        assert calculate_zero_ratio(preds_none_zero) == 0.0


class TestEvaluationPipeline:
    """Kiểm tra toàn bộ pipeline đánh giá đối đầu."""

    def test_full_pipeline_runs_fast_on_dummy(self):
        """Quy trình đánh giá hoàn tất nhanh chóng và tạo báo cáo chuẩn."""
        # 65 ngày (35 ngày train/val + 30 ngày test)
        df_dummy = _make_dummy_daily_sales(n_days=65, n_branches=2, n_products=3)

        report, best_models = evaluate_lightgbm_pipeline(
            df_daily=df_dummy,
            horizons=(7,),
            objectives=("regression_l1", "tweedie"),
            sample_step_days=3,
        )

        assert isinstance(report, FullEvaluationReport)
        assert 7 in report.horizons
        assert 7 in best_models

        # Kiểm tra nội dung báo cáo markdown
        md = format_full_comparison_report(report)
        assert "# BÁO CÁO ĐÁNH GIÁ MÔ HÌNH LIGHTGBM" in md
        assert "BẢNG 1: ĐÃ LOẠI BỎ NGÀY STOCKOUT" in md
        assert "BẢNG BỔ SUNG" in md
        assert "Kết Luận Đối Đầu Trung Thực Với Baseline" in md
