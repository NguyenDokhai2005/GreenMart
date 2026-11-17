# #152 — Kiểm tra lại phân quyền chi nhánh ngày 14/11/2026

QA: Huynh-Phuc-Dat (SV08). **Đã chạy kiểm thử; chưa nghiệm thu #152 vì audit của lookup còn thiếu metadata.**

## Baseline và nguồn yêu cầu

- Đã pull fast-forward develop từ 7711f92 lên **c25ae39477f8b2de72b2f022bbfda03c00d28073**. Nhánh local: `feature/152-rbac-recheck-20261114`.
- Đã đọc bản GitHub tại SHA này của CONTRIBUTING, README, CODEOWNERS, template bug, SRS, BA, user stories, OpenAPI/API README, test plan, security checklist, RBAC test cases, QA questions và backend README; đọc issue #152/#309/#310/#259 cùng quyết định Lead.
- #309/PR338 và #310/PR355 đã merge. Trạng thái CLOSED không thay thế kết quả kiểm tra API thật.
- Lead xác nhận trong #259: ADMIN xem hóa đơn nhận 403. ADMIN/OWNER bỏ giới hạn chi nhánh nhưng vẫn phải đúng vai trò của endpoint; không tự cho phép ADMIN bán POS hoặc duyệt kho.
- Q-27: audit phải có action BRANCH_FORBIDDEN, user_id từ actor, entity branch, entity_id dạng chuỗi, detail gồm branchId/method/path/traceId; không lưu query string hoặc body. Q-31: lỗi FORBIDDEN do sai vai trò hiện chưa ghi audit.

## Môi trường

- Docker Engine lúc đầu chưa chạy; đã mở Docker Desktop, không restart/dừng/xóa các stack khác.
- QA mới: **greenmart-qa152-20261114**, backend **http://127.0.0.1:18160/api/v1**. PostgreSQL, Redis, RabbitMQ, network và volumes riêng; cả bốn service healthy sau kiểm thử.
- Image: `greenmart-backend:qa152-c25ae39`; OCI revision là full SHA trên.
- Image ID: `sha256:d70575003b0b546b2da08a36800d4304d5cfb8da866bf587876b5b8fea4e7a6d`.
- Seed generator SV06 nguyên bản chỉ nạp một lần vào DB mới. Không đọc .env, inspect environment hoặc raw logs; không xuất token, password hay hash mật khẩu.
- Build dùng -DskipTests chỉ tạo image, không phải bằng chứng backend unit tests PASS.
- Không sửa backend, frontend, Compose, migration, contract, seed hoặc nhánh thành viên khác.

## Kết quả chính thức

| Nhóm | Tổng | PASS | FAIL | Gián đoạn runtime |
|---|---:|---:|---:|---|
| Matrix chính | 591 | 573 | 18 | Không |
| Matrix bổ sung | 173 | 155 | 18 | Không |
| Tổng hai matrix | **764** | **728** | **36** | Không |

**36 ca FAIL thuộc cùng một lỗi metadata audit, không phải 36 bug độc lập.** Tất cả 36 request đều đã nhận đúng 403 BRANCH_FORBIDDEN. Lỗi trả tồn chi nhánh khác với HTTP 200 trước đây không còn tái hiện trong ma trận đã chạy.

Hai probe độc lập ngoài tổng 764: inventory 403 + audit đầy đủ PASS; lookup SKU 403 + audit thiếu ID chi nhánh FAIL. Hai probe availability ngoài tổng 764: GET orders và GET branches còn BLOCKED vì trả 404. Không cộng lặp các probe vào số ca matrix.

## Các quyền đã kiểm tra đạt

- Đủ 12 tài khoản demo đăng nhập đúng vai trò và chi nhánh; ADMIN và hai OWNER được dùng toàn chuỗi tại endpoint có quyền.
- Tồn kho, tồn kho chi tiết, sổ kho, quote, hóa đơn đã lưu, báo cáo doanh thu/top-products, cảnh báo tồn và phiếu kho: các request được thử trả đúng quyền vai trò/chi nhánh.
- TRANSFER: đọc ở nguồn/đích, từ chối chi nhánh thứ ba; duyệt tại nguồn và nhận tại đích theo quyền.
- Các thao tác lập/sửa/hủy/duyệt/nhận phiếu và tạo POS trái quyền bị từ chối; snapshot dữ liệu nghiệp vụ trước/sau giống nhau.
- Lookup SKU và barcode: 9 tài khoản hạn chế × 2 chi nhánh ngoài phạm vi đều bị chặn 403; ADMIN/OWNER đọc theo quyền.
- Giá vốn ở chi tiết sản phẩm chỉ hiện cho ADMIN/OWNER/BRANCH_MANAGER; cashier/storekeeper nhận null. Vai trò ngoài OWNER không sửa giá hoặc tạo danh mục; snapshot product/category không đổi.
- Không truyền branchId: inventory và customer order history không lộ chi nhánh ngoài phạm vi trong dữ liệu trả về; danh sách phiếu kho kiểm tra phạm vi nguồn/đích.
- Các branch denial ngoài lookup có metadata audit đầy đủ trong những request đã thử. Đối chiếu audit theo traceId từng request.
- 24 bước logout của hai matrix đều PASS; ba phiên ở lượt kiểm chứng độc lập cũng logout 204. Mật khẩu demo giữ nguyên.

