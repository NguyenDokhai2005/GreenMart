# Test Cases – [AUTH-02] Spring Security RBAC + Lọc chi nhánh (Issue #146)

- **Phụ trách:** SV08 – QA/Security (Huỳnh Phúc Đạt)
- **Hạn:** 01/11/2026 · **Tuần:** 1
- **Nhãn:** `security`, `test`, `milestone-week1`
- **Tham chiếu:** AUTH-02, BR-11, EX-11, AC-04, `openapi.yaml` 2.0.0, `V1__init_schema.sql`
- **Phạm vi:** Spring Security RBAC (`x-roles`), lọc chi nhánh (`branchId`), ghi `audit_log` khi truy cập bị từ chối

---

## Nguồn đối chiếu

| File | Lấy gì |
|---|---|
| `docs/api/openapi.yaml` 2.0.0 | Mỗi endpoint có `x-roles` hay không; mô tả `BRANCH_FORBIDDEN`, `FORBIDDEN`; quy tắc `branchId` |
| `backend/src/main/java/.../ErrorCode.java` | `BRANCH_FORBIDDEN` → 403, `FORBIDDEN` → 403 |
| `backend/src/main/resources/db/migration/V1__init_schema.sql` | Bảng `user_branch` (user_id, branch_id); `audit_log` (action, entity, entity_id, detail) |
| `docs/requirements/user-stories.md` – mục #146 | Tiêu chí chấp nhận AUTH-02, BR-11, EX-11, AC-04 |

**Quy tắc từ openapi.yaml 2.0.0:**
- Request có `branchId` chỉ hợp lệ với chi nhánh được gán cho người dùng; **ADMIN và OWNER dùng được mọi chi nhánh**.
- Sai chi nhánh trả 403 `BRANCH_FORBIDDEN` **và được ghi nhật ký**.
- `x-roles` trên endpoint: chỉ vai trò được liệt kê mới gọi được; sai trả 403 `FORBIDDEN`.
- Không ghi `x-roles` = mọi người đã đăng nhập đều gọi được.
- `costPrice` chỉ trả về cho ADMIN, OWNER, BRANCH_MANAGER; vai trò khác nhận `null`.

---

## Tiền điều kiện

Seed dữ liệu từ issue #209 (đã xác nhận Q-28). Chạy 2 bước:

```bash
python forecast-service/scripts/generate_data.py
docker compose exec -T postgres psql -U greenmart -d greenmart -v ON_ERROR_STOP=1 \
  < forecast-service/data/seed_data.sql
```

| Tài khoản | Role | Chi nhánh được gán |
|---|---|---|
| `thungan.gm001` / `Demo@123` | CASHIER | CN1 (branch_id=1) |
| `thungan.gm002` / `Demo@123` | CASHIER | CN2 (branch_id=2) |
| `quanly.gm001` / `Demo@123` | BRANCH_MANAGER | CN1 (branch_id=1) |
| `thukho.gm001` / `Demo@123` | STOREKEEPER | CN1 (branch_id=1) |
| `owner` / `Demo@123` | OWNER | tất cả (branch_id=1,2,3) |
| `admin` / `Demo@123` | ADMIN | tất cả (branch_id=1,2,3) |

Xác nhận `user_branch` đúng trước khi chạy test:

```bash
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "SELECT u.username, u.role, ub.branch_id FROM app_user u
   JOIN user_branch ub ON u.id = ub.user_id ORDER BY u.username, ub.branch_id;"
```

---

## Lệnh kiểm tra nhanh (đính kèm khi đóng issue #146)

```bash
# Lấy token cho các vai trò cần test
CASHIER1_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"thungan.gm001","password":"Demo@123"}' | jq -r .accessToken)

OWNER_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"owner","password":"Demo@123"}' | jq -r .accessToken)

ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Demo@123"}' | jq -r .accessToken)

# 1. CASHIER CN1 gọi endpoint của CN2 → phải 403
curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $CASHIER1_TOKEN" \
  "http://localhost:8080/api/v1/orders?branchId=2"
# → 403

# 2. OWNER không truyền branchId → phải 200
curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  "http://localhost:8080/api/v1/orders"
# → 200

# 3. CASHIER gọi endpoint x-roles=[OWNER] → phải 403
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/api/v1/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $CASHIER1_TOKEN" \
  -d '{"sku":"TEST-001","name":"Test","unit":"kg","categoryId":1,"salePrice":10000,"costPrice":8000,"active":true}'
# → 403

# 4. Kiểm tra audit_log ghi khi BRANCH_FORBIDDEN
docker compose exec postgres psql -U greenmart -d greenmart -c \
  "SELECT action, entity, detail, created_at FROM audit_log
   WHERE action='BRANCH_FORBIDDEN' ORDER BY created_at DESC LIMIT 3;"
```

