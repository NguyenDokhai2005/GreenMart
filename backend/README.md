# Backend – Spring Boot

Java 21 · Spring Boot 3 · Maven · PostgreSQL · Redis · RabbitMQ · Flyway.
Kiến trúc modular monolith: mỗi module là một package trong `vn.greenmart`, chỉ gọi nhau qua interface service hoặc sự kiện.

| Thư mục | Nội dung |
| --- | --- |
| `src/main/java/vn/greenmart/<module>` | Mỗi module có `api` (controller, DTO), `service`, `domain` (entity), `repository` |
| `src/main/resources/db/migration` | Migration Flyway: `V1__init.sql`, `V2__...` (SV03 quản lý) |
| `src/test/java/vn/greenmart` | Unit test và integration test (Testcontainers) |

Khung dự án (pom.xml, Application.java) do SV07 khởi tạo trong tuần 1.

## Migration V4 — thời gian phiếu (#215)

- `return_document.created_at`: lúc lập phiếu; `approved_at`: lúc duyệt trả,
  dùng làm ngày trả khi tổng hợp `daily_sales` (BR-18).
- `stock_document.approved_at`: lúc duyệt; `received_at`: lúc nhận chuyển kho.
- V4 backfill phiếu cũ trước khi thêm CHECK trạng thái và thứ tự thời gian.
  Thời gian backfill chỉ là giá trị thay thế, không khôi phục lịch sử thực tế.
  Đặc biệt `return_document.created_at` cũ được điền bằng thời điểm migration.
- Generator phải xuất timestamp lịch sử khi INSERT phiếu đã duyệt/đã nhận.
  Seed cũ bỏ qua `approved_at` sẽ bị CHECK từ chối sau V4; không bỏ CHECK để nạp seed.
- Theo phạm vi #215, chưa tạo entity/service phiếu. Các issue #53, #54, #58,
  #63 và #43 bổ sung mapping và ghi timestamp khi triển khai nghiệp vụ tương ứng.

`DocumentTimestampsMigrationTest` kiểm tra schema mới, nâng cấp từ V3 với
150 phiếu trả đã duyệt, backfill các trạng thái phiếu kho, chạy lại Flyway và
các trường hợp timestamp không hợp lệ trên PostgreSQL 16.10.

## Sổ kho cùng transaction (#50)

`reserve`, `release`, `commitSale`, `sellDirect` ghi đúng một dòng
`inventory_transaction` sau mỗi conditional UPDATE thành công, cùng transaction
của caller. `refId` bắt buộc là ID đơn gốc (`ref_type=ORDER`). Ghi sổ thất bại
hoặc caller rollback thì cả tồn và sổ đều rollback.

Writer chỉ cung cấp thao tác thêm mới. `INSERT ... SELECT` chụp tồn sau cập nhật;
row lock từ UPDATE được giữ đến commit nên không chụp nhầm tồn của giao dịch khác.
`created_by` hiện để NULL theo schema vì chưa tích hợp Identity; chưa ghi người
thực hiện, không dùng một ID giả thay cho người đăng nhập.

V5 chặn UPDATE/DELETE sổ kho bằng trigger (BR-06). Sửa sai bằng một giao dịch
điều chỉnh mới. TRUNCATE bằng tài khoản quản trị vẫn là thao tác bảo trì đặc quyền;
test chỉ dùng nó để dọn dữ liệu trong PostgreSQL container riêng.
