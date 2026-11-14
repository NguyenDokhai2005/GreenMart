# Kiểm thử lại #153 — 14/11/2026

## Phạm vi và môi trường

- Nhiệm vụ QA/Security của Huynh-Phuc-Dat: EX-05, EX-06, EX-10 theo BA mục 11; đối chiếu BA mục 7 và OpenAPI hiện tại.
- Đã cập nhật `develop` bằng `git pull --ff-only origin develop`. Commit GitHub/local dùng kiểm thử: `c25ae39477f8b2de72b2f022bbfda03c00d28073`; nhánh QA `feature/153-recheck-20261114`.
- Docker riêng `greenmart-qa153-20261114`, API `http://127.0.0.1:18161/api/v1`, PostgreSQL thật, Redis và RabbitMQ. Cả bốn container healthy sau lượt chạy.
- Backend build từ source trên: `greenmart-backend:qa153-c25ae39`; image ID `sha256:3a89d5fa9bf8e8e217d35944eb0f47f498a56edd552d2b3059abe94420f8b7a4`, OCI revision khớp commit.
- Đọc issue #153 và các phụ thuộc #38–#42, #320 cùng bình luận quyết định. Đối chiếu tài liệu quy trình, nghiệp vụ, API và QA tại cùng baseline. Không thay đổi expected result để làm kiểm thử xanh.

## Kết quả thực tế

**41 kiểm tra: 30 PASS, 7 FAIL, 4 BLOCKED; lượt chạy không gián đoạn.** Đây là số kiểm tra kỹ thuật, không phải 41 acceptance criteria độc lập. Bảy FAIL thuộc hai nhóm vấn đề đã có issue, không phải bảy lỗi độc lập.

Bằng chứng: [JSON kết quả](results/issue-153-2026-11-14.json). Fixture riêng `QA153-46821E8A`.

### Khuyến mãi và EX-10 — phụ thuộc #39, SV02 @t-boai

Đã xác nhận campaign ACTIVE, còn hiệu lực, đúng chi nhánh 1/kênh POS, chưa dùng lượt. Campaign 10% có ngưỡng 200.000, trần giảm 30.000; campaign giảm 25.000 có ngưỡng 300.000.

| Trường hợp | Tổng mong đợi | Tổng thực tế | Kết quả |
|---|---:|---:|---|
| Đơn 180.000 dưới ngưỡng | 180.000 | 180.000 | PASS |
| Đơn 223.000, giảm 10% | 200.700 | 223.000 | FAIL |
| Đơn 260.000, giảm 10% | 234.000 | 260.000 | FAIL |
| Đơn 350.000, chọn giảm 30.000 | 320.000 | 350.000 | FAIL |
| Đơn 250.000, hai mức giảm bằng nhau | 225.000 | 250.000 | FAIL |
| Quote 1, campaign còn đúng một lượt, đơn 100.000 | 90.000 | 100.000 | FAIL |
| Quote 2, campaign còn đúng một lượt, đơn 100.000 | 90.000 | 100.000 | FAIL |

Trường hợp bằng mức giảm điều chỉnh ngưỡng campaign 25.000 xuống 200.000, kỳ vọng chọn campaign hết hạn sớm hơn. Thực tế mọi quote trên đều không có orderPromotion và giảm 0.

EX-10 tranh chấp lượt cuối **BLOCKED**: chưa tạo được hai quote có khuyến mãi hợp lệ; không gửi expectedTotal giả để mô phỏng một lượt đua đã thành công. Đọc code thấy hai phương thức đánh giá khuyến mãi trong `DefaultPromotionCalculator` trả null, phù hợp biểu hiện runtime; phần này chưa được QA sửa.

Các control không giảm khi sai kênh, PAUSED, hết lượt hoặc hết hạn campaign trả đúng tổng; quote không tăng lượt dùng/không tạo đơn. Tuy nhiên các control âm này chưa chứng minh đầy đủ logic lọc hoạt động khi control dương vẫn thất bại.

### EX-05 — phụ thuộc #41/#320, SV02 @t-boai

1. Tạo đơn ONLINE thật qua API WEB với chữ ký HMAC hợp lệ: HTTP 202, đơn `10202`, trạng thái RESERVED — PASS.
2. Deadline ban đầu khoảng 900 giây; giữ 3 sản phẩm, onHand vẫn 19, reserved từ 0 lên 3 — PASS.
3. Chỉ đưa `reserved_until` của đơn QA này về quá khứ. Không ép trạng thái EXPIRED hoặc tự giải phóng tồn kho. Quan sát scheduler hơn 70 giây, vượt cửa sổ xử lý một phút của yêu cầu.
4. Thực tế vẫn RESERVED, reserved=3, available=16, onHand=19; ledger RELEASE=0, sự kiện hết hạn=0 — **FAIL**.

