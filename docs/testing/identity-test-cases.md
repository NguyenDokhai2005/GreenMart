# Test Cases – Module Identity (Issue #144)

- **Phụ trách:** SV08 – QA/Security (Huỳnh Phúc Đạt)
- **Hạn:** 30/10/2026 · **Tuần:** 1
- **Nhãn:** `security`, `test`, `milestone-week1`
- **Tham chiếu:** AUTH-01..04, NFR-05, NFR-06, BR-11, `openapi.yaml` 2.0.0, `V1__init_schema.sql`
- **Phạm vi:** bảng `app_user`, `user_branch`, `branch`, BCrypt, đổi mật khẩu, audit log đăng nhập

> **Trạng thái phụ thuộc:**
> - PR #211 (đã merge): TC đăng nhập, refresh token, logout, `GET /auth/me`, bảo vệ JWT → `docs/testing/auth-login-jwt-test-cases.md`
> - PR #146 (đã push): TC phân quyền chi nhánh RBAC, lọc `branchId`, `costPrice`, `audit_log` khi BRANCH_FORBIDDEN → `docs/testing/rbac-branch-filter-test-cases.md`

---

## Tiền điều kiện – Dữ liệu cần có trước khi chạy test

> Seed chi nhánh và user thuộc **SV02 (module identity)**.
> Tất cả test case chỉ chạy được sau khi SV02 tạo seed script và `docker compose up` thành công.
> Tên tài khoản demo xác nhận từ `forecast-service/scripts/generate_data.py` (issue #209, Q-28 ✅).

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
| `thungan.gm002` / `Demo@123` | CASHIER | CN2 (branch_id=2) |
| `thungan.gm003` / `Demo@123` | CASHIER | CN3 (branch_id=3) |
| `quanly.gm001` / `Demo@123` | BRANCH_MANAGER | CN1 (branch_id=1) |
| `quanly.gm002` / `Demo@123` | BRANCH_MANAGER | CN2 (branch_id=2) |
| `quanly.gm003` / `Demo@123` | BRANCH_MANAGER | CN3 (branch_id=3) |
| `thukho.gm001` / `Demo@123` | STOREKEEPER | CN1 (branch_id=1) |
| `thukho.gm002` / `Demo@123` | STOREKEEPER | CN2 (branch_id=2) |
| `thukho.gm003` / `Demo@123` | STOREKEEPER | CN3 (branch_id=3) |
| `owner` / `Demo@123` | OWNER | tất cả (branch_id=1,2,3) |
| `admin` / `Demo@123` | ADMIN | tất cả (branch_id=1,2,3) |

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

# 3. Kiểm tra audit_log ghi thao tác nhạy cảm
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "SELECT action, detail, created_at FROM audit_log ORDER BY created_at DESC LIMIT 5;"
```

---

## 1. Phân quyền chi nhánh và RBAC

> Các TC phân quyền chi nhánh (BRANCH_FORBIDDEN), RBAC theo x-roles, OWNER/ADMIN toàn chuỗi,
> costPrice ẩn theo vai trò, và ghi `audit_log` khi BRANCH_FORBIDDEN đã được tập trung tại
> `docs/testing/rbac-branch-filter-test-cases.md` (issue #146) để tránh trùng lặp.
>
> Tra cứu tại:
> - TC-AUTH02-01..08 (lọc chi nhánh BRANCH_FORBIDDEN)
> - TC-AUTH02-10..15 (RBAC x-roles FORBIDDEN)
> - TC-AUTH02-20..22 (OWNER/ADMIN toàn chuỗi)
> - TC-AUTH02-30..33 (costPrice ẩn theo role)
> - TC-AUTH02-40..41 (ghi audit_log khi BRANCH_FORBIDDEN)
>
> TC gọi API không có token: xem `auth-login-jwt-test-cases.md` TC-AUTH01-30 (PR #211 đã merge).
> TC JWT giả mạo: xem `auth-login-jwt-test-cases.md` TC-AUTH01-32..34.

---

## 2. BCrypt password hashing (NFR-05)

| ID | Tiêu đề | Cách kiểm tra | Kết quả mong đợi | Mức độ |
|---|---|---|---|---|
| ID-050 | Password lưu dạng BCrypt | `SELECT LEFT(password_hash, 7) FROM app_user` | Mọi dòng bắt đầu `$2a$` hoặc `$2b$` (BCrypt cost ≥ 10) | Must |
| ID-051 | Không có plain text trong DB | Xem toàn bộ cột `app_user` | Không có cột nào chứa password rõ | Must |
| ID-052 | Cùng password → hash khác nhau | 2 user cùng `Demo@123` | `password_hash` của 2 user khác nhau (BCrypt salt ngẫu nhiên) | Must |
| ID-053 | API không trả password hash | `GET /auth/me`, `POST /auth/login` response | Không có field `password` hay `passwordHash` trong JSON | Must |

---

## 3. Đổi mật khẩu – `POST /api/v1/auth/change-password`

`ChangePasswordRequest` (openapi.yaml 2.0.0): `currentPassword`, `newPassword` (`minLength: 8`, `maxLength: 100`).
Mô tả endpoint: **"Mật khẩu mới tối thiểu 8 ký tự, có chữ và số."**

> **Lưu ý ID-065, ID-066:** Schema `ChangePasswordRequest` trong `openapi.yaml` chỉ khai báo `minLength: 8`,
> không có `pattern` regex cho "có chữ và số". Backend (SV02) cần implement `@Pattern` riêng để enforce rule này.
> Nếu SV02 chưa implement pattern, ID-065 và ID-066 sẽ trả 204 thay vì 400 —
> cần xác nhận với SV02 trước khi chạy 2 TC này.

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| ID-060 | Đổi thành công | Đang đăng nhập `thungan.gm001` | `POST /auth/change-password` `{"currentPassword":"Demo@123","newPassword":"NewPass8"}` | HTTP 204; mọi refresh token bị thu hồi | Must |
| ID-061 | Đăng nhập lại với password mới | Sau ID-060 | `POST /auth/login` `{"username":"thungan.gm001","password":"NewPass8"}` | HTTP 200 | Must |
| ID-062 | Password cũ không còn dùng được | Sau ID-060 | `POST /auth/login` với `Demo@123` | HTTP 401 `INVALID_CREDENTIALS` | Must |
| ID-063 | currentPassword sai | Nhập sai `currentPassword` | `POST /auth/change-password` | HTTP 422 `WRONG_PASSWORD` | Must |
| ID-064 | Password mới < 8 ký tự | `newPassword="abc123"` (6 ký tự) | `POST /auth/change-password` | HTTP 400 `VALIDATION_ERROR`; `details` liệt kê field `newPassword` | Must |
| ID-065 | Password mới chỉ có chữ, không có số | `newPassword="abcdefgh"` (8 ký tự, không có số) | `POST /auth/change-password` | HTTP 400 `VALIDATION_ERROR` _(phụ thuộc SV02 implement `@Pattern` — xem lưu ý trên)_ | Should |
| ID-066 | Password mới chỉ có số, không có chữ | `newPassword="12345678"` (8 ký tự, không có chữ) | `POST /auth/change-password` | HTTP 400 `VALIDATION_ERROR` _(phụ thuộc SV02 implement `@Pattern` — xem lưu ý trên)_ | Should |

---

## 4. Chi nhánh – `GET /api/v1/branches`

`Branch` schema (openapi.yaml 2.0.0): `id`, `code`, `name`, `address`, `active` (boolean).

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| ID-070 | Seed đủ 3 chi nhánh | Seed script đã chạy | `GET /api/v1/branches` với ADMIN token | HTTP 200; array có 3 phần tử; mỗi phần tử có `id`, `code`, `name`, `active=true` | Must |
| ID-071 | CASHIER chỉ thấy chi nhánh mình | `thungan.gm001` gán CN1 | `GET /api/v1/branches` | HTTP 200; array có 1 phần tử (CN1) | Must |
| ID-072 | Lọc `active=false` | Tồn tại chi nhánh bị vô hiệu hóa | `GET /api/v1/branches?active=false` với ADMIN | Chỉ trả chi nhánh có `active=false` | Must |

---

## 5. Nhật ký thao tác nhạy cảm – bảng `audit_log` (AUTH-03)

> TC ghi log khi truy cập sai chi nhánh (BRANCH_FORBIDDEN) đã chuyển sang
> `rbac-branch-filter-test-cases.md` (TC-AUTH02-40, issue #146).
>
> ⚠️ **Câu hỏi mở Q-25 và Q-26** (xem `docs/testing/qa-questions-week1.md`):
> Tên `action` cho các ca đăng nhập bên dưới cần SV02 xác nhận trước khi chạy test.
> Q-27 đã chốt `action = BRANCH_FORBIDDEN` cho TC-AUTH02-40 tại `rbac-branch-filter-test-cases.md`.

| ID | Tiêu đề | Hành động | Kiểm tra DB | Mức độ |
|---|---|---|---|---|
| ID-080 | Ghi log đăng nhập thất bại | Đăng nhập sai password | `SELECT action, created_at FROM audit_log WHERE action='LOGIN_FAILED' ORDER BY created_at DESC LIMIT 1` → có bản ghi | Must |
| ID-081 | Ghi log khi tài khoản bị khóa | Sai đủ 5 lần | `audit_log` có bản ghi `action='ACCOUNT_LOCKED'` | Must |

---

*Tổng: **16 test case** (ID-050..053: 4, ID-060..066: 7, ID-070..072: 3, ID-080..081: 2).
TC phân quyền chi nhánh, RBAC và audit_log BRANCH_FORBIDDEN: xem `rbac-branch-filter-test-cases.md` (issue #146).
TC đăng nhập, refresh token, logout, JWT: xem `auth-login-jwt-test-cases.md` (issue #145, PR #211 đã merge).*
