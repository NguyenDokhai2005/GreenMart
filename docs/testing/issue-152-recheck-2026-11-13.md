# #152 — Kết quả phân quyền chéo chi nhánh ngày 13/11/2026

QA: Huynh-Phuc-Dat (SV08). **Đã chạy đầy đủ lượt kiểm tra: 591 checks, 572 PASS, 19 FAIL, không BLOCKED. Chưa nghiệm thu AC-04/#152 vì còn hai vấn đề thực tế.**

## Baseline và nguồn yêu cầu

- [Issue #152](https://github.com/NguyenDokhai2005/GreenMart/issues/152), SRS AC-04/BR-11, business-analysis EX-11 và user-stories: nhân viên chỉ truy cập dữ liệu của chi nhánh được gán; sai chi nhánh trả 403 BRANCH_FORBIDDEN, có audit. ADMIN/OWNER được bỏ giới hạn chi nhánh.
- Người dùng đã xác nhận vẫn tuân thủ `x-roles` của OpenAPI. ADMIN bị FORBIDDEN ở API không cho phép ADMIN không phải lỗi giới hạn chi nhánh. Không sửa expected result để làm xanh.
- Đọc lại issue/comments và đối chiếu GitHub CONTRIBUTING, README, CODEOWNERS, SRS/BA/user-stories, API README/OpenAPI, test-plan/security-checklist/QA questions. Q-27 chưa chốt tên audit action: kiểm tra dòng audit mới theo actor, không ép một tên action giả định.
- Pull fast-forward `develop`: `15d136b` → **`83589787bd78aec61940177f9863a80853ca6226`**. Nhánh `feature/152-rbac-recheck-20261113`; giữ nguyên mọi file QA cũ, không rebase/merge nhánh PR trước.
- Image `greenmart-backend:qa152-8358978`; image ID `sha256:7e2fed7000456bdf1fdce60607a6d2411a4ba4effcbeffa8e18fbac0b79a609b`. Nhãn `org.opencontainers.image.revision` được kiểm tra khớp commit trên.

## Docker và môi trường thật

- Đã restart Docker Desktop theo yêu cầu. Người dùng xác nhận cho phép stop đúng bảy project QA cũ: `greenmart-qa148`, `greenmart-qa149`, `greenmart-qa150`, `greenmart-qa151`, `greenmart-qa151-20261109`, `greenmart-qa151-20261110`, `greenmart-qa152-20261111`.
- Chọn 36 container QA cũ theo label project và stop thành công. Không xóa container/volume, không reset dữ liệu. Không chủ động stop project phát triển `greenmart` hoặc `backend-db`; Desktop restart có gián đoạn tạm thời toàn Engine.
- Sau giảm tải, Engine và build phản hồi ổn định; Maven package khoảng 32 giây. Build dùng `-DskipTests`: không coi đây là kết quả unit test PASS.
- Build context QA local sao chép nguyên pom/src từ baseline; chỉ thêm artifact Maven `jaxb-runtime:4.0.6` đã kiểm tra checksum vào cache và đặt Maven heap 512 MiB. Không sửa Dockerfile/code của nhóm. SHA-256 artifact: `1c0d57f8c25f9605d5a2f7ad0a87581893776ac85b00b101b2651258edaa9118`.
- Stack mới **`greenmart-qa152-20261113`**, backend **`http://127.0.0.1:18154`**, PostgreSQL/Redis/RabbitMQ thật và volume/network riêng. Readiness healthy; generator seed SV06 nguyên bản chỉ nạp một lần vào DB mới. Không đọc `.env`; secrets hạ tầng ngẫu nhiên chỉ tồn tại trong môi trường process/container.
- Cả bốn container QA mới healthy sau test. Snapshot tài nguyên: backend khoảng 570 MiB/768 MiB, Postgres 77 MiB, RabbitMQ 138 MiB, Redis 4 MiB. Nhiều stack cũ đồng thời chạy có thể gây áp lực tài nguyên; không có bằng chứng đủ để quy mọi lỗi TLS/DNS trước đó cho thiếu RAM.
- Project dev có frontend restarting và forecast unhealthy trước test; chưa chẩn đoán/sửa hai dịch vụ này. Không dùng log dev làm bằng chứng cho QA152.

## Cách thực hiện và bằng chứng

- Runner: [tests/api/issue152_rbac.py](../../tests/api/issue152_rbac.py). Đặt mật khẩu demo được cấp vào biến môi trường process `GREENMART_DEMO_PASSWORD`, chạy `python tests/api/issue152_rbac.py`. Runner chỉ chấp nhận project/cổng QA đã chỉ định, xác minh tag/image ID, trả exit 1 nếu FAIL/BLOCKED.
- [JSON lượt đầy đủ](results/issue-152-2026-11-13.json): 591 checks; mỗi ca có actor, method/path, expected/actual, assertion và traceId nếu API trả lỗi. Đây là 591 phép kiểm tra, không phải 591 API khác nhau.
- [JSON tái kiểm tra độc lập](results/issue-152-2026-11-13-recheck.json): đối chứng lookup đúng/sai chi nhánh, inventory 403, audit sau request và logout.
- 12 tài khoản gồm ADMIN, hai OWNER, ba BRANCH_MANAGER, ba CASHIER, ba STOREKEEPER. Ma trận target GM-001/002/003; đúng chi nhánh là positive control, hai chi nhánh còn lại kiểm tra từ chối. ADMIN/OWNER là control ngoại lệ chi nhánh trong phạm vi role của endpoint.
- Fixture tạo qua API: ba IMPORT nháp; ba TRANSFER một sản phẩm/đơn vị, chuyển vòng 1→2→3→1. Thủ kho lập, manager khác người lập duyệt, thủ kho đích nhận ở cuối lượt. Các thay đổi hợp lệ này chỉ xảy ra trong QA mới.
- Snapshot DB chụp sau setup và trước các receive hợp lệ: orders, documents/items, inventory và ledger giữ nguyên qua toàn bộ mutation bị từ chối. Có 12/12 logout cleanup thành công.
- Lượt đầu runner dừng vì dùng tên thuộc tính Java `active` trong SQL thay vì cột `is_active`; đã sửa riêng runner theo schema. Lượt đầu chỉ login/logout 12 tài khoản, không ghi đó là PASS ma trận. Bằng chứng lượt đầu giữ local; 591 checks là lượt chạy sau sửa harness.

## Tổng hợp kết quả

| Nhóm kiểm tra | PASS | FAIL |
| --- | ---: | ---: |
| Login/role/branch fixture | 12 | 0 |
| Inventory theo branch | 36 | 0 |
| Product lookup theo branch | 18 | **18** |
| Ledger theo role/branch | 36 | 0 |
| Quote theo role/branch | 36 | 0 |
| Chi tiết đơn theo ID/branch | 36 | 0 |
| Revenue report theo role/branch | 36 | 0 |
| Top-products report theo role/branch | 36 | 0 |
| Đọc IMPORT theo ID/branch | 36 | 0 |
| Đọc TRANSFER phía nguồn/đích/chi nhánh thứ ba | 36 | 0 |
| Từ chối nhận TRANSFER ngoài chi nhánh đích | 27 | 0 |
| Create/update/cancel/approve/receive IMPORT và POS bị từ chối | 162 | 0 |
| Inventory mặc định và lịch sử đơn khách không lộ branch | 24 | 0 |
| Setup IMPORT/TRANSFER và nhận TRANSFER đúng đích | 12 | 0 |
| Probe inventory 403 và snapshot không đổi | 2 | 0 |
| Audit sau probe từ chối | 0 | **1** |
| Logout | 12 | 0 |
| Alerts branch guard bổ sung từ #61 | 15 | 0 |
| **Tổng** | **572** | **19** |

Alerts #61 đã merge nhưng route chưa có trong OpenAPI baseline; 15 ca bổ sung chỉ áp dụng OWNER/BRANCH_MANAGER theo phạm vi quản lý của #61 và BR-11, không tự định nghĩa contract cho mọi vai trò. Tách khỏi core OpenAPI: **576 core checks = 557 PASS + 19 FAIL**; supplementary alerts 15 PASS.

## Lỗi 1 — Product lookup làm lộ tồn chi nhánh không được gán

- **Mức độ đề xuất: nghiêm trọng (P1), vi phạm AC-04/BR-11. Người xử lý chính: SV02 @t-boai (Catalog/Product lookup); SV03 @Dat9161 phối hợp interface Inventory nếu cần.**
- 18 ca FAIL: mỗi tài khoản trong chín tài khoản BRANCH_MANAGER/CASHIER/STOREKEEPER xem được tồn ở cả hai chi nhánh ngoài phạm vi. ADMIN/OWNER xem mọi chi nhánh ở endpoint này là hợp lệ.
- Ví dụ: đăng nhập `thungan.gm001`, gọi `GET /api/v1/products/lookup?code=COM-SUON-BI-CHA-0001&branchId=2`. Mong đợi **403 BRANCH_FORBIDDEN**; thực tế **200**, trả `branchId=2` và số `available` của GM-002. Lookup GM-001 là đối chứng 200 hợp lệ.
- Tái kiểm tra độc lập vẫn 200 cho GM-002; cùng session gọi `GET /inventory?branchId=2` bị chặn 403, nên token/chi nhánh của actor không bị cấp nhầm. Response thành công của lookup không có error traceId; không tự tạo trace giả.
- Đường gọi đọc code: `backend/src/main/java/vn/greenmart/catalog/service/ProductServiceImpl.java:165` (`lookupProduct`) → `backend/src/main/java/vn/greenmart/inventory/service/InventoryServiceImpl.java:138` (`getAvailable`). Chưa thấy guard accessible branches trước khi trả tồn.
- Đề xuất: kiểm tra branch tại boundary lookup theo SecurityUtils/BR-11, thêm regression test PostgreSQL/JWT cho role × branch; giữ nguyên quyền đọc mọi branch của ADMIN/OWNER. QA không tự sửa module của thành viên khác.

## Lỗi 2 — Không ghi audit cho truy cập chi nhánh bị từ chối

- **Mức độ đề xuất: trung bình (P2), thiếu bằng chứng truy vết theo AC-04/EX-11. Người xử lý chính: SV03 @Dat9161 — Auth/RBAC (#146, #241); phối hợp owner endpoint khi cần.**
- Probe độc lập: login `thungan.gm001`, chụp max ID audit, gọi `GET /api/v1/inventory?branchId=2`, nhận đúng 403 BRANCH_FORBIDDEN. Đếm các dòng audit mới sau marker: **0 dòng**, gồm **0 dòng của actor**. Không phụ thuộc tên action, vì Q-27 chưa chốt.
- Tái kiểm tra lần hai: traceId **`35052d86-f608-487b-88e0-5bfb700671ee`**, vẫn 403 và audit mới = 0. Không có request QA khác xen vào đoạn probe; không đọc/in raw audit details hoặc secrets.
- Đường gọi: `InventoryQueryServiceImpl` ném BRANCH_FORBIDDEN; `GlobalExceptionHandler.handleAppException` trả ErrorResponse. Cần bổ sung audit bền vững khi transaction nghiệp vụ bị từ chối, không để rollback làm mất event; Lead chốt tên action và trường metadata phù hợp.
- Phát hiện này chứng minh thiếu audit ở request inventory đã kiểm tra, không tự kết luận mọi endpoint đều thiếu audit. Chưa sửa backend hoặc đăng issue/comment GitHub.

## Giới hạn và bàn giao

- Không chứng nhận browser E2E, mọi role × mọi endpoint hoặc mọi ca đồng thời. `GET /orders` và single-item inventory chưa có controller tại baseline, không gọi 404 là PASS quyền truy cập.
- Chuyển kho dùng một sản phẩm/đơn vị; chưa kiểm tra nhận thiếu/thừa hay mọi loại chứng từ. Audit nếu được sửa vẫn cần kiểm tra correlation/metadata và khả năng tồn tại sau rollback.
- Cần thành viên sửa hai lỗi trên rồi QA verify đúng commit tích hợp. Tài liệu có kết quả và đề xuất người nhận; không tự tạo issue/đổi assignee hoặc sửa backend; PR này chỉ bàn giao QA.
- Giữ QA13 healthy để kiểm chứng tiếp; bảy QA cũ đang stop theo cho phép, volume còn nguyên. Các kết quả 0 ca/bị chặn ngày 11–12/11 được giữ nguyên, không thay thành PASS.
- **#152 chưa Done:** còn lỗi AC-04, chưa review/CI/merge. Lead quyết định nghiệm thu sau khi có bản sửa và bằng chứng chạy lại.
