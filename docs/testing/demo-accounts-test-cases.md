# Test Cases – Tài khoản demo 5 vai trò × 3 chi nhánh (Issue #148)

- **Phụ trách:** SV08 – QA/Security (Huỳnh Phúc Đạt)
- **Hạn:** 01/11/2026 · **Tuần:** 1
- **Nhãn:** `security`, `test`, `milestone-week1`
- **Tham chiếu:** SRS 7.4, `openapi.yaml` 2.0.0, `V1__init_schema.sql`, `forecast-service/scripts/generate_data.py`
- **Phạm vi:** Xác nhận seed tạo đủ tài khoản demo 5 vai trò × 3 chi nhánh, mật khẩu thống nhất, phân quyền chi nhánh đúng

---

## Nguồn đối chiếu

| File | Lấy gì |
|---|---|
| `forecast-service/scripts/generate_data.py` | Danh sách tài khoản demo, hash BCrypt, gán chi nhánh |
| `docs/api/openapi.yaml` 2.0.0 | `TokenResponse` schema, `CurrentUser` schema (`role`, `branches`) |
| `V1__init_schema.sql` | Bảng `app_user` (role CHECK), `user_branch` (user_id, branch_id) |

---

## Tiền điều kiện

Chạy seed script theo 2 bước:

```bash
# Bước 1 – Sinh file SQL
python forecast-service/scripts/generate_data.py

# Bước 2 – Nạp vào PostgreSQL
docker compose exec -T postgres psql -U greenmart -d greenmart -v ON_ERROR_STOP=1 \
  < forecast-service/data/seed_data.sql
```

**Danh sách tài khoản demo đầy đủ** (từ `generate_data.py`, mật khẩu chung `Demo@123`):

| Tài khoản | Họ tên | Role | Chi nhánh được gán |
|---|---|---|---|
| `admin` | Quản trị hệ thống | ADMIN | CN1, CN2, CN3 |
| `owner` | Chủ chuỗi GreenMart | OWNER | CN1, CN2, CN3 |
| `owner.pho` | Trần Thị Phó Chủ Chuỗi | OWNER | CN1, CN2, CN3 |
| `quanly.gm001` | Trần Văn Quản Lý 1 | BRANCH_MANAGER | CN1 (branch_id=1) |
| `quanly.gm002` | Lê Thị Quản Lý 2 | BRANCH_MANAGER | CN2 (branch_id=2) |
| `quanly.gm003` | Phạm Văn Quản Lý 3 | BRANCH_MANAGER | CN3 (branch_id=3) |
| `thungan.gm001` | Nguyễn Thu Ngân | CASHIER | CN1 (branch_id=1) |
| `thungan.gm002` | Vũ Thu Ngân 2 | CASHIER | CN2 (branch_id=2) |
| `thungan.gm003` | Đặng Thu Ngân 3 | CASHIER | CN3 (branch_id=3) |
| `thukho.gm001` | Hoàng Văn Thủ Kho 1 | STOREKEEPER | CN1 (branch_id=1) |
| `thukho.gm002` | Bùi Văn Thủ Kho 2 | STOREKEEPER | CN2 (branch_id=2) |
| `thukho.gm003` | Đỗ Văn Thủ Kho 3 | STOREKEEPER | CN3 (branch_id=3) |

---

## Lệnh kiểm tra nhanh (đính kèm khi đóng issue #148)

