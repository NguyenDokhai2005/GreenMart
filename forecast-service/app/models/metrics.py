"""Module đo lường sai số dự báo MAE và WAPE cho GreenMart (Task #111).

Tuân thủ nghiêm ngặt đặc tả DT03, SRS 3.9 và Phân tích nghiệp vụ mục 9:
- MAE: Sai số tuyệt đối trung bình trên các đơn vị bán.
- WAPE: Sai số phần trăm tuyệt đối có trọng số, tính gộp (pooled) trên mọi dòng
  (chi nhánh × sản phẩm × ngày) để phản ánh chính xác sai số trên toàn danh mục:
      WAPE = sum(|y_true - y_pred|) / sum(y_true)
"""

from __future__ import annotations

from typing import List, Optional, Union
import numpy as np
import pandas as pd


def calculate_mae(
    y_true: Union[np.ndarray, List[float], pd.Series],
    y_pred: Union[np.ndarray, List[float], pd.Series],
) -> float:
    """Tính sai số tuyệt đối trung bình (Mean Absolute Error - MAE).

    Công thức:
        MAE = (1 / N) * sum(|y_true - y_pred|)

    Tham số:
        y_true: Mảng giá trị thực tế (qty_net).
        y_pred: Mảng giá trị dự báo tương ứng.

    Trả về:
        Giá trị float của MAE (cùng đơn vị với y_true).

    Ngoại lệ:
        ValueError: Nếu mảng đầu vào rỗng hoặc hai mảng không cùng kích thước.
    """
    y_t = np.asarray(y_true, dtype=float).ravel()
    y_p = np.asarray(y_pred, dtype=float).ravel()

    if y_t.size == 0 or y_p.size == 0:
        raise ValueError("Đầu vào y_true và y_pred không được rỗng.")

    if y_t.size != y_p.size:
        raise ValueError(
            f"y_true và y_pred phải có cùng kích thước, nhận được ({y_t.size} và {y_p.size})."
        )

    return float(np.mean(np.abs(y_t - y_p)))


def calculate_wape(
    y_true: Union[np.ndarray, List[float], pd.Series],
    y_pred: Union[np.ndarray, List[float], pd.Series],
    as_percent: bool = False,
) -> Optional[float]:
    """Tính sai số phần trăm tuyệt đối có trọng số (Weighted Absolute Percentage Error - WAPE).

    Đặc tả nghiệp vụ (BA mục 9 & SRS 3.9):
    WAPE gộp (pooled) trên mọi dòng (chi nhánh × sản phẩm × ngày):
        WAPE = sum(|y_true - y_pred|) / sum(y_true)

    Khắc phục triệt để nhược điểm của MAPE khi dữ liệu bán lẻ có nhiều ngày không bán
    (y_true = 0), tránh lỗi chia cho 0 ở từng quan sát đơn lẻ.

    Quy tắc xử lý biên:
    - Nếu sum(y_true) == 0 và sum(|y_true - y_pred|) == 0: Trả về 0.0 (khớp tuyệt đối).
    - Nếu sum(y_true) <= 0 và sum(|y_true - y_pred|) > 0: Trả về None (tránh chia cho 0
      hoặc sai số phần trăm âm do trả hàng vượt bán).

    Tham số:
        y_true: Mảng giá trị thực tế (qty_net).
        y_pred: Mảng giá trị dự báo tương ứng.
        as_percent: Nếu True, kết quả nhân với 100 (đơn vị %). Mặc định False (tỷ lệ 0.0 - 1.0).

    Trả về:
        Giá trị float của WAPE hoặc None nếu tổng thực tế không hợp lệ để làm mẫu số.

    Ngoại lệ:
        ValueError: Nếu mảng đầu vào rỗng hoặc hai mảng không cùng kích thước.
    """
    y_t = np.asarray(y_true, dtype=float).ravel()
    y_p = np.asarray(y_pred, dtype=float).ravel()

    if y_t.size == 0 or y_p.size == 0:
        raise ValueError("Đầu vào y_true và y_pred không được rỗng.")

    if y_t.size != y_p.size:
        raise ValueError(
            f"y_true và y_pred phải có cùng kích thước, nhận được ({y_t.size} và {y_p.size})."
        )

    total_abs_err = float(np.sum(np.abs(y_t - y_p)))
    total_actual = float(np.sum(y_t))

    if total_actual <= 0.0:
        if total_abs_err == 0.0:
            return 0.0
        return None

    ratio = total_abs_err / total_actual
    return float(ratio * 100.0 if as_percent else ratio)
