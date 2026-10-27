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
1. Sao chép `.env.example` thành `.env` và điền giá trị.
2. Chạy `docker compose up` (có sau khi SV07 hoàn thành `docker-compose.yml`, hạn 03/11).