```bash
# 1. Đếm tài khoản demo theo role
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "SELECT role, COUNT(*) FROM app_user GROUP BY role ORDER BY role;"

# 2. Kiểm tra user_branch – mỗi tài khoản gán đúng chi nhánh
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "SELECT u.username, u.role, ub.branch_id
   FROM app_user u JOIN user_branch ub ON u.id = ub.user_id
   ORDER BY u.role, u.username, ub.branch_id;"

# 3. Xác nhận BCrypt – mọi hash phải bắt đầu $2a$ hoặc $2b$
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "SELECT username, LEFT(password_hash, 7) AS prefix FROM app_user ORDER BY username;"

# 4. Test đăng nhập tất cả 12 tài khoản – tất cả phải trả 200 (mật khẩu thống nhất)
for USER in admin owner owner.pho \
            quanly.gm001 quanly.gm002 quanly.gm003 \
            thungan.gm001 thungan.gm002 thungan.gm003 \
            thukho.gm001 thukho.gm002 thukho.gm003; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST http://localhost:8080/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USER\",\"password\":\"Demo@123\"}")
  echo "$USER → $HTTP_CODE"
done
# Tất cả 12 dòng phải là 200
```

---

## 1. Seed tạo đủ số lượng tài khoản (SRS 7.4)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-DEMO-01 | Có đủ 5 vai trò trong DB | Seed đã chạy | `SELECT DISTINCT role FROM app_user ORDER BY role` | Kết quả gồm đủ: `ADMIN`, `BRANCH_MANAGER`, `CASHIER`, `OWNER`, `STOREKEEPER` | Must |
| TC-DEMO-02 | Có ít nhất 1 ADMIN | Seed đã chạy | `SELECT COUNT(*) FROM app_user WHERE role='ADMIN'` | ≥ 1 | Must |
| TC-DEMO-03 | Có ít nhất 2 OWNER | Seed đã chạy | `SELECT COUNT(*) FROM app_user WHERE role='OWNER'` | ≥ 2 (`owner`, `owner.pho`) | Must |
| TC-DEMO-04 | Có đủ BRANCH_MANAGER 3 CN | Seed đã chạy | `SELECT u.username, ub.branch_id FROM app_user u JOIN user_branch ub ON u.id=ub.user_id WHERE u.role='BRANCH_MANAGER' ORDER BY ub.branch_id` | Có BRANCH_MANAGER tại CN1, CN2, CN3 (mỗi CN ít nhất 1) | Must |
| TC-DEMO-05 | Có đủ CASHIER 3 CN | Seed đã chạy | Tương tự TC-DEMO-04 với `role='CASHIER'` | Có CASHIER tại CN1, CN2, CN3 | Must |
| TC-DEMO-06 | Có đủ STOREKEEPER 3 CN | Seed đã chạy | Tương tự TC-DEMO-04 với `role='STOREKEEPER'` | Có STOREKEEPER tại CN1, CN2, CN3 | Must |

---

