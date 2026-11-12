# Hướng dẫn Kiểm thử Forecast Service (Task #116)

Tài liệu kiểm thử và ánh xạ tiêu chí chấp nhận cho module tổng hợp `daily_sales` (Task #109) và hàm đo lường sai số `MAE`, `WAPE` (Task #111).

## 1. Ánh xạ Tiêu chí Chấp nhận (AC) sang Test Case

| Tiêu chí chấp nhận (AC) | File kiểm thử | Tên Test Case | Số liệu tính tay kiểm chứng |
|---|---|---|---|
| Khớp số tính tay `daily_sales` (bán & trả khác ngày) | `tests/test_daily_sales.py` | `test_net_quantity_sales_and_return_different_days` | Bán 3 cái ngày D, trả 1 cái ngày D+2:<br>- Ngày D: `qty_sold = 3, qty_returned = 0, qty_net = 3`<br>- Ngày D+2: `qty_sold = 0, qty_returned = 1, qty_net = -1` |
| Phủ ngày 0 (không phát sinh giao dịch) | `tests/test_daily_sales.py` | `test_zero_sales_day_imputation` | 2 chi nhánh × 3 sản phẩm × 3 ngày = 18 dòng có `qty_sold = qty_returned = qty_net = revenue = 0` |
| Loại bỏ đơn không hoàn tất | `tests/test_daily_sales.py` | `test_failed_orders_excluded` | Đơn `CANCELLED`, `EXPIRED`, `REJECTED`, `NEW` không tính vào `qty_sold` (bằng 0) |
| Không âm doanh thu | `tests/test_daily_sales.py` | `test_revenue_non_negative_constraint` | Hoàn tiền 60.000 > bán 30.000 -> `revenue = max(0, 30000 - 60000) = 0` |
| Cờ hết hàng (Stockout) | `tests/test_daily_sales.py` | `test_stockout_flag_mapping` | Đúng theo lịch `MID_DAY` và `FULL_DAY` |
| Cờ & tỷ lệ khuyến mại (Promotion) | `tests/test_daily_sales.py` | `test_multiple_item_promotions_max_discount_pct` | 2 KM 10% & 25% cùng ngày -> lấy `max = 25.0%` |
| Khớp số tính tay MAE | `tests/test_metrics.py` | `test_mae_hand_calculated` | $y = [10, 20, 30]$, $\hat{y} = [12, 18, 33]$<br>Sai số tuyệt đối: $\|10-12\|=2, \|20-18\|=2, \|30-33\|=3$<br>$\text{MAE} = (2 + 2 + 3) / 3 = 7 / 3 \approx 2.333333$ |
| Khớp số tính tay WAPE | `tests/test_metrics.py` | `test_wape_hand_calculated_ratio_and_percent` | $y = [10, 20, 30]$, $\hat{y} = [12, 18, 33]$<br>$\sum \|y - \hat{y}\| = 7$, $\sum y = 60$<br>$\text{WAPE} = 7 / 60 \approx 0.116667$ ($11.67\%$) |
| Biên WAPE khi tổng thực tế = 0 | `tests/test_metrics.py` | `test_wape_all_zeros_actual_and_pred`<br>`test_wape_zero_actual_with_nonzero_pred` | - $y=[0, 0], \hat{y}=[0, 0] \rightarrow 0.0$<br>- $y=[0, 0], \hat{y}=[1, 2] \rightarrow \text{None}$ (tránh chia cho 0) |
| Biên WAPE khi tổng thực tế âm | `tests/test_metrics.py` | `test_wape_negative_actual_sum` | $\sum y < 0$ (do trả nhiều hơn bán) $\rightarrow \text{None}$ |

## 2. Lệnh thực thi kiểm thử

Thực thi trong thư mục `forecast-service/`:

```bash
# Chạy toàn bộ unit test độc lập (không yêu cầu kết nối CSDL)
python -m pytest -m "not integration"

# Chạy riêng các bài kiểm thử tiêu chí #116 kèm chi tiết
python -m pytest tests/test_daily_sales.py tests/test_metrics.py -v --tb=short -m "not integration"
```
