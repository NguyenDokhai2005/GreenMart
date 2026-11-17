# #155 — Rate limit đăng nhập và OWASP cơ bản, 14/11/2026

## Phạm vi và nguồn yêu cầu

Huynh-Phuc-Dat (SV08 QA/Security) kiểm tra NFR-06 theo #155: vượt ngưỡng đăng nhập theo IP phải nhận 429; kiểm tra SQL injection, không lộ stack trace và CORS giới hạn nguồn.

Đã kiểm tra công việc local, chuyển `develop`, pull `--ff-only origin develop`, tạo nhánh `feature/155-security-qa`. GitHub/local dùng đối chiếu và build: **`c25ae39477f8b2de72b2f022bbfda03c00d28073`**. Không stash/reset hoặc sửa các untracked có sẵn.

Đã đọc issue #155 và comments (không có quyết định bổ sung); quy trình README/CONTRIBUTING/CODEOWNERS/template, SRS/BA/user stories, API README/OpenAPI, test plan/security checklist/QA questions và backend README cùng baseline; xác minh lại user stories #155, checklist, OpenAPI qua GitHub. Đối chiếu SecurityConfig, AuthServiceImpl và các repository tham số hóa của Product/Customer. Các tài liệu tại cùng SHA đã đọc trong lượt QA trước được dùng tiếp, không nhận rằng có thay đổi nghiệp vụ mới.

**Người dùng xác nhận Lead chưa chốt số lần/cửa sổ giới hạn IP và origin frontend được phép.** Không tự chọn ngưỡng để đánh dấu PASS hoặc kết luận FAIL theo một chính sách chưa có. OpenAPI hiện mô tả khóa tài khoản bằng 401, chưa có response 429 cho login; Lead quản lý việc chốt và cập nhật contract.

## Môi trường và bằng chứng

- QA riêng `greenmart-qa155-20261114`; API `http://127.0.0.1:18165/api/v1`.
- Backend, PostgreSQL thật, Redis, RabbitMQ; seed chính thức nạp một lần vào DB mới. Cả bốn container healthy sau lượt chính đầu tiên; các lượt sau chỉ dùng API/SQL đọc trên stack này, không reset/reseed.
- Backend image `greenmart-backend:qa155-c25ae39`, immutable ID `sha256:de7ef8e93e3cb34b9b8fb058482cd919fc372f756e3734abda8cee5f288a2b6c`. Runner xác minh tag/OCI revision trước mọi request.
- [Lượt recheck chính](results/issue-155-2026-11-14-recheck.json): **28 PASS, 3 BLOCKED**, 31 kiểm tra kỹ thuật, không gián đoạn; fixture `QA155-8372621F`.
- [Lượt riêng phản hồi 500](results/issue-155-2026-11-14-error-recheck.json): **4 PASS**, không gián đoạn.
- Tổng hai artifact bàn giao: **32 PASS, 3 BLOCKED, 0 FAIL trong các assertion bảo mật đã chạy**. Có prerequisite/control/logout và ca trùng mục tiêu giữa hai lượt; không hiểu là 35 yêu cầu độc lập hay toàn hệ thống không có bug.

## Đã kiểm tra thực tế

