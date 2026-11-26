# Runtime OpenAPI của Forecast

Production mặc định `FORECAST_DOCS_ENABLED=false`. Các URL `/openapi.json`, `/docs` và `/redoc` trả 404. API nghiệp vụ tiếp tục yêu cầu Bearer token; health chỉ trả trạng thái.

Khi phát triển cục bộ, đặt `FORECAST_DOCS_ENABLED=true` trước khi tạo app hoặc recreate container Forecast để bật schema và giao diện docs. Đặt lại false khi kết thúc. Hợp đồng chính thức được quản lý ở `docs/api/openapi.yaml`.

Compose bind cổng Forecast vào `127.0.0.1:8001`; Backend gọi service qua mạng Compose. Khi đổi port bằng override, tiếp tục bind vào 127.0.0.1. Không đặt token vào URL của docs.
