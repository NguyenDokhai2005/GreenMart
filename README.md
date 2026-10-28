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

Yêu cầu: Git, Docker Engine/Docker Desktop đang chạy Linux containers và Docker
Compose v2. Dùng PowerShell 7 để chạy script kiểm tra bên dưới. Không cần cài
Java/Maven/Node trên máy nếu chỉ chạy bằng Docker.

Các phần nền móng được giao ở #122–#126. Còn chờ khung React/Vite của SV04
(#68) trước khi chạy đủ 5 service.
Nếu frontend chưa có `package.json` và `package-lock.json`, build frontend sẽ lỗi;
đây là phụ thuộc chưa hoàn thành, không phải một hệ thống đã nghiệm thu.

Tại thư mục gốc:

```powershell
Copy-Item .env.example .env
# Sửa .env: thay mọi mật khẩu/secret change-me bằng giá trị riêng trước khi chạy.
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 300
```

Không commit `.env`. Tên biến RabbitMQ là `RABBITMQ_DEFAULT_USER` và
`RABBITMQ_DEFAULT_PASS`; Redis cần cùng `REDIS_PASSWORD` ở server và backend.
Host trong Docker là `postgres`, `redis`, `rabbitmq`, không phải `localhost`.
Múi giờ nghiệp vụ là `Asia/Ho_Chi_Minh`. JVM nhận tùy chọn qua `JAVA_TOOL_OPTIONS`.

## Truy cập và kiểm tra

| Dịch vụ | Địa chỉ từ máy chạy Docker |
| --- | --- |
| Frontend | http://localhost:3000 |
| Backend API | http://localhost:8080/api/v1 |
| Health tổng hợp | http://localhost:8080/actuator/health |
| Readiness / liveness | `/actuator/health/readiness`, `/actuator/health/liveness` trên backend |
| PostgreSQL / Redis / RabbitMQ | Các cổng localhost qua override; xem hướng dẫn IDE bên dưới |

```powershell
docker compose ps
pwsh -File scripts/check-health.ps1
```

Health tổng hợp trả HTTP 200/UP khi DB, Redis, RabbitMQ truy cập được.
Readiness chỉ gồm `readinessState,db,redis`: RabbitMQ lỗi không làm readiness
lỗi nếu backend, DB và Redis vẫn sẵn sàng. Liveness chỉ kiểm tra tiến trình sống.
Chỉ phơi health/info, `/actuator/env` trả 404.
Không phơi thông tin chi tiết kết nối hoặc stack trace.

Kiểm tra sự cố broker trên môi trường phát triển riêng:

```powershell
pwsh -File scripts/check-health.ps1 -CheckBrokerFailure
```

Script dừng RabbitMQ, kiểm tra health tổng hợp 503/DOWN nhưng readiness và
liveness vẫn 200/UP,
khởi động lại trong `finally`, rồi đợi health phục hồi. Không chạy khi người khác
đang dùng chung môi trường. Đây chưa phải bài kiểm tra outbox EX-12 (#138).

Theo thiết kế outbox, giao dịch và sự kiện chờ gửi được lưu trong DB; broker
phục hồi thì bộ gửi xử lý tiếp. Readiness độc lập RabbitMQ không thay thế việc
triển khai và kiểm thử outbox EX-12.

Healthcheck backend trong Dockerfile và Compose dùng
`/actuator/health/readiness` để broker lỗi không đánh dấu backend `unhealthy`.
Cấu hình `management.*` được quản lý trong `application.yml`;
không dùng file `application.properties` riêng.

## Chạy từng phần trong lúc chờ frontend

```powershell
docker compose up -d --wait postgres redis rabbitmq
docker compose up --build -d --wait backend
```

Backend scaffold chưa có API nghiệp vụ; `/api/v1` có thể trả 404.
Migration thuộc SV03; Hibernate dùng `validate`, không tự sinh schema.
Role `forecast_ro` được tạo lúc DB khởi tạo. Quyền ghi cho `forecast_ro` cấp trong
migration `V2__grant_forecast.sql` (PR #198).
Tài khoản đăng nhập demo chờ seed từ SV03/SV08; repo hiện chưa cung cấp tài khoản.

## Dừng, chạy lại và đọc log

```powershell
docker compose logs --tail 100 backend
docker compose logs --tail 100 postgres redis rabbitmq
docker compose down
docker compose up -d --wait
```

`down` giữ volume dữ liệu. `docker compose down -v` xóa toàn bộ dữ liệu của
project: chỉ dùng khi chủ động reset môi trường demo và đã sao lưu dữ liệu cần giữ.
Script backup/restore và seed được bổ sung ở các issue sau.

## Xử lý lỗi thường gặp

| Hiện tượng | Cách xử lý |
| --- | --- |
| Không kết nối Docker Engine | Mở Docker Desktop, chờ Engine sẵn sàng và kiểm tra `docker info` |
| Thiếu `.env` hoặc biến bắt buộc | Sao chép file mẫu, điền giá trị; chạy `docker compose config --quiet` |
| Frontend thiếu package/lockfile | Chờ khung SV04; dùng lệnh chạy từng phần ở trên |
| Backend unhealthy | Đọc log backend và ba service hạ tầng; kiểm tra mật khẩu/hostname |
| Đổi mật khẩu DB trong `.env` nhưng không kết nối được | Volume cũ giữ mật khẩu cũ; đổi role có kiểm soát hoặc reset dữ liệu phát triển khi được phép |
| Chưa có bảng / Flyway lỗi | Phối hợp SV03; không sửa bằng Hibernate auto-create hoặc SQL thủ công |
| Cổng 3000/8080 đã được dùng | Dừng ứng dụng chiếm cổng hoặc thống nhất đổi cổng local |

Không chia sẻ `.env`, `docker inspect` hoặc đầu ra Compose có chứa secrets.

## Chạy backend từ IDE, hạ tầng bằng Docker

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
