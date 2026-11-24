# Chính sách runtime docs Forecast — #431

Trạng thái: đề xuất trong PR, chờ Lead duyệt trước khi triển khai. Không thay đổi hợp đồng API nghiệp vụ hoặc cơ chế Bearer nội bộ của #344.

1. Production mặc định không xuất bản `/openapi.json`, `/docs`, `/redoc`: cả ba trả 404 khi không đặt flag hoặc `FORECAST_DOCS_ENABLED` khác `true`.
2. Development có thể bật rõ ràng `FORECAST_DOCS_ENABLED=true` trước khi tạo ứng dụng. Flag này không cấp quyền truy cập dữ liệu: API nghiệp vụ vẫn trả 401 khi không có token hợp lệ.
3. Health chỉ trả trạng thái tối thiểu; không công khai token, DSN hoặc chi tiết kết nối.
4. Contract tĩnh trong `docs/api/openapi.yaml` vẫn là hợp đồng chính thức. Runtime schema khi bật development mô tả riêng service nội bộ.

Ứng dụng và cấu hình Compose được review cùng PR. Cổng Forecast mặc định bind loopback; hướng dẫn vận hành tại [FORECAST-DOCS.md](../operations/FORECAST-DOCS.md).
