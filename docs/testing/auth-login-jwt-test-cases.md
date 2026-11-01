# Test Cases – [AUTH-01] Login / Refresh JWT (Issue #145)

- **Phụ trách:** SV08 – QA/Security (Huỳnh Phúc Đạt)
- **Hạn:** 31/10/2026 · **Tuần:** 1
- **Nhãn:** `security`, `test`, `milestone-week1`
- **Tham chiếu:** AUTH-01, NFR-05, NFR-06, NFR-08, `openapi.yaml` 2.0.0, `ErrorCode.java`, `V1__init_schema.sql`
- **Phạm vi:** `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `GET /auth/me`

---

## Nguồn đối chiếu

| File | Lấy gì |
|---|---|
| `docs/api/openapi.yaml` | Endpoint, request/response schema, error code, example username/password, `security: []` hay không |
| `backend/src/main/java/.../ErrorCode.java` | Tên error code thật, HTTP status tương ứng |
| `backend/src/main/resources/db/migration/V1__init_schema.sql` | Cột `failed_login_count`, `locked_until` của `app_user`; `audit_log` có cột `action` |
| `docs/requirements/SRS.md` – mục AUTH-01, NFR-05, NFR-06, NFR-08 | Thời hạn token, BCrypt, không lộ stack trace, correlation ID |

---

## Tiền điều kiện

Seed dữ liệu do SV02 thực hiện. Tên tài khoản và password lấy từ example trong `openapi.yaml` 2.0.0:

| Tài khoản | Password | Nguồn |
|---|---|---|
| `thungan.gm001` | `Demo@123` | `openapi.yaml` – example của `/auth/login` |
| `owner` | `Demo@123` | seed #209 |

Sau khi seed, kiểm tra BCrypt (NFR-05):

```bash
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "SELECT username, LEFT(password_hash, 7) AS prefix FROM app_user;"
# Mọi dòng phải bắt đầu $2a$ hoặc $2b$
```

---

## Lệnh kiểm tra nhanh (đính kèm khi đóng issue #145)

```bash
# 1. Login – kiểm tra TokenResponse
RESP=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"thungan.gm001","password":"Demo@123"}')
echo $RESP | jq '{tokenType: .tokenType, expiresIn: .expiresIn, role: .user.role}'
# → tokenType=Bearer, expiresIn=900

# 2. GET /auth/me với access token
ACCESS=$(echo $RESP | jq -r .accessToken)
curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $ACCESS" \
  http://localhost:8080/api/v1/auth/me
# → 200

