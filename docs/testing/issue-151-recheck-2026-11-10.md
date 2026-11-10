# #151 — Báo cáo kiểm tra lại ngày 10/11/2026

QA: Huynh-Phuc-Dat (SV08). Trạng thái: **báo cáo từng phần; chưa hoàn tất #151**.

## Baseline, phạm vi và cách kiểm tra

- Đã pull fast-forward develop `624e402` → `e722882e91ef0d4c5a001ad41e238d3aff0a68b6`; nhánh QA `feature/151-recheck-20261110`.
- Đối chiếu yêu cầu #151, CONTRIBUTING/CODEOWNERS, SRS/BA, OpenAPI, test plan/security và phân công issue liên quan trên GitHub trước kiểm thử.
- Build và chạy backend/frontend/forecast/mock-connector từ source trên; PostgreSQL/Redis/RabbitMQ thật, seed demo SV06 nguyên bản.
- Stack riêng `greenmart-qa151-20261110`: backend 18090, connector 18091, forecast 18092, frontend 18093; network/volume riêng. Backend QA giới hạn768 MiB/2 CPU/JVM 512 MiB.
- Không sửa code nghiệp vụ, migration, API contract hoặc Dockerfile production; không reset dữ liệu hay dừng các stack khác.
- Kiểm tra HTTP thật: danh mục/sản phẩm/khách hàng, quote/POS, token/role/branch, inventory/ledger và phiếu IMPORT/EXPORT. SQL chỉ đọc để đối chiếu tồn, đơn và sổ kho; connector kiểm HMAC/version và forecast kiểm health/metadata.
- Công cụ QA đang ở workspace local, chưa nằm trong PR tài liệu này. Các kiểm tra có method/path, expected/actual, assertion, source và traceId trong JSON; không công bố credentials/token/hash/raw log.

## Kết quả mới có bằng chứng

[Bằng chứng tổng hợp](results/issue-151-2026-11-10.json) giữ các giá trị từ báo cáo gốc; mỗi check và route trên một dòng để review được.

| Nhóm | PASS | FAIL | Người phụ trách theo phân công |
| --- | ---: | ---: | --- |
| Catalog/Customer/Sales | 42 | 1 | SV02 — @t-boai |
| Auth/Inventory/phiếu kho | 39 | 0 | SV03 — @Dat9161; Auth chuyển theo #241/#146 |
| Connector/forecast health | 15 | 0 | SV07; mô hình dự báo thuộc SV06 |
| Tổng kiểm tra được ghi | **96** | **1** | Không phải97 API riêng biệt |

Đã gọi **28/29 thao tác controller** hiện có. API mới `POST /stock-documents/{id}/receive` chưa được gọi. Cleanup logout bị mất kết nối, runner ghi **BLOCKED: Logout unavailable; QA session may remain until expiry**. Vì vậy không kết luận lượt chạy hoặc toàn hệ thống PASS; các phiên QA chưa logout có thể còn hiệu lực đến expiry.

Frontend QA tooling build, typecheck, lint và format:check đã PASS. Unit/component chưa hoàn tất; không dùng kết quả136unit/7integration ngày 09/11 để báo PASS cho ngày 10/11.

## Lỗi còn tái hiện — Customer #267, SV02

1. Đăng nhập CASHIER GM-001 trên backend QA18090; khách demo `0991510151` đã được tạo thành công qua API.
2. Gọi `GET /api/v1/customers?phone=0991510151` với Bearer token hợp lệ, không truyền `q`.
3. Mong đợi HTTP 200/CustomerPage theo OpenAPI; thực tế **500 INTERNAL_SERVER_ERROR**.

Case `SV02-025`, traceId `806b1d4b-4233-4262-b59c-6c49cb1be1ef`. Mức độ: nghiêm trọng, chặn tra SĐT tại POS. Người nhận theo module: @t-boai. Liên kết [#267](https://github.com/NguyenDokhai2005/GreenMart/issues/267), không mở trùng.

PR #270 tại head `14796cb2c020d7d74c4891c38c900c28cd990a3d` có sửa CAST(q)/AND phone+q nhưng vẫn OPEN/chưa merge tại lúc đọc. Lead yêu cầu loại 6 file Sales/Idempotency ngoài phạm vi. **Lỗi 500 trên develop chưa chứa fix không chứng minh fix PR #270 thất bại**. QA chưa chạy API thật trên head PR; chờ nhánh sạch, đúng commit rồi verify 4 tổ hợp lọc và pagination/validation.

## Phần đã đạt và thay đổi mới

- **#34 Idempotency đạt các ca kiểm tra lại:** lần đầu 201; cùng key/body trả 200/đúng đơn cũ; snapshot DB không đổi; body khác cùng key trả 409 IDEMPOTENCY_CONFLICT. Không tiếp tục báo lỗi trùngđơn lịch sử là còn tồn tại trên e722882. Chưa verify TTL24h/mọi race.
- SV03: các ca auth/refresh/me/tokennegative, inventory/ledger và IMPORT/EXPORT đã ghi nhận 39 PASS. Không thay thế toàn bộ #242 hoặc mọi tổ hợp role/API, lockout, audit và deadlock.
- #58 chuyển kho đã đóng, source có receive mới nhưng QA chưa verify endpoint nhận; không nhận PASS chỉ từ trạng thái issue.
- Source frontend đã mount PosLayout; mặc định vẫn tải PosPlaceholderPage. Không tiếp tục dùng kết luận cũ rằng POS hoàn toàn chưa mount, cũng không coi có layout là đã checkout/hóa đơn E2E.
- #259 hóa đơn vẫn OPEN/chưa có endpoint tại baseline: phụ thuộc triển khai SV02, không mở bug trùng và không nhận Newman #149 receipt PASS.

## Bị chặn / chưa kiểm chứng

Backend 18090 mất kết nối; readiness timeout, Docker stats timeout 15s và docker ps timeout 10s. Người dùng đã thông báo sẽ kiểm tra Desktop. Chỉ dừng helper QA component đang chờ và Docker CLI con; không restart Docker/dừng stack khác. Hủy CLI không chứng minh container tạm đã dừng; cần kiểm tra sau Engine hồi phục.

Chưa hoàn tất: receive chuyển kho, unit/component frontend, auth integration rerun, connector Node rerun, LightGBM import rerun, browser UI mới và full Maven/Testcontainers. Browser CUA mở 18093 timeout, không có tab để đọc; chưa quan sát trực tiếp giao diện mới.

Finding image forecast thiếu `libgomp.so.1` đã được tái hiện ngày 09/11, code image không đổi; **chưa verify lại ngày 10/11**. Không lấy finding đó làm kết quả runtime mới. Không chứng nhận UI E2E, online, API dự báo, tải 50 VU, OWASP hoặc staging từ lượt kiểm tra này.

## Điều kiện còn chờ của #151

- AC #151 yêu cầu lỗi lên board có nhãn bug, bước tái hiện, mức độ và người nhận. #267 lúc đọc chỉ có nhãn test, assignees=null, milestone=null; chưa xác minh board.
- Chờ verify bản fix Customer đúng commit, các ca bị chặn và endpoint receive mới; giữ #151 mở.
- Chưa có review/CI/merge cho báo cáo này. PR bàn giao kết quả từng phần, không tự đóng #151. Chỉ Lead/reviewer quyết định nghiệm thu sau khi điều kiện còn thiếu được đáp ứng.
