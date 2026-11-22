# Sự kiện thanh toán/hủy đơn từ kênh (#416)

Endpoint `POST /api/v1/integrations/{channelCode}/orders/{externalOrderId}/events`
dùng giao thức chữ ký sự kiện v1. Webhook tạo đơn và callback stock/status vẫn dùng
HMAC trên nguyên body như trước. Không có fallback chấp nhận chữ ký body cũ ở events.

## Chữ ký và gửi lại

Header bắt buộc: `X-Event-ID` là UUID, `X-Event-Timestamp` là epoch giây lúc gửi,
`X-Signature` là HMAC-SHA256 hex dùng secret riêng của kênh (cũng nhận tiền tố `sha256=`).
Chuỗi ký UTF-8 có đúng 6 dòng, phân cách LF, không LF cuối:

```text
GREENMART-EVENT-V1
POST
/api/v1/integrations/WEB/orders/EXTERNAL-ID/events
<X-Event-ID>
<X-Event-Timestamp>
<SHA-256 hex chữ thường của nguyên byte body>
```

Giữ raw path bao gồm `/api/v1`, đúng percent-encoding; query string không thuộc giao thức
vì endpoint không nhận tham số query. Không serialize lại JSON giữa lúc ký và gửi.
Sai/thiếu header, chữ ký hoặc lệch transport timestamp quá ±300 giây trả401 INVALID_SIGNATURE.
`occurredAt` trong body là thời gian nghiệp vụ, không dùng để chặn PAID đến muộn hợp lệ.

Retry giữ eventId, path và body nguyên byte; ký lại timestamp mới. Một eventId trong
một kênh không được dùng với target/body khác (409 CONFLICT). Kết quả lần thành công
đầu tiên được lưu cùng transaction với payment/order/audit/outbox. Retry trả lại kết quả
đó và không xử lý lại; acknowledgement có thể khác trạng thái hiện tại sau khi đơn đã
bị hủy/hoàn, muốn xem hiện tại dùng API đọc đơn. Lần xử lý thất bại rollback cả receipt,
nên có thể gửi lại để thử xử lý. Receipt giữ ít nhất7 ngày, hiện không có job tự xóa.

## Mã giao dịch và dữ liệu cũ

PAID bắt buộc có providerRef không rỗng, tối đa60 ký tự. Mã là chuỗi opaque,
phân biệt hoa/thường. `sales_channel.payment_provider_scope` xác định gateway/merchant
phát hành mã; chỉ cấu hình ở server, không lấy scope từ body webhook.
WEB/MARKET mặc định dùng chung `MOCK:GREENMART`, nên cùng reference không thể xác nhận
hai đơn khác nhau dù đi qua hai kênh. Chỉ đặt scope khác nếu đúng là nhà phát hành/merchant
khác có miền mã độc lập; không đổi scope để né một giao dịch đang xung đột.

`channel_payment_reference` giữ liên kết reference→order vĩnh viễn, cả sau refund;
một đơn không đổi sang reference khác. Trigger payment bảo vệ cả các writer ngoài
webhook. Không xóa binding để cho phép sử dụng lại mã thanh toán. COD CASH không có
providerRef vẫn hoàn tất bình thường; PAID đến cho đơn đã CONFIRMED/COMPLETED chỉ
idempotent khi khớp giao dịch PAID/REFUNDED đã có, không tạo một thanh toán mới.

Migration V19 backfill các payment online PAID/REFUNDED có reference và giữ nguyên
payment. Nếu dữ liệu cũ gắn một reference cho nhiều đơn hoặc một đơn có nhiều reference,
migration dừng để đối soát; không tự sửa tên mã, xóa payment hoặc sinh refund.
Chạy migration trong môi trường riêng trước khi triển khai; nếu phát hiện xung đột,
kiểm tra giao dịch gốc và xử lý dữ liệu bằng quy trình được phép rồi mới chạy lại.
Không bật fallback chữ ký cũ trong giai đoạn chuyển đổi: cập nhật bên gửi cùng backend.

## Mock connector và kiểm tra

Module `mock-connector/src/event-signature.js` xuất `eventHeaders({path, body, secret,
eventId, timestamp})`. `POST /demo/{channel}/orders/{externalOrderId}/events` chuyển tiếp
event, tự ký timestamp mới và trả header X-Event-ID; retry gửi lại header này cùng body.
Client cần lưu UUID trước lần gửi đầu, không đợi nhận response mới tạo UUID, để retry
được cả khi mất response. Secret không được đưa vào frontend hoặc log.

Các test backend gồm chữ ký Java/Node, biên thời gian, đổi path/body/channel, replay,
race cùng event/ref, rollback audit/outbox và nâng schema dữ liệu lịch sử.
LatePaidAtomicityTest giữ các trường hợp EX-06 với transport timestamp mới.
Test dùng PostgreSQL container riêng, không reset dữ liệu dùng chung.

```text
mvn -B -ntp verify                     (backend, JDK21 và Docker)
npm test                              (mock-connector, Node22)
npm run generate:api-types             (frontend, khi đổi OpenAPI)
```
