# Hạ tầng

Phụ trách: SV07.

| Thư mục | Nội dung |
| --- | --- |
| `docker` | Dockerfile, cấu hình Nginx cho frontend |
| `postgres` | Script khởi tạo CSDL, tạo tài khoản chỉ đọc cho forecast |
| `rabbitmq` | Định nghĩa exchange `greenmart.events`, queue, dead-letter |

`docker-compose.yml` đặt ở thư mục gốc để chạy toàn bộ bằng `docker compose up`.