## 2. Đăng nhập thành công với mật khẩu thống nhất `Demo@123`

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-DEMO-10 | ADMIN đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"admin","password":"Demo@123"}` | HTTP 200; `user.role=ADMIN`; `user.branches` có 3 chi nhánh | Must |
| TC-DEMO-11 | OWNER (`owner`) đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"owner","password":"Demo@123"}` | HTTP 200; `user.role=OWNER`; `user.branches` có 3 chi nhánh | Must |
| TC-DEMO-11b | OWNER (`owner.pho`) đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"owner.pho","password":"Demo@123"}` | HTTP 200; `user.role=OWNER`; `user.branches` có 3 chi nhánh | Must |
| TC-DEMO-12 | BRANCH_MANAGER CN1 đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"quanly.gm001","password":"Demo@123"}` | HTTP 200; `user.role=BRANCH_MANAGER`; `user.branches` chỉ có CN1 | Must |
| TC-DEMO-13 | BRANCH_MANAGER CN2 đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"quanly.gm002","password":"Demo@123"}` | HTTP 200; `user.role=BRANCH_MANAGER`; `user.branches` chỉ có CN2 | Must |
| TC-DEMO-14 | BRANCH_MANAGER CN3 đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"quanly.gm003","password":"Demo@123"}` | HTTP 200; `user.role=BRANCH_MANAGER`; `user.branches` chỉ có CN3 | Must |
| TC-DEMO-15 | CASHIER CN1 đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"thungan.gm001","password":"Demo@123"}` | HTTP 200; `user.role=CASHIER`; `user.branches` chỉ có CN1 | Must |
| TC-DEMO-16 | CASHIER CN2 đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"thungan.gm002","password":"Demo@123"}` | HTTP 200; `user.role=CASHIER`; `user.branches` chỉ có CN2 | Must |
| TC-DEMO-17 | CASHIER CN3 đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"thungan.gm003","password":"Demo@123"}` | HTTP 200; `user.role=CASHIER`; `user.branches` chỉ có CN3 | Must |
| TC-DEMO-18 | STOREKEEPER CN1 đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"thukho.gm001","password":"Demo@123"}` | HTTP 200; `user.role=STOREKEEPER`; `user.branches` chỉ có CN1 | Must |
| TC-DEMO-19 | STOREKEEPER CN2 đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"thukho.gm002","password":"Demo@123"}` | HTTP 200; `user.role=STOREKEEPER`; `user.branches` chỉ có CN2 | Must |
| TC-DEMO-20 | STOREKEEPER CN3 đăng nhập thành công | Seed đã chạy | `POST /auth/login` `{"username":"thukho.gm003","password":"Demo@123"}` | HTTP 200; `user.role=STOREKEEPER`; `user.branches` chỉ có CN3 | Must |
| TC-DEMO-21 | Mật khẩu thống nhất – không tài khoản nào dùng password khác | Seed đã chạy | Thử đăng nhập tất cả tài khoản với `Demo@123` (dùng lệnh kiểm tra nhanh bên trên) | Tất cả 12 tài khoản trả HTTP 200; không có tài khoản nào trả 401 | Must |

---

## 3. GET /auth/me trả đúng role và branches

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-DEMO-30 | ADMIN thấy mọi chi nhánh qua /auth/me | Đăng nhập `admin` | `GET /auth/me` | `role=ADMIN`; `branches` có 3 phần tử (CN1, CN2, CN3) | Must |
| TC-DEMO-31 | OWNER thấy mọi chi nhánh qua /auth/me | Đăng nhập `owner` | `GET /auth/me` | `role=OWNER`; `branches` có 3 phần tử | Must |
| TC-DEMO-32 | CASHIER CN1 chỉ thấy CN1 qua /auth/me | Đăng nhập `thungan.gm001` | `GET /auth/me` | `role=CASHIER`; `branches` có đúng 1 phần tử (CN1) | Must |
| TC-DEMO-33 | BRANCH_MANAGER CN2 chỉ thấy CN2 | Đăng nhập `quanly.gm002` | `GET /auth/me` | `role=BRANCH_MANAGER`; `branches` có đúng 1 phần tử (CN2) | Must |
| TC-DEMO-34 | STOREKEEPER CN3 chỉ thấy CN3 | Đăng nhập `thukho.gm003` | `GET /auth/me` | `role=STOREKEEPER`; `branches` có đúng 1 phần tử (CN3) | Must |

---

## 4. Mật khẩu lưu dạng BCrypt (NFR-05)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-DEMO-40 | Tất cả tài khoản demo dùng BCrypt | Seed đã chạy | `SELECT username, LEFT(password_hash, 7) FROM app_user` | Mọi dòng bắt đầu `$2a$` hoặc `$2b$` (BCrypt cost ≥ 10) | Must |
| TC-DEMO-41 | Hash của các tài khoản khác nhau dù cùng password | Seed đã chạy | So sánh `password_hash` của `thungan.gm001` và `thungan.gm002` | Hai hash khác nhau (BCrypt salt ngẫu nhiên) | Must |

---

*Tổng: **26 test case** (TC-DEMO-01..06: 6, TC-DEMO-10..21: 13, TC-DEMO-30..34: 5, TC-DEMO-40..41: 2).
Seed phải được SV06 (#209) cung cấp trước khi chạy bất kỳ TC nào trong file này.*
