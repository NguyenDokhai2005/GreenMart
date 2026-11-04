# Outbox và DLQ (#127)

Trong service nghiệp vụ có `@Transactional`, inject `OutboxService` rồi gọi:

```java
outbox.enqueue("ORDER", orderId.toString(), "order.completed", payload);
```

Không gọi RabbitMQ trực tiếp trong transaction bán hàng. `enqueue` yêu cầu transaction
đang hoạt động; lỗi nghiệp vụ rollback cả dữ liệu lẫn sự kiện. Relay lấy tối đa 25
sự kiện mỗi lượt, khóa từng dòng bằng `FOR UPDATE SKIP LOCKED` và chỉ đánh dấu SENT
khi broker confirm ACK **và** không trả lại message không có queue nhận.

Broker lỗi: giữ PENDING, tăng attempts và thử lại với backoff tối đa 60 giây, không
bỏ sự kiện vì vượt số lần thử. Có thể tắt relay bằng `GREENMART_OUTBOX_ENABLED=false`.
Crash sau khi broker nhận nhưng trước commit có thể gửi trùng: consumer phải xử lý
idempotent bằng `messageId` (id outbox), ghi dấu đã xử lý cùng transaction nghiệp vụ.
Đây là at-least-once, không phải exactly-once.

Topology durable: exchange topic `greenmart.events`, queue `greenmart.integration.events`
nhận `order.#`, `stock.#`, `forecast.completed`; exchange `greenmart.dlx`, queue
`greenmart.integration.dlq`, routing key `integration.failed`. Message persistent.
Listener Spring mặc định thử tổng cộng 5 lần (1/2/4/8 giây giữa các lần), sau đó reject
không requeue để RabbitMQ chuyển vào DLQ. Consumer nghiệp vụ được bổ sung ở các issue
đồng bộ sau; không có consumer giả tự ACK và làm mất sự kiện đang chờ xử lý.

Migration mới V6 cần được phối hợp với SV03 nếu có migration song song trước merge.

## Kiểm chứng

Docker Engine phải chạy. Java 21, Maven:

```powershell
mvn -f backend/pom.xml -Dtest=OutboxIntegrationTest test
```

Testcontainers dùng DB/broker riêng: rollback và MANDATORY; confirm/persistent;
unroutable giữ PENDING; stop_app/start_app gửi bù; consumer lỗi 5 lần vào DLQ.
Không dừng broker local dùng chung. EX-12 kéo dài 5 phút được demo ở #138; test ở đây
kiểm chứng cùng cơ chế phục hồi bằng một lần ngắt ngắn.

```powershell
docker compose exec rabbitmq rabbitmqctl list_queues name messages arguments
docker compose exec postgres psql -U greenmart -d greenmart -c "SELECT id,event_type,status,attempts FROM outbox_event ORDER BY id;"
```

Không đưa payload nhạy cảm hoặc cấu hình chứa secrets vào bằng chứng PR.
