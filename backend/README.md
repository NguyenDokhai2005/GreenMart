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

## Đăng nhập JWT (#241)

Cấu hình `JWT_SECRET` ngẫu nhiên ít nhất 32 byte trong `.env`. Backend từ chối secret
ngắn hoặc placeholder `change-me...`; không có secret mặc định cho production.
Maven tests dùng khóa chỉ dành cho test trong `src/test/resources/application.properties`.
Seed tài khoản theo `forecast-service/scripts/README.md`; bảng 12 tài khoản ở README gốc.

Ví dụ PowerShell sau khi backend/seed sẵn sàng:

```powershell
$session = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login `
  -ContentType 'application/json' -Body '{"username":"thungan.gm001","password":"Demo@123"}'
$headers = @{ Authorization = "Bearer $($session.accessToken)" }
Invoke-RestMethod -Uri http://localhost:8080/api/v1/auth/me -Headers $headers
$payload = @{ refreshToken = $session.refreshToken } | ConvertTo-Json
$session = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/refresh `
  -ContentType 'application/json' -Body $payload
```

Access token HS256 hạn 900 giây; refresh token hạn 7 ngày, xoay khi refresh. Token
refresh dạng `<userId>.<32-byte-random-base64url>`; userId chỉ để tìm key, phần random
mới là bí mật xác thực. Redis key `auth:refresh:{userId}` là sorted set chỉ chứa SHA-256
hash, score = thời điểm hết hạn từng token (epoch milliseconds), key TTL 7 ngày kể từ
lần cấp gần nhất. Lua loại token hết hạn và đổi hash cũ lấy hash mới nguyên tử.
Không lưu token gốc trong Redis; không log token/password.

Mọi API cần xác thực, trừ POST login/refresh và actuator health. Các URL auth có
prefix `/api/v1`. JWT filter đặt principal userId và authority ROLE_<role>, kiểm tra
user còn hoạt động/role hiện tại. Mock SecurityContext vẫn dùng được khi test không
truyền Authorization. Lỗi filter 401/403 dùng ErrorResponse và traceId chung.

Login sai cập nhật counter dưới khóa user; lần thứ 5 khóa 15 phút, trả ACCOUNT_LOCKED.
Lỗi login được chuyển sang HTTP sau commit để counter/audit không rollback.
Audit action hiện triển khai: LOGIN_FAILED, ACCOUNT_LOCKED, CHANGE_PASSWORD;
đây là hợp đồng code để SV08 đối chiếu Q-25/Q-26 (Q-27 trong tài liệu QA là truy cập
sai chi nhánh, không phải login). Q-29 lưu Redis và Q-30 JSON handlers theo issue #241.

Logout (cần access token) thu hồi một refresh token thuộc user; access token còn dùng
đến khi hết hạn. Đổi mật khẩu cần mật khẩu hiện tại đúng, mật khẩu mới ít nhất 8 ký tự,
có chữ và số, thu hồi toàn bộ refresh. Login/refresh/logout/change-password cùng khóa
user để tránh refresh đồng thời thoát revoke. Redis lỗi thì không trả token thành công;
PostgreSQL và Redis không có commit nguyên tử chung: nếu DB commit thất bại sau revoke,
phiên có thể đã bị thu hồi và người dùng cần đăng nhập lại.

Kiểm tra JDK 21 + Docker: `mvn -B -ntp test -Dtest=AuthApiTest,CatalogIntegrationTest`,
sau đó `mvn -B -ntp verify`. AuthApiTest dùng PostgreSQL 16.10 + Redis 7.4.6 thật,
đồng hồ test để kiểm tra hết hạn/khóa mà không đợi thực tế; không thay thế QA API thật #242.

Theo quyết định leader được người dùng xác nhận, mật khẩu mới áp dụng giới hạn BCrypt **72 byte UTF-8**, không yêu cầu hỗ trợ đủ 100 ký tự. Vượt giới hạn trả 400 VALIDATION_ERROR trước khi đổi mật khẩu hoặc thu hồi phiên; không cắt mật khẩu và không tự thêm pre-hash. Ký tự tiếng Việt có thể chiếm nhiều byte. Câu hỏi gốc: https://github.com/NguyenDokhai2005/GreenMart/issues/241#issuecomment-6624570873. OpenAPI hiện còn maxLength 100 ký tự; phần mô tả giới hạn byte của hợp đồng API do lead cập nhật, code áp dụng giới hạn byte đã chốt.

Tối ưu xác thực: filter chỉ truy vấn `role` của user đang hoạt động qua Identity service,
không lấy hash mật khẩu hoặc danh sách chi nhánh cho mỗi request. `/auth/me` vẫn trả đủ
branches theo contract. User đổi role hoặc bị vô hiệu hóa phải lấy token phù hợp mới.
Access token có UUID `jti`, nên login/refresh cùng một giây vẫn nhận token khác nhau;
token bị từ chối ngay tại thời điểm `now == exp`. Login vào tài khoản đang khóa không
chạy BCrypt lại, nhưng vẫn ghi audit; tài khoản không tồn tại/inactive vẫn có bước kiểm
tra hash để giảm khác biệt thời gian phản hồi.
