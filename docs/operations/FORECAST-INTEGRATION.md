# Backend gọi Forecast-service

Đặt `FORECAST_INTERNAL_TOKEN` trong file cấu hình môi trường dùng chung của backend và forecast-service. Compose truyền cùng giá trị vào hai dịch vụ. `FORECAST_SERVICE_URL` của backend là `http://forecast-service:8000` trong mạng Compose.

Backend gửi Bearer token khi gọi `POST /forecast/run` và `GET /forecast/runs/{id}`. ADMIN và OWNER gọi qua `POST /api/v1/forecasts/runs` và `GET /api/v1/forecasts/runs/{id}`. API khởi chạy ghi audit `FORECAST_RUN`.

Khi thiếu token hoặc Forecast-service trả 401, backend trả 503 `FORECAST_UNAVAILABLE`. Kiểm tra cấu hình token và trạng thái Forecast-service rồi thử lại. Không đưa token, header Authorization hoặc body lỗi của dịch vụ nội bộ vào log.

Kiểm tra hồi quy bằng `mvn -f backend/pom.xml -Dtest=ForecastClientTest,ForecastRunApiTest test`. HTTP server trong test kiểm tra header của cả hai operation. Test API kiểm tra quyền và audit. Việc trả 409 khi chưa có daily_sales là kiểm tra nghiệp vụ sau xác thực, chưa phải bằng chứng mô hình chạy thành công.
