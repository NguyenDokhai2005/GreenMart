# Kiến trúc hệ thống – GreenMart

Khớp SRS mục 2.4, 2.5, 8.3–8.5 và `docs/api/openapi.yaml`.

| # | Sơ đồ | Nội dung | Ảnh |
| --- | --- | --- | --- |
| 00 | Tổng thể | Các container chạy bằng Docker Compose và cách chúng giao tiếp | [png](png/00-kien-truc-tong-the.png) |
| 01 | Module backend | 10 module trong `vn.greenmart.*` và luồng gọi chính | [png](png/01-module-backend.png) |

## Thành phần

| Thành phần | Công nghệ | Phụ trách | Vai trò |
| --- | --- | --- | --- |
| Frontend | React + Vite, nginx (cổng 3000) | SV04, SV05 | Giao diện web tiếng Việt cho 5 vai trò |
| Backend | Spring Boot 3, Java 21 (cổng 8080) | SV02, SV03 | Toàn bộ nghiệp vụ; sở hữu các bảng nghiệp vụ |
| PostgreSQL | 16 | SV03 | Nguồn dữ liệu duy nhất; schema quản lý bằng Flyway |
| RabbitMQ | 3.13 | SV07 | Sự kiện nội bộ `order.*`, `stock.*`, `forecast.completed` |
| Redis | 7.4 | SV02 | Dữ liệu ngắn hạn, ví dụ `Idempotency-Key` của đơn POS (24 giờ) |
| Forecast service | Python 3.11, FastAPI, LightGBM | SV06 | Tổng hợp `daily_sales`, dự báo 7/30 ngày, chạy 02:00 hằng đêm |
| Mock connector | – | SV07 | Giả lập kênh WEB, MARKET |

## Giao tiếp

| Từ → Đến | Cách | Ghi chú |
| --- | --- | --- |
| Frontend → Backend | REST `/api/v1`, JSON, Bearer JWT | Hợp đồng: `openapi.yaml` |
| Mock connector → Backend | `POST /api/v1/integrations/{channelCode}/orders` | Ký HMAC-SHA256 ở `X-Signature`; trùng đơn trả đơn cũ (BR-05) |
| Backend → Mock connector | `PUT /mock/{channelCode}/stock`, `PUT /mock/{channelCode}/orders/{id}/status` | Thử lại 5 lần (1, 2, 4, 8, 16 giây) rồi vào hàng đợi lỗi |
| Backend → RabbitMQ | Outbox | Ghi `outbox_event` cùng transaction rồi mới đẩy, không mất sự kiện khi RabbitMQ lỗi |
| Backend → Forecast service | `POST /forecast/run`, `GET /forecast/runs/{runId}` | Chạy theo yêu cầu; trả `runId` ngay |
| Forecast service → PostgreSQL | Tài khoản chỉ đọc (`forecast_ro`) | Chỉ ghi 3 bảng `daily_sales`, `forecast_run`, `forecast_result` |
| Forecast service → RabbitMQ | `forecast.completed` | Backend nhận, đọc kết quả, tính ROP |

## Nguyên tắc

- Backend là modular monolith: module chỉ gọi nhau qua interface service hoặc sự kiện, không truy cập repository của module khác.
- Thay đổi tồn kho chỉ đi qua `InventoryService` (conditional UPDATE, cùng transaction với sổ kho).
- Mọi lỗi API theo `ErrorResponse` và `ErrorCode` trong `common`.
- Forecast service và mock connector hiện chưa có trong `docker-compose.yml`; thêm vào khi các thành phần này có mã nguồn.

## Sửa sơ đồ

Sửa file `.puml`, rồi render lại ảnh:

```bash
cd docs/design/architecture/png
java -jar plantuml.jar -charset UTF-8 -tpng -o "$PWD" ../0*.puml
```