| Hạng mục | Cách kiểm tra và kết quả | Đánh giá |
|---|---|---|
| SQL injection tìm kiếm | Product/Customer có dữ liệu baseline; q chứa `' OR 1=1 --`, `' OR 1=2 --`, `' UNION SELECT NULL --` đều trả 200, 0 kết quả. Control sản phẩm QA tìm đúng một sản phẩm. Không gửi payload xóa/sửa dữ liệu | PASS trong phạm vi probe |
| SQL injection login | Username giống biểu thức SQL không đăng nhập được, trả 401 và không token | PASS |
| Side effect | Số hàng product/customer/app_user không đổi qua các probe injection; quote lỗi không tạo đơn/ledger | PASS |
| Lỗi đầu vào | JSON login hỏng, ID sai kiểu, thiếu code lookup, số lượng âm: envelope lỗi có code/message/traceId, không stack/SQL internals | PASS |
| Lỗi hệ thống 500 | Quote `items:[null]` tái hiện INTERNAL_SERVER_ERROR, response vẫn không lộ stack trace hoặc thông tin JDBC/PostgreSQL | PASS về che thông tin; bug chức năng vẫn có |
| CORS origin không tin cậy | Origin `https://untrusted.qa.invalid` và `null`: GET /auth/me có Bearer trả 200 nhưng không ACAO; preflight login 403 và không ACAO | PASS về không cấp browser quyền đọc cross-origin |
| CORS frontend hợp lệ | Candidate từ README `http://localhost:3000` cũng không ACAO, preflight 403; allowlist chính thức chưa chốt | BLOCKED nghiệm thu dương tính |
| Header framework | Không thấy X-Powered-By hoặc Server chứa phiên bản trong response đã kiểm tra | PASS phạm vi response |
| Login theo một IP | 60 request tuần tự cùng client, username không tồn tại khác nhau, khoảng **11,91 giây**; **60 HTTP 401 INVALID_CREDENTIALS, 0 HTTP 429** | Quan sát thực tế; BLOCKED ngưỡng/window/cooldown |
| Khóa theo tài khoản | Thu ngân QA GM-002 sai 5 lần: bốn lần INVALID_CREDENTIALS, lần thứ năm ACCOUNT_LOCKED, đều 401; đúng mật khẩu vẫn bị khóa | PASS |
| Thời hạn khóa | DB còn khoảng **899,60 giây** ngay sau khóa; failed_login_count=5 | PASS; chưa chờ thực tế hết 15 phút |
| Kiểm tra thêm log | Phiên bản runner cuối không đọc Docker logs; không kết luận toàn log không chứa secret/correlation đầy đủ | BLOCKED kiểm tra bổ sung |

Khóa tài khoản **không thay thế** rate limit IP: một IP có thể luân phiên username. Burst không giả X-Forwarded-For, không thử vượt proxy, chỉ đi thẳng backend QA local. 60 request không có 429 không chứng minh không có limiter ở mọi ngưỡng hoặc trên gateway/staging khác.

CORS không phải quyền truy cập API cho client ngoài browser: HTTP 200 cho GET có token từ Origin lạ chưa có nghĩa browser được đọc response. Không có ACAO ngăn browser đọc; không tự gọi đây là bypass. Chưa kiểm tra browser thực tế hoặc đường proxy frontend. Vite dùng proxy `/api` nên cần phân biệt same-origin proxy và direct cross-origin backend khi Lead chốt thiết kế.

## Bug đã có và phần đọc code

