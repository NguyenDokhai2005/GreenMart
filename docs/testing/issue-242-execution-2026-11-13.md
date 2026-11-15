# #242 — Kiểm thử AUTH/Identity/RBAC trên API thật — 13/11/2026

QA: Huynh-Phuc-Dat (SV08). **Đã thực thi bộ kiểm thử; chưa nghiệm thu toàn bộ #242.**

## Kết quả

| Nhóm | PASS | FAIL | BLOCKED |
|---|---:|---:|---:|
| AUTH — 26 mã | 26 | 0 | 0 |
| RBAC — 23 mã | 16 | 0 | 7 |
| Identity — 16 mã | 13 | 0 | 3 |
| Tổng mã gốc | **55** | **0** | **10** |
| Probe bổ sung audit/lookup | 0 | **2** | 0 |
| Demo accounts #148 | **26** | 0 | 0 |

Newman core: **14 requests / 26 assertions PASS**, cả lượt chính và recheck. Không cộng các assertion Newman/demo/probe thành 65 TC gốc. Hai FAIL bổ sung là hai lỗi hiện có #309/#310; 10 BLOCKED không được tính PASS.

## Nguồn và baseline

- Đọc issue [#242](https://github.com/NguyenDokhai2005/GreenMart/issues/242), yêu cầu/assignee và bình luận, repo rules, CODEOWNERS, template bug, SRS/BA/user stories, OpenAPI, README API/backend, test plan/checklist, ba file test case và câu hỏi QA. #241 đã merge; không dùng test SV03 để thay cho bằng chứng QA riêng.
- Pull ff-only develop từ d4cb341 → **d43b75a546c172fbece49b3ea7ef2d9ceae52010**. Nhánh QA **feature/242-auth-rbac-api-qa**. Không sửa backend/frontend/contract/seed/migration.
- Cả hai backend build từ source commit trên, image **greenmart-backend:qa242-d43b75a**, OCI label full SHA; image ID và timestamp nằm trong cases.json. Build -DskipTests chỉ tạo image, không nhận là backend unit tests PASS.
- Lượt đầu **greenmart-qa242-20261113**, backend cổng 18158; recheck **greenmart-qa242-recheck-20261113**, backend cổng 18159. Mỗi stack có PostgreSQL/Redis/RabbitMQ, network/volumes riêng; seed SV06 generator nguyên bản nạp một lần vào DB mới.
- Giữ nguyên các stack trước, không reset/xóa volume/đọc .env hoặc xuất secrets. Mật khẩu demo giữ nguyên; tài khoản tạm đổi mật khẩu đã vô hiệu hóa; các phiên thử đã logout, cleanupWarnings rỗng.
- Fixture riêng mỗi DB: một CASHIER tạm + gán GM001, một sản phẩm OWNER tạo, một IMPORT draft chưa duyệt và một branch inactive. Chưa phê duyệt phiếu kho hoặc thay tồn để thử quyền.

## Quyết định dùng cho expected

- Q-29: #241 chốt Redis chỉ giữ hash SHA-256, expiry phiên và TTL 7 ngày. TC-AUTH01-15 đưa score một refresh của tài khoản tạm về quá khứ rồi gọi API thật; không chỉ kiểm tra code và không chờ 7 ngày.
- TC-AUTH01-22/31: dùng JWT đã hết hạn, ký HS256 đúng bằng key ngẫu nhiên chỉ dành QA trong process startup. Các JWT sai chữ ký/alg:none chạy riêng, không dùng chúng thay ca hết hạn.
- Q-30: ErrorResponse + traceId theo #241 được kiểm tra thực tế ở 401/validation/security errors.
- [Quyết định Q-27 tại #310](https://github.com/NguyenDokhai2005/GreenMart/issues/310#issuecomment-6854060178): action BRANCH_FORBIDDEN, actor từ token, entity branch, entity_id chuỗi branch ID, detail branchId/method/path/traceId; transaction riêng; không lưu query/body.
- **Q-31 chưa được Lead chốt.** Bình luận #310 chỉ tách audit cho 403 FORBIDDEN do sai vai trò ra khỏi phạm vi #310; không quyết định rằng không cần audit. TC-AUTH02-41 giữ **BLOCKED — chờ chốt Q-31**, dù quan sát runtime là 403 FORBIDDEN và 0 dòng audit mới.
- Theo review CHANGES_REQUESTED của Lead trên PR #354 ngày 14/11, tổng đánh giá đúng là **55 PASS / 0 FAIL / 10 BLOCKED**. Lượt đầu đã có cùng tổng này; JSON recheck lịch sử từng phân loại sai TC41 thành PASS được giữ nguyên để truy vết, không dùng tổng 56/9 làm kết luận nghiệm thu.
- [Assessment đã sửa theo review](results/issue-242-review-assessment-2026-11-14.json) chứa đủ 65 mã, hash SHA-256 artifact gốc và lý do điều chỉnh. Đây là sửa phân loại theo review, **không phải chạy API mới**; mọi quan sát HTTP/DB cũ được giữ nguyên.

## Các mục bị chặn và lỗi có issue

- **#333 / SV02 @t-boai:** GET /orders trả 404: TC-AUTH02-01/06/07/20/22/40 BLOCKED. Không đổi sang API hóa đơn hoặc inventory để nhận các mã gốc PASS. TC40 còn cần metadata audit theo Q-27 khi endpoint sẵn sàng.
- **#335 / chưa có assignee, Lead chốt:** GET /branches trả 404: ID-070/071/072 BLOCKED, kể cả đã chuẩn bị branch inactive riêng cho bộ lọc.
- **#309 / SV02 @t-boai:** CASHIER GM001 lookup sản phẩm branch2 nhận 200 thay vì 403; probe bổ sung FAIL. Không mở issue trùng.
- **#310 / SV03 @Dat9161:** inventory branch2 nhận 403 đúng nhưng không có audit mới. Probe actor/metadata FAIL, traceId trong JSON. Đã tái kiểm tra predicate đủ actor/action/entity/detail theo quyết định Lead trong supplemental-recheck.json. Không mở issue trùng.
- Các lỗi được liệt kê theo backend commit đã chạy ngày 13/11; không suy ra trạng thái lỗi trên develop mới hơn. Lead theo dõi **@NguyenDokhai2005**.

## Bảng kết quả từng mã (đánh giá đã sửa theo review 14/11)

| Mã | Nội dung | Kết quả | Bằng chứng chính |
|---|---|---|---|
| ID-050 | Password lưu dạng BCrypt | PASS | predicate/API-DB; xem JSON |
| ID-051 | Không có plain text trong DB | PASS | Schema và predicate BCrypt; không xuất raw values |
| ID-052 | Cùng password → hash khác nhau | PASS | predicate/API-DB; xem JSON |
| ID-053 | API không trả password hash | PASS | predicate/API-DB; xem JSON |
| ID-060 | Đổi thành công | PASS | HTTP 204 |
| ID-061 | Đăng nhập lại với password mới | PASS | HTTP 200 |
| ID-062 | Password cũ không còn dùng được | PASS | HTTP 401 |
| ID-063 | currentPassword sai | PASS | HTTP 422 |
| ID-064 | Password mới < 8 ký tự | PASS | HTTP 400 |
| ID-065 | Password mới chỉ có chữ, không có số | PASS | HTTP 400 |
| ID-066 | Password mới chỉ có số, không có chữ | PASS | HTTP 400 |
| ID-070 | Seed đủ 3 chi nhánh | BLOCKED | HTTP 404; #335 |
| ID-071 | CASHIER chỉ thấy chi nhánh mình | BLOCKED | HTTP 404; #335 |
| ID-072 | Lọc `active=false` | BLOCKED | HTTP 404; #335 |
| ID-080 | Ghi log đăng nhập thất bại | PASS | predicate/API-DB; xem JSON |
| ID-081 | Ghi log khi tài khoản bị khóa | PASS | predicate/API-DB; xem JSON |
| TC-AUTH01-01 | Login thành công | PASS | HTTP 200 |
| TC-AUTH01-02 | Response không chứa password | PASS | predicate/API-DB; xem JSON |
| TC-AUTH01-03 | Sai password – `remainingAttempts` = 4 | PASS | HTTP 401 |
| TC-AUTH01-04 | Khóa sau 5 lần sai | PASS | HTTP 401 |
| TC-AUTH01-05 | Mở khóa sau 15 phút | PASS | HTTP 200 |
| TC-AUTH01-06 | Trong thời gian khóa – đúng password vẫn bị từ chối | PASS | HTTP 401 |
| TC-AUTH01-07 | Username không tồn tại – cùng thông báo với sai password | PASS | HTTP 401 |
| TC-AUTH01-08 | `is_active=false` | PASS | HTTP 401 |
| TC-AUTH01-09 | Thiếu field `password` | PASS | HTTP 400 |
| TC-AUTH01-10 | Body không phải JSON | PASS | HTTP 400 |
| TC-AUTH01-11 | Refresh thành công | PASS | HTTP 200 |
| TC-AUTH01-12 | Refresh token cũ bị thu hồi sau khi xoay | PASS | HTTP 401 |
| TC-AUTH01-13 | Refresh token sai / giả mạo | PASS | HTTP 401 |
| TC-AUTH01-14 | Thiếu field `refreshToken` | PASS | HTTP 400 |
| TC-AUTH01-15 | Refresh token hết hạn 7 ngày | PASS | HTTP 401; mô phỏng expiry trên QA |
| TC-AUTH01-20 | Logout thành công | PASS | HTTP 204 |
| TC-AUTH01-21 | Refresh token sau logout không dùng lại được | PASS | HTTP 401 |
| TC-AUTH01-22 | Logout khi access token hết hạn | PASS | HTTP 401; mô phỏng expiry trên QA |
| TC-AUTH01-30 | Không có header `Authorization` | PASS | HTTP 401 |
| TC-AUTH01-31 | Access token hết hạn | PASS | HTTP 401; mô phỏng expiry trên QA |
| TC-AUTH01-32 | JWT ký bằng secret sai | PASS | HTTP 401 |
| TC-AUTH01-33 | JWT `alg:none` – tấn công bỏ qua chữ ký | PASS | HTTP 401 |
| TC-AUTH01-34 | JWT `alg:none` gửi làm refreshToken | PASS | HTTP 401 |
| TC-AUTH01-35 | Response lỗi không lộ stack trace, có `traceId` | PASS | HTTP 401 |
| TC-AUTH01-40 | CASHIER chỉ thấy chi nhánh được gán | PASS | HTTP 200 |
| TC-AUTH01-41 | OWNER thấy mọi chi nhánh | PASS | HTTP 200 |
| TC-AUTH02-01 | CASHIER CN1 không xem đơn CN2 qua query param | BLOCKED | HTTP 404; #333 |
| TC-AUTH02-02 | CASHIER CN1 không tạo đơn tại CN2 qua body | PASS | HTTP 403 |
| TC-AUTH02-03 | CASHIER không xem tồn CN khác qua path param | PASS | HTTP 403 |
| TC-AUTH02-04 | BRANCH_MANAGER CN1 không xem phiếu kho CN2 | PASS | HTTP 403 |
| TC-AUTH02-05 | STOREKEEPER CN1 không xem tồn CN2 | PASS | HTTP 403 |
| TC-AUTH02-06 | CASHIER CN1 gọi đúng CN1 → được phép | BLOCKED | HTTP 404; #333 |
| TC-AUTH02-07 | ADMIN xem mọi chi nhánh không bị chặn | BLOCKED | HTTP 404; #333 |
| TC-AUTH02-08 | OWNER xem mọi chi nhánh không bị chặn | PASS | HTTP 200 |
| TC-AUTH02-10 | CASHIER không tạo sản phẩm (`x-roles: [OWNER]`) | PASS | HTTP 403 |
| TC-AUTH02-11 | CASHIER không sửa sản phẩm (`x-roles: [OWNER]`) | PASS | HTTP 403 |
| TC-AUTH02-12 | CASHIER không xem sổ giao dịch kho (`x-roles: [OWNER, BRANCH_MANAGER, STOREKEEPER]`) | PASS | HTTP 403 |
| TC-AUTH02-13 | STOREKEEPER không duyệt phiếu kho (`x-roles: [BRANCH_MANAGER]`) | PASS | HTTP 403 |
| TC-AUTH02-14 | OWNER tạo sản phẩm thành công (`x-roles: [OWNER]`) | PASS | HTTP 201 |
| TC-AUTH02-15 | BRANCH_MANAGER xem sổ kho thành công (`x-roles: [OWNER, BRANCH_MANAGER, STOREKEEPER]`) | PASS | HTTP 200 |
| TC-AUTH02-20 | OWNER không truyền branchId → thấy đơn mọi chi nhánh | BLOCKED | HTTP 404; #333 |
| TC-AUTH02-21 | ADMIN không truyền branchId → thấy tồn mọi chi nhánh | PASS | HTTP 200 |
| TC-AUTH02-22 | CASHIER không truyền branchId → chỉ thấy CN mình | BLOCKED | HTTP 404; #333 |
| TC-AUTH02-30 | CASHIER không thấy costPrice | PASS | HTTP 200 |
| TC-AUTH02-31 | STOREKEEPER không thấy costPrice | PASS | HTTP 200 |
| TC-AUTH02-32 | BRANCH_MANAGER thấy costPrice | PASS | HTTP 200 |
| TC-AUTH02-33 | OWNER thấy costPrice | PASS | HTTP 200 |
| TC-AUTH02-40 | BRANCH_FORBIDDEN ghi audit_log | BLOCKED | HTTP 404; #333 |
| TC-AUTH02-41 | FORBIDDEN (sai role) – kiểm tra audit_log | BLOCKED | Chờ Lead chốt Q-31; quan sát HTTP 403 FORBIDDEN, 0 audit mới |

## Artifact và cách chạy

- Lượt đầu: `docs/testing/results/issue-242-2026-11-13/cases.json`, `demo-accounts.json`, `auth242/summary.json`.
- Recheck: `docs/testing/results/issue-242-2026-11-13-recheck/cases.json`, `demo-accounts.json`, `auth242/summary.json`, `supplemental-recheck.json`.
- Hướng dẫn: `tests/api/auth242/README.md`. Script tạo QA từ chối stack/volume tồn tại, không seed lại; report cases.json không bị ghi đè.
- Tích hợp core Newman vào lệnh `npm run pos` hiện được QA POS Newman CI gọi; regression runner nằm trong `npm test`. Không sửa workflow .github hoặc thêm dependency. Job vẫn tên QA POS Newman nhưng thực thi cả POS và AUTH; xem README trong tests/api/pos149.

## Kiểm tra công cụ và giới hạn

- Bản sửa theo review ngày 14/11: regression Q-31/token Compose **5/5 PASS**, runner POS **3/3 PASS**, runner AUTH **3/3 PASS**, seed helper **3/3 PASS**. Đây là kiểm tra công cụ với doubles; không chạy API/Docker mới hoặc thay baseline kết quả ngày 13/11.
- Newman HTTP doubles: success→0, sai quyền→1, mất kết nối→1; đều đi đến cleanup và artifact không chứa token/password. Đây là test runner, không phải bằng chứng backend.
- Runner POS149 regression 3/3; seed helper regression 3/3; compile Python/Node và diff check. Không chạy lại toàn bộ unit backend hoặc gitleaks trong lượt này.
- ID-051 là schema + predicate BCrypt, không xuất toàn bộ giá trị DB; ID-052 so sánh hash boolean của tài khoản demo. TC-AUTH01-05 mô phỏng hết khóa bằng timestamp; không chờ 15 phút thực.
- Không kiểm thử UI, toàn bộ OWASP, rate limit IP 429 hoặc mọi hành vi audit #154 trong phạm vi #242. Không tự sửa lỗi backend để làm xanh.
- Chưa nghiệm thu #242: còn 10 mã gốc BLOCKED và 2 probe FAIL tại baseline đã chạy. Lead đã review PR #354 và yêu cầu chỉnh sửa; CI 7/7 xanh được ghi nhận cho commit review 8ff53d5, không tự áp dụng cho bản sửa mới. PR dùng Refs #242 đến khi đủ điều kiện nghiệm thu.