# 3. Refresh token – nhận token mới
REFRESH=$(echo $RESP | jq -r .refreshToken)
NEW_RESP=$(curl -s -X POST http://localhost:8080/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH\"}")
echo $NEW_RESP | jq '.expiresIn'
# → 900

# 4. Refresh token CŨ phải bị thu hồi (openapi.yaml: "refresh token cũ bị thu hồi (xoay vòng)")
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH\"}"
# → 401

# 5. Logout
NEW_ACCESS=$(echo $NEW_RESP | jq -r .accessToken)
NEW_REFRESH=$(echo $NEW_RESP | jq -r .refreshToken)
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/api/v1/auth/logout \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $NEW_ACCESS" \
  -d "{\"refreshToken\":\"$NEW_REFRESH\"}"
# → 204

# 6. Mở khóa sau 15 phút – tua locked_until về quá khứ
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "UPDATE app_user SET locked_until = NOW() - INTERVAL '1 minute' WHERE username = 'thungan.gm001';"
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"thungan.gm001","password":"Demo@123"}'
# → 200
```

---

## 1. Login – `POST /api/v1/auth/login`

**Từ openapi.yaml 2.0.0:**
- Request: `{username, password}` – cả hai bắt buộc
- Response 200: `TokenResponse` – `accessToken`, `refreshToken`, `tokenType=Bearer`, `expiresIn=900`, `user` (CurrentUser)
- Response 401: `INVALID_CREDENTIALS` (kèm `details.remainingAttempts`) hoặc `ACCOUNT_LOCKED` (kèm `details.lockedUntil`)
- Mô tả: "Sai 5 lần liên tiếp thì khóa tài khoản 15 phút"
- `security: []` – không cần token

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH01-01 | Login thành công | `thungan.gm001` tồn tại, `is_active=true` | `POST /auth/login` `{"username":"thungan.gm001","password":"Demo@123"}` | HTTP 200; `tokenType=Bearer`; `expiresIn=900`; `accessToken` không rỗng; `refreshToken` không rỗng; `user.id`, `user.username`, `user.fullName`, `user.role`, `user.branches` đều có mặt | Must |
| TC-AUTH01-02 | Response không chứa password | Sau TC-AUTH01-01 | Xem body response | Không có field `password`, `passwordHash` hay `password_hash` | Must |
| TC-AUTH01-03 | Sai password – `remainingAttempts` = 4 | `failed_login_count=0`, password sai lần đầu | `POST /auth/login` | HTTP 401 `INVALID_CREDENTIALS`; `details.remainingAttempts=4`; `traceId` có mặt (NFR-08) | Must |
| TC-AUTH01-04 | Khóa sau 5 lần sai | `failed_login_count=4`, password sai lần thứ 5 | `POST /auth/login` | HTTP 401 `ACCOUNT_LOCKED`; `details.lockedUntil` là ISO 8601 (theo example openapi: `2026-11-05T09:45:00+07:00`); cột `locked_until` trong DB được ghi | Must |
| TC-AUTH01-05 | Mở khóa sau 15 phút | Tài khoản đang bị khóa; tua `locked_until` về quá khứ bằng SQL: `UPDATE app_user SET locked_until = NOW() - INTERVAL '1 minute' WHERE username='thungan.gm001'` | `POST /auth/login` password đúng | HTTP 200; `failed_login_count` về 0 | Must |
| TC-AUTH01-06 | Trong thời gian khóa – đúng password vẫn bị từ chối | `locked_until` chưa qua | `POST /auth/login` password đúng | HTTP 401 `ACCOUNT_LOCKED` | Must |
| TC-AUTH01-07 | Username không tồn tại – cùng thông báo với sai password | Username không có trong DB | `POST /auth/login` | HTTP 401 `INVALID_CREDENTIALS`; message giống hệt TC-AUTH01-03 (không lộ username có tồn tại hay không – NFR-06) | Must |
| TC-AUTH01-08 | `is_active=false` | Admin set `is_active=false` | `POST /auth/login` password đúng | HTTP 401 `INVALID_CREDENTIALS` _(dùng cùng code để không lộ lý do từ chối – NFR-06; `ErrorCode.java` không có `ACCOUNT_DISABLED`)_ | Must |
| TC-AUTH01-09 | Thiếu field `password` | Body `{"username":"thungan.gm001"}` | `POST /auth/login` | HTTP 400 `VALIDATION_ERROR`; `details` liệt kê field `password` | Must |
| TC-AUTH01-10 | Body không phải JSON | Body `not-json`, `Content-Type: application/json` | `POST /auth/login` | HTTP 400 `VALIDATION_ERROR`; body có `code`, `message`, `traceId`; không có stack trace (NFR-06) | Must |

---

## 2. Refresh token – `POST /api/v1/auth/refresh`

**Từ openapi.yaml 2.0.0:**
- Request: `{refreshToken}` – bắt buộc
- Response 200: `TokenResponse` mới
- Response 401: `INVALID_REFRESH_TOKEN`
- Mô tả: "Trả cặp token mới; refresh token cũ bị thu hồi (xoay vòng)"
- `security: []` – không cần `Authorization` header

> **Câu hỏi mở Q-29** (xem `docs/testing/qa-questions-week1.md` trong PR #204):
> Refresh token lưu ở đâu (Redis, DB, hay in-memory)? Cách giả lập hết hạn 7 ngày khi test cục bộ?
> TC-AUTH01-15 phụ thuộc câu trả lời này. Q-29 trong #204 đang trỏ TC-AUTH01-12 (sai) —
> cần sửa thành TC-AUTH01-15 sau khi #211 merge.

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH01-11 | Refresh thành công | Refresh token còn hiệu lực | `POST /auth/refresh` `{"refreshToken":"..."}` | HTTP 200; `TokenResponse` mới; `accessToken` và `refreshToken` khác token cũ | Must |
| TC-AUTH01-12 | Refresh token cũ bị thu hồi sau khi xoay | Đã gọi TC-AUTH01-11 | Gửi lại refresh token cũ | HTTP 401 `INVALID_REFRESH_TOKEN` | Must |
| TC-AUTH01-13 | Refresh token sai / giả mạo | Chuỗi không hợp lệ | `POST /auth/refresh` | HTTP 401 `INVALID_REFRESH_TOKEN` | Must |
| TC-AUTH01-14 | Thiếu field `refreshToken` | Body `{}` | `POST /auth/refresh` | HTTP 400 `VALIDATION_ERROR` | Must |
| TC-AUTH01-15 | Refresh token hết hạn 7 ngày | Refresh token đã qua 7 ngày _(cần SV02 xác nhận cơ chế giả lập – Q-29; có thể dùng Redis TTL hoặc cập nhật trực tiếp thời gian hết hạn trong DB)_ | `POST /auth/refresh` | HTTP 401 `INVALID_REFRESH_TOKEN` | Must |

---

## 3. Logout – `POST /api/v1/auth/logout`

**Từ openapi.yaml 2.0.0:**
- Request: `{refreshToken}` – bắt buộc
- Response 204: thành công
- Response 401: thiếu/hết hạn token
- Mô tả: "Thu hồi refresh token. Access token hết hạn tự nhiên sau tối đa 15 phút."
- `security: bearerAuth` – cần `Authorization: Bearer <accessToken>` hợp lệ

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH01-20 | Logout thành công | Access token hợp lệ, refresh token hợp lệ | `POST /auth/logout` + header `Authorization` + body `{"refreshToken":"..."}` | HTTP 204 | Must |
| TC-AUTH01-21 | Refresh token sau logout không dùng lại được | Đã logout | Gửi lại refresh token vừa logout vào `POST /auth/refresh` | HTTP 401 `INVALID_REFRESH_TOKEN` | Must |
| TC-AUTH01-22 | Logout khi access token hết hạn | JWT `exp` đã qua | `POST /auth/logout` với access token hết hạn | HTTP 401 `UNAUTHORIZED` | Must |

---

## 4. Bảo vệ JWT – endpoint protected

**Từ openapi.yaml 2.0.0:** Tất cả endpoint không có `security: []` đều yêu cầu `bearerAuth`.
**Từ SRS NFR-06:** "không lộ stack trace". **Từ NFR-08:** "log JSON có correlation ID".

> **Câu hỏi mở Q-30** (xem `docs/testing/qa-questions-week1.md` trong PR #204):
> SV02 có implement `AuthenticationEntryPoint` riêng không? Nếu không thì 401 do Spring Security
> filter không có `traceId` theo định dạng `ErrorResponse`.
> TC-AUTH01-30 và TC-AUTH01-35 phụ thuộc vào câu trả lời này.

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH01-30 | Không có header `Authorization` | Không có header | `GET /auth/me` | HTTP 401 `UNAUTHORIZED`; `traceId` có mặt | Must |
| TC-AUTH01-31 | Access token hết hạn | JWT `exp` đã qua (sau 15 phút theo SRS AUTH-01) | `GET /auth/me` | HTTP 401 `UNAUTHORIZED` | Must |
| TC-AUTH01-32 | JWT ký bằng secret sai | Token tạo với secret khác | `GET /auth/me` | HTTP 401 `UNAUTHORIZED` | Must |
| TC-AUTH01-33 | JWT `alg:none` – tấn công bỏ qua chữ ký | `NONE_TOKEN="$(printf '{"alg":"none"}' \| base64 \| tr -d '=').$(printf '{"sub":"1"}' \| base64 \| tr -d '=')."` | `GET /auth/me` với `Authorization: Bearer $NONE_TOKEN` | HTTP 401 `UNAUTHORIZED` | Must |
| TC-AUTH01-34 | JWT `alg:none` gửi làm refreshToken | Như trên nhưng gửi vào body `refreshToken` | `POST /auth/refresh` `{"refreshToken":"$NONE_TOKEN"}` | HTTP 401 `INVALID_REFRESH_TOKEN` | Must |
| TC-AUTH01-35 | Response lỗi không lộ stack trace, có `traceId` | Gửi request gây lỗi | Bất kỳ endpoint trả lỗi | Body chỉ có các trường của `ErrorResponse` (`code`, `message`, `details` nếu có, `traceId`); không có `at vn.greenmart`, `exception`, `trace`; `traceId` không rỗng (NFR-06, NFR-08) | Must |

---

## 5. `GET /auth/me` – thông tin người dùng hiện tại

**Từ openapi.yaml 2.0.0:** Trả `CurrentUser`: `id`, `username`, `fullName`, `role`, `branches`.
ADMIN và OWNER nhận mọi chi nhánh đang hoạt động.

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH01-40 | CASHIER chỉ thấy chi nhánh được gán | `thungan.gm001` gán CN1 | `GET /auth/me` | HTTP 200; `role=CASHIER`; `branches` có đúng 1 phần tử (CN1) | Must |
| TC-AUTH01-41 | OWNER thấy mọi chi nhánh | `owner` (OWNER) | `GET /auth/me` | HTTP 200; `role=OWNER`; `branches` có 3 phần tử (CN1, CN2, CN3) | Must |

---

*Tổng: **26 test case** (TC-AUTH01-01..10: 10, TC-AUTH01-11..15: 5, TC-AUTH01-20..22: 3, TC-AUTH01-30..35: 6, TC-AUTH01-40..41: 2).*

*Lưu ý Q-29 trong `qa-questions-week1.md` (PR #204) đang trỏ sai TC-AUTH01-12 cho "refresh token hết hạn" — cần sửa thành **TC-AUTH01-15** sau khi #211 merge vào `develop`.*