---

## 1. Lọc chi nhánh – BRANCH_FORBIDDEN (BR-11, AUTH-02)

> Quy tắc: request có `branchId` hoặc path param `branchId` chỉ hợp lệ với chi nhánh được gán.
> ADMIN và OWNER bỏ qua kiểm tra này.
> Vi phạm → 403 `BRANCH_FORBIDDEN` **và ghi `audit_log`** (EX-11).

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH02-01 | CASHIER CN1 không xem đơn CN2 qua query param | `thungan.gm001` chỉ có CN1 trong `user_branch` | `GET /api/v1/orders?branchId=2` với token CASHIER1 | HTTP 403 `BRANCH_FORBIDDEN`; `audit_log` có bản ghi `action='BRANCH_FORBIDDEN'` | Must |
| TC-AUTH02-02 | CASHIER CN1 không tạo đơn tại CN2 qua body | `thungan.gm001` gán CN1 | `POST /api/v1/orders/pos` body `{"branchId":2,...}` | HTTP 403 `BRANCH_FORBIDDEN` | Must |
| TC-AUTH02-03 | CASHIER không xem tồn CN khác qua path param | `thungan.gm001` gán CN1 | `GET /api/v1/inventory/2/{productId}` (branchId=2 trong path) | HTTP 403 `BRANCH_FORBIDDEN` | Must |
| TC-AUTH02-04 | BRANCH_MANAGER CN1 không xem phiếu kho CN2 | `quanly.gm001` gán CN1 | `GET /api/v1/stock-documents?branchId=2` | HTTP 403 `BRANCH_FORBIDDEN` | Must |
| TC-AUTH02-05 | STOREKEEPER CN1 không xem tồn CN2 | `thukho.gm001` gán CN1 | `GET /api/v1/inventory?branchId=2` | HTTP 403 `BRANCH_FORBIDDEN` | Must |
| TC-AUTH02-06 | CASHIER CN1 gọi đúng CN1 → được phép | `thungan.gm001` gán CN1 | `GET /api/v1/orders?branchId=1` | HTTP 200 | Must |
| TC-AUTH02-07 | ADMIN xem mọi chi nhánh không bị chặn | `admin` (ADMIN) | `GET /api/v1/orders?branchId=2` | HTTP 200 | Must |
| TC-AUTH02-08 | OWNER xem mọi chi nhánh không bị chặn | `owner` (OWNER) | `GET /api/v1/inventory?branchId=3` | HTTP 200 | Must |

---

## 2. RBAC theo vai trò – FORBIDDEN (x-roles, AUTH-02)

> Quy tắc: endpoint có `x-roles` → chỉ vai trò trong danh sách được phép.
> Vi phạm → 403 `FORBIDDEN` (không ghi audit_log, chỉ BRANCH_FORBIDDEN mới ghi).

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH02-10 | CASHIER không tạo sản phẩm (`x-roles: [OWNER]`) | `thungan.gm001` (CASHIER) | `POST /api/v1/products` | HTTP 403 `FORBIDDEN` | Must |
| TC-AUTH02-11 | CASHIER không sửa sản phẩm (`x-roles: [OWNER]`) | `thungan.gm001` (CASHIER) | `PUT /api/v1/products/{productId}` | HTTP 403 `FORBIDDEN` | Must |
| TC-AUTH02-12 | CASHIER không xem sổ giao dịch kho (`x-roles: [OWNER, BRANCH_MANAGER, STOREKEEPER]`) | `thungan.gm001` (CASHIER) | `GET /api/v1/inventory/transactions?branchId=1` | HTTP 403 `FORBIDDEN` | Must |
| TC-AUTH02-13 | STOREKEEPER không duyệt phiếu kho (`x-roles: [BRANCH_MANAGER]`) | `thukho.gm001` (STOREKEEPER) | `POST /api/v1/stock-documents/{id}/approve` | HTTP 403 `FORBIDDEN` | Must |
| TC-AUTH02-14 | OWNER tạo sản phẩm thành công (`x-roles: [OWNER]`) | `owner` (OWNER) | `POST /api/v1/products` với body hợp lệ | HTTP 201 (không phải 403) | Must |
| TC-AUTH02-15 | BRANCH_MANAGER xem sổ kho thành công (`x-roles: [OWNER, BRANCH_MANAGER, STOREKEEPER]`) | `quanly.gm001` (BRANCH_MANAGER) | `GET /api/v1/inventory/transactions?branchId=1` | HTTP 200 | Must |