- `POST /api/v1/orders/quote` với `{"branchId":1,"items":[null]}` vẫn trả **500 INTERNAL_SERVER_ERROR**, thay vì 400 VALIDATION_ERROR. Lượt cuối trace `4212aec5-be87-4fe4-b63b-aba543278c10`; ca độc lập trace `ee26c2e0-b462-4887-8b3d-b9c45e0b6c8a`.
- Đã đọc lại [#316](https://github.com/NguyenDokhai2005/GreenMart/issues/316): OPEN, giao **SV02 @t-boai**, đúng cùng lỗi DTO null item. Không tạo issue trùng hoặc sửa Sales. Che stack trace PASS không có nghĩa bug 500 đã sửa.
- Đọc code: SecurityConfig gọi `.cors(...disable)`; chưa thấy filter/config rate limit login trong nguồn đã tìm. Đây là bằng chứng đọc code và chính sách còn thiếu, không phải kết luận đã test hết reverse proxy hoặc tự giao lỗi implementation cho một thành viên.
- Repository tìm kiếm Product/Customer dùng truy vấn tham số. Kết hợp probe hữu hạn ở trên hỗ trợ đánh giá, chưa chứng minh mọi endpoint không có SQL injection.

## Bộ QA và tái lập

- `tests/api/issue155_security.py`: chỉ target QA155, SQL đọc, probe có giới hạn, xuất assertion/counter/trace an toàn; file evidence tồn tại thì từ chối ghi đè. `QA155_MODE=error-only` chạy riêng ca 500, không lặp burst/lockout.
- `tests/api/run_issue155_qa.py`: dựng project/volume mới, credential sinh trong process; không đọc `.env`, Compose kiểm tra `config --quiet`, không tái dùng stack để seed.
- `tests/api/test_issue155_safety.py`: bảo vệ evidence, từ chối project chung, sai image/revision thì không chạy HTTP/SQL, từ chối SQL ghi trước khi gọi Docker.

Để chạy mới, đặt `GREENMART_DEMO_PASSWORD` trong process theo tài liệu demo; chọn `QA155_PROJECT`, `QA155_PORT`, `QA155_REPORT` mới, `QA155_MODE=full` rồi chạy `python tests/api/run_issue155_qa.py`. Cần Docker và image Python seed nêu trong helper. Không dùng lại project/volume đã có để reset/reseed. Chạy lại checker phải dùng report mới và tài khoản QA chưa bị khóa: `QA155_LOCKOUT_USER` chỉ nhận `thungan.gm002` hoặc `thungan.gm003`.

Tài khoản GM-003 dùng cho lockout lượt đầu; GM-002 dùng cho lượt cuối, đều thuộc DB QA155 riêng. Giữ khóa tạm thời theo đúng hành vi hệ thống, không SQL-reset hoặc thay mật khẩu. OWNER/CASHIER GM-001 và phiên bổ sung đã logout 204. Giữ dữ liệu làm bằng chứng, không dừng stack khác.

Kiểm tra bộ QA: guard **5/5 PASS**; `py_compile` ba file PASS; `git diff --check` PASS. Backend build skip Maven tests, không nhận suite backend PASS từ bước build. Exit 1 của full checker phản ánh BLOCKED; error-only exit 0 không đồng nghĩa #155 Done.

## Giới hạn và quyết định còn chờ

- Lead cần chốt ngưỡng/window, key IP/độ tin cậy proxy, thời gian mở lại, mã lỗi/Retry-After và response 429 trong OpenAPI trước nghiệm thu rate limit. Chưa tự triển khai filter hoặc sửa SecurityConfig thuộc phạm vi backend khi chưa rõ chính sách/người thực hiện.
- Lead cần chốt origin frontend cho từng môi trường, phương thức/header/credentials và direct API hay proxy; sau đó QA kiểm tra origin hợp lệ, không hợp lệ và recovery/window theo đúng chính sách.
- Automatic approval review từ chối đọc thêm Docker logs vì nguy cơ chứa secrets. Đã bỏ mọi lệnh đọc Docker logs khỏi phiên bản runner cuối và chạy lại full/API-only; không tìm đường khác để lấy log. Lượt đầu trước đó có kiểm tra hữu hạn known-value/correlation nhưng không dùng thay cho xác nhận an toàn toàn bộ log; bằng chứng chẩn đoán đầu giữ local, không xuất bản raw log.
- Lượt đầu request POS null item còn thiếu payment nên chỉ gây 400. Đã chuyển ca chính sang quote null item đúng tái hiện #316, kiểm tra 500 riêng và chạy lại toàn bộ checker cuối; giữ artifact đầu local, không ghi đè để che kết quả.
- Chưa chạy browser XSS/path traversal/mọi JWT/refresh/replay webhook; không tuyên bố đã hoàn tất toàn checklist. Tìm source frontend không thấy các sink `dangerouslySetInnerHTML`/`innerHTML`/`document.write` trong phạm vi rg, không thay thế kiểm tra XSS runtime.
- `.env` không tracked và không thấy commit cho đường `.env` trong các ref local được kiểm tra; có workflow gitleaks. Máy không có CLI gitleaks, chưa chạy full secret/history scan, không đọc `.env` hoặc in values của `.env.example`; không nhận toàn repo sạch secrets/CI xanh.

**#155 chưa đủ điều kiện Done.** Người dùng đã cho phép commit/push/tạo PR phần QA này. PR ghi `Refs #155`, không tự đóng khi còn BLOCKED; trạng thái CI remote theo PR. Đã kiểm tra lại issue mở/đóng: bug chức năng trùng #316 OPEN, giao @t-boai; chính sách rate/CORS tiếp tục được theo dõi trong #155, không tự tạo báo lỗi mới hoặc quy trách nhiệm khi chưa có chính sách. Không đăng comment riêng hoặc đổi assignee. Nhật ký cá nhân chỉ local; chờ Lead chốt chính sách và kiểm thử phần còn thiếu, sau đó review/CI/merge theo quy trình.
