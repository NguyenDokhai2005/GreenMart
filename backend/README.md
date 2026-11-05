# Backend – Spring Boot

Java 21 · Spring Boot 3 · Maven · PostgreSQL · Redis · RabbitMQ · Flyway.
Kiến trúc modular monolith: mỗi module là một package trong `vn.greenmart`, chỉ gọi nhau qua interface service hoặc sự kiện.

| Thư mục                               | Nội dung                                                                          |
| ------------------------------------- | --------------------------------------------------------------------------------- |
| `src/main/java/vn/greenmart/<module>` | Mỗi module có `api` (controller, DTO), `service`, `domain` (entity), `repository` |
| `src/main/resources/db/migration`     | Migration Flyway: `V1__init.sql`, `V2__...` (SV03 quản lý)                        |
| `src/test/java/vn/greenmart`          | Unit test và integration test (Testcontainers)                                    |

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

## Lưu ý Bảo mật & Phân quyền

- `SecurityConfig` hiện tại cấu hình `anyRequest().permitAll()` để không chặn các endpoint đọc công khai (`GET /api/v1/products`, `/categories`, `/products/lookup`) trong khi module `identity` đang hoàn thiện.
- Các thao tác ghi (POST, PUT, DELETE) được kiểm soát vai trò chặt chẽ tại tầng Service thông qua `SecurityUtils.requireRole(...)
- Kế hoạch hoàn thiện: Issue #146 sẽ tích hợp JWT Filter và chuyển sang `anyRequest().authenticated()` trên chính `SecurityConfig` này.

## API xem tồn kho (#52)

`GET /api/v1/inventory` trả trang tồn theo OpenAPI, hỗ trợ `branchId`, `productId`,
`categoryId`, `q` (tên/SKU/barcode), `stockStatus`, `page`, `size`, và `sort` lặp lại.
Danh mục lọc theo ID được truyền. Ví dụ: `?branchId=1&stockStatus=LOW&sort=available,asc`.

Khả dụng bằng `onHand-reserved`; OUT khi bằng 0, LOW khi khả dụng dương không vượt
ROP, còn lại OK (gồm khả dụng dương khi ROP chưa có). Filter và quyền chi nhánh
áp dụng trước count/phân trang; không truyền chi nhánh thì chỉ xem các chi nhánh
được gán, ADMIN/OWNER được xem toàn bộ. API chỉ đọc, không ghi sổ hoặc tăng version.

API yêu cầu danh tính trong Spring SecurityContext: chưa đăng nhập trả 401,
đọc chi nhánh ngoài quyền trả 403 `BRANCH_FORBIDDEN`. Không dùng tài khoản/header
mặc định để vượt kiểm tra này. `InventoryQueryApiTest` dựng danh tính test, gọi
MockMvc với PostgreSQL 16.10 thật; chưa kiểm chứng luồng Bearer JWT production.
Chạy `mvn -B -ntp test -Dtest=InventoryQueryApiTest` với JDK 21 và Docker
(Windows local có thể thêm `-P local-dev`).

## Phiếu nhập có duyệt (#53)

Các endpoint `/api/v1/stock-documents` (POST), `/{id}` (GET/PUT),
`/{id}/cancel` và `/{id}/approve` (POST) hiện xử lý loại IMPORT.
STOREKEEPER hoặc BRANCH_MANAGER được tạo/sửa/hủy ở chi nhánh được gán;
OWNER có quyền đọc. Chỉ BRANCH_MANAGER đúng chi nhánh được duyệt và phải
khác người lập (422 SELF_APPROVAL_FORBIDDEN). Chỉ DRAFT được sửa/hủy/duyệt;
thao tác trên trạng thái khác trả 409 INVALID_STATUS.

Phiếu nháp cần supplier và 1–500 dòng khác productId, quantity 1–99999,
unitCost nguyên không âm. Nháp không làm đổi tồn/giá vốn/sổ kho. Sửa thay toàn bộ
items; supplier/note bỏ qua giữ giá trị cũ. Hủy ghi lý do trong audit_log.

Duyệt khóa phiếu, xử lý productId tăng dần, cộng on_hand (tạo dòng tồn nếu thiếu),
giữ reserved và ghi IMPORT/STOCK_DOCUMENT kèm người duyệt. Giá vốn chung:
`(SUM(on_hand toàn chuỗi) * giá vốn cũ + quantity * unitCost) / (tồn cũ + quantity)`.
Dùng on_hand gồm cả hàng giữ chỗ, BigDecimal HALF_UP nguyên đồng, kiểm tra tràn long.
Khóa product FOR UPDATE trước SUM; các thao tác tồn khác lấy FOR KEY SHARE trước
conditional UPDATE để thống nhất thứ tự product → inventory. Tất cả thay đổi tồn,
giá vốn, ledger, approvedBy/approvedAt cùng transaction: một dòng lỗi rollback cả phiếu.
Duyệt lặp không cộng tồn hai lần. Chỉ IMPORT cập nhật giá vốn.

Theo quyết định leader: https://github.com/NguyenDokhai2005/GreenMart/issues/53#issuecomment-6622491737
List phiếu, EXPORT/TRANSFER/ADJUST chưa nằm trong phần IMPORT này. JWT production
chưa được triển khai; actor phải có userId thực, hoạt động trong SecurityContext.

Kiểm tra với JDK 21 và Docker:
`mvn -B -ntp test -Dtest=StockDocumentDraftApiTest,StockImportTransactionTest`.
Test dùng PostgreSQL thật; HTTP tests dựng danh tính trong SecurityContext, concurrency
chạy transaction độc lập với hai worker, có giới hạn thời gian chờ.

## Phiếu xuất hủy có duyệt (#54)

Các endpoint phiếu kho hiện hỗ trợ cả IMPORT và EXPORT. EXPORT cần exportReason:
EXPIRED, DAMAGED, LOST hoặc OTHER; OTHER bắt buộc note không trống. Không gửi supplier,
toBranchId, unitCost hoặc countedQuantity cho EXPORT. IMPORT vẫn bắt supplier/unitCost.
Tạo và sửa nháp kiểm tra quantity <= available, chưa làm đổi tồn. Lúc duyệt kiểm tra lại
bằng conditional UPDATE: chỉ giảm on_hand khi on_hand-reserved đủ, không lấy vào reserved.
Thiếu tồn trả409 INSUFFICIENT_STOCK với details.lines[].available.

Duyệt bởi BRANCH_MANAGER đúng chi nhánh và khác người lập; phiếu được khóa chống duyệt
lặp. Xử lý productId tăng dần, khóa product FOR SHARE để chụp giá vốn ổn định, rồi cập nhật
inventory và ghi EXPORT/STOCK_DOCUMENT có actor. Snapshot giá vốn, approvedBy/approvedAt,
ledger và audit cùng transaction; lỗi một dòng rollback toàn bộ phiếu. EXPORT không đổi
giá vốn sản phẩm. Số tiền dùng long, kiểm tra overflow từng dòng và tổng phiếu.

Migration V7 thêm stock_document.export_reason (giữ reason hiện có làm note) và
stock_document_item.export_cost_price riêng với unit_cost dành cho IMPORT. totalCost
EXPORT sau duyệt = tổng quantity × snapshot giá vốn, chỉ OWNER/BRANCH_MANAGER thấy;
STOREKEEPER thấy null và unitCost EXPORT vẫn null. Nháp chưa có snapshot trả totalCost null.
Phiếu cũ trước V7: giữ note; reason đúng mã enum được dùng lại, còn lại phân loại OTHER;
OTHER thiếu note có ghi chú nguồn dữ liệu cũ. Không gán giá vốn hiện tại cho lịch sử cũ:
export_cost_price và totalCost của phiếu cũ chưa có snapshot giữ null.

Chạy JDK21/Docker: `mvn -B -ntp test -Dtest=StockExportApiTest,StockExportMigrationTest,StockDocumentDraftApiTest,StockImportTransactionTest`,
sau đó `mvn -B -ntp verify`. Tests dùng PostgreSQL16.10 thật, Flyway + Hibernate validate;
HTTP dựng SecurityContext theo nền develop, không nhận đây là E2E JWT production.
