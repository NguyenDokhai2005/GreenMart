# #156 — Kiểm tra lại Playwright thật, ngày 15/11/2026

## Kết luận

**Lượt cuối: 3 PASS, 2 BLOCKED, không có test FAIL/timeout còn lại. #156 chưa đủ điều kiện Done.**

- API POS và online đi tới COMPLETED trên backend/PostgreSQL thật.
- Browser đăng nhập thật, vào POS và logout đạt.
- Browser đã xác nhận POS còn placeholder và trang đơn hàng đang phát triển; chưa thực hiện được checkout/complete qua UI. Hai ca này là `skipped` với annotation BLOCKED, không được cộng thành PASS.
- Playwright gốc có `status=passed` vì không còn assertion fail, nhưng reporter ghi `acceptance=BLOCKED` và trả **exit 1**. Đây không phải lỗi Docker hoặc một kết quả nghiệm thu xanh.

## Baseline và phạm vi

Đã bảo toàn công việc local, chuyển develop và `git pull --ff-only origin develop` thành công tới **`917a36e1fc87739077c93fe79d741d6826806d5c`**; nhánh `feature/156-playwright-recheck-20261115`. Đọc lại #156/comments và trạng thái các phụ thuộc trên GitHub. Bộ yêu cầu/quy trình/API/QA đã đọc tại baseline 4c3a4fa không có thay đổi nội dung ở SHA mới (đối chiếu Git diff), không tự đổi expected.

Chỉ sửa QA156 (selector và bằng chứng bước chạy an toàn), không sửa frontend/backend, contract, migration hoặc seed của thành viên khác. Các file #156 ngày 14/11 và JSON các lượt không bị ghi đè.

## Môi trường đã kiểm chứng

- Project riêng **`greenmart-qa156-20261115-r2`**, API `http://127.0.0.1:18168/api/v1`, UI **`http://127.0.0.1:13168`**.
- Backend/frontend/PostgreSQL/Redis/RabbitMQ đều **running/healthy** sau lượt cuối. Official seed chỉ nạp một lần vào DB mới; recheck dùng fixture mới trên cùng stack, không reseed/reset.
- Backend image ID `sha256:9cb9622b7f41d8d481b09b03cc7a1a9cd619c3bb28fe243d4136f9c8f4b017f4`.
- Frontend image ID `sha256:3b2bf3752bfd943dae1277430fb7c6aa1db10522bbd3069f31418e24db24cad0`.
- OCI revision của cả hai image và container dùng test khớp full SHA trên; guard kiểm tra trước mọi fixture/API.
- Playwright **1.65.1**; Chromium headless riêng, version thực tế **148.0.7778.96**, không dùng profile browser cá nhân. Không mock API hoặc can thiệp response UI.
- Thời gian JSON dùng UTC: ví dụ `2026-11-14T18:49:52.419Z` tương ứng **01:49:52 ngày 15/11 Asia/Saigon**; tên báo cáo dùng ngày local.

## Năm test và bằng chứng thực tế

| Test | Kết quả | Bằng chứng chính |
|---|---|---|
| API POS | PASS | Đơn **10203**, product **1003**, quote/total **62.000** cho 2 × 31.000; CASH PAID, tiền nhận 100.000, tiền thừa 38.000; COMPLETED và GET detail đúng. Cùng Idempotency-Key trả lại đúng đơn; tồn 20→18, reserved=0, chỉ 1 SALE |
| API online | PASS | Đơn **10204**, product **1004**, WEB HMAC thật: RESERVED giữ 3 → PAID → CONFIRMED → manager complete → COMPLETED. Tổng 93.000, PAID, tồn 20→17, reserved=0; 1 SALE, 1 order.reserved, 1 order.completed |
| Browser smoke | PASS | CASHIER nhập form login thật → `/pos`, thấy heading Bán hàng tại quầy và placeholder → logout API 204, về `/login` |
| Browser POS business | BLOCKED | Login và mở POS thành công, xác nhận “Màn hình đang được chuẩn bị”, logout 204; chưa có giỏ hàng/thanh toán để tiếp tục |
| Browser online business | BLOCKED | Manager login → `/operations` → menu Đơn hàng → `/operations/orders`, thấy “Chức năng đang phát triển”, logout 204; chưa có danh sách/chi tiết/complete UI |

API online còn kiểm tra **PAID gửi lặp** vẫn đúng order ID/CONFIRMED, reserved không tăng thêm; **complete lần hai** trả 409 INVALID_STATUS, tồn và SALE không bị trừ/ghi lại. Không dùng các ca API này để thay thế nghiệp vụ browser đang thiếu.

Bốn phiên API được cleanup qua logout; ba lượt browser trong suite cũng xác minh logout HTTP 204. Lượt cuối không có lỗi afterAll/cleanup làm suite fail. Các sản phẩm/đơn QA được giữ làm bằng chứng, không xóa dữ liệu.

