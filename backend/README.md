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

### Giới hạn đăng nhập theo IP (#155)

`POST /api/v1/auth/login` cho phép tối đa 20 lần thử trong 60 giây gần nhất
cho mỗi IP, tính cả thành công và body không hợp lệ. Lần thứ 21 bắt đầu chặn
60 giây, trả `429 LOGIN_RATE_LIMITED` kèm `Retry-After` (giây còn lại).
Request trong lúc chặn không kéo dài hạn chặn; hết hạn bắt đầu bộ đếm mới.
Thành công không xóa bộ đếm IP. Redis Lua thực hiện kiểm tra/cập nhật nguyên tử,
dùng chung hạn mức khi có nhiều backend. Redis không khả dụng thì login trả
`503 LOGIN_UNAVAILABLE`, không truy vấn tài khoản. Filter chạy trước Spring Security
và controller. Refresh có bộ đếm riêng 60 request/IP/phút, vượt ngưỡng chặn
60 giây (`REFRESH_RATE_LIMITED`). Đổi mật khẩu có bộ đếm riêng 20 request/IP/phút
và 5 lần xác minh/tài khoản/15 phút, vượt ngưỡng chặn 60 giây hoặc 15 phút
(`PASSWORD_RATE_LIMITED`). Tính cả thành công/thất bại; thay IP/đăng nhập lại
không xóa hạn mức tài khoản. Mọi phản hồi 429 có `Retry-After`.

Mặc định backend lấy IP từ socket. Chỉ đặt `LOGIN_TRUSTED_PROXIES` cho proxy thực
sự được quản lý, dạng danh sách IP, CIDR hoặc tên dịch vụ phân cách bằng dấu phẩy.
Compose mặc định tin dịch vụ `frontend`; Nginx của dịch vụ này ghi đè `X-Real-IP`
bằng IP kết nối đến nó. Backend chỉ nhận `X-Real-IP` khi socket peer thuộc danh
sách tin cậy, bỏ qua `X-Forwarded-For`. Không cấu hình tin mọi IP hoặc bật tự động
ghi đè remote address qua forwarded headers. Nếu thêm gateway/LB trước Nginx,
cần thiết kế lại chuỗi proxy tin cậy để tránh gom mọi người vào IP gateway.

Sai mật khẩu, username không tồn tại, tài khoản ngừng hoạt động hoặc đang khóa
đều trả cùng `401 INVALID_CREDENTIALS`, không lộ số lần còn lại hay hạn khóa.
Nội bộ vẫn khóa tài khoản sau 5 lần sai liên tiếp trong 15 phút và ghi audit.
CAPTCHA/email mở khóa cần thiết kế luồng riêng, chưa nằm trong thay đổi này.
QA cần đối chiếu OpenAPI mới khi cập nhật các script còn chờ `ACCOUNT_LOCKED`
hoặc `remainingAttempts`; giữ nguyên bằng chứng QA lịch sử. Bài tải POS nên
chuẩn bị token trước giai đoạn đo để không bị giới hạn login cùng IP.

Kiểm tra bằng JDK 21 và Docker với `mvn -B -ntp verify`. Các test
`AuthRateLimiterTest`, `LoginClientIpResolverTest`, `LoginRateLimitApiTest`
và `LoginRateLimitHttpTest` kiểm tra Redis thật, đồng thời, proxy giả mạo,
thứ tự chặn trước DB và HTTP qua Tomcat.

### Phiên JWT và đăng xuất

Access JWT có hạn 15 phút và claim `sid` gắn với phiên đăng nhập. Refresh token
có hạn 7 ngày, xoay vòng khi dùng và giữ cùng `sid`. Backend xác minh refresh
trong Redis trước khi truy vấn tài khoản; Lua kiểm tra/tiêu thụ lại nguyên tử
khi xoay để giữ an toàn với refresh đồng thời và đổi mật khẩu.

