# Forecast service – Python

Python 3.11 · FastAPI · pandas · LightGBM. Phụ trách: SV06.

| Thư mục | Nội dung |
| --- | --- |
| `app/api` | Endpoint `POST /forecast/run`, `GET /forecast/runs/{id}` |
| `app/pipelines` | Tổng hợp `daily_sales`, tạo đặc trưng, huấn luyện, dự báo |
| `app/models` | Baseline (naive theo tuần, trung bình trượt 28 ngày) và LightGBM |
| `app/db` | Kết nối PostgreSQL (tài khoản chỉ đọc + ghi 3 bảng của dịch vụ) |
| `scripts` | Script sinh dữ liệu: 3 chi nhánh, 1.000 sản phẩm, 10.000 đơn |
| `notebooks` | Khám phá dữ liệu, đánh giá MAE/WAPE |
| `tests` | Unit test pipeline và hàm tính sai số |

Quy tắc dữ liệu: xem mục 7 SRS và tin nhắn thống nhất `daily_sales`.
