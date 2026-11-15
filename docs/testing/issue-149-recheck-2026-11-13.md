# #149 — Kiểm tra lại bản sửa hóa đơn SV02 ngày 13/11/2026

QA: Huynh-Phuc-Dat (SV08). **Luồng Newman trên API/DB thật đã PASS: 8 request, 15/15 assertion, 0 failures, exit 0. Lỗi receipt 404 trước đây không còn trên baseline đã kiểm tra.**

## GitHub và baseline

- [#259](https://github.com/NguyenDokhai2005/GreenMart/issues/259), giao SV02 @t-boai, hiện CLOSED/completed. [PR #289](https://github.com/NguyenDokhai2005/GreenMart/pull/289) đã merge: head `cfef42b890a7900ff98f59c9f5771e101c7c071c`, merge commit `306d0cc95a3207e0767c075d3f8f15734b54c578`.
- Đọc yêu cầu #149/#259, bình luận Lead trên PR #254 và quyết định #259: ADMIN xem đơn nhận 403; không mở rộng vai trò ngoài quyết định này.
- Đã chuyển develop và pull fast-forward an toàn: vẫn tại **`83589787bd78aec61940177f9863a80853ca6226`**, có bản sửa #289. Nhánh đọc/kiểm tra lại riêng `feature/149-recheck-20261113`; giữ nguyên công việc #152/#153 và các file untracked.
- [PR #254](https://github.com/NguyenDokhai2005/GreenMart/pull/254) vẫn OPEN/unmerged, head **`f24b779c5adc43fa04959cdc7deb44209e305358`**. Thông tin head này là trước lượt cập nhật nhánh. Sau khi người dùng cho phép, đã merge develop `8358978` vào đúng nhánh PR, giữ nguyên code nhóm và collection; CI mới cần đọc sau push.
- Dùng nguyên collection, runner, fixture và lockfile từ head trên, trích vào thư mục QA local ignored; đã so byte với Git để xác nhận không đổi assertion hoặc bỏ bước hóa đơn.

## Môi trường thực tế

- Stack mới **`greenmart-qa149-recheck-20261113`**, backend **`http://127.0.0.1:18156/api/v1`**; PostgreSQL/Redis/RabbitMQ và network/volume riêng, cả bốn container healthy sau kiểm tra.
- Image `greenmart-backend:qa149-recheck-8358978` là alias của image cùng source `8358978`; OCI revision được kiểm tra khớp. Không dùng backend QA149 cũ ngày 07/11.
- Seed SV06 nguyên bản chỉ nạp một lần vào DB mới. Fixture gốc #149 nạp sau khi xác nhận hai ID sản phẩm `900001/900002` chưa tồn tại; không reset hoặc ghi đè dữ liệu khác.
- Fixture không khuyến mại: gạo 180.000 + hai sữa 29.000 = 238.000; trả tiền mặt 250.000, tiền thối 12.000. Collection kiểm tra giá/tổng/thanh toán, hóa đơn đã lưu và biến động tồn.
- Secrets hạ tầng ngẫu nhiên chỉ trong môi trường process; không đọc `.env`/raw logs, không xuất session token vào artifact. Không dừng QA152/QA153 hoặc project dev.

## Kết quả Newman

| Request | HTTP thực tế | Assertion |
| --- | ---: | --- |
| 01 Login CASHIER GM-001 | 200 | PASS |
| 02 Current user | 200 | PASS |
| 03 Inventory before | 200 | PASS |
| 04 Quote | 200 | PASS |
| 05 POS cash checkout | 201 | PASS |
| **06 Receipt persisted order** | **200** | **PASS: hóa đơn khớp đơn vừa tạo** |
| 07 Inventory after | 200 | PASS: available giảm một theo fixture |
| 08 Logout | 204 | PASS |

Tổng **8 request, 15 assertion, 0 request error/failure, passed=true, exit 0**. Trước đây hai assertion receipt fail do 404; hiện cả HTTP và nội dung hóa đơn đều đạt, không chỉ kiểm tra endpoint tồn tại.

## Regression của runner

Ba scenario HTTP double đều đạt: success → exit 0; forced assertion failure → exit 1; missing receipt → exit 1. Cả ba chạy đủ tám request, logout và kiểm tra artifact sanitized. Đây là kiểm tra runner, tách khỏi bằng chứng backend thật ở trên.

Lần chạy regression trong sandbox ban đầu bị EACCES cho localhost; chạy lại với quyền mạng local phù hợp đạt cả ba, không sửa code/expected result để vượt lỗi môi trường.

## Bằng chứng và lệnh đã chạy

- [Summary 15 assertion](results/issue-149-2026-11-13-summary.json).
- [JUnit Newman](results/issue-149-2026-11-13-newman.xml).
- [Provenance/recheck](results/issue-149-2026-11-13-recheck.json): backend commit/image, collection commit, SHA-256 và exit codes của regression.
- Collection SHA-256: `9fffadd94fc6021fa43892cea3bd2139fff9b55bcf4b6d99c9bbf419e3a7e36b`.
- Newman **6.2.1**, cài đúng package-lock của PR #254 bằng `npm ci --ignore-scripts --no-audit --no-fund` trong thư mục local QA.
- Chạy `npm test`; sau đó `npm run pos` với `QA_BASE_URL=http://127.0.0.1:18156/api/v1`, `QA_SOURCE_COMMIT=83589787bd78aec61940177f9863a80853ca6226`, `QA_TEST_MODE=real-api-recheck`, `QA_FORCE_FAILURE=false`, `QA_REPORT_DIR` trỏ thư mục artifact local riêng.
- Đã parse JSON/XML, kiểm tra đúng 8/15/0, receipt 200, logout 204 và scan token/password/hash. Không xuất request/response body hoặc headers.

## Kết luận và phần còn chờ

**SV02 đã sửa lỗi hóa đơn chặn #149; QA local trên bản tích hợp hiện tại đã xác nhận PASS luồng core bằng collection gốc.** Không tiếp tục mô tả receipt 404 là lỗi hiện tại trên baseline này.

Đây **không phải lượt CI mới của PR #254**. Theo chỉ dẫn Lead, nhánh PR đã được cập nhật với develop chứa #289; tiếp theo cần pos-newman trên GitHub, review/CI xanh rồi mới merge PR. #149 vẫn OPEN, PR #254 chưa merge; không tự nhận Done chỉ vì local PASS.

Luồng core không có khuyến mại, không chứng nhận toàn bộ RBAC, mọi phương thức thanh toán, online hoặc các tình huống #153. Sau khi được người dùng cho phép, chỉ cập nhật nhánh PR bằng develop và bổ sung báo cáo/bằng chứng. Không sửa riêng backend, collection, runner hoặc workflow, không merge PR/đóng issue hay đăng bình luận. Công cụ/setup local không nằm trong báo cáo công khai.
