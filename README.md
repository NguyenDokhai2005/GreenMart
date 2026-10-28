# GreenMart – Hệ thống bán hàng đa kênh, tồn kho và dự báo nhu cầu (DT03)

Hệ thống quản trị bán hàng omnichannel cho chuỗi 3 cửa hàng GreenMart: POS, đơn online qua mock connector, tồn kho theo chi nhánh (chống tồn âm khi bán đồng thời), dashboard và dự báo nhu cầu 7/30 ngày.

## Công nghệ
Spring Boot 3 (Java 21) · React + TypeScript + Vite · PostgreSQL · Redis · RabbitMQ · Python (FastAPI, LightGBM) · Docker Compose · GitHub Actions

## Nhóm
| SV | Vai trò | GitHub |
| --- | --- | --- |
| 01 | BA / Lead | @NguyenDokhai2005 |
| 02 | Backend Sales | @t-boai |
| 03 | Backend Inventory | @Dat9161 |
| 04 | Frontend Sales | @voxuanvy611-sys |
| 05 | Frontend Operations | @TranVanVu2502 |
| 06 | Data / AI | @hongthienhao |
| 07 | Integration / DevOps | @thanhcode118 |
| 08 | QA / Security | @Huynh-Phuc-Dat |

## Cấu trúc thư mục
```
GreenMart/
├── backend/            Spring Boot – modular monolith (vn.greenmart.<module>)
├── frontend/           React + TypeScript + Vite – khu POS và Operations
├── forecast-service/   Python FastAPI – dữ liệu, dự báo, đánh giá MAE/WAPE
├── mock-connector/     Kênh bán giả lập WEB và MARKET
├── tests/              Postman (api), k6 (load), Playwright (e2e)
├── infra/              Docker, khởi tạo PostgreSQL, cấu hình RabbitMQ
├── scripts/            Sao lưu, khôi phục, seed dữ liệu
├── docs/               SRS, thiết kế, openapi.yaml, kiểm thử, vận hành, báo cáo
└── .github/            CI, template issue/PR, CODEOWNERS
```
Mỗi thư mục có README ghi rõ nội dung và người phụ trách.

## Cách làm việc
Xem [CONTRIBUTING.md](CONTRIBUTING.md). Việc của từng người nằm trong Issues, chia theo 4 milestone (tuần).

## Chạy dự án
Xem [hướng dẫn quản lý biến môi trường và secrets](docs/operations/SECRETS.md).

1. Sao chép `.env.example` thành `.env` và điền giá trị.
2. Chạy `docker compose up --build -d --wait` để chạy toàn bộ hệ thống khi đã có
   Dockerfile backend/frontend và khung ứng dụng từ các PR liên quan.

### Chạy backend từ IDE, hạ tầng bằng Docker

Yêu cầu Docker Engine/Docker Desktop đang chạy và Docker Compose v2. Tại thư mục
gốc, sao chép `.env.example` thành `.env` nếu chưa có, rồi thay các giá trị
`change-me`. Không commit `.env`.

```powershell
docker compose config --quiet
docker compose up -d --wait postgres redis rabbitmq
docker compose ps
```

Compose tự ghép `docker-compose.yml` với `docker-compose.override.yml`. File
override mở các cổng sau chỉ trên `127.0.0.1` của máy chạy Docker:

| Dịch vụ | Địa chỉ từ máy local |
| --- | --- |
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |
| RabbitMQ AMQP | `localhost:5672` |
| RabbitMQ Management | http://localhost:15672 |

Trong cấu hình Run/Debug của IDE, đặt các biến môi trường sau; thay phần trong
`<...>` bằng giá trị tương ứng trong `.env` (IDE không tự đọc `.env`):

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/<POSTGRES_DB>
SPRING_DATASOURCE_USERNAME=<POSTGRES_USER>
SPRING_DATASOURCE_PASSWORD=<POSTGRES_PASSWORD>
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=<REDIS_PASSWORD>
SPRING_RABBITMQ_HOST=localhost
SPRING_RABBITMQ_PORT=5672
SPRING_RABBITMQ_USERNAME=<RABBITMQ_DEFAULT_USER>
SPRING_RABBITMQ_PASSWORD=<RABBITMQ_DEFAULT_PASS>
```

Dùng tài khoản `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` để đăng nhập
trang quản trị. Nếu backend Docker đang chạy, dừng bằng
`docker compose stop backend` trước khi chạy backend trong IDE để tránh trùng
cổng 8080. Backend chạy trong Docker vẫn dùng hostname `postgres`, `redis`,
`rabbitmq` theo file Compose gốc.

Để chỉ dùng cấu hình gốc, không mở cổng hạ tầng ra máy, chỉ định file rõ ràng:

```powershell
docker compose -f docker-compose.yml up -d --wait postgres redis rabbitmq
```

Nếu cổng 5432/6379/5672/15672 đã được dùng, dừng dịch vụ local đang chiếm cổng
hoặc đổi cổng phía máy trong override và cập nhật cấu hình IDE tương ứng.
Dừng môi trường bằng `docker compose down`; dữ liệu volume vẫn được giữ lại.
