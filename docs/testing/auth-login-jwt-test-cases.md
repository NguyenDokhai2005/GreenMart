# Test Cases – [AUTH-01] Login / Refresh JWT (Issue #145)

- **Phụ trách:** SV08 – QA/Security (Huỳnh Phúc Đạt)
- **Hạn:** 31/10/2026 · **Tuần:** 1
- **Tham chiếu:** AUTH-01, NFR-05, NFR-06, NFR-08, `openapi.yaml` v1, `ErrorCode.java`, `V1__init_schema.sql`
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

Seed dữ liệu do SV02 thực hiện. Tên tài khoản và password lấy từ example trong `openapi.yaml`:

| Tài khoản | Password | Nguồn |
|---|---|---|
| `thungan.gm001` | `Demo@123` | `openapi.yaml` – example của `/auth/login` |

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
```

---

## 1. Login – `POST /api/v1/auth/login`

**Từ openapi.yaml:**
- Request: `{username, password}` – cả hai bắt buộc
- Response 200: `TokenResponse` – `accessToken`, `refreshToken`, `tokenType=Bearer`, `expiresIn=900`, `user` (CurrentUser)
- Response 401: `INVALID_CREDENTIALS` (kèm `details.remainingAttempts`) hoặc `ACCOUNT_LOCKED` (kèm `details.lockedUntil`)
- Mô tả: "Sai 5 lần liên tiếp thì khóa tài khoản 15 phút"
- `security: []` – không cần token

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH01-01 | Login thành công | `thungan.gm001` tồn tại, `is_active=true` | `POST /auth/login` `{"username":"thungan.gm001","password":"Demo@123"}` | HTTP 200; `tokenType=Bearer`; `expiresIn=900`; `accessToken` không rỗng; `refreshToken` không rỗng; `user.id`, `user.username`, `user.fullName`, `user.role`, `user.branches` đều có mặt | Must |
| TC-AUTH01-02 | Response không chứa password | Sau TC-AUTH01-01 | Xem body response | Không có field `password`, `passwordHash` hay `password_hash` | Must |
| TC-AUTH01-03 | Sai password – `remainingAttempts` giảm | `failed_login_count=0`, password sai | `POST /auth/login` | HTTP 401 `INVALID_CREDENTIALS`; `details.remainingAttempts` có mặt; `traceId` có mặt (NFR-08) | Must |
| TC-AUTH01-04 | Khóa sau 5 lần sai | `failed_login_count=4`, password sai lần thứ 5 | `POST /auth/login` | HTTP 401 `ACCOUNT_LOCKED`; `details.lockedUntil` là ISO 8601 (theo example openapi: `2026-11-05T09:45:00+07:00`); cột `locked_until` trong DB được ghi | Must |
| TC-AUTH01-05 | Trong thời gian khóa – đúng password vẫn bị từ chối | `locked_until` chưa qua | `POST /auth/login` password đúng | HTTP 401 `ACCOUNT_LOCKED` | Must |
| TC-AUTH01-06 | Username không tồn tại – cùng thông báo với sai password | Username không có trong DB | `POST /auth/login` | HTTP 401 `INVALID_CREDENTIALS`; message giống hệt TC-AUTH01-03 (không lộ username có tồn tại hay không – NFR-06) | Must |
| TC-AUTH01-07 | `is_active=false` | Admin set `is_active=false` | `POST /auth/login` password đúng | HTTP 401 `INVALID_CREDENTIALS` _(dùng cùng code để không lộ lý do từ chối – NFR-06; `ErrorCode.java` không có `ACCOUNT_DISABLED`)_ | Must |
| TC-AUTH01-08 | Thiếu field `password` | Body `{"username":"thungan.gm001"}` | `POST /auth/login` | HTTP 400 `VALIDATION_ERROR`; `details` liệt kê field `password` | Must |
| TC-AUTH01-09 | Body không phải JSON | Body `not-json`, `Content-Type: application/json` | `POST /auth/login` | HTTP 400 `VALIDATION_ERROR`; response chỉ có `code`, `message`, `traceId`; không có stack trace (NFR-06) | Must |

---

## 2. Refresh token – `POST /api/v1/auth/refresh`

**Từ openapi.yaml:**
- Request: `{refreshToken}` – bắt buộc
- Response 200: `TokenResponse` mới
- Response 401: `INVALID_REFRESH_TOKEN`
- Mô tả: "Trả cặp token mới; refresh token cũ bị thu hồi (xoay vòng)"
- `security: []` – không cần `Authorization` header

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH01-10 | Refresh thành công | Refresh token còn hiệu lực | `POST /auth/refresh` `{"refreshToken":"..."}` | HTTP 200; `TokenResponse` mới; `accessToken` và `refreshToken` khác token cũ | Must |
| TC-AUTH01-11 | Refresh token cũ bị thu hồi sau khi xoay | Đã gọi TC-AUTH01-10 | Gửi lại refresh token cũ | HTTP 401 `INVALID_REFRESH_TOKEN` | Must |
| TC-AUTH01-12 | Refresh token sai / giả mạo | Chuỗi không hợp lệ | `POST /auth/refresh` | HTTP 401 `INVALID_REFRESH_TOKEN` | Must |
| TC-AUTH01-13 | Thiếu field `refreshToken` | Body `{}` | `POST /auth/refresh` | HTTP 400 `VALIDATION_ERROR` | Must |

---

## 3. Logout – `POST /api/v1/auth/logout`

**Từ openapi.yaml:**
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

## 4. Bảo vệ JWT – `GET /auth/me` và các endpoint protected

**Từ openapi.yaml:** Tất cả endpoint không có `security: []` đều yêu cầu `bearerAuth`.
**Từ SRS NFR-06:** "không lộ stack trace". **Từ NFR-08:** "log JSON có correlation ID".

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH01-30 | Không có header `Authorization` | Không có header | `GET /auth/me` | HTTP 401 `UNAUTHORIZED`; `traceId` có mặt | Must |
| TC-AUTH01-31 | Access token hết hạn | JWT `exp` đã qua (sau 15 phút theo SRS AUTH-01) | `GET /auth/me` | HTTP 401 `UNAUTHORIZED` | Must |
| TC-AUTH01-32 | JWT ký bằng secret sai | Token tạo với secret khác | `GET /auth/me` | HTTP 401 `UNAUTHORIZED` | Must |
| TC-AUTH01-33 | JWT `alg:none` – tấn công bỏ qua chữ ký | `NONE_TOKEN="$(printf '{"alg":"none"}' \| base64 \| tr -d '=').$(printf '{"sub":"1"}' \| base64 \| tr -d '=')."` | `GET /auth/me` với `Authorization: Bearer $NONE_TOKEN` | HTTP 401 `UNAUTHORIZED` | Must |
| TC-AUTH01-34 | JWT `alg:none` gửi làm refreshToken | Như trên nhưng gửi vào body `refreshToken` | `POST /auth/refresh` `{"refreshToken":"$NONE_TOKEN"}` | HTTP 401 `INVALID_REFRESH_TOKEN` | Must |
| TC-AUTH01-35 | Response lỗi không lộ stack trace, có `traceId` | Gửi request gây lỗi | Bất kỳ endpoint trả lỗi | Body chỉ có `code`, `message`, `traceId`; không có `at vn.greenmart`; `traceId` không rỗng (NFR-06, NFR-08) | Must |

---

*Tổng: **26 test case** – chạy được sau khi SV02 implement module auth và seed tài khoản `thungan.gm001` theo example trong `openapi.yaml`.*