Mỗi request có JWT kiểm tra phiên còn hoạt động trong Redis trước khi đọc
trạng thái/vai trò từ DB. Logout thu hồi đúng phiên ngay, gồm access và refresh;
đổi mật khẩu thu hồi mọi phiên. Request đã được xác thực trước lúc thu hồi
có thể đang xử lý; thu hồi chặn các lần xác thực tiếp theo.

`POST /api/v1/auth/logout` dùng refresh token trong body làm bằng chứng thu hồi,
không cần access còn hạn hoặc tài khoản đang hoạt động. Token đã xoay vẫn có
lookup SHA-256 về phiên trong tối đa 7 ngày để logout không bỏ sót request
refresh chạy đồng thời. Lookup chỉ cho phép thu hồi, không cho phép dùng lại
refresh cũ để cấp token. Các lookup và bản ghi phiên tự hết hạn; không lưu
nguyên văn refresh token. Gọi lại logout với token đúng định dạng vẫn trả 204.
Redis lỗi trả `503 AUTH_UNAVAILABLE`, không báo thu hồi thành công hay coi token
đã hết hạn. Frontend xóa phiên local, báo lỗi nếu server chưa thu hồi được và
không xin token mới để đăng xuất. Refresh thành công cập nhật cả user/role/
chi nhánh trong AuthContext.

Triển khai thay đổi này yêu cầu đăng nhập lại một lần: JWT cũ chưa có `sid`
hoặc refresh cũ chưa có registry phiên bị từ chối. Không cần migration DB.
Các bài test module nghiệp vụ tự ký JWT dùng mock registry để tách phạm vi;
`AuthSessionSecurityTest` kiểm tra registry thật với PostgreSQL/Redis, gồm
logout/refresh đồng thời, tài khoản khóa, vô hiệu hóa và thu hồi mọi phiên.

`SecurityConfig` cho phép login/refresh/logout, health và các callback tích hợp được chỉ định
truy cập công khai; các request còn lại cần xác thực JWT. Quyền vai trò và phạm vi
chi nhánh tiếp tục được kiểm tra tại security chain và tầng service tương ứng.

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

## Tra cứu sổ kho (#55)

`GET /api/v1/inventory/transactions` dành cho OWNER, BRANCH_MANAGER và STOREKEEPER;
CASHIER và ADMIN trả 403 FORBIDDEN theo OpenAPI. OWNER xem toàn chuỗi; hai vai trò
còn lại chỉ xem chi nhánh được gán. Chọn chi nhánh ngoài quyền trả BRANCH_FORBIDDEN;
không được gán chi nhánh trả trang rỗng, không lộ tổng số giao dịch của nơi khác.

Bộ lọc: branchId, productId, type, refType, refId, from, to. Ngày dạng yyyy-MM-dd,
tính trọn ngày Việt Nam (Asia/Ho_Chi_Minh), bao gồm cả from và to. Phân trang mặc định
page=0, size=20, tối đa 100; luôn sắp createdAt giảm dần rồi id giảm dần để ổn định
khi nhiều giao dịch cùng thời điểm. ID phải dương, from không được sau to.

Mỗi dòng có biến động và số dư onHand/reserved, SKU, loại/mã chứng từ và người tạo.
Mã chứng từ được ghép đúng refType và chi nhánh (TRANSFER cho cả nơi đi và đến);
tham chiếu không còn tồn tại trả refCode=null nhưng vẫn giữ dòng lịch sử. createdBy
có thể null cho giao dịch hệ thống. Truy vấn chỉ đọc, dùng REPEATABLE_READ để tổng số
và nội dung trang cùng snapshot; không thay đổi tồn kho hoặc tạo giao dịch mới.

Kiểm tra JDK 21 + Docker: `mvn -B -ntp test -Dtest=InventoryTransactionApiTest`.
Test dùng PostgreSQL 16.10, Flyway và MockMvc; có kiểm tra Bearer JWT thật,
phân quyền/phạm vi chi nhánh, biên ngày, phân trang, tham chiếu và đầu vào sai.
