"""Unit tests cho các hàm đo lường sai số MAE và WAPE (Task #111).

Tuân thủ nghiêm ngặt:
1. WAPE gộp (pooled) trên mọi dòng: sum|y - y_hat| / sum(y).
2. Đầu vào rỗng hoặc khác độ dài -> raise ValueError (không trả 0.0).
3. Test tính tay, test ngoại lệ tổng y = 0, test các kiểu dữ liệu (list, np.ndarray, pd.Series).
"""

from __future__ import annotations

import math
import numpy as np
import pandas as pd
import pytest

from app.models.metrics import calculate_mae, calculate_wape


class TestCalculateMae:
    """Kiểm tra hàm calculate_mae."""

    def test_mae_hand_calculated(self):
        """Test số tính tay: y=[10, 20, 30], y_pred=[12, 18, 33] -> MAE = 7/3 ≈ 2.333..."""
        y_true = [10.0, 20.0, 30.0]
        y_pred = [12.0, 18.0, 33.0]
        # Sai số tuyệt đối: |10-12|=2, |20-18|=2, |30-33|=3 -> tổng = 7 -> mean = 7/3
        expected_mae = 7.0 / 3.0
        assert calculate_mae(y_true, y_pred) == pytest.approx(expected_mae, rel=1e-6)

    def test_mae_perfect_prediction(self):
        """Dự báo hoàn hảo sai số bằng 0.0."""
        y_true = [5.0, 15.0, 25.0, 50.0]
        y_pred = [5.0, 15.0, 25.0, 50.0]
        assert calculate_mae(y_true, y_pred) == 0.0

    def test_mae_raises_on_empty_input(self):
        """Đầu vào rỗng phải raise ValueError."""
        with pytest.raises(ValueError, match="không được rỗng"):
            calculate_mae([], [])

    def test_mae_raises_on_different_lengths(self):
        """Đầu vào khác độ dài phải raise ValueError."""
        with pytest.raises(ValueError, match="cùng kích thước"):
            calculate_mae([1.0, 2.0], [1.0, 2.0, 3.0])

    def test_mae_supports_numpy_and_pandas(self):
        """Hỗ trợ numpy array và pandas Series."""
        y_true_np = np.array([10.0, 20.0])
        y_pred_np = np.array([12.0, 22.0])
        assert calculate_mae(y_true_np, y_pred_np) == 2.0

        y_true_pd = pd.Series([10.0, 20.0])
        y_pred_pd = pd.Series([12.0, 22.0])
        assert calculate_mae(y_true_pd, y_pred_pd) == 2.0


class TestCalculateWape:
    """Kiểm tra hàm calculate_wape."""

    def test_wape_hand_calculated_ratio_and_percent(self):
        """Test số tính tay: y=[10, 20, 30], y_pred=[12, 18, 33] -> WAPE = 7/60 ≈ 0.1167 (11.67%)."""
        y_true = [10.0, 20.0, 30.0]
        y_pred = [12.0, 18.0, 33.0]
        # sum(|e|) = 7.0, sum(y) = 60.0 -> ratio = 7/60 ≈ 0.1166667
        expected_ratio = 7.0 / 60.0
        assert calculate_wape(y_true, y_pred, as_percent=False) == pytest.approx(expected_ratio, rel=1e-6)

        # Mặc định dạng tỷ lệ (0.1167)
        assert calculate_wape(y_true, y_pred) == pytest.approx(expected_ratio, rel=1e-6)

        # Dạng phần trăm
        assert calculate_wape(y_true, y_pred, as_percent=True) == pytest.approx(expected_ratio * 100.0, rel=1e-6)

    def test_wape_perfect_prediction(self):
        """Dự báo hoàn hảo WAPE bằng 0.0."""
        y_true = [10.0, 20.0]
        y_pred = [10.0, 20.0]
        assert calculate_wape(y_true, y_pred) == 0.0

    def test_wape_all_zeros_actual_and_pred(self):
        """Khi cả thực tế và dự báo đều bằng 0 -> sai số bằng 0, WAPE = 0.0."""
        y_true = [0.0, 0.0, 0.0]
        y_pred = [0.0, 0.0, 0.0]
        assert calculate_wape(y_true, y_pred) == 0.0

    def test_wape_zero_actual_with_nonzero_pred(self):
        """Khi tổng thực tế = 0 nhưng có dự báo sai lệch -> trả về None (không chia cho 0)."""
        y_true = [0.0, 0.0, 0.0]
        y_pred = [1.0, 2.0, 0.0]
        assert calculate_wape(y_true, y_pred) is None
        assert calculate_wape(y_true, y_pred, as_percent=True) is None

    def test_wape_negative_actual_sum(self):
        """Nếu tổng thực tế <= 0 do trả hàng nhiều hơn bán -> trả về None (tránh số âm/chia 0)."""
        y_true = [-5.0, 2.0]  # sum = -3.0
        y_pred = [0.0, 0.0]
        assert calculate_wape(y_true, y_pred) is None

    def test_wape_raises_on_empty_input(self):
        """Đầu vào rỗng phải raise ValueError."""
        with pytest.raises(ValueError, match="không được rỗng"):
            calculate_wape([], [])

    def test_wape_raises_on_different_lengths(self):
        """Đầu vào khác độ dài phải raise ValueError."""
        with pytest.raises(ValueError, match="cùng kích thước"):
            calculate_wape([1.0, 2.0], [1.0])

    def test_wape_supports_numpy_and_pandas(self):
        """Hỗ trợ numpy array và pandas Series."""
        y_true = np.array([20.0, 80.0])
        y_pred = np.array([25.0, 75.0])
        # sum(|e|) = 10, sum(y) = 100 -> ratio = 0.1
        assert calculate_wape(y_true, y_pred) == pytest.approx(0.1, rel=1e-6)

        s_true = pd.Series([20.0, 80.0])
        s_pred = pd.Series([25.0, 75.0])
        assert calculate_wape(s_true, s_pred) == pytest.approx(0.1, rel=1e-6)
