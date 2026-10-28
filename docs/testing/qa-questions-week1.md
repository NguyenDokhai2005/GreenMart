# Câu hỏi & Giả định cần xác nhận – QA Tuần 1

**Người hỏi:** SV08 – QA/Security (Huỳnh Phúc Đạt)  
**Ngày:** 27/10/2026  
**Gửi tới:** Lead (SV01), Backend (SV02, SV03), DevOps (SV07)

---

Trước khi viết đầy đủ test case, QA cần xác nhận các điểm sau để tránh viết test sai spec.
Mỗi điểm có giả định tạm thời; nếu giả định đúng thì không cần phản hồi thêm.

---

## 1. API Contract (cần SV01 xác nhận)

| # | Câu hỏi | Giả định tạm thời |
|---|---|---|
| Q-01 | Endpoint tạo đơn POS là `POST /api/v1/orders/pos` hay `POST /api/v1/orders`? | `POST /api/v1/orders/pos` |
| Q-02 | Response body của login có field `accessToken` hay `access_token`? | `accessToken` (camelCase theo SRS §8.2) |
| Q-03 | Header idempotency chính xác là `Idempotency-Key` hay `X-Idempotency-Key`? | `Idempotency-Key` |
| Q-04 | Webhook HMAC header là `X-Signature` với format `sha256=<hex>`? | Đúng theo SRS §8.3 |
| Q-05 | `openapi.yaml` bao giờ sẵn sàng để QA viết test khớp spec? | Tuần 2 (SV01 merge) |
| Q-06 | Khi bị 403, body có field `code` = `FORBIDDEN` không? | Có, theo định dạng lỗi SRS §8.2 |

---

## 2. Tồn kho đồng thời (cần SV03 xác nhận)

| # | Câu hỏi | Giả định tạm thời |
|---|---|---|
| Q-07 | Cơ chế chống tồn âm dùng `SELECT ... FOR UPDATE` hay `UPDATE ... WHERE available >= qty`? | `UPDATE ... WHERE available >= qty` (update có điều kiện, SRS §UC-09) |
| Q-08 | Khi tồn âm, lỗi trả về là `409` với code `INSUFFICIENT_STOCK` hay code khác? | `409` + `INSUFFICIENT_STOCK` |
| Q-09 | `CHECK constraint` trong DB có tên gì? Để QA verify trong migration SQL | `ck_inventory_non_negative` (giả định) |
| Q-10 | Đơn nhiều dòng cập nhật tồn theo thứ tự `product_id tăng dần` để tránh deadlock — đã confirm sẽ implement vậy? | Đúng theo SRS §5.3 |

---

## 3. Xác thực và bảo mật (cần SV02 xác nhận)

| # | Câu hỏi | Giả định tạm thời |
|---|---|---|
| Q-11 | Brute force lockout sau 5 lần sai — 15 phút hay cấu hình được? | 15 phút, cấu hình được qua biến môi trường |
| Q-12 | Khi tài khoản bị khóa, status code là `401` hay `429`? | `401` với message rõ "tài khoản bị khóa" |
| Q-13 | Nhật ký đăng nhập thất bại ghi vào `audit_log` với `action = LOGIN_FAILED`? | Đúng |
| Q-14 | `refresh_token` lưu ở đâu — DB hay Redis? Để QA verify không bị replay sau khi logout | Redis (giả định để đơn giản) |
| Q-15 | Sau khi `POST /auth/logout`, refresh token cũ có bị vô hiệu hóa ngay không? | Có |

---

## 4. Môi trường test (cần SV07 xác nhận)

| # | Câu hỏi | Giả định tạm thời |
|---|---|---|
| Q-16 | `docker-compose.yml` sẵn sàng ngày nào? QA cần để chạy test local | 03/11/2026 (deadline SV07) |
| Q-17 | Seed script (SV06) chạy bằng lệnh gì? | `./scripts/seed.sh` hoặc Maven/Gradle task |
| Q-18 | Staging URL sau khi SV07 setup CI/CD là gì? | Thông báo sau |
| Q-19 | Health check endpoint là `GET /actuator/health` (Spring Boot mặc định)? | Đúng |
| Q-20 | Database test dùng chung DB với dev hay có DB riêng? | DB riêng trên Docker Compose |

---

## 5. Quy tắc nghiệp vụ còn mơ hồ (cần SV01 xác nhận)

| # | Câu hỏi | Ảnh hưởng test case |
|---|---|---|
| Q-21 | BR-07: "hòa nhau → chọn KM hết hạn sớm hơn" — nếu `end_at` bằng nhau thì chọn theo `id` nhỏ hơn? | TC-POS-02 cần ví dụ số chính xác |
| Q-22 | Giới hạn lượt dùng KM (`usage_limit`) = NULL có nghĩa là không giới hạn? | Ảnh hưởng TC về KM |
| Q-23 | Đơn POS có thể có 0 dòng hàng không? Hay bắt buộc ≥ 1 dòng? | TC validation đơn POS |
| Q-24 | Khi `reserved_until` hết hạn mà job chưa chạy, tồn vẫn bị giữ — có endpoint manual expire để test không? | TC-EXP-01 cần mock thời gian hoặc endpoint dev |

---

## Ưu tiên trả lời

**Cần ngay (tuần 1):** Q-01, Q-02, Q-03, Q-04, Q-16  
**Cần trước tuần 2:** Q-05, Q-07, Q-08, Q-11, Q-12  
**Có thể trả lời tuần 3:** Q-21 đến Q-24

---

*Sẽ tạo Issues tương ứng nếu sau daily 28/10 chưa có phản hồi.*