## Lịch sử lượt chạy và xử lý trở ngại

1. **Bootstrap đầu ngày 15/11:** backend build được, build frontend thất bại khi Docker API trả HTTP 500/timeout; chưa chạy Playwright. Máy quan sát còn khoảng 613 MB RAM trống trên 16 GB — có áp lực tài nguyên, chưa chứng minh duy nhất nguyên nhân Engine.
2. Người dùng cho phép restart Docker Desktop và tạm dừng đúng 5 project QA ngày 14/11: qa152/153/154/155/156 với tiền tố `greenmart-` và hậu tố `-20261114`. Đã restart thành công rồi **stop 20 container đúng allowlist**, giữ nguyên volume/dữ liệu. Không chọn container phát triển để stop; restart Engine có gián đoạn chung đã được người dùng chấp thuận. Không tự mở lại các QA cũ vừa dừng.
3. **R2 sau khôi phục:** build/backend/frontend và cả 5 dịch vụ healthy, seed được nạp. Hai API test PASS; ba browser test timeout do script dùng `getByLabel(..., exact:true)` cho label có dấu `*` bắt buộc. Đối chứng public login: selector exact=0, pattern=1; Chromium tải trang và asset 200, không có pageerror trong lượt đối chứng. Đây là lỗi selector QA, không quy thành bug ứng dụng.
4. Sửa selector theo label thực tế, không sửa frontend hoặc expected nghiệp vụ. Chạy lại toàn suite với fixture/report mới trên cùng stack: **3 passed / 2 skipped (BLOCKED)**. Giữ nguyên kết quả timeout trước đó để truy vết.

## Artifact

- [Kết quả chính thức sau sửa selector](results/issue-156-2026-11-15-recheck.json).
- [Image ID và health đã kiểm chứng](results/issue-156-runtime-2026-11-15.json).
- [Lượt R2 trước sửa selector](results/issue-156-2026-11-15-r2.json): 2 passed / 3 timedOut, chưa dùng làm lỗi backend/frontend.
- [Bootstrap đầu và thao tác khôi phục được duyệt](results/issue-156-bootstrap-2026-11-15.json).
- [Báo cáo ngày 14/11](issue-156-playwright-2026-11-14.md) và bootstrap cũ giữ nguyên trạng thái NOT RUN, không sửa thành PASS.

Reporter chỉ ghi title/status, các bước API với method/path/status và metadata nghiệp vụ cho phép; không ghi password/token/header/body hoặc error raw. Không đọc Docker logs/environment secrets; trace/video/storageState/screenshot tự động tắt. HMAC key sinh mới trong process, chỉ cập nhật key/mapping fixture của DB QA riêng.

## Phụ thuộc và giới hạn nghiệm thu

- **SV04 @voxuanvy611-sys:** #73 (giỏ hàng), #75 (thanh toán), #80 (danh sách đơn), #81 (chi tiết/hoàn tất/hủy) còn OPEN; thiết kế #174/#176 còn OPEN. Lượt này đã xác nhận placeholder bằng browser thật, không chỉ đọc source.
- Đây là các chức năng chưa hoàn tất có issue phụ thuộc, không tự tạo bug trùng, sửa UI của SV04 hoặc bỏ skip để nhận PASS. Khi UI được duyệt/triển khai, QA phải viết tiếp thao tác checkout và complete thật.
- HMAC sender của test mô phỏng bên kênh nhưng gửi vào controller/backend thật; không dựng storefront hoặc mock-connector receiver. Có kiểm tra outbox, **chưa xác minh consumer/receiver bên kênh nhận trạng thái hoàn tất**. Không tuyên bố full SYNC-04 đầu cuối.
- Không kiểm tra mọi vai trò/chi nhánh, mọi phương thức thanh toán, hủy/hết hạn/refund, đa browser/mobile hoặc tải lớn trong #156 này. Build backend skip Maven tests không thay cho backend suite PASS.
- Guard **4/4 PASS**, node syntax và diff check PASS; test discovery ngày 14/11 không được cộng vào số runtime PASS ngày 15/11. Không thêm workflow CI cho suite còn BLOCKED.

## Trạng thái bàn giao

**Docker không còn là trở ngại ở lượt cuối; phần còn thiếu để nghiệm thu #156 là UI nghiệp vụ và kiểm chứng bổ sung theo phạm vi kênh.** QA vẫn để acceptance BLOCKED. PR QA dùng `Refs #156`, không tự đóng nhiệm vụ khi còn BLOCKED; cần review và CI cho commit xuất bản. Các QA cũ được duyệt vẫn dừng, stack QA156 r2 được giữ để đối chiếu.
