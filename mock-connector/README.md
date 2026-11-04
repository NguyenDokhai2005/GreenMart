# Mock connector – kênh bán giả lập

Giả lập 2 kênh WEB và MARKET. Phụ trách: SV07.

- Giao diện đơn giản để bấm tạo đơn giả và gửi tới webhook `POST /api/v1/integrations/{channelCode}/orders` (ký HMAC-SHA256).
- Nhận cập nhật tồn `PUT /mock/{channelCode}/stock` và trạng thái đơn `PUT /mock/{channelCode}/orders/{id}/status`.
- Bỏ qua bản cập nhật tồn có số phiên bản cũ hơn bản đã nhận.

Hợp đồng chi tiết: mục 8.3 SRS và `docs/api/openapi.yaml`.

## Chạy và kiểm tra (#128)

Node 22, không có dependency npm bên ngoài. Điền secrets riêng trong `.env`:

```powershell
docker compose up --build -d --wait mock-connector
# Mở http://localhost:8090
node --test mock-connector/test/connector.test.js
```

Chọn WEB/MARKET, sửa JSON và gửi. Gửi lại cùng `externalOrderId` để kiểm tra trùng;
trang giữ mã đơn cho tới khi bấm Tạo mã đơn mới. Kết quả hiển thị nguyên mã HTTP và
JSON từ backend (202/200/409/422). SKU mẫu phải có ánh xạ tương ứng (#130).
Endpoint backend nhận đơn thuộc #132 và nghiệp vụ #40: nếu chưa có, phản hồi 404
được hiển thị đúng, không giả lập thành công phía backend.

Callback nhận `X-Signature` hex (chấp nhận cả tiền tố `sha256=`), kiểm tra HMAC trên
nguyên body trước khi cập nhật. Version cũ hoặc bằng bị bỏ qua; kiểm tra toàn bộ
batch trước khi ghi. WEB và MARKET có state/secret riêng. Trạng thái lưu vào volume
`mockdata`; khởi động lại không mất version. `GET /mock/state` phục vụ quan sát demo,
`GET /healthz` là healthcheck. Secrets chỉ ở server, không đưa ra trình duyệt/log.

Chạy riêng từ thư mục `mock-connector`: đặt `CHANNEL_WEB_SECRET`,
`CHANNEL_MARKET_SECRET`, `BACKEND_URL=http://localhost:8080`,
`STATE_FILE=./data/state.json`, rồi `npm start`. Mặc định cổng 8090 khớp OpenAPI.
Chỉ mở cổng demo trên localhost; không dùng mock connector làm dịch vụ công khai.

Test HTTP dùng backend fixture kiểm chứng cả hai kênh và mã phản hồi; test callback
kiểm chữ ký sai/body bị sửa, version cũ, lưu state, batch lỗi không ghi một phần,
callback trạng thái, validation và backend mất kết nối. Nghiệm thu E2E với API nhận
đơn thật thực hiện khi #132 và #40 sẵn sàng.
