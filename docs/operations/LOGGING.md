# Log JSON và correlation ID (#129)

Spring Boot 3.5 xuất console theo Logstash JSON; MDC `traceId` thành trường JSON.
`CorrelationFilter` nhận `X-Correlation-ID` tối đa 64 ký tự chữ/số/`_`/`-`; giá trị
thiếu/sai được thay bằng UUID. Header phản hồi và `ErrorResponse.traceId` dùng cùng
ID. Filter log method/status, không log body, query, Authorization hay mật khẩu.

`RabbitTemplate` gắn ID vào header AMQP cùng tên. Listener factory khôi phục MDC
trong từng lần xử lý rồi dọn trong finally, kể cả lỗi; cấu hình retry của Boot được
giữ nguyên. Service bất đồng bộ khác cần chủ động truyền context. Outbox #127 lưu ID
vào DB khi enqueue và khôi phục lúc relay, nên restart hay retry vẫn truy được ID gốc.
Không dùng correlation ID làm quyền truy cập hoặc khóa chống trùng.

```powershell
mvn -f backend/pom.xml -Dtest=CorrelationFilterTest,RabbitCorrelationTest test
curl.exe -i -H "X-Correlation-ID: demo-129" http://localhost:8080/api/v1/khong-ton-tai
docker compose logs backend | Select-String 'demo-129'
```

Lỗi 404 có `traceId=demo-129`, header `X-Correlation-ID: demo-129`; log JSON cùng ID.
Test RabbitMQ riêng kiểm đường HTTP filter → template → broker → listener và JSON
log, đồng thời kiểm message kế tiếp không nhận nhầm trace của request trước.
Các consumer sau phải dùng factory `rabbitListenerContainerFactory` mặc định để áp
dụng context và retry này. Không bật batch listener nếu chưa bổ sung context theo
từng message trong batch.