---

## 3. OWNER / ADMIN – truy cập toàn chuỗi (AC-04)

> OWNER và ADMIN không bị kiểm tra `branchId`; thấy dữ liệu mọi chi nhánh khi không truyền `branchId`.

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH02-20 | OWNER không truyền branchId → thấy đơn mọi chi nhánh | `owner` (OWNER) | `GET /api/v1/orders` không truyền `branchId` | HTTP 200; response có đơn của nhiều chi nhánh (hoặc array không rỗng sau khi seed) | Must |
| TC-AUTH02-21 | ADMIN không truyền branchId → thấy tồn mọi chi nhánh | `admin` (ADMIN) | `GET /api/v1/inventory` không truyền `branchId` | HTTP 200; response gồm tồn kho của cả 3 chi nhánh | Must |
| TC-AUTH02-22 | CASHIER không truyền branchId → chỉ thấy CN mình | `thungan.gm001` gán CN1 | `GET /api/v1/orders` không truyền `branchId` | HTTP 200; tất cả đơn trong response có `branchId=1` | Must |

---

## 4. costPrice ẩn theo vai trò (BR-11)

> `costPrice` chỉ trả về cho ADMIN, OWNER, BRANCH_MANAGER; CASHIER và STOREKEEPER nhận `null`.

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH02-30 | CASHIER không thấy costPrice | `thungan.gm001` (CASHIER) | `GET /api/v1/products/{productId}` | Response có `costPrice=null` | Must |
| TC-AUTH02-31 | STOREKEEPER không thấy costPrice | `thukho.gm001` (STOREKEEPER) | `GET /api/v1/products/{productId}` | Response có `costPrice=null` | Must |
| TC-AUTH02-32 | BRANCH_MANAGER thấy costPrice | `quanly.gm001` (BRANCH_MANAGER) | `GET /api/v1/products/{productId}` | Response có `costPrice` là số nguyên ≥ 0 | Must |
| TC-AUTH02-33 | OWNER thấy costPrice | `owner` (OWNER) | `GET /api/v1/products/{productId}` | Response có `costPrice` là số nguyên ≥ 0 | Must |

---

## 5. Ghi audit_log khi BRANCH_FORBIDDEN (EX-11, AUTH-03)

> Theo tiêu chí #146 và `user-stories.md` #146 tiêu chí 1: truy cập sai chi nhánh phải được ghi nhật ký.
> Tên `action` giả định là `BRANCH_FORBIDDEN` — cần SV02 xác nhận (Q-27 trong `qa-questions-week1.md`).

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH02-40 | BRANCH_FORBIDDEN ghi audit_log | `thungan.gm001` gán CN1 | `GET /api/v1/orders?branchId=2` → 403 | `audit_log` có bản ghi: `user_id` của `thungan.gm001`; `action='BRANCH_FORBIDDEN'`; `entity_id` là VARCHAR chứa branch_id bị từ chối; `detail` JSONB chứa thông tin chi nhánh | Must |
| TC-AUTH02-41 | FORBIDDEN (sai role) không ghi audit_log | `thungan.gm001` (CASHIER) | `POST /api/v1/products` → 403 `FORBIDDEN` | `audit_log` **không** có bản ghi mới cho thao tác này (chỉ BRANCH_FORBIDDEN mới ghi – AC-04) | Should |

---

*Tổng: **24 test case** (TC-AUTH02-01..08: 8, TC-AUTH02-10..15: 6, TC-AUTH02-20..22: 3, TC-AUTH02-30..33: 4, TC-AUTH02-40..41: 2).*

*Câu hỏi mở: Q-27 trong `docs/testing/qa-questions-week1.md` — tên `action` chính xác khi ghi `audit_log` cho BRANCH_FORBIDDEN cần SV02 xác nhận trước khi chạy TC-AUTH02-40.*
