# AUTH-03 — kiểm tra audit log #154, ngày 14/11/2026

## Yêu cầu và baseline

Issue #154 giao **Huynh-Phuc-Dat — SV08 QA/Security**: kiểm tra đổi giá, duyệt/điều chỉnh kho, hủy đơn, đăng nhập thất bại và truy cập sai chi nhánh có audit chứa người thao tác, hành động, đối tượng, chi tiết, thời gian; không sửa/xóa bản ghi được.

- Trước nhiệm vụ đã kiểm tra tracked/untracked và commit local, chuyển `develop`, pull `--ff-only origin develop` an toàn, không stash/reset hoặc đưa thay đổi cũ vào nhiệm vụ mới.
- GitHub/local develop: `c25ae39477f8b2de72b2f022bbfda03c00d28073`; nhánh thực hiện `feature/154-audit-log-qa`.
- Đọc issue #154/comments, phân công #63, #42, quyết định Q-27 trên #310; quy trình README/CONTRIBUTING/CODEOWNERS/template; SRS, BA, user stories, API README/OpenAPI, test plan, security checklist, QA questions và tài liệu backend liên quan tại baseline này.
- AUTH-03 kiểm tra audit cho các hành động thành công; không tự yêu cầu audit `FORBIDDEN` sai vai trò: Lead đã tách Q-31 khỏi phạm vi #310.
- Chỉ thêm bộ QA/báo cáo; không sửa backend, schema, migration, contract hoặc code của thành viên khác.

## Môi trường và bằng chứng

- Docker riêng: `greenmart-qa154-20261114`; API `http://127.0.0.1:18164/api/v1`.
- PostgreSQL thật, Redis, RabbitMQ và backend build từ source trên; seed demo chính thức vào DB mới, không reset/reseed môi trường có sẵn.
- Image `greenmart-backend:qa154-c25ae39`; immutable ID `sha256:17e1192d27a107c1ccad0e25ef97be416822d705dca5dc2f39e0cd91e02487f7`; OCI revision được đối chiếu trước mọi thao tác API/fixture.
- Bằng chứng chính thức: [issue-154-2026-11-14-recheck.json](results/issue-154-2026-11-14-recheck.json); fixture `QA154-96335868`.
- **38 kiểm tra kỹ thuật: 35 PASS, 1 FAIL, 2 BLOCKED; không gián đoạn.** Số này gồm prerequisite, control và logout, không phải 38 yêu cầu nghiệp vụ độc lập.

## Kết quả theo yêu cầu

| Nhóm | Đã kiểm tra thực tế | Kết quả | Phụ trách/phụ thuộc |
|---|---|---|---|
| Đổi giá | OWNER sửa sản phẩm 1002 từ 29.000 lên 31.000; đúng một audit CHANGE_PRICE, actor 2, entity product/1002, oldPrice/newPrice và thời gian | PASS | Catalog — SV02 |
| Control đổi giá | Lưu giá không đổi không tạo CHANGE_PRICE; CASHIER sửa giá bị 403, giá không đổi và không có audit báo thành công giả | PASS | Catalog/Identity |
| Duyệt kho | STOREKEEPER lập IMPORT, manager duyệt phiếu 5; tồn thực 10, đúng một audit APPROVE_STOCK_DOCUMENT, actor 3, đối tượng và detail IMPORT/APPROVED | PASS | SV03 @Dat9161 |
| Control duyệt | Duyệt lần hai trả 409, không thêm audit duyệt thành công | PASS | SV03 |
| Đăng nhập sai | Thu ngân sai mật khẩu nhận 401; audit LOGIN_FAILED vẫn được commit, actor/entity user 7, remainingAttempts=4 | PASS | SV03 Identity |
| Truy cập kho CN2 | CASHIER CN1 nhận 403 BRANCH_FORBIDDEN; audit đủ actor, branch/2, branchId=2, method, path không query, traceId khớp response | PASS | SV03; quyết định Q-27 |
| Lookup sản phẩm CN2 | HTTP 403 đúng nhưng audit thiếu entity_id và detail.branchId | **FAIL** | SV02 @t-boai, đã có #358 |
| Điều chỉnh kho | POST ADJUST trả 400 VALIDATION_ERROR; không tạo được phiếu để duyệt và kiểm chứng audit | **BLOCKED** | #63 OPEN, SV03 @Dat9161, hạn 18/11 tuần 4 |
| Hủy đơn online | Đơn COD thật 10202 đã CONFIRMED; manager gọi cancel đúng hợp đồng nhận 404 NOT_FOUND | **BLOCKED** | #42/#320 OPEN, SV02 @t-boai |
| Không sửa audit | UPDATE bản ghi audit 125 bị chặn SQLSTATE 55000, hash nội dung không đổi | PASS | Trigger migration V13 |
| Không xóa audit | DELETE cùng bản ghi bị chặn SQLSTATE 55000, hash nội dung không đổi | PASS | Trigger migration V13 |