## Lỗi còn tái hiện: audit lookup thiếu branch ID

Ví dụ tài khoản `thungan.gm001` chỉ thuộc GM-001 gọi:

`GET /api/v1/products/lookup?code=COM-SUON-BI-CHA-0001&branchId=2`

Thực tế trả 403 đúng. Audit có actor/action/entity/method/path/traceId, nhưng **entity_id = null**, **detail.branchId không có**.

- Trace lookup độc lập: `13146bdc-798f-4058-a590-8d442b496c73`.
- Đối chứng cùng phiên: GET inventory branch 2 trả 403 và audit có entity_id="2", branchId=2. Trace: `49de2c25-8daf-4c44-8781-b6ceb0880c35`.
- Phạm vi 36 FAIL: 9 tài khoản BRANCH_MANAGER/CASHIER/STOREKEEPER × 2 chi nhánh ngoài phạm vi × 2 cách lookup SKU/barcode.
- `ProductServiceImpl.lookupProduct` đang dùng AppException thường, không truyền deniedBranchId cho cơ chế audit mới. Finding đã được tái hiện; QA chưa sửa backend.
- Người xử lý đề xuất: SV02 @t-boai (Catalog/#309), phối hợp SV03 @Dat9161 (audit/#310). Lead @NguyenDokhai2005. Mức độ đề xuất P2 về truy vết, không phải lỗi lộ tồn P1 cũ.
- Search GitHub theo lookup/audit và entity_id/branchId chỉ tìm thấy #310 đã đóng, chưa thấy issue riêng cho lỗi metadata này tại thời điểm kiểm tra. Bản nháp báo bổ sung hoặc mở issue liên kết đã chuẩn bị local, chờ người dùng duyệt; chưa tạo/reopen/comment/tag trên GitHub.

Quyết định nghiệp vụ: https://github.com/NguyenDokhai2005/GreenMart/issues/310#issuecomment-6854060178

## Phụ thuộc và giới hạn

- OWNER GET `/orders?branchId=2`: 404 NOT_FOUND, BLOCKED liên quan #333.
- ADMIN GET `/branches`: 404 NOT_FOUND, BLOCKED liên quan #335.
- Hai probe này nằm ngoài denominator 764; không thay endpoint khác để nhận các route chưa có là PASS.
- Chưa chạy browser E2E, full OWASP, toàn bộ backend unit tests hay CI remote. Không nhận mọi tính năng tương lai, mọi trang/bộ lọc và mọi tác động DB đã được kiểm hết.
- Helper từ chối project/volume tồn tại. Runner từ chối ghi đè bằng chứng và xác minh image/OCI revision trước HTTP/SQL.
- Hai lượt matrix chính tạo tổng 6 IMPORT draft và 6 TRANSFER một đơn vị theo vòng GM001→GM002→GM003→GM001. Phiếu IMPORT không duyệt; chuyển kho được duyệt/nhận đúng quyền. Fixture, ledger, version và outbox hợp lệ được giữ lại; không reset DB.

## Cách chạy và bằng chứng

Helper mới: `tests/api/run_issue152_qa.py`. Đặt GREENMART_DEMO_PASSWORD theo tài khoản demo công khai trong README rồi chạy helper. Khi tạo môi trường mới, dùng QA152_PROJECT, QA152_PORT và QA152_REPORT mới; không chạy helper để seed lại stack đang tồn tại. Mặc định port 18160 và project ngày 14/11.

Để kiểm tra trên QA đã chuẩn bị, đặt QA152_SOURCE_COMMIT full SHA, QA152_PROJECT, QA152_PORT, QA152_REPORT đường dẫn mới và GREENMART_DEMO_PASSWORD. Chạy hai script tuần tự với report riêng:

    python tests/api/issue152_rbac.py
    python tests/api/issue152_supplemental.py

Matrix chính sẽ tạo thêm fixture riêng khi chạy lại, không seed/reset. Chỉ dùng môi trường QA152, không dùng DB dev/staging.

Artifact:

- `docs/testing/results/issue-152-2026-11-14.json`: 591 checks.
- `docs/testing/results/issue-152-2026-11-14-supplemental.json`: 173 checks.
- `docs/testing/results/issue-152-2026-11-14-independent.json`: đối chiếu metadata, availability và cleanup.

Regression guard **3/3 PASS**: report cũ được bảo toàn; sai image hoặc OCI revision dừng trước HTTP/SQL. Lệnh:

    python -m unittest discover -s tests/api -p test_issue152_safety.py -v

Python compile, diff check, JSON parse/counter và kiểm tra artifact không chứa password/token/hash đều đạt.

Lượt đầu chạy hai nhóm đồng thời khiến phép đếm audit theo max ID có thể lẫn sự kiện. Bằng chứng đó được giữ trong .local-tools, không dùng chốt kết quả. Đã bổ sung lọc traceId và chạy lại tuần tự để có các kết quả chính thức trên; không đổi expected để làm PASS.

## Trạng thái bàn giao

Đã thực hiện kiểm thử và báo cáo; **chưa Done #152** vì audit lookup còn thiếu metadata và còn các route phụ thuộc. Chưa commit/push/PR cho lượt này. Mọi bản nháp báo lỗi phải được người dùng duyệt trước khi đăng; không tự sửa code thành viên khác hoặc đóng issue.
