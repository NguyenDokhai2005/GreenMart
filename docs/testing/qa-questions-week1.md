# Câu hỏi & Giả định cần xác nhận – QA Tuần 1

- **Người hỏi:** SV08 – QA/Security (Huỳnh Phúc Đạt)
- **Ngày:** 27/10/2026
- **Gửi tới:** Lead (SV01), Backend (SV02, SV03), DevOps (SV07)

---

Trước khi viết đầy đủ test case, QA cần xác nhận các điểm sau để tránh viết test sai spec.
Mỗi điểm có giả định tạm thời; nếu giả định đúng thì không cần phản hồi thêm.

---

## 1. API Contract (cần SV01 xác nhận)

| # | Câu hỏi | Giả định tạm thời | Trạng thái |
|---|---|---|---|
| Q-01 | Endpoint tạo đơn POS là gì? | `POST /api/v1/orders/pos` | ✅ Xác nhận – openapi.yaml |
| Q-02 | Response body của login có field gì? | `accessToken`, `refreshToken`, `tokenType`, `expiresIn`, `user` | ✅ Xác nhận – `TokenResponse` schema |
| Q-03 | Header idempotency tên chính xác? | `Idempotency-Key` (UUID, bắt buộc) | ✅ Xác nhận – `IdempotencyKey` parameter |
| Q-04 | Webhook HMAC header là gì? | `X-Signature` format `sha256=<hex>` | ✅ Xác nhận – SRS §8.3, scope v2 |
| Q-05 | `openapi.yaml` bao giờ sẵn sàng? | Đã có v1 (27/10), v2 hạn 04/11 | ✅ Đã merge – `docs/api/openapi.yaml` |
| Q-06 | Khi bị 403 sai chi nhánh, code là gì? | `BRANCH_FORBIDDEN` (khác với `FORBIDDEN`) | ✅ Xác nhận – bảng mã lỗi openapi.yaml |

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

---

## 6. Module Identity – audit_log (cần SV02 xác nhận)

| # | Câu hỏi | Ảnh hưởng test case |
|---|---|---|
| Q-25 | Tên `action` ghi vào `audit_log` khi đăng nhập thất bại là gì? (`LOGIN_FAILED`?) | ID-080 |
| Q-26 | Tên `action` khi tài khoản bị khóa là gì? (`ACCOUNT_LOCKED`?) | ID-081 |
| Q-27 | Tên `action` khi truy cập sai chi nhánh là gì? (`BRANCH_FORBIDDEN`?) | ID-082 |
| Q-28 | Seed script module identity (user, branch) chạy bằng lệnh gì? Tên tài khoản demo và password? | ✅ Xác nhận từ #209 – chạy 2 bước:<br>1. `python forecast-service/scripts/generate_data.py`<br>2. `docker compose exec -T postgres psql -U greenmart -d greenmart -v ON_ERROR_STOP=1 < forecast-service/data/seed_data.sql`<br>Tài khoản: `admin`, `owner`, `quanly.gm001..003`, `thungan.gm001..003`, `thukho.gm001..003`; mật khẩu chung `Demo@123` |

**Tag:** @t-boai (SV02 – Backend Identity)

---

## 7. Kỹ thuật kiểm thử JWT (cần SV02 xác nhận)

| # | Câu hỏi | Ảnh hưởng test case |
|---|---|---|
| Q-29 | Refresh token lưu ở đâu (Redis, DB, hay in-memory)? Cách giả lập hết hạn 7 ngày khi test cục bộ? | TC hết hạn refresh token trong `auth-login-jwt-test-cases.md` (#211). Lưu ý: TC-AUTH01-12 trong PR #211 là "Refresh token sai/giả mạo", không phải TC hết hạn — TC hết hạn chưa có mã, đã yêu cầu bổ sung tại #211. |
| Q-30 | SV02 có implement `AuthenticationEntryPoint` riêng không? Nếu không thì 401 do Spring Security filter không có `traceId` theo định dạng `ErrorResponse` | TC-AUTH01-30, TC-AUTH01-35 |

**Tag:** @t-boai (SV02 – Backend Auth)
