# User story và tiêu chí chấp nhận theo issue

Bổ sung SRS mục 4 (US-01..US-10): mỗi issue nhãn `must` có user story và tiêu chí Given/When/Then. Nội dung giống phần "User story" trong mô tả từng issue trên GitHub; khi sửa, sửa cả hai nơi.

- Tiêu chí dùng trực tiếp làm test case (QA, SV08) và làm điều kiện đóng issue.
- Mã lỗi, tên trường theo `docs/api/openapi.yaml` v2.0.0; quy tắc theo SRS (BR, EX) và tài liệu phân tích nghiệp vụ.
- 112 issue có user story. Không áp dụng cho 36 issue quản lý, báo cáo, sửa lỗi chung (danh sách cuối trang).

## Mục lục

- [Nền tảng API](#nền-tảng-api) (1)
- [Xác thực, phân quyền](#xác-thực-phân-quyền) (6)
- [Danh mục, khách hàng](#danh-mục-khách-hàng) (3)
- [Bán tại quầy](#bán-tại-quầy) (7)
- [Khuyến mại](#khuyến-mại) (2)
- [Đơn online](#đơn-online) (4)
- [Tồn kho](#tồn-kho) (8)
- [Phiếu kho](#phiếu-kho) (4)
- [Tích hợp kênh](#tích-hợp-kênh) (8)
- [Báo cáo, cảnh báo](#báo-cáo-cảnh-báo) (5)
- [Dữ liệu và dự báo](#dữ-liệu-và-dự-báo) (14)
- [Frontend nền tảng](#frontend-nền-tảng) (4)
- [Frontend bán hàng](#frontend-bán-hàng) (11)
- [Frontend vận hành](#frontend-vận-hành) (9)
- [Vận hành](#vận-hành) (6)
- [Kiểm thử](#kiểm-thử) (9)
- [Thiết kế giao diện](#thiết-kế-giao-diện) (11)

## Nền tảng API

### #28 · Phân trang, lọc, định dạng lỗi chung theo quy ước API

Là **lập trình viên frontend**, tôi muốn mọi danh sách phân trang, sắp xếp, lọc theo một quy ước để dùng chung một cách gọi cho mọi màn danh sách. *(SRS 8.2)*

1. **Given** không truyền `page`, `size`, **When** gọi một API danh sách, **Then** `page = 0`, `size = 20`, có `totalElements`, `totalPages`.
2. **Given** `size = 101`, **When** gọi API danh sách, **Then** 400 `VALIDATION_ERROR`, `details[0].field = size`.
3. **Given** `sort=passwordHash,asc` (trường không cho phép), **When** gọi API danh sách, **Then** 400, `details[0].field = sort`.
4. **Given** `from=2026-11-01&to=2026-11-01`, **When** lọc đơn, **Then** gồm mọi đơn từ 00:00 đến 23:59:59 ngày 01/11 giờ Việt Nam.

## Xác thực, phân quyền

### #144 · Module identity: user, role, chi nhánh, BCrypt

Là **QA**, tôi muốn có test case cho module identity để kiểm được người dùng, vai trò, chi nhánh, BCrypt. *(AUTH-04, NFR-05)*

1. **Given** tài liệu `identity-test-cases.md`, **When** đối chiếu `openapi.yaml` và seed #209, **Then** tên trường, mã lỗi, tài khoản khớp; mọi TC có kết quả mong đợi cụ thể.

### #145 · [AUTH-01] Login/refresh JWT

Là **nhân viên**, tôi muốn đăng nhập bằng tài khoản và giữ phiên an toàn để làm việc cả ca không phải đăng nhập lại. *(AUTH-01)*

1. **Given** đúng tài khoản, **When** đăng nhập, **Then** nhận access token 15 phút, refresh token 7 ngày.
2. **Given** refresh token đã dùng để xoay, **When** dùng lại, **Then** 401 `INVALID_REFRESH_TOKEN`.
3. **Given** sai 5 lần liên tiếp, **When** đăng nhập lần thứ 5, **Then** 401 `ACCOUNT_LOCKED`, `lockedUntil` sau 15 phút.
4. **Given** token `alg: none`, **When** gọi API, **Then** 401 `UNAUTHORIZED`.

### #146 · [AUTH-02] Spring Security RBAC + lọc chi nhánh

Là **chủ chuỗi**, tôi muốn nhân viên chỉ thao tác dữ liệu chi nhánh của họ để bảo vệ dữ liệu. *(AUTH-02, BR-11, EX-11, AC-04)*

1. **Given** thu ngân CN1, **When** gọi API xem hoặc tạo đơn CN2, **Then** 403 `BRANCH_FORBIDDEN`, `audit_log` ghi lần truy cập bị từ chối.
2. **Given** CASHIER, **When** gọi endpoint có `x-roles` không gồm CASHIER, **Then** 403 `FORBIDDEN`.
3. **Given** OWNER, **When** gọi danh sách không truyền `branchId`, **Then** thấy dữ liệu mọi chi nhánh.

### #148 · Tài khoản demo 5 vai trò × 3 chi nhánh

Là **QA**, tôi muốn có tài khoản demo đủ vai trò và chi nhánh để test và demo mọi vai trò. *(SRS 7.4)*

1. **Given** seed, **When** đăng nhập từng tài khoản demo, **Then** đủ ADMIN, OWNER và QLCN, thu ngân, thủ kho cho mỗi chi nhánh; mật khẩu thống nhất.

### #154 · [AUTH-03] Audit log thao tác nhạy cảm

Là **chủ chuỗi**, tôi muốn có nhật ký các thao tác nhạy cảm để truy trách nhiệm. *(AUTH-03)*

1. **Given** đổi giá, duyệt/điều chỉnh kho, hủy đơn, đăng nhập thất bại, truy cập sai chi nhánh, **When** thao tác, **Then** `audit_log` có bản ghi: người, hành động, đối tượng, chi tiết, thời gian; không sửa/xóa được.

### #155 · Rate limit đăng nhập, kiểm tra OWASP cơ bản

Là **chủ chuỗi**, tôi muốn hệ thống chống dò mật khẩu và lỗi OWASP cơ bản để an toàn. *(NFR-06)*

1. **Given** một IP đăng nhập liên tục, **When** vượt ngưỡng, **Then** bị giới hạn (429).
2. **Given** chạy checklist OWASP cơ bản, **When** kiểm tra, **Then** không SQL injection, không lộ stack trace, CORS giới hạn nguồn.

## Danh mục, khách hàng

### #26 · [PRD-01] Module catalog: CRUD danh mục và sản phẩm

Là **chủ chuỗi**, tôi muốn quản lý danh mục cha–con và sản phẩm với SKU, barcode, giá bán, giá vốn để dữ liệu bán hàng ở mọi chi nhánh và kênh dùng chung một nguồn. *(PRD-01, PRD-02, BR-12)*

1. **Given** đã đăng nhập OWNER, **When** gửi `POST /products` với SKU mới hợp lệ, **Then** 201, sản phẩm có trong `GET /products`.
2. **Given** SKU `SUA-TH-1L` đã tồn tại, **When** tạo sản phẩm khác cùng SKU, **Then** 409 `DUPLICATE_SKU`.
3. **Given** đăng nhập CASHIER, **When** gửi `PUT /products/{id}` đổi `salePrice`, **Then** 403 `FORBIDDEN`; giá không đổi.
4. **Given** OWNER đổi giá bán từ 29.000 đ thành 31.000 đ, **When** lưu thành công, **Then** `audit_log` có bản ghi đổi giá (PRD-02); đơn cũ giữ giá đã chụp (BR-12).
5. **Given** danh mục A là cha của B, **When** đặt cha của A là B, **Then** 422 `CATEGORY_CYCLE`.
6. **Given** danh mục còn sản phẩm hoặc danh mục con, **When** xóa danh mục, **Then** 409 `CATEGORY_IN_USE`.
7. **Given** đăng nhập CASHIER, **When** gọi `GET /products`, **Then** `costPrice` trả về `null`.

### #27 · [CUS-01] Module customer: CRUD khách hàng, SĐT duy nhất

Là **thu ngân**, tôi muốn tìm và tạo khách theo số điện thoại để gắn khách vào đơn và xem lịch sử mua trên mọi kênh. *(CUS-01, CUS-02)*

1. **Given** chưa có khách 0901234567, **When** tạo khách với SĐT đó, **Then** 201; tìm `GET /customers?q=0901234567` thấy đúng 1 khách.
2. **Given** SĐT đã thuộc khách khác, **When** tạo hoặc sửa khách dùng SĐT đó, **Then** 409 `DUPLICATE_PHONE`, `details.customerId` là khách đang giữ SĐT.
3. **Given** SĐT `12345`, **When** tạo khách, **Then** 400 `VALIDATION_ERROR`, `details[0].field = phone`.
4. **Given** khách có đơn POS và đơn WEB, **When** gọi `GET /customers/{id}/orders`, **Then** trả đủ đơn của cả hai kênh, mới nhất trước.

### #30 · Unit test catalog và customer

Là **QA**, tôi muốn có unit test cho catalog và customer để phát hiện lỗi quy tắc dữ liệu trước khi merge. *(PRD-01, CUS-01)*

1. **Given** chạy `mvn verify`, **When** CI chạy test catalog, customer, **Then** có test cho trùng SKU, trùng barcode, trùng SĐT, vòng lặp danh mục, CASHIER không đổi giá; tất cả pass.
2. **Given** một quy tắc ở #26, #27 bị sửa sai, **When** chạy lại test, **Then** ít nhất 1 test fail chỉ đúng quy tắc đó.

## Bán tại quầy

### #31 · Entity sales_order, order_item, payment; POST /orders/quote

Là **thu ngân**, tôi muốn thấy tạm tính, khuyến mại và tổng tiền ngay khi thêm hàng để báo đúng số tiền cho khách trước khi thu. *(UC-01, PRM-02, BR-07)*

1. **Given** giỏ gạo ST25 180.000 đ (KM giảm 15.000 đ) và 2 sữa 29.000 đ, KM đơn 10% từ 200.000 đ tối đa 30.000 đ, **When** gọi `POST /orders/quote`, **Then** `subtotal` 238.000, `itemDiscount` 15.000, `orderDiscount` 22.300, `total` 200.700.
2. **Given** một dòng có `quantity` > khả dụng, **When** gọi quote, **Then** dòng đó `sufficient = false`, `canComplete = false`; không ghi gì vào CSDL.
3. **Given** gọi quote, **When** kiểm tra CSDL, **Then** không có đơn, giữ chỗ hay giao dịch kho nào được tạo.

### #32 · [ORD-01] POST /orders/pos trong một transaction (UC-01)

Là **thu ngân**, tôi muốn hoàn tất đơn và trừ tồn trong một thao tác để khách thanh toán xong là xong, không lệch tồn. *(ORD-01, UC-01, BR-01, BR-02)*

1. **Given** sản phẩm X khả dụng 10, **When** bán 2, thanh toán tiền mặt đủ, **Then** 201, đơn COMPLETED, khả dụng 8, sổ kho có 1 dòng SALE −2, outbox có `order.completed` và `stock.changed`.
2. **Given** tổng tính lại khác `expectedTotal` (KM vừa hết hạn), **When** hoàn tất, **Then** 422 `TOTAL_CHANGED`, `details.quote` là báo giá mới; không tạo đơn.
3. **Given** một dòng thiếu tồn lúc hoàn tất, **When** hoàn tất, **Then** 409 `INSUFFICIENT_STOCK`, `details.lines` liệt kê dòng thiếu; không tạo đơn, không dòng nào bị trừ.
4. **Given** thu ngân CN1, **When** tạo đơn với `branchId` CN2, **Then** 403 `BRANCH_FORBIDDEN`.

### #33 · [PAY-01] Thanh toán giả lập tiền mặt, thẻ, QR

Là **thu ngân**, tôi muốn thu tiền mặt, thẻ, QR giả lập để thử được cả trường hợp thành công lẫn thất bại. *(PAY-01)*

1. **Given** tổng 200.700 đ, **When** thanh toán CASH với `cashReceived` 250.000, **Then** đơn COMPLETED, payment PAID, `change` 49.300.
2. **Given** tổng 200.700 đ, **When** CASH với `cashReceived` 200.000, **Then** 422 `CASH_INSUFFICIENT`.
3. **Given** CARD với `simulate = FAILED`, **When** hoàn tất, **Then** 422 `PAYMENT_FAILED`; không tạo đơn, tồn không đổi.
4. **Given** QR với `simulate = TIMEOUT`, **When** hoàn tất, **Then** 422 `PAYMENT_TIMEOUT` sau tối đa 60 giây; không tạo đơn.

### #34 · Idempotency-Key cho tạo đơn và thanh toán (Redis 24h)

Là **thu ngân**, tôi muốn bấm Hoàn tất lại sau lỗi mạng mà không tạo đơn trùng để khách không bị trừ tiền hai lần. *(ORD-01, EX-04)*

1. **Given** đơn đã tạo với `Idempotency-Key` K, **When** gửi lại cùng K, cùng nội dung trong 24 giờ, **Then** 200, trả đúng đơn cũ; không tạo đơn, không trừ tồn thêm.
2. **Given** đã dùng K, **When** gửi K với nội dung khác, **Then** 409 `IDEMPOTENCY_CONFLICT`.
3. **Given** request với K đang xử lý, **When** gửi song song request thứ hai cùng K, **Then** 409 `IDEMPOTENCY_CONFLICT`; cuối cùng chỉ có 1 đơn.

### #35 · Test hóa đơn ví dụ ra đúng 200.700 đ

Là **QA**, tôi muốn có test khóa đúng hóa đơn ví dụ trong tài liệu để công thức khuyến mại không bị sửa sai âm thầm. *(BR-07, UC-01)*

1. **Given** dữ liệu hóa đơn ví dụ (BA mục 3), **When** chạy test, **Then** tổng phải trả đúng 200.700 đ, giảm dòng 15.000 đ, giảm đơn 22.300 đ.

### #36 · Integration test luồng POS bằng Testcontainers

Là **QA**, tôi muốn có integration test luồng POS trên PostgreSQL thật để bắt lỗi transaction, khóa và ràng buộc CSDL. *(ORD-01, NFR)*

1. **Given** Testcontainers PostgreSQL + Flyway, **When** chạy luồng quote → pos, **Then** đơn, dòng đơn, payment, sổ kho, outbox được ghi đúng trong một transaction.
2. **Given** lỗi xảy ra ở dòng thứ 2 của đơn 2 dòng, **When** hoàn tất, **Then** toàn bộ rollback, dòng 1 không bị trừ tồn.

### #37 · Negative test: thiếu tồn 409, dữ liệu sai 400, vi phạm nghiệp vụ 422

Là **QA**, tôi muốn có bộ test lỗi cho API bán hàng để client nhận đúng mã lỗi để hiển thị. *(ORD-01, SRS 8.2)*

1. **Given** thiếu tồn / dữ liệu sai / vi phạm nghiệp vụ, **When** gọi `POST /orders/pos`, **Then** lần lượt 409 `INSUFFICIENT_STOCK`, 400 `VALIDATION_ERROR` có `details`, 422 với đúng `code`.
2. **Given** mọi response lỗi, **When** kiểm tra body, **Then** đúng `ErrorResponse`, có `traceId`, không có stack trace.

## Khuyến mại

### #38 · [PRM-01] Module promotion: CRUD, trạng thái, phạm vi

Là **chủ chuỗi**, tôi muốn tạo và bật/tắt chương trình khuyến mại theo chi nhánh, kênh, thời gian để chạy khuyến mại đúng nơi, đúng lúc. *(PRM-01, PRM-03, BR-08)*

1. **Given** OWNER, **When** tạo KM cấp ORDER 10% tối đa 30.000 đ, đơn từ 200.000 đ, **Then** 201, trạng thái DRAFT.
2. **Given** KM DRAFT, **When** đổi trạng thái sang ACTIVE, **Then** KM được áp ở quote trong thời gian hiệu lực.
3. **Given** KM ENDED, **When** đổi sang ACTIVE, **Then** 409 `INVALID_STATUS`.
4. **Given** KM cấp ITEM không có `productIds`, **When** tạo, **Then** 400 `VALIDATION_ERROR`.
5. **Given** mã KM đã tồn tại, **When** tạo KM cùng mã, **Then** 409 `DUPLICATE_PROMOTION_CODE`.
6. **Given** BRANCH_MANAGER, **When** tạo KM, **Then** 403 `FORBIDDEN`.

### #39 · [PRM-02] Bộ tính khuyến mại + test bảng ví dụ

Là **khách hàng**, tôi muốn được tự áp khuyến mại có lợi nhất để không bị thiệt khi thu ngân quên chọn. *(PRM-02, BR-07, EX-10)*

1. **Given** các dòng trong bảng ví dụ BA mục 7 (180.000 / 223.000 / 260.000 / 350.000 / kênh ngoài phạm vi), **When** tính quote, **Then** KM được chọn và số giảm đúng từng dòng của bảng.
2. **Given** hai KM cấp đơn giảm bằng nhau, **When** tính quote, **Then** chọn KM hết hạn sớm hơn.
3. **Given** KM còn 1 lượt, hai đơn cùng hoàn tất, **When** gửi đồng thời, **Then** chỉ 1 đơn được giảm; đơn kia nhận 422 `TOTAL_CHANGED`; `used_count` không vượt `usage_limit`.

## Đơn online

### #40 · [ORD-02] Đơn online: vòng đời trạng thái, phân bổ chi nhánh

Là **quản lý chi nhánh**, tôi muốn đơn từ kênh tự được phân bổ chi nhánh và giữ hàng để không phải nhập tay và không bán trùng hàng. *(ORD-02, UC-02, BR-03, BR-14)*

1. **Given** CN mặc định của WEB đủ hàng, **When** nhận đơn WEB 3 cái, **Then** đơn RESERVED tại CN mặc định, `reservedUntil` = lúc nhận + 15 phút, khả dụng giảm 3.
2. **Given** CN mặc định thiếu, CN khác phục vụ kênh đủ hàng, **When** nhận đơn, **Then** chọn CN đủ hàng có tổng khả dụng cao nhất (BR-14), không tách đơn.
3. **Given** không CN nào đủ, **When** nhận đơn, **Then** đơn REJECTED lý do `OUT_OF_STOCK`, không giữ chỗ.
4. **Given** đơn COD, **When** giữ chỗ thành công, **Then** đơn chuyển CONFIRMED ngay.
5. **Given** đơn CONFIRMED, **When** thủ kho gọi `POST /orders/{id}/complete`, **Then** COMPLETED, tồn thực và giữ chỗ cùng giảm, sổ kho SALE.

### #41 · Job hết hạn giữ chỗ 15 phút → EXPIRED

Là **chủ chuỗi**, tôi muốn hàng giữ cho đơn không thanh toán được tự trả lại để bán được cho khách khác. *(ORD-03, EX-05, EX-06)*

1. **Given** đơn RESERVED giữ 3 cái lúc 10:00, **When** đến 10:15 chưa thanh toán, **Then** trong vòng 1 phút đơn EXPIRED, giữ chỗ giảm 3 (RELEASE), kênh nhận tồn mới.
2. **Given** đơn đã EXPIRED, còn đủ hàng, **When** kênh gửi sự kiện PAID, **Then** giữ chỗ lại, đơn CONFIRMED.
3. **Given** đơn đã EXPIRED, hết hàng, **When** kênh gửi PAID, **Then** đơn CANCELLED, payment REFUNDED (giả lập).

### #42 · Hủy đơn và phát sự kiện order.* qua outbox

Là **quản lý chi nhánh**, tôi muốn hủy đơn chưa giao kèm lý do để nhả hàng giữ chỗ và báo kênh ngay. *(ORD-04, UC-03, AUTH-03)*

1. **Given** đơn CONFIRMED đã thanh toán, **When** QLCN hủy với lý do `CUSTOMER_REQUEST`, **Then** CANCELLED, nhả giữ chỗ, payment REFUNDED, hoàn lượt KM, `audit_log` có bản ghi, outbox `order.cancelled`.
2. **Given** đơn COMPLETED hoặc đơn POS, **When** gọi hủy, **Then** 409 `INVALID_STATUS`.
3. **Given** lý do `OTHER` không có `note`, **When** gọi hủy, **Then** 400 `VALIDATION_ERROR`.

### #81 · Chi tiết đơn online: xác nhận, hoàn tất, hủy

Là **quản lý chi nhánh**, tôi muốn xem chi tiết đơn online, hoàn tất hoặc hủy để xử lý đơn đúng trạng thái. *(ORD-03, ORD-04)*

1. **Given** đơn CONFIRMED, **When** bấm Hoàn tất, **Then** đơn COMPLETED; nút chỉ hiện với QLCN, Thủ kho.
2. **Given** đơn RESERVED, **When** bấm Hủy và chọn lý do, **Then** đơn CANCELLED; lý do OTHER bắt buộc ghi chú.
3. **Given** đơn COMPLETED, **When** mở chi tiết, **Then** không có nút Hủy.

## Tồn kho

### #50 · [INV-06] Ghi inventory_transaction cùng transaction

Là **quản lý chi nhánh**, tôi muốn mọi thay đổi tồn đều có dòng sổ kho trong cùng transaction để truy được mọi biến động về chứng từ gốc. *(INV-06, BR-06)*

1. **Given** bán POS 2 cái, **When** transaction commit, **Then** có 1 dòng SALE `deltaOnHand = -2`, `onHandAfter` đúng tồn sau, `refType = ORDER`.
2. **Given** giữ chỗ 3 cái cho đơn online, **When** commit, **Then** có 1 dòng RESERVE `deltaOnHand = 0, deltaReserved = +3`.
3. **Given** ghi sổ kho bị lỗi, **When** trong cùng transaction, **Then** cập nhật tồn cũng rollback.
4. **Given** bất kỳ dòng sổ kho, **When** cố UPDATE hoặc DELETE, **Then** không có API hay code nào làm được (chỉ INSERT).

### #51 · Test 100 luồng song song mua sản phẩm còn 5 (EX-01)

Là **chủ chuỗi**, tôi muốn hệ thống không bao giờ bán vượt tồn khi nhiều người mua cùng lúc để không phải hủy đơn của khách. *(INV-07, EX-01, AC-02)*

1. **Given** sản phẩm còn khả dụng 5, **When** 100 yêu cầu (POS và online lẫn lộn) mỗi cái mua 1 gửi đồng thời, **Then** đúng 5 thành công, 95 nhận 409 `INSUFFICIENT_STOCK`, khả dụng = 0, không âm.

### #52 · [INV-01] API GET /inventory

Là **thủ kho**, tôi muốn xem tồn thực, giữ chỗ, khả dụng của từng sản phẩm tại chi nhánh để biết còn bao nhiêu hàng bán được. *(INV-01)*

1. **Given** sản phẩm có tồn thực 35, giữ chỗ 5, **When** gọi `GET /inventory?branchId=1`, **Then** `onHand` 35, `reserved` 5, `available` 30, `stockStatus` theo ROP.
2. **Given** thu ngân CN1, **When** gọi `GET /inventory?branchId=2`, **Then** 403 `BRANCH_FORBIDDEN`.
3. **Given** lọc `stockStatus=OUT`, **When** gọi API, **Then** chỉ trả dòng có khả dụng = 0.

### #55 · API sổ giao dịch kho

Là **quản lý chi nhánh**, tôi muốn xem và lọc sổ giao dịch kho để đối chiếu khi tồn thực tế lệch. *(INV-06)*

1. **Given** lọc theo sản phẩm và khoảng ngày, **When** gọi `GET /inventory/transactions`, **Then** trả các dòng mới nhất trước, mỗi dòng có `refType`, `refId`, `refCode`.
2. **Given** CASHIER, **When** gọi API, **Then** 403 `FORBIDDEN`.

### #56 · Integration test chống tồn âm và phiếu kho (Testcontainers)

Là **QA**, tôi muốn có integration test chống tồn âm và phiếu kho để đảm bảo ràng buộc giữ đúng cả khi code sai. *(INV-07, INV-02, INV-03)*

1. **Given** Testcontainers, **When** chạy các luồng nhập, xuất hủy, bán đồng thời, **Then** tồn không bao giờ âm; mỗi thay đổi có đúng 1 dòng sổ kho.

### #57 · Test deadlock hai đơn ngược thứ tự (EX-02)

Là **QA**, tôi muốn có test hai đơn ngược thứ tự sản phẩm để chắc không có deadlock. *(EX-02)*

1. **Given** đơn 1 [A, B], đơn 2 [B, A], **When** gửi đồng thời 50 lần, **Then** không lần nào deadlock; cả hai thành công khi đủ tồn (khóa theo `productId` tăng dần).

### #59 · Phát sự kiện stock.changed qua outbox

Là **khách mua online**, tôi muốn thấy số tồn mới ngay khi tồn đổi để không đặt món đã hết. *(SYNC-03, BR-15)*

1. **Given** tồn khả dụng đổi trong transaction, **When** commit, **Then** outbox có đúng 1 sự kiện `stock.changed` kèm `version` mới; rollback thì không có sự kiện.

### #62 · Rà soát ràng buộc CSDL: FK, UNIQUE, CHECK, chỉ mục

Là **lập trình viên**, tôi muốn ràng buộc CSDL chặn dữ liệu sai để lỗi code không làm hỏng dữ liệu. *(NFR, BR-01)*

1. **Given** migration V1–V4, **When** rà soát, **Then** mọi FK, UNIQUE, CHECK trong ERD README có trong migration; có các chỉ mục ở mục "Chỉ mục cần có".
2. **Given** chèn tồn âm bằng SQL, **When** thực thi, **Then** bị CHECK từ chối.

## Phiếu kho

### #53 · [INV-02] Phiếu nhập có duyệt (UC-05)

Là **quản lý chi nhánh**, tôi muốn duyệt phiếu nhập trước khi tồn tăng để tránh nhập sai hoặc gian lận. *(INV-02, UC-05, BR-13)*

1. **Given** thủ kho A lập phiếu nhập 24 sữa giá 23.500 đ, **When** A tự duyệt, **Then** 422 `SELF_APPROVAL_FORBIDDEN`; tồn không đổi.
2. **Given** phiếu nhập DRAFT 24 cái, **When** QLCN duyệt, **Then** APPROVED, `approvedAt` có giá trị, tồn thực +24, sổ kho IMPORT +24, giá vốn bình quân cập nhật.
3. **Given** tồn 20 giá vốn 10.000 đ, **When** duyệt nhập 30 giá 11.000 đ, **Then** giá vốn mới 10.600 đ.
4. **Given** phiếu đã APPROVED, **When** sửa hoặc hủy, **Then** 409 `INVALID_STATUS`.

### #54 · [INV-03] Phiếu xuất hủy có lý do (UC-06)

Là **thủ kho**, tôi muốn lập phiếu xuất hủy có lý do bắt buộc để biết hàng mất vì sao và giá trị hao hụt. *(INV-03, UC-06, EX-07)*

1. **Given** phiếu xuất hủy không có lý do, **When** lập phiếu, **Then** 400 `VALIDATION_ERROR`.
2. **Given** khả dụng 8 (tồn thực 10, giữ chỗ 2), **When** duyệt phiếu xuất hủy 10, **Then** 409 `INSUFFICIENT_STOCK`, `details.lines[0].available = 8`.
3. **Given** phiếu xuất hủy 3 cái, **When** QLCN duyệt, **Then** tồn thực −3, sổ kho EXPORT −3, giá trị hủy = 3 × giá vốn.

### #58 · [INV-04] Chuyển kho hai bước, ghi chênh lệch (UC-07)

Là **thủ kho**, tôi muốn chuyển hàng sang chi nhánh khác có chứng từ hai bước để truy được trách nhiệm khi nhận thiếu. *(INV-04, UC-07, BR-16, EX-09)*

1. **Given** CN1 khả dụng 80, **When** QLCN1 duyệt phiếu chuyển 50 sang CN2, **Then** phiếu IN_TRANSIT, `approvedAt` có giá trị, tồn thực CN1 −50 (TRANSFER_OUT).
2. **Given** phiếu IN_TRANSIT 50 cái, **When** thủ kho CN2 nhận 48, **Then** RECEIVED, `receivedAt` có giá trị, tồn CN2 +48 (TRANSFER_IN), `hasDiscrepancy = true`, chênh lệch −2.
3. **Given** nhập số nhận 51 > 50, **When** nhận hàng, **Then** 422 `RECEIVED_QUANTITY_EXCEEDED`.
4. **Given** người dùng CN3, **When** nhận phiếu chuyển CN1 → CN2, **Then** 403 `BRANCH_FORBIDDEN`.

### #215 · Migration V4: thêm cột thời gian cho phiếu trả hàng và phiếu kho

Là **QA**, tôi muốn có cột thời gian cho phiếu trả và phiếu kho để kiểm được hạn trả 7 ngày và ngày trả hàng. *(BR-10, BR-18)*

1. **Given** DB đã có V1–V3 và dữ liệu seed, **When** chạy V4, **Then** thành công; phiếu đã duyệt có `approved_at`.
2. **Given** phiếu APPROVED thiếu `approved_at`, **When** INSERT, **Then** bị CHECK từ chối.
3. **Given** `received_at` sớm hơn `approved_at`, **When** INSERT, **Then** bị CHECK từ chối.

## Tích hợp kênh

### #127 · Outbox + exchange greenmart.events + DLQ

Là **chủ chuỗi**, tôi muốn sự kiện không bị mất khi RabbitMQ lỗi để kênh luôn nhận tồn đúng. *(SRS 8.5, EX-12)*

1. **Given** giao dịch ghi `outbox_event`, **When** RabbitMQ đang chạy, **Then** sự kiện đẩy lên exchange `greenmart.events`, outbox SENT.
2. **Given** RabbitMQ dừng, **When** có giao dịch mới, **Then** outbox giữ PENDING; chạy lại thì gửi bù đủ.
3. **Given** consumer lỗi nhiều lần, **When** xử lý, **Then** message vào DLQ.

### #128 · [SYNC-01] Mock connector 2 kênh WEB, MARKET

Là **người demo**, tôi muốn có 2 kênh giả lập WEB, MARKET với giao diện tạo đơn để trình diễn đơn đa kênh. *(SYNC-01)*

1. **Given** mở mock connector, **When** tạo đơn giả, **Then** gửi webhook có `X-Signature` đúng; hiện kết quả 202/200/409.
2. **Given** backend đẩy tồn, **When** mock nhận, **Then** bỏ qua bản có `version` cũ hơn.

### #130 · Bảng sales_channel, channel_product, ánh xạ SKU

Là **quản trị**, tôi muốn cấu hình kênh và ánh xạ SKU để nhận đơn đúng sản phẩm. *(SYNC-02)*

1. **Given** SKU kênh `W-SUA-TH-1L` ánh xạ sản phẩm 205, **When** kênh gửi đơn, **Then** dòng đơn là sản phẩm 205.
2. **Given** một kênh hai SKU trùng nhau, **When** lưu ánh xạ, **Then** bị UNIQUE chặn.

### #132 · [SYNC-02] Webhook nhận đơn: HMAC, chống trùng

Là **quản lý chi nhánh**, tôi muốn đơn từ kênh vào hệ thống an toàn, không trùng để không xử lý đơn hai lần. *(SYNC-02, BR-05, EX-03)*

1. **Given** chữ ký HMAC sai, **When** kênh gửi đơn, **Then** 401 `INVALID_SIGNATURE`, không ghi gì.
2. **Given** đơn WEB-100 đã nhận, **When** kênh gửi lại 3 lần, **Then** 200, trả đơn cũ, giữ chỗ 1 lần.
3. **Given** SKU chưa ánh xạ, **When** kênh gửi đơn, **Then** đơn REJECTED, 422 `UNMAPPED_SKU`.

### #133 · [SYNC-03] Đẩy tồn ra kênh theo version

Là **khách mua online**, tôi muốn thấy đúng số hàng còn bán để không đặt món đã hết. *(SYNC-03, BR-15)*

1. **Given** khả dụng các CN phục vụ WEB là 30 và 13, tồn an toàn 2, **When** tồn đổi, **Then** WEB nhận `available` 41 kèm `version` mới trong vòng 1 phút.
2. **Given** tổng khả dụng 1, **When** tính, **Then** đẩy 0, không âm.

### #134 · [SYNC-04] Retry 5 lần, ghi sync_log

Là **quản trị**, tôi muốn gửi ra kênh tự thử lại và có nhật ký để không mất cập nhật khi kênh lỗi tạm thời. *(SYNC-05, BA E3)*

1. **Given** kênh trả lỗi, **When** gửi, **Then** thử lại 5 lần sau 1, 2, 4, 8, 16 giây; `sync_log` ghi số lần.
2. **Given** vẫn lỗi sau 5 lần, **When** kết thúc, **Then** `sync_log` FAILED, vào hàng đợi lỗi.

### #137 · Báo trạng thái đơn về kênh

Là **khách mua online**, tôi muốn nhận trạng thái đơn trên kênh để biết đơn đã xử lý tới đâu. *(SYNC-04)*

1. **Given** đơn đổi trạng thái (CONFIRMED, COMPLETED, CANCELLED, EXPIRED, REJECTED), **When** commit, **Then** kênh nhận `PUT /mock/{channel}/orders/{id}/status` kèm lý do nếu có.

### #138 · Demo tắt RabbitMQ 5 phút, gửi bù (EX-12)

Là **hội đồng**, tôi muốn thấy hệ thống chịu được RabbitMQ dừng để tin dữ liệu không mất. *(EX-12, AC-03)*

1. **Given** dừng RabbitMQ 5 phút trong lúc bán, **When** chạy lại, **Then** bán hàng không gián đoạn; mọi sự kiện được gửi bù; kênh có tồn đúng.

## Báo cáo, cảnh báo

### #60 · [DASH-03] Cảnh báo tồn thấp theo ROP

Là **quản lý chi nhánh**, tôi muốn được cảnh báo khi hàng sắp hết để nhập kịp. *(DASH-03, BR-09)*

1. **Given** ROP sữa CN1 là 34, **When** khả dụng giảm từ 35 xuống 30, **Then** mở 1 cảnh báo "Tồn thấp".
2. **Given** khả dụng về 0, **When** sau giao dịch, **Then** cảnh báo chuyển mức "Hết hàng"; vẫn chỉ 1 cảnh báo mở cho sản phẩm × chi nhánh.
3. **Given** đang có cảnh báo, **When** nhập thêm 96, khả dụng 126 > ROP, **Then** cảnh báo tự đóng.

### #61 · API /alerts/low-stock và sự kiện stock.low

Là **quản lý chi nhánh**, tôi muốn xem danh sách cảnh báo tồn thấp qua API và sự kiện để dashboard và kênh khác nhận được. *(DASH-03)*

1. **Given** có cảnh báo mở, **When** gọi `GET /alerts/low-stock?branchId=1`, **Then** trả danh sách, Hết hàng trước rồi Tồn thấp.
2. **Given** cảnh báo vừa mở, **When** commit, **Then** outbox có `stock.low`.

### #97 · [DASH-01] Dashboard: thẻ số, doanh thu 30 ngày, theo chi nhánh

Là **chủ chuỗi**, tôi muốn xem doanh thu theo ngày và chi nhánh để nắm tình hình kinh doanh. *(DASH-01)*

1. **Given** đơn COMPLETED và trả hàng, **When** xem dashboard 30 ngày, **Then** doanh thu tính theo ngày hoàn tất, trả hàng trừ vào ngày trả (BR-18); lọc được theo chi nhánh, kênh.

### #98 · Danh sách cảnh báo tồn thấp

Là **quản lý chi nhánh**, tôi muốn xem danh sách cảnh báo tồn thấp để nhập kịp. *(DASH-03)*

1. **Given** có cảnh báo, **When** mở màn cảnh báo, **Then** Hết hàng (đỏ) trước, Tồn thấp (cam) sau; nhập xong cảnh báo biến mất.

### #115 · API báo cáo doanh thu /reports/revenue, /reports/top-products

Là **chủ chuỗi**, tôi muốn lấy báo cáo doanh thu và top sản phẩm qua API để dashboard hiển thị đúng. *(DASH-01, DASH-02, BR-18)*

1. **Given** đơn COMPLETED và trả hàng, **When** gọi `/reports/revenue?groupBy=day`, **Then** doanh thu theo ngày hoàn tất, trừ trả hàng theo ngày trả.
2. **Given** gọi `/reports/top-products`, **When** tháng 11, **Then** top 10 bán chạy và chậm theo số lượng ròng.

## Dữ liệu và dự báo

### #64 · Tính ROP và gợi ý nhập từ forecast_result

Là **quản lý chi nhánh**, tôi muốn có ROP và số lượng gợi ý nhập tính từ dự báo để nhập đủ, không thừa. *(AI-05)*

1. **Given** d̄ = 12, σ = 4, L = 2, z = 1,65, khả dụng 30, quy cách 12, **When** tính, **Then** SS = 10, ROP = 34, gợi ý = 96 (8 thùng) đúng ví dụ BA 8.3.
2. **Given** chưa có dự báo, **When** tính ROP, **Then** dùng ROP nhập tay.

### #107 · Sinh tồn kho đầu kỳ và sản phẩm tồn thấp để demo

Là **người demo**, tôi muốn có tồn đầu kỳ và vài sản phẩm tồn thấp sẵn để trình diễn cảnh báo ngay. *(AC-06)*

1. **Given** chạy seed, **When** xem `inventory`, **Then** mọi sản phẩm × 3 chi nhánh có tồn; có ít nhất 10 sản phẩm khả dụng ≤ ROP để demo cảnh báo.

### #108 · Seed chạy lại bằng một lệnh, random seed cố định, kiểm đếm AC-01

Là **hội đồng**, tôi muốn chạy seed một lệnh và đếm đúng số liệu để kiểm chứng AC-01. *(AI-01, AC-01)*

1. **Given** DB trống, **When** chạy một lệnh seed, **Then** 3 chi nhánh, 1.000 sản phẩm, 10.000 đơn đã xử lý.
2. **Given** chạy seed 2 lần, **When** so sánh, **Then** số liệu và SHA-256 file sinh ra giống hệt.

### #109 · Bảng daily_sales (bán ròng, ngày 0, cờ stockout, cờ KM)

Là **mô hình dự báo**, tôi muốn có bảng bán ròng theo ngày × sản phẩm × chi nhánh để học đúng nhu cầu. *(AI-02, BR-18)*

1. **Given** đơn COMPLETED ngày D và trả hàng ngày D+2, **When** tổng hợp, **Then** `qty_sold` ngày D, `qty_returned` ngày D+2 (theo `approved_at` phiếu trả), `qty_net` đúng.
2. **Given** ngày không bán, **When** tổng hợp, **Then** có dòng 0; ngày hết hàng có cờ `stockout`; ngày có KM có cờ và % giảm.
3. **Given** đơn CANCELLED, EXPIRED, REJECTED, **When** tổng hợp, **Then** không tính.

### #110 · Notebook khám phá dữ liệu

Là **SV06**, tôi muốn có notebook khám phá dữ liệu để chọn đặc trưng đúng. *(AI-02)*

1. **Given** `daily_sales`, **When** chạy notebook, **Then** có biểu đồ mùa vụ theo thứ, Tết, tác động KM, phân bố 80/20.

### #111 · [AI-03] Hai baseline + MAE/WAPE

Là **chủ chuỗi**, tôi muốn có baseline để so mô hình để biết mô hình có tốt thật không. *(AI-04)*

1. **Given** 30 ngày cuối làm tập kiểm tra, **When** chạy naive theo tuần và trung bình trượt 28 ngày, **Then** có MAE, WAPE cho tầm 7 và 30 ngày.

### #112 · Mô hình LightGBM bản đầu

Là **SV06**, tôi muốn có mô hình LightGBM bản đầu để có kết quả để cải tiến. *(AI-03)*

1. **Given** đặc trưng từ notebook, **When** huấn luyện, **Then** dự báo được tập kiểm tra; ghi MAE, WAPE so baseline.

### #113 · [AI-02] Dự báo 7 và 30 ngày, so baseline theo nhóm

Là **chủ chuỗi**, tôi muốn có dự báo 7 và 30 ngày tốt hơn baseline để lập kế hoạch nhập. *(AI-03, AC-05)*

1. **Given** dữ liệu 12 tháng, **When** chạy dự báo, **Then** mỗi sản phẩm × chi nhánh có số cho từng ngày 7 và 30 ngày tới.
2. **Given** 30 ngày cuối, **When** so sánh, **Then** WAPE mô hình < baseline tốt nhất ở cả hai tầm.

### #114 · Service FastAPI /forecast/run, ghi forecast_result

Là **backend**, tôi muốn gọi chạy dự báo theo yêu cầu để không phải chờ 02:00. *(AI-03, SRS 8.4)*

1. **Given** gọi `POST /forecast/run`, **When** ngay lập tức, **Then** trả `runId`, chạy nền.
2. **Given** chạy xong, **When** gọi `GET /forecast/runs/{runId}`, **Then** trạng thái DONE kèm MAE, WAPE; `forecast_result` có dữ liệu.

### #116 · Unit test daily_sales và hàm MAE/WAPE

Là **QA**, tôi muốn có unit test cho tổng hợp và hàm sai số để chắc số liệu báo cáo đúng. *(AI-02, AI-04)*

1. **Given** bộ dữ liệu nhỏ tự tính tay, **When** chạy test, **Then** `daily_sales`, MAE, WAPE khớp số tính tay.

### #117 · Báo cáo MAE/WAPE lần 1 (AC-05)

Là **hội đồng**, tôi muốn có báo cáo MAE/WAPE lần 1 để thấy tiến độ mô hình. *(AC-05)*

1. **Given** kết quả #111, #112, **When** viết báo cáo, **Then** bảng mô hình vs baseline theo tầm 7, 30 ngày và theo nhóm hàng.

### #118 · Lịch chạy dự báo 02:00, sự kiện forecast.completed

Là **chủ chuỗi**, tôi muốn dự báo tự chạy mỗi đêm để sáng có số mới. *(AI-03, SRS 8.4)*

1. **Given** đến 02:00, **When** lịch chạy, **Then** có `forecast_run` mới; xong phát `forecast.completed`.

### #119 · API GET /forecasts; hỗ trợ tính ROP

Là **quản lý chi nhánh**, tôi muốn xem dự báo qua API để so dự báo với thực tế. *(AI-05, AI-06)*

1. **Given** có `forecast_result`, **When** gọi `GET /forecasts?branchId=1&productId=205`, **Then** trả dự báo theo ngày cho 7 và 30 ngày.

### #120 · Báo cáo đánh giá mô hình cuối cùng

Là **hội đồng**, tôi muốn có báo cáo đánh giá mô hình cuối để chấm AC-05. *(AC-05)*

1. **Given** mô hình cuối, **When** viết báo cáo, **Then** có MAE, WAPE, so baseline, phân tích sai số theo nhóm, giới hạn của mô hình.

## Frontend nền tảng

### #69 · API client từ openapi.yaml và mock server

Là **lập trình viên frontend**, tôi muốn gọi API theo đúng hợp đồng và chạy được với mock để làm giao diện trước khi backend xong. *(SRS 8.2)*

1. **Given** `openapi.yaml` thay đổi, **When** chạy `npm run generate:api-types`, **Then** `schema.ts` cập nhật; sai tên trường thì `npm run typecheck` báo lỗi.
2. **Given** access token hết hạn, **When** gọi API, **Then** client tự refresh một lần rồi gọi lại; refresh thất bại thì về màn đăng nhập.
3. **Given** chạy `npm run dev:mock`, **When** mở POS, **Then** dữ liệu lấy từ Prism mock.

### #70 · Màn đăng nhập, làm mới token, chặn route theo vai trò

Là **nhân viên**, tôi muốn đăng nhập và chỉ thấy chức năng đúng vai trò để không thao tác nhầm phần không được phép. *(AUTH-01, AUTH-02, NFR-06)*

1. **Given** nhập sai mật khẩu, **When** đăng nhập, **Then** hiện thông báo không lộ tài khoản có tồn tại, có số lần còn lại.
2. **Given** sai 5 lần, **When** đăng nhập lần nữa, **Then** hiện tài khoản tạm khóa đến giờ `lockedUntil`.
3. **Given** CASHIER, **When** mở trực tiếp URL màn phiếu kho, **Then** bị chuyển về trang không đủ quyền.
4. **Given** người dùng được gán 1 chi nhánh, **When** đăng nhập, **Then** vào thẳng khu làm việc, không phải chọn chi nhánh.

### #87 · Component dùng chung: bảng, form, hộp thoại, thông báo

Là **lập trình viên frontend**, tôi muốn có bộ component dùng chung theo Design System để các màn đồng nhất, viết nhanh. *(SRS 8.1)*

1. **Given** component Table, **When** truyền `loading`, `error`, dữ liệu rỗng, **Then** hiện đúng trạng thái đang tải, lỗi kèm Thử lại, rỗng.
2. **Given** Dialog mở, **When** nhấn Esc hoặc Tab, **Then** Esc đóng; Tab không ra khỏi hộp thoại; đóng trả focus về nút mở.

### #90 · PR component dùng chung + hướng dẫn cho SV04

Là **SV04**, tôi muốn có hướng dẫn dùng component dùng chung để không viết lại component. *(SRS 8.1)*

1. **Given** trang ComponentShowcase, **When** mở, **Then** có ví dụ dùng từng component kèm props.

## Frontend bán hàng

### #71 · Layout khu POS và điều hướng

Là **thu ngân**, tôi muốn có khung POS với điều hướng rõ ràng để chuyển nhanh giữa bán hàng, hóa đơn, khách. *(SRS 8.1)*

1. **Given** thu ngân đã đăng nhập, **When** vào khu POS, **Then** thấy menu Bán hàng, Hóa đơn, Tra cứu khách; không thấy Đơn online, Phiếu kho.
2. **Given** đang ở màn bán hàng, **When** dùng phím tắt, **Then** chuyển được giữa các mục chính không cần chuột.

### #72 · Màn sản phẩm và khách hàng

Là **nhân viên**, tôi muốn tra cứu sản phẩm và khách hàng để trả lời khách nhanh. *(PRD-01, CUS-01)*

1. **Given** màn sản phẩm, **When** tìm theo tên, SKU hoặc barcode, **Then** danh sách lọc đúng, có phân trang; CASHIER không thấy giá vốn.
2. **Given** OWNER sửa giá bán, **When** lưu, **Then** giá mới hiện ngay; CASHIER không thấy nút sửa giá.

### #73 · Màn POS: quét/tìm sản phẩm, giỏ hàng, tồn khả dụng

Là **thu ngân**, tôi muốn quét hoặc tìm sản phẩm và thấy giỏ hàng cập nhật ngay để bán nhanh bằng máy quét. *(UC-01, ORD-01)*

1. **Given** máy quét gửi barcode như bàn phím, **When** quét sản phẩm, **Then** dòng được thêm vào giỏ; quét lại cùng mã thì tăng số lượng.
2. **Given** số lượng vượt khả dụng, **When** thêm vào giỏ, **Then** dòng đánh dấu thiếu hàng, nút Thanh toán bị khóa.
3. **Given** mỗi lần giỏ đổi, **When** sau khi gọi quote, **Then** tạm tính, giảm giá, tổng cập nhật.

### #74 · Gắn khách theo SĐT, tạo nhanh khách mới

Là **thu ngân**, tôi muốn gắn khách bằng số điện thoại, tạo nhanh nếu chưa có để khách được hưởng KM và lưu lịch sử. *(CUS-01)*

1. **Given** SĐT đã có khách, **When** nhập SĐT, **Then** hiện tên khách và gắn vào đơn.
2. **Given** SĐT chưa có, **When** tạo nhanh với tên, **Then** khách được tạo và gắn vào đơn; SĐT trùng thì báo lỗi rõ.

### #75 · Thanh toán tiền mặt/thẻ/QR, gửi Idempotency-Key

Là **thu ngân**, tôi muốn thanh toán tiền mặt, thẻ, QR và không tạo đơn trùng để khách trả tiền một lần. *(PAY-01, EX-04)*

1. **Given** tiền mặt, **When** nhập số khách đưa, **Then** hiện tiền thối; nhỏ hơn tổng thì không cho hoàn tất.
2. **Given** bấm Hoàn tất bị lỗi mạng, **When** bấm lại, **Then** gửi cùng `Idempotency-Key`; chỉ 1 đơn.
3. **Given** thẻ thất bại, **When** nhận 422 `PAYMENT_FAILED`, **Then** giỏ hàng giữ nguyên, gợi ý chọn phương thức khác.

### #76 · Kiểm tra dữ liệu form phía client

Là **thu ngân**, tôi muốn được báo lỗi nhập liệu ngay trên form để không phải chờ server trả lỗi. *(SRS 8.1)*

1. **Given** số lượng 0 hoặc âm, SĐT sai định dạng, **When** nhập, **Then** hiện lỗi cạnh trường, không gửi request.

### #77 · Chuyển sang API thật; hóa đơn sau khi bán

Là **thu ngân**, tôi muốn in hóa đơn ngay sau khi bán bằng API thật để giao cho khách. *(ORD-07)*

1. **Given** đơn vừa hoàn tất, **When** mở hóa đơn, **Then** có mã đơn, chi nhánh, thu ngân, dòng hàng, chiết khấu, tổng, tiền thối.
2. **Given** tắt mock, **When** chạy luồng bán, **Then** mọi màn POS dùng API thật, không lỗi.

### #78 · Hiện khuyến mại theo dòng và cấp đơn

Là **thu ngân**, tôi muốn thấy KM áp cho từng dòng và cấp đơn để giải thích cho khách. *(PRM-02)*

1. **Given** dòng có KM cấp ITEM, **When** xem giỏ, **Then** hiện mã KM và số giảm ở dòng.
2. **Given** đơn có KM cấp đơn, **When** xem tổng, **Then** hiện mã KM đơn và số giảm riêng.

### #79 · Xử lý lỗi POS: thiếu tồn, KM đổi, thanh toán thất bại

Là **thu ngân**, tôi muốn được hướng dẫn rõ khi thiếu tồn, KM đổi hoặc thanh toán lỗi để xử lý tiếp mà không mất giỏ. *(UC-01 5a, 5b, 4a)*

1. **Given** nhận 409 `INSUFFICIENT_STOCK`, **When** hoàn tất, **Then** tô đỏ dòng thiếu với số khả dụng, giỏ giữ nguyên.
2. **Given** nhận 422 `TOTAL_CHANGED`, **When** hoàn tất, **Then** hiện tổng mới từ `details.quote`, yêu cầu xác nhận lại.

### #80 · [ORD-05] Danh sách đơn có bộ lọc

Là **quản lý chi nhánh**, tôi muốn lọc đơn theo kênh, trạng thái, ngày, khách để tìm đơn nhanh. *(ORD-06)*

1. **Given** chọn trạng thái RESERVED và CONFIRMED, khoảng ngày, **When** lọc, **Then** URL có tham số lọc; tải lại trang giữ bộ lọc; kết quả đúng.

### #83 · Responsive POS và danh sách đơn (≥ 360 px), phím tắt

Là **thu ngân**, tôi muốn dùng POS và danh sách đơn trên màn nhỏ và bằng phím tắt để bán được trên máy quầy và tablet. *(NFR-12)*

1. **Given** màn 360 px, **When** mở POS và danh sách đơn, **Then** không cuộn ngang, nút bấm được.
2. **Given** chỉ dùng bàn phím, **When** bán một đơn, **Then** làm trọn từ quét đến hoàn tất.

## Frontend vận hành

### #88 · Layout khu Operations, menu theo vai trò, chọn chi nhánh

Là **quản lý chi nhánh**, tôi muốn thấy menu đúng vai trò và chọn chi nhánh làm việc để chỉ thao tác dữ liệu chi nhánh mình. *(AUTH-02, BR-11)*

1. **Given** OWNER, **When** mở khu Operations, **Then** chọn được "Tất cả chi nhánh" hoặc từng chi nhánh.
2. **Given** QLCN được gán 1 chi nhánh, **When** mở khu Operations, **Then** chi nhánh cố định, không có dropdown.
3. **Given** THỦ KHO, **When** xem menu, **Then** không có Khuyến mại, Quản trị.

### #89 · Chọn thư viện biểu đồ, biểu đồ thử với mock

Là **lập trình viên frontend**, tôi muốn chọn được thư viện biểu đồ phù hợp để dashboard làm đúng hạn. *(DASH-01)*

1. **Given** dữ liệu mock doanh thu 30 ngày, **When** vẽ biểu đồ, **Then** hiện đúng, có tooltip số VND; ghi lý do chọn thư viện trong PR.

### #91 · Màn tồn kho: thực, giữ chỗ, khả dụng

Là **thủ kho**, tôi muốn xem tồn thực, giữ chỗ, khả dụng và trạng thái cảnh báo để biết món nào cần nhập. *(INV-01)*

1. **Given** màn tồn kho, **When** lọc "Hết hàng", **Then** chỉ hiện dòng khả dụng 0, màu đỏ.

### #92 · Màn phiếu nhập: tạo, danh sách, duyệt

Là **thủ kho**, tôi muốn lập phiếu nhập và gửi QLCN duyệt để nhập hàng có chứng từ. *(INV-02, BR-13)*

1. **Given** thủ kho lập phiếu, **When** lưu, **Then** phiếu DRAFT trong danh sách.
2. **Given** người lập mở phiếu của mình, **When** xem, **Then** không có nút Duyệt.
3. **Given** QLCN duyệt, **When** bấm Duyệt, **Then** phiếu APPROVED, tồn trên màn tồn kho tăng.

### #93 · Màn phiếu xuất hủy

Là **thủ kho**, tôi muốn lập phiếu xuất hủy chọn lý do để ghi nhận hao hụt. *(INV-03)*

1. **Given** không chọn lý do, **When** lưu phiếu, **Then** báo lỗi tại trường lý do.
2. **Given** số lượng vượt khả dụng, **When** duyệt, **Then** hiện số tối đa được hủy từ lỗi 409.

### #94 · Màn sổ giao dịch kho

Là **quản lý chi nhánh**, tôi muốn xem sổ giao dịch kho và mở chứng từ gốc để đối chiếu. *(INV-06)*

1. **Given** dòng sổ kho có `refCode`, **When** bấm vào, **Then** mở đơn hoặc phiếu gốc.

### #95 · Kiểm tra dữ liệu form phiếu kho

Là **thủ kho**, tôi muốn được báo lỗi ngay trên form phiếu kho để không gửi phiếu sai. *(INV-02..04)*

1. **Given** phiếu nhập thiếu giá nhập, chuyển kho cùng CN nguồn và đích, trùng sản phẩm, **When** nhập, **Then** báo lỗi cạnh trường, không gửi.

### #96 · Màn chuyển kho: lập, duyệt, nhận, chênh lệch

Là **thủ kho**, tôi muốn lập, duyệt xuất, nhận phiếu chuyển kho và thấy chênh lệch để biết hàng thiếu ở đâu. *(INV-04, UC-07)*

1. **Given** phiếu IN_TRANSIT, **When** thủ kho CN đích nhập số nhận thiếu, **Then** phiếu RECEIVED, hiện chênh lệch âm màu cam.

### #101 · Responsive dashboard và màn kho (≥ 360 px)

Là **quản lý chi nhánh**, tôi muốn xem dashboard và kho trên màn nhỏ để kiểm tra khi đi lại trong kho. *(NFR-12)*

1. **Given** màn 360 px, **When** mở dashboard và tồn kho, **Then** không cuộn ngang; bảng chuyển dạng thẻ.

## Vận hành

### #129 · Log JSON có correlation ID qua HTTP và RabbitMQ

Là **lập trình viên**, tôi muốn log JSON có correlation ID xuyên HTTP và RabbitMQ để tra lỗi từ `traceId`. *(NFR-08)*

1. **Given** request lỗi có `traceId` X, **When** tìm trong log, **Then** thấy mọi dòng log của request đó, kể cả consumer RabbitMQ.

### #131 · Thêm forecast service vào compose

Là **lập trình viên**, tôi muốn chạy forecast service bằng docker compose để cả hệ thống lên bằng một lệnh. *(SRS 2.4)*

1. **Given** `docker compose up`, **When** sau khi khởi động, **Then** forecast service healthy, kết nối được PostgreSQL bằng tài khoản chỉ đọc.

### #135 · Môi trường staging tự triển khai từ develop

Là **nhóm**, tôi muốn có staging tự triển khai từ develop để test trên môi trường gần thật. *(SRS 2.5)*

1. **Given** merge vào develop, **When** CI chạy xong, **Then** staging cập nhật bản mới, health check xanh.

### #136 · Script backup/restore pg_dump, đã thử khôi phục

Là **quản trị**, tôi muốn sao lưu và khôi phục CSDL để không mất dữ liệu. *(SRS 7.4)*

1. **Given** DB có dữ liệu, **When** chạy script backup rồi restore vào DB trống, **Then** số bản ghi các bảng chính khớp.

### #140 · Quét lỗ hổng phụ thuộc, image có tag phiên bản

Là **nhóm**, tôi muốn image không có lỗ hổng nghiêm trọng và có tag phiên bản để triển khai an toàn. *(NFR)*

1. **Given** CI build image, **When** quét phụ thuộc, **Then** không lỗ hổng mức Critical; image gắn tag theo phiên bản.

### #142 · Môi trường demo sạch, đo thời gian khởi động

Là **người demo**, tôi muốn có môi trường demo sạch khởi động nhanh để demo không sự cố. *(NFR)*

1. **Given** máy demo mới, **When** `docker compose up` + seed, **Then** toàn hệ thống sẵn sàng; ghi lại thời gian khởi động.

## Kiểm thử

### #149 · Bộ Postman luồng POS chạy bằng Newman trong CI

Là **QA**, tôi muốn bộ Postman luồng POS chạy trong CI để phát hiện hồi quy API. *(ORD-01)*

1. **Given** PR mới, **When** CI chạy Newman, **Then** luồng đăng nhập → quote → pos → hóa đơn pass; fail thì CI đỏ.

### #150 · Script k6: 100 yêu cầu song song (AC-02)

Là **hội đồng**, tôi muốn thấy bằng chứng không bán vượt tồn để chấm AC-02. *(AC-02)*

1. **Given** sản phẩm còn 5, **When** k6 gửi 100 yêu cầu song song, **Then** đúng 5 thành công; báo cáo k6 đính kèm.

### #151 · Báo cáo lỗi tuần 2 trên board

Là **Lead**, tôi muốn thấy lỗi tuần 2 trên board để ưu tiên sửa. *(Quy trình)*

1. **Given** kết thúc tuần 2, **When** QA tổng hợp, **Then** mỗi lỗi là issue nhãn `bug`, có bước tái hiện, mức độ, người nhận.

### #152 · Test phân quyền chéo chi nhánh (AC-04)

Là **hội đồng**, tôi muốn thấy phân quyền chéo chi nhánh đúng để chấm AC-04. *(AC-04, EX-11)*

1. **Given** mỗi vai trò × chi nhánh, **When** gọi API của chi nhánh khác, **Then** đều 403; ADMIN, OWNER được phép.

### #153 · Test khuyến mại và đơn hết hạn (EX-05, EX-06)

Là **QA**, tôi muốn test khuyến mại và đơn hết hạn để bắt lỗi biên. *(EX-05, EX-06, EX-10)*

1. **Given** các kịch bản EX-05, EX-06, EX-10, **When** chạy test, **Then** đúng kết quả mong đợi ghi trong BA mục 11.

### #156 · E2E Playwright: luồng POS và đơn online

Là **QA**, tôi muốn có E2E Playwright cho POS và đơn online để chạy lại luồng chính trước mỗi lần demo. *(ORD-01, ORD-02)*

1. **Given** hệ thống chạy đủ, **When** chạy Playwright, **Then** luồng bán POS và đơn online từ kênh đến hoàn tất pass.

### #157 · Test đa kênh: gửi trùng, kênh lỗi (AC-03)

Là **hội đồng**, tôi muốn thấy đồng bộ đa kênh đúng để chấm AC-03. *(AC-03)*

1. **Given** kênh gửi đơn trùng, kênh lỗi tạm thời, **When** chạy test, **Then** không đơn trùng; cập nhật được gửi lại đủ sau khi kênh hoạt động.

### #158 · Bộ negative test và hồi quy sau feature freeze

Là **QA**, tôi muốn bộ negative test và hồi quy sau feature freeze để không lỗi cũ quay lại. *(NFR)*

1. **Given** sau feature freeze, **When** chạy bộ hồi quy, **Then** mọi lỗi đã sửa có test; tất cả pass.

### #159 · Test tải k6 50 người dùng, đo p95

Là **chủ chuỗi**, tôi muốn biết hệ thống chịu tải 50 người dùng để yên tâm vận hành. *(NFR-01)*

1. **Given** 50 người dùng ảo trong 5 phút, **When** k6 chạy luồng POS, **Then** p95 thời gian phản hồi đạt NFR-01, lỗi < 1%.

## Thiết kế giao diện

### #170 · Figma: layout khu Operations (menu theo vai trò, chọn chi nhánh)

Là **quản lý chi nhánh**, tôi muốn có thiết kế Figma layout khu Operations được duyệt để lập trình viên code đúng một lần. *(SRS 8.1, BR-11)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

### #171 · Figma: màn đăng nhập và layout khu POS

Là **thu ngân**, tôi muốn có thiết kế Figma màn đăng nhập và layout khu POS được duyệt để lập trình viên code đúng một lần. *(AUTH-01, SRS 8.1)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

### #172 · Figma: màn sản phẩm và khách hàng

Là **nhân viên**, tôi muốn có thiết kế Figma màn sản phẩm và khách hàng được duyệt để lập trình viên code đúng một lần. *(PRD-01, CUS-01)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

### #173 · Figma: màn tồn kho và sổ giao dịch kho

Là **thủ kho**, tôi muốn có thiết kế Figma màn tồn kho và sổ giao dịch kho được duyệt để lập trình viên code đúng một lần. *(INV-01, INV-06)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

### #174 · Figma: màn bán hàng POS đầy đủ trạng thái

Là **thu ngân**, tôi muốn có thiết kế Figma màn bán hàng POS đầy đủ trạng thái được duyệt để lập trình viên code đúng một lần. *(UC-01)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

### #175 · Figma: phiếu nhập và phiếu xuất hủy

Là **thủ kho**, tôi muốn có thiết kế Figma phiếu nhập và phiếu xuất hủy được duyệt để lập trình viên code đúng một lần. *(INV-02, INV-03)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

### #176 · Figma: danh sách đơn và chi tiết đơn online

Là **quản lý chi nhánh**, tôi muốn có thiết kế Figma danh sách đơn và chi tiết đơn online được duyệt để lập trình viên code đúng một lần. *(ORD-03, ORD-06)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

### #177 · Figma: chuyển kho (lập, duyệt xuất, nhận, chênh lệch)

Là **thủ kho**, tôi muốn có thiết kế Figma chuyển kho được duyệt để lập trình viên code đúng một lần. *(INV-04)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

### #178 · Figma: dashboard, cảnh báo tồn thấp, top sản phẩm

Là **chủ chuỗi**, tôi muốn có thiết kế Figma dashboard, cảnh báo tồn thấp, top sản phẩm được duyệt để lập trình viên code đúng một lần. *(DASH-01..03)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

### #179 · Figma: màn trả hàng

Là **thu ngân**, tôi muốn có thiết kế Figma màn trả hàng được duyệt để lập trình viên code đúng một lần. *(ORD-05)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

### #180 · Figma: màn dự báo và gợi ý nhập

Là **chủ chuỗi**, tôi muốn có thiết kế Figma màn dự báo và gợi ý nhập được duyệt để lập trình viên code đúng một lần. *(AI-05, AI-06)*

1. **Given** frame 1440 px trong file Figma chung, **When** Lead xem, **Then** đủ 4 trạng thái bình thường, đang tải, rỗng, lỗi; dùng component #169.
2. **Given** tên trường, nút, trạng thái, **When** đối chiếu SRS và `openapi.yaml`, **Then** khớp tên và giá trị (mã chi nhánh GM-00x, trạng thái đúng enum).
3. **Given** Lead comment "Đã duyệt", **When** đóng issue, **Then** issue code tương ứng được bắt đầu.

## Issue Must không cần user story

Việc quản lý, báo cáo, review, sửa lỗi chung: tiêu chí hoàn thành nằm sẵn trong mô tả issue.

#5, #6, #8, #10, #11, #12, #13, #14, #15, #16, #17, #18, #19, #20, #21, #22, #23, #24, #25, #44, #45, #46, #65, #66, #67, #84, #85, #86, #102, #103, #104, #121, #141, #143, #160, #161
