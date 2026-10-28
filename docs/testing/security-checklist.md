# Security Checklist – GreenMart (DT03)

**Phiên bản:** 1.0  
**Ngày soạn:** 27/10/2026  
**Người soạn:** SV08 – QA/Security (Huỳnh Phúc Đạt)  
**Tham chiếu:** NFR-05, NFR-06, NFR-07, AUTH-01..04

---

## Hướng dẫn sử dụng

Chạy checklist này ở tuần 3 sau khi hệ thống tích hợp đầy đủ. Mỗi mục đánh dấu:
- ✅ Đạt
- ❌ Không đạt → mở Issue ngay
- ⏭ Bỏ qua kèm lý do

---

## 1. Xác thực và phân quyền (NFR-05)

### 1.1 JWT

| # | Kiểm tra | Cách thực hiện | Kết quả mong đợi | Kết quả |
|---|---|---|---|---|
| A-01 | Access token hết hạn sau 15 phút | Decode JWT kiểm tra `exp`, chờ hết hạn gọi API | HTTP 401 | |
| A-02 | Refresh token còn hiệu lực 7 ngày | Kiểm tra `exp` trong refresh token | Thời hạn đúng 7 ngày | |
| A-03 | JWT giả mạo với secret sai | Tạo token với secret tùy ý, gọi API | HTTP 401 | |
| A-04 | JWT algorithm `none` | Gửi token `alg: none` | HTTP 401 | |
| A-05 | JWT thiếu claim `role` hoặc `branchIds` | Tạo token không có claim cần thiết | HTTP 401 hoặc 403 | |

### 1.2 Mật khẩu

| # | Kiểm tra | Cách thực hiện | Kết quả mong đợi | Kết quả |
|---|---|---|---|---|
| P-01 | Mật khẩu lưu dạng BCrypt | `SELECT password_hash FROM app_user LIMIT 5` | Bắt đầu `$2a$` hoặc `$2b$` | |
| P-02 | Không có mật khẩu plain text trong DB | Xem trực tiếp bảng `app_user` | Không có cột nào chứa mật khẩu rõ | |
| P-03 | Brute force lockout sau 5 lần sai | Gửi 6 lần đăng nhập sai liên tiếp | Tài khoản khóa 15 phút sau lần 5 | |
| P-04 | Thông báo lỗi đăng nhập không lộ nguyên nhân | Đăng nhập sai username vs sai password | Cùng một thông báo lỗi chung | |

### 1.3 Phân quyền (RBAC)

| # | Kiểm tra | Endpoint | Role test | Kết quả mong đợi | Kết quả |
|---|---|---|---|---|---|
| R-01 | Thu ngân CN1 không truy cập CN2 | `GET /api/v1/orders?branchId={CN2}` | CASHIER (CN1) | HTTP 403 | |
| R-02 | Thu ngân không sửa giá | `PUT /api/v1/products/{id}` (price) | CASHIER | HTTP 403 | |
| R-03 | Thủ kho không duyệt phiếu mình lập | Tạo rồi duyệt cùng tài khoản | STOREKEEPER | HTTP 422 | |
| R-04 | Admin truy cập toàn chuỗi | Bất kỳ endpoint có `branchId` | ADMIN | HTTP 200 | |
| R-05 | Owner truy cập toàn chuỗi | `GET /api/v1/orders` mọi branch | OWNER | HTTP 200 | |
| R-06 | Truy cập trực tiếp URL không đủ quyền | `GET /api/v1/admin/users` | CASHIER | HTTP 403 | |
| R-07 | API không có token | `GET /api/v1/orders` | Không có header Authorization | HTTP 401 | |

---

## 2. Injection và dữ liệu đầu vào (NFR-06)

