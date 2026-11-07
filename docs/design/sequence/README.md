# Sơ đồ tuần tự (sequence) – GreenMart

Issue #12. Khớp OpenAPI 2.0.0, BA §3, §4, kiến trúc trong `docs/design/architecture` và code trên `develop` ngày 07/11/2026.

| # | Luồng | Trạng thái code | Ảnh |
| --- | --- | --- | --- |
| 01 | Tạo đơn POS (`POST /orders/pos`) | Đã có phần lõi (#31, #32, #33) và outbox relay (#127); đang làm Idempotency-Key (#34) và khóa theo `productId` tăng dần (#57) | [png](png/01-tao-don-pos.png) |
| 02 | Nhận đơn từ kênh (`POST /integrations/{channelCode}/orders`) | Thiết kế đích cho Tuần 3 (#132, #40, #137); đã có mock-connector, ánh xạ SKU, outbox | [png](png/02-nhan-don-tu-kenh.png) |
| 03 | Đồng bộ tồn kho ra kênh (`stock.changed` → `PUT /mock/{channelCode}/stock`) | Thiết kế đích cho Tuần 3 (#59, #133, #134); đã có outbox, publisher confirm, DLQ | [png](png/03-dong-bo-ton-kho.png) |

## Quy ước

- Tên lớp trong sơ đồ 01 là tên thật trong `vn.greenmart.*`. Sơ đồ 02, 03 dùng tên dự kiến; người làm issue tương ứng giữ tên này hoặc cập nhật sơ đồ trong cùng PR.
- Khung `@Transactional` là ranh giới transaction: lỗi trong khung rollback toàn bộ, kể cả bản ghi `outbox_event`.
- Ghi chú màu cam ở cuối mỗi sơ đồ nói rõ phần nào đã có, phần nào đang làm, để không nhầm sơ đồ với code hiện tại.

## Cập nhật ảnh

```sh
cd docs/design/sequence
java -jar plantuml.jar -charset UTF-8 -tpng -o png *.puml
```
