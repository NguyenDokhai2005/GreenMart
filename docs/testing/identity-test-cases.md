# Test Cases – Module Identity (Issue #144)

- **Phụ trách:** SV08 – QA/Security (Huỳnh Phúc Đạt)
- **Hạn:** 30/10/2026 · **Tuần:** 1
- **Tham chiếu:** AUTH-01..04, NFR-05, NFR-06, BR-11, `openapi.yaml` v1, `V1__init_schema.sql`
- **Phạm vi:** bảng `app_user`, `user_branch`, `branch`, BCrypt, JWT, phân quyền chi nhánh

---

## Tiền điều kiện – Dữ liệu cần có trước khi chạy test

> Seed chi nhánh và user thuộc **SV02 (module identity)**.
> Tất cả test case chỉ chạy được sau khi SV02 tạo seed script và `docker compose up` thành công.
> Tên tài khoản demo xác nhận từ `forecast-service/scripts/generate_data.py` (issue #209).

**Nạp dữ liệu seed vào DB (chạy 2 bước theo thứ tự):**

```bash
# Bước 1 – Sinh file SQL từ generate_data.py
python forecast-service/scripts/generate_data.py

# Bước 2 – Nạp vào PostgreSQL
docker compose exec -T postgres psql -U greenmart -d greenmart -v ON_ERROR_STOP=1 \
  < forecast-service/data/seed_data.sql
```

| Tài khoản | Role | Chi nhánh được gán |
|---|---|---|
| `thungan.gm001` / `Demo@123` | CASHIER | CN1 (branch_id=1) |
| `quanly.gm001` / `Demo@123` | BRANCH_MANAGER | CN1 (branch_id=1) |
| `owner` / `Demo@123` | OWNER | tất cả (branch_id=1,2,3) |
| `admin` / `Demo@123` | ADMIN | tất cả (branch_id=1,2,3) |
| `thukho.gm001` / `Demo@123` | STOREKEEPER | CN1 (branch_id=1) |

Chi nhánh cần có: 3 chi nhánh với `active = true`.

---

## Lệnh kiểm tra nhanh (đính kèm khi đóng issue #144)

```bash
# 1. Xác nhận 3 chi nhánh tồn tại
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "SELECT id, code, name FROM branch ORDER BY id;"

# 2. Xác nhận BCrypt – mọi hash phải bắt đầu $2a$ hoặc $2b$
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "SELECT username, LEFT(password_hash, 7) AS hash_prefix FROM app_user;"

# 3. Kiểm tra phân quyền chi nhánh – phải trả 403
CASHIER_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"thungan.gm001","password":"Demo@123"}' | jq -r .accessToken)

curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $CASHIER_TOKEN" \
  "http://localhost:8080/api/v1/orders?branchId=2"
# → phải là 403

# 4. Kiểm tra JWT algorithm none – phải trả 401
NONE_TOKEN="$(printf '{"alg":"none"}' | base64 | tr -d '=').$(printf '{"sub":"1"}' | base64 | tr -d '=')."
curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $NONE_TOKEN" \
  http://localhost:8080/api/v1/auth/me
# → phải là 401

# 5. Kiểm tra audit_log ghi thao tác nhạy cảm
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "SELECT action, detail, created_at FROM audit_log ORDER BY created_at DESC LIMIT 5;"
```

---

## 1–4 và TC JWT trong mục 5: Xem `auth-login-jwt-test-cases.md`

Toàn bộ test case cho đăng nhập, refresh token, logout, `GET /auth/me` và bảo vệ JWT (TC-AUTH01-01..35) đã được tập trung tại `docs/testing/auth-login-jwt-test-cases.md` (issue #145) để tránh trùng lặp.

Các TC tương đương:

| Nhóm | Xem tại `auth-login-jwt-test-cases.md` |
|---|---|
| Đăng nhập | TC-AUTH01-01..09 |
| Refresh token | TC-AUTH01-10..13 |
| Logout | TC-AUTH01-20..22 |
| `GET /auth/me` | TC-AUTH01-01 (role/branches), TC-AUTH01-30..31 (401) |
| JWT giả mạo / `alg:none` | TC-AUTH01-32..35 |

---

## 5. Phân quyền theo chi nhánh (BR-11, AUTH-02)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| ID-040 | CASHIER CN1 không xem đơn CN2 | `thungan.gm001` chỉ có CN1 trong `user_branch` | `GET /api/v1/orders?branchId=2` | HTTP 403 `BRANCH_FORBIDDEN`; `details.branchId=2` | Must |
| ID-041 | CASHIER CN1 không tạo đơn tại CN2 | `thungan.gm001` gán CN1 | `POST /api/v1/orders/pos` body `branchId=2` | HTTP 403 `BRANCH_FORBIDDEN` | Must |
| ID-042 | CASHIER không xem tồn CN khác | `thungan.gm001` gán CN1 | `GET /api/v1/inventory/2/{productId}` | HTTP 403 `BRANCH_FORBIDDEN` | Must |
| ID-043 | ADMIN xem tồn mọi chi nhánh | `admin` (ADMIN, branch_ids=1,2,3 theo `generate_data.py`) | `GET /api/v1/inventory?branchId=2` | HTTP 200 | Must |
| ID-044 | OWNER xem mọi chi nhánh | `owner` (OWNER, branch_ids=1,2,3 theo `generate_data.py`) | `GET /api/v1/orders` không truyền `branchId` | HTTP 200; thấy đơn mọi chi nhánh | Must |
| ID-045 | CASHIER không đổi giá sản phẩm | `thungan.gm001` (CASHIER) | `PUT /api/v1/products/{productId}` với `salePrice` mới | HTTP 403 `FORBIDDEN` | Must |
| ID-046 | Gọi API không có token | Không có header `Authorization` | Bất kỳ protected endpoint | HTTP 401 `UNAUTHORIZED` – xem chi tiết tại TC-AUTH01-30 trong `auth-login-jwt-test-cases.md` | Must |

> **TC JWT giả mạo** (JWT secret sai, `alg:none`): xem TC-AUTH01-32..34 trong `auth-login-jwt-test-cases.md`.

---

## 6. BCrypt password hashing (NFR-05)

| ID | Tiêu đề | Cách kiểm tra | Kết quả mong đợi | Mức độ |
|---|---|---|---|---|
| ID-050 | Password lưu dạng BCrypt | `SELECT LEFT(password_hash,7) FROM app_user` | Mọi dòng bắt đầu `$2a$` hoặc `$2b$` (BCrypt cost ≥ 10) | Must |
| ID-051 | Không có plain text trong DB | Xem toàn bộ cột `app_user` | Không có cột nào chứa password rõ | Must |
| ID-052 | Cùng password → hash khác nhau | 2 user cùng `Demo@123` | `password_hash` của 2 user khác nhau (BCrypt salt ngẫu nhiên) | Must |
| ID-053 | API không trả password hash | `GET /auth/me`, `POST /auth/login` response | Không có field `password` hay `passwordHash` trong JSON | Must |

---

## 7. Đổi mật khẩu – `POST /api/v1/auth/change-password`

`ChangePasswordRequest` schema (openapi.yaml): `currentPassword`, `newPassword` (`minLength: 8`, `maxLength: 100`).
Mô tả endpoint: **"Mật khẩu mới tối thiểu 8 ký tự, có chữ và số."**

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| ID-060 | Đổi thành công | Đang đăng nhập `thungan.gm001` | `POST /auth/change-password` `{"currentPassword":"Demo@123","newPassword":"NewPass8"}` | HTTP 204; mọi refresh token bị thu hồi | Must |
| ID-061 | Đăng nhập lại với password mới | Sau ID-060 | `POST /auth/login` `{"username":"thungan.gm001","password":"NewPass8"}` | HTTP 200 | Must |
| ID-062 | Password cũ không còn dùng được | Sau ID-060 | `POST /auth/login` với `Demo@123` | HTTP 401 `INVALID_CREDENTIALS` | Must |
| ID-063 | currentPassword sai | Nhập sai `currentPassword` | `POST /auth/change-password` | HTTP 422 `WRONG_PASSWORD` | Must |
| ID-064 | Password mới < 8 ký tự | `newPassword="abc123"` (6 ký tự) | `POST /auth/change-password` | HTTP 400 `VALIDATION_ERROR`; `details` liệt kê field `newPassword` | Must |
| ID-065 | Password mới chỉ có chữ, không có số | `newPassword="abcdefgh"` (8 ký tự, không có số) | `POST /auth/change-password` | HTTP 400 `VALIDATION_ERROR` | Must |
| ID-066 | Password mới chỉ có số, không có chữ | `newPassword="12345678"` (8 ký tự, không có chữ) | `POST /auth/change-password` | HTTP 400 `VALIDATION_ERROR` | Must |

---

## 8. Chi nhánh – `GET /api/v1/branches`

`Branch` schema (openapi.yaml): `id`, `code`, `name`, `address`, **`active`** (boolean).

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| ID-070 | Seed đủ 3 chi nhánh | Seed script đã chạy | `GET /api/v1/branches` với ADMIN token | HTTP 200; array có 3 phần tử; mỗi phần tử có `id`, `code`, `name`, **`active=true`** | Must |
| ID-071 | CASHIER chỉ thấy chi nhánh mình | `thungan.gm001` gán CN1 | `GET /api/v1/branches` | HTTP 200; array có 1 phần tử (CN1) | Must |
| ID-072 | Lọc `active=false` | Tồn tại chi nhánh bị vô hiệu hóa | `GET /api/v1/branches?active=false` với ADMIN | Chỉ trả chi nhánh có `active=false` | Must |

---

## 9. Nhật ký thao tác nhạy cảm – bảng `audit_log` (AUTH-03)

> ⚠️ **Câu hỏi mở Q-25..28** (xem `docs/testing/qa-questions-week1.md`):
> Tên `action` chính xác chưa có trong SRS hay openapi.yaml — cần SV02 xác nhận trước khi chạy test.
> Giá trị dưới đây là giả định tạm thời.

| ID | Tiêu đề | Hành động | Kiểm tra DB | Mức độ |
|---|---|---|---|---|
| ID-080 | Ghi log đăng nhập thất bại | Đăng nhập sai password | `SELECT action, created_at FROM audit_log WHERE action='LOGIN_FAILED' ORDER BY created_at DESC LIMIT 1` → có bản ghi | Must |
| ID-081 | Ghi log khi tài khoản bị khóa | Sai đủ 5 lần | `audit_log` có bản ghi `action='ACCOUNT_LOCKED'` | Must |
| ID-082 | Ghi log khi truy cập sai chi nhánh | CASHIER CN1 gọi `GET /orders?branchId=2` | `audit_log` có bản ghi `action='BRANCH_FORBIDDEN'` | Must |

---

*Tổng: **22 test case** trong file này – mục 5 (phân quyền chi nhánh, BCrypt, đổi mật khẩu, chi nhánh, audit log). Toàn bộ TC xác thực JWT xem tại `auth-login-jwt-test-cases.md`.*