Năm phiên đăng nhập (bốn vai trò và phiên thu ngân phục hồi sau control sai mật khẩu) đều logout HTTP 204. Lần đăng nhập thành công phục hồi bộ đếm sai qua API, không SQL-reset tài khoản.

### Lỗi đã tái hiện — trùng #358

1. CASHIER `thungan.gm001`, được gán CN1, đăng nhập trên QA154.
2. Gọi `GET /api/v1/products/lookup?branchId=2&code=QA154-96335868` với Bearer hợp lệ.
3. Thực tế HTTP 403 `BRANCH_FORBIDDEN`, trace `944204b7-c267-4ba7-8907-1682d42daca8`.
4. Đối chiếu actor/action/trace và audit ID mới, có bản ghi 129: user 7, entity branch nhưng `entity_id=NULL`; detail có method/path/traceId, thiếu `branchId`.
5. Theo Lead Q-27, phải có `entity_id="2"` và detail `branchId=2`. Thiếu dữ liệu khiến không truy ra chi nhánh bị từ chối chỉ từ audit. Phạm vi từ chối truy cập vẫn hoạt động, không báo thành lỗ hổng vượt quyền.

Bug [#358](https://github.com/NguyenDokhai2005/GreenMart/issues/358) đã OPEN và giao @t-boai; không tạo trùng hoặc tự đăng bình luận. Đối chứng cùng tài khoản gọi inventory CN2 có đủ metadata (audit 128, trace `6d2a5463-1669-4150-9c50-fb2e2dde05fb`). Đọc code cho thấy lookup ném AppException không mang deniedBranchId; đây là nhận định từ đọc code bổ trợ bằng chứng runtime, không phải thay đổi QA đã sửa backend.

## Giới hạn và cách đánh giá

- Không coi thiếu audit sau một chức năng chưa triển khai là lỗi audit đã được tái hiện. ADJUST thuộc #63 còn OPEN; hủy đơn thuộc #42/#320 còn thiếu route. Giữ cả hai BLOCKED, không chèn audit hoặc giả trạng thái đơn/phiếu trong DB để nhận PASS.
- Kiểm tra duyệt kho thực tế ở IMPORT; không tuyên bố đã bao phủ mọi loại chứng từ, mọi role, ACCOUNT_LOCKED, user không tồn tại hoặc audit mọi endpoint của toàn hệ thống.
- SQL fixture chỉ thêm mapping cho sản phẩm QA và thay WEB signing key bằng key ngẫu nhiên riêng của QA154 để tạo đơn COD thật; không đọc/in key cũ. Không có SQL INSERT audit fixture. Có giữ chỗ của các đơn COD chờ backend xử lý; giữ dữ liệu làm bằng chứng, không tự giải phóng/đóng đơn.
- UPDATE/DELETE chạy trong transaction luôn rollback, ON_ERROR_STOP đóng kết nối và rollback khi bị chặn. Nếu trigger không chặn, statement cũng không được commit. Không tắt trigger, thử TRUNCATE/DDL, thu hồi quyền hoặc đánh giá khả năng DBA đặc quyền phá bảo vệ. PASS ở đây là chống UPDATE/DELETE DML tại schema runtime đã kiểm tra.
- Giá trị audit được đối chiếu actor/action/entity/entity_id và chi tiết kỳ vọng, audit ID lớn hơn mốc trước thao tác; branch denial đối chiếu cả traceId. Thời gian kiểm tra hiện diện và trong cửa sổ 5 phút của DB, chưa kiểm tra clock skew toàn hệ thống.
- Chỉ xuất các trường chi tiết nằm trong danh sách cho phép. Kiểm tra audit các ca có chứa mật khẩu demo/access/refresh token đang dùng hay không; không phát hiện các giá trị đó. Không khẳng định đã quét toàn bộ lịch sử audit hoặc mọi secret trong hệ thống.
- Lượt đầu script lookup truyền nhầm `q` thay vì `code`; bốn assertion phát sinh từ request 400 đó không dùng làm báo lỗi backend. Đã sửa theo OpenAPI, chạy lại toàn bộ checker với fixture mới và file kết quả mới. Bằng chứng lượt đầu giữ riêng local; báo cáo này chỉ dùng recheck hợp lệ. Không thay expected để che lỗi.

## Tái lập và kiểm tra bộ QA

Các file:

- `tests/api/issue154_audit.py`: thực thi API/DB và tạo JSON đã loại credential.
- `tests/api/run_issue154_qa.py`: build/dựng stack mới và nạp seed một lần, sinh credential trong process, không đọc `.env` và dùng Compose `config --quiet`.
- `tests/api/test_issue154_safety.py`: từ chối project dùng chung, không ghi đè bằng chứng, dừng trước API/SQL khi image hoặc OCI revision sai.

Chạy mới: cung cấp `GREENMART_DEMO_PASSWORD` trong process theo tài liệu demo, chọn `QA154_PROJECT`, `QA154_PORT`, `QA154_REPORT` chưa dùng rồi chạy `python tests/api/run_issue154_qa.py`. Cần Docker và image Python seed được ghi trong helper (`QA154_SEED_IMAGE` có thể thay thế). Helper từ chối project/volume đã tồn tại. Lượt recheck chỉ chạy checker trên stack QA154 đã xác minh, không dựng lại/seed lại; mọi sản phẩm/đơn fixture dùng prefix mới.

- `python -m unittest discover -s tests/api -p test_issue154_safety.py -v`: **4/4 PASS**.
- `python -m py_compile tests/api/issue154_audit.py tests/api/run_issue154_qa.py tests/api/test_issue154_safety.py`: PASS.
- `git diff --check`: PASS.
- Exit 1 của runner phản ánh FAIL/BLOCKED thực tế, không phải lỗi Docker. Build backend skip Maven tests; không nhận suite test backend PASS từ bước build này.

## Trạng thái bàn giao

**#154 chưa đủ điều kiện Done**: chờ sửa #358, triển khai #63 và #42/#320 rồi kiểm lại audit trên các luồng thật. Người dùng đã cho phép commit/push/tạo PR cho phần QA này. PR ghi `Refs #154`, không tự đóng nhiệm vụ khi còn FAIL/BLOCKED; CI remote theo trạng thái PR. Đã xác minh lại #358 OPEN, giao @t-boai, đúng cùng lỗi nên không tạo issue trùng. Không đăng comment riêng, thay assignee hoặc đóng issue. Nhật ký công việc cá nhân được cập nhật local và không xuất bản. Lead theo dõi việc nghiệm thu các phụ thuộc, QA không tự sửa phạm vi backend của SV02/SV03.