| # | Kiểm tra | Payload | Endpoint ví dụ | Kết quả mong đợi | Kết quả |
|---|---|---|---|---|---|
| I-01 | SQL Injection – tìm kiếm sản phẩm | `' OR 1=1--` | `GET /api/v1/products?search=...` | HTTP 400 hoặc 200 với kết quả trống, không lộ DB | |
| I-02 | SQL Injection – tìm khách hàng | `'; DROP TABLE app_user--` | `GET /api/v1/customers?phone=...` | Không xóa dữ liệu, không lỗi 500 | |
| I-03 | XSS trong tên sản phẩm | `<script>alert(1)</script>` | `POST /api/v1/products` | Lưu dưới dạng escaped hoặc từ chối | |
| I-04 | Path traversal | `../../etc/passwd` | Bất kỳ endpoint có ID | HTTP 400 hoặc 404 | |
| I-05 | Integer overflow giá bán | Giá = `9999999999999999` | `PUT /api/v1/products/{id}` | Validation lỗi hoặc bị cắt đúng kiểu | |
| I-06 | Số lượng âm trong đơn | `quantity: -1` | `POST /api/v1/orders` | HTTP 400 | |
| I-07 | JSON injection / nested object | Thêm trường ngoài spec | `POST /api/v1/orders` | Trường ngoài bị bỏ qua, không lỗi | |

---

## 3. Lộ thông tin (NFR-06)

| # | Kiểm tra | Cách thực hiện | Kết quả mong đợi | Kết quả |
|---|---|---|---|---|
| E-01 | Stack trace không xuất hiện trong response lỗi | Gửi request sai format cố ý gây exception | Response chỉ có `code`, `message`, `traceId` | |
| E-02 | Không lộ version framework | Xem headers response | Không có `X-Powered-By`, `Server` version cụ thể | |
| E-03 | Log không chứa secret | Xem application log staging | Không có JWT, password, API key trong log | |
| E-04 | `traceId` trong log khớp với response | Gây lỗi, lấy `traceId` từ response, tìm trong log | Log có đúng correlation ID | |

---

## 4. CORS (NFR-06)

| # | Kiểm tra | Cách thực hiện | Kết quả mong đợi | Kết quả |
|---|---|---|---|---|
| C-01 | Origin không cho phép bị chặn | Gửi request với `Origin: https://evil.com` | Không có `Access-Control-Allow-Origin: *` hoặc không trả header CORS | |
| C-02 | Origin frontend hợp lệ được chấp nhận | Request từ origin đúng | `Access-Control-Allow-Origin` đúng domain | |
| C-03 | Preflight OPTIONS trả đúng headers | `OPTIONS` request | HTTP 200/204, headers CORS đúng | |

---

## 5. Secrets và CI (NFR-07)

| # | Kiểm tra | Cách thực hiện | Kết quả mong đợi | Kết quả |
|---|---|---|---|---|
| G-01 | Không có secrets trong repo | `gitleaks detect --source .` | `0 leaks found` | |
| G-02 | `.env` không bị commit | `git log --all -- .env` | Không có commit chứa `.env` | |
| G-03 | Biến môi trường qua `.env.example` | Xem `.env.example` | Chỉ có key, không có value thật | |
| G-04 | Gitleaks chạy trong CI | Xem `.github/workflows/` | Có step gitleaks khi PR | |
| G-05 | Webhook secret trong DB được mã hóa | `SELECT webhook_secret FROM sales_channel` | Không lưu plain text | |

---

## 6. Tích hợp kênh bán (SYNC-02)

| # | Kiểm tra | Cách thực hiện | Kết quả mong đợi | Kết quả |
|---|---|---|---|---|
| W-01 | Webhook không có chữ ký | Gửi POST không có header `X-Signature` | HTTP 401 | |
| W-02 | Webhook chữ ký sai | Gửi POST với HMAC sai secret | HTTP 401 | |
| W-03 | Webhook replay attack | Gửi lại đúng request đã xử lý | Trả đơn cũ, không tạo mới (idempotent) | |
| W-04 | Webhook payload bị sửa | Sửa body nhưng giữ signature cũ | HTTP 401 | |

---

## 7. Kết luận

Hoàn thành checklist này → cập nhật kết quả vào `docs/testing/test-report.md` và mở Issue cho mọi mục ❌.

Mọi lỗi bảo mật mức **nghiêm trọng** phải được fix trước khi demo cuối kỳ.
