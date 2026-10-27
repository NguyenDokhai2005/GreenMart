# Mock connector – kênh bán giả lập

Giả lập 2 kênh WEB và MARKET. Phụ trách: SV07.

- Giao diện đơn giản để bấm tạo đơn giả và gửi tới webhook `POST /api/v1/integrations/{channelCode}/orders` (ký HMAC-SHA256).
- Nhận cập nhật tồn `PUT /mock/{channelCode}/stock` và trạng thái đơn `PUT /mock/{channelCode}/orders/{id}/status`.
- Bỏ qua bản cập nhật tồn có số phiên bản cũ hơn bản đã nhận.

Hợp đồng chi tiết: mục 8.3 SRS và `docs/api/openapi.yaml`.