Đây là kiểm thử tăng tốc deadline bằng fixture SQL, không phải chờ thực tế đủ 15 phút. Bước kênh nhận lại lượng tồn **BLOCKED** vì release chưa xảy ra; không tuyên bố đã kiểm tra thành công đồng bộ kênh đầu cuối. Vấn đề đã nằm trong #320, tránh tạo issue trùng.

### EX-06 — phụ thuộc #40/#41/#320

- PAID đến sau hết hạn, đủ tồn → CONFIRMED: **BLOCKED**.
- PAID đến sau hết hạn, thiếu tồn → CANCELLED/payment REFUNDED: **BLOCKED**.

Chưa có tiền điều kiện EXPIRED thật. Probe tuyến events với HMAC hợp lệ nhận 401 khi không Bearer và 404 khi dùng OWNER hợp lệ; đây chỉ là chẩn đoán tuyến API chưa khả dụng, không phải bằng chứng đã kiểm thử luồng thanh toán/refund. Không sửa SQL để giả trạng thái EXPIRED hoặc REFUNDED.

### Những phần PASS khác

- Đăng nhập bốn vai trò OWNER, BRANCH_MANAGER, CASHIER, STOREKEEPER và logout cả bốn phiên HTTP 204.
- Tạo bảy sản phẩm QA; nhập kho và duyệt nhập qua API thật.
- Quote không campaign và hoàn tất đơn POS control thành công trước khi cấu hình campaign.
- Chuẩn bị/đọc lại điều kiện campaign và kiểm tra quote không tạo side effect.

## Thay đổi QA và cách chạy

- `tests/api/issue153_edges.py`: kiểm tra baseline/image; HMAC thật; tạo đơn ONLINE; quan sát expiry/release; giữ nguyên phân biệt FAIL/BLOCKED và bảo vệ file bằng chứng.
- `tests/api/run_issue153_qa.py`: dựng môi trường mới riêng, credential ngẫu nhiên trong process, seed chính thức; từ chối tái dùng project/volume đã tồn tại.
- `tests/api/test_issue153_safety.py`: ba test bảo vệ không ghi đè bằng chứng và dừng trước HTTP/SQL khi image hoặc revision sai — **3/3 PASS**.
- `py_compile` cả ba file và `git diff --check` thành công. Build backend bỏ qua Maven test; không nhận đây là kết quả unit/integration suite backend.

Helper cần biến môi trường `GREENMART_DEMO_PASSWORD` với mật khẩu demo theo quy trình nhóm. Chạy `python tests/api/run_issue153_qa.py` chỉ trên project/port/report mới chưa tồn tại. Có thể đặt `QA153_PROJECT`, `QA153_PORT`, `QA153_REPORT` để tạo lượt độc lập. Không chạy lại vào stack hiện tại để seed hoặc ghi đè bằng chứng. Runner trả exit 1 khi có FAIL/BLOCKED là chủ đích, không che lỗi để CI xanh.

Campaign/mapping và deadline fixture dùng SQL trong DB QA riêng do endpoint quản trị còn thiếu. WEB signing key được tạo mới cho QA, không đọc hoặc xuất secret cũ; không lưu credential/token/chữ ký trong báo cáo. Không sửa source backend/frontend/seed, contract, migration hoặc Compose dùng chung. Giữ nguyên dữ liệu QA cuối lượt (3 đơn vị còn reserved) làm bằng chứng; không reset volume hay dừng stack khác.

## Bàn giao và phần còn chờ

- SV02 xử lý #39 và #41/#320; QA chạy lại EX-10 và EX-06 khi tiền điều kiện/backend sẵn sàng. Phần tích hợp kênh cần phối hợp người phụ trách integration khi luồng Sales đã phát sinh release/event thật.
- Đã kiểm tra lại issue mở/đóng trên GitHub trước khi xuất bản: lỗi khuyến mãi đã được Lead ghi cụ thể trong #39 (bình luận ngày 13/11); expiry và tuyến events đã nằm trong bug #320. Cả hai OPEN, giao @t-boai. Không tạo báo lỗi trùng; PR QA dẫn lại #39/#41/#320 để Lead theo dõi.
- #153 **chưa hoàn tất acceptance criteria**. Người dùng đã cho phép commit/push/tạo PR cho phần QA; PR chỉ ghi `Refs #153`, không tự đóng nhiệm vụ khi còn FAIL/BLOCKED. Kết quả CI remote theo trạng thái PR, không suy ra từ test local. Báo cáo và bằng chứng ngày 13/11 được giữ nguyên, không ghi đè.
