# Kế hoạch kiểm thử – GreenMart (DT03)

**Phiên bản:** 1.0  
**Ngày soạn:** 27/10/2026  
**Người soạn:** SV08 – QA/Security (Huỳnh Phúc Đạt)  
**Trạng thái:** Tuần 1 – Bản nháp khởi tạo

---

## 1. Mục tiêu kiểm thử

Đảm bảo hệ thống GreenMart đáp ứng toàn bộ 6 tiêu chí nghiệm thu (AC-01..AC-06), 14 yêu cầu phi chức năng (NFR-01..14) và không có lỗi thuộc 19 quy tắc nghiệp vụ (BR-01..19). Trọng tâm đặc biệt vào:

- **Tính nguyên tử của tồn kho** — không tồn âm trong mọi tình huống đồng thời (AC-02, INV-07).
- **Phân quyền và bảo mật** — mọi API kiểm tra vai trò + chi nhánh ở backend (AC-04, NFR-05/06).
- **Đồng bộ hai chiều** — đơn online và tồn kho đến kênh đúng và không mất dữ liệu (AC-03).
- **Chất lượng mã** — unit test coverage ≥ 70 % trên module sales và inventory (NFR-11).

---

## 2. Phạm vi

### 2.1 Trong phạm vi kiểm thử

| Khu vực | Loại test | Công cụ |
|---|---|---|
| API REST (/api/v1) | API test theo luồng nghiệp vụ | Postman + Newman |
| Tồn kho đồng thời | Load test / concurrency test | k6 |
| Phân quyền (RBAC) | Negative test – gọi sai vai trò/chi nhánh | Postman |
| Bảo mật cơ bản | OWASP Top 10 check cơ bản | Thủ công + công cụ |
| Luồng E2E giao diện | Bán tại quầy, đơn online | Playwright |
| Secrets trong repo | Quét gitleaks | CI / gitleaks |
| Idempotency | Gửi trùng đơn, idempotency-key | Postman |
| Tích hợp RabbitMQ | Tắt 5 phút, kiểm tra bù sự kiện | Test tích hợp thủ công |

### 2.2 Ngoài phạm vi

- Thanh toán thật (ngân hàng, ví điện tử)
- Kết nối Shopee, Lazada, TikTok Shop thật
- Kiểm thử ứng dụng di động

---

## 3. Chiến lược kiểm thử

### 3.1 Phân loại

```
Unit test (SV02, SV03 viết) ──► Integration test ──► API test ──► E2E / Load test
                                        ↑
                               SV08 phụ trách từ Integration trở lên
```

- **Unit test**: SV02 (backend sales), SV03 (backend inventory) viết; SV08 review coverage report.
- **API test (Postman)**: SV08 viết, chạy trong CI với Newman.
- **Load test (k6)**: SV08 viết script, chạy thủ công và CI.
- **E2E (Playwright)**: SV08 viết, chạy trong CI.
- **Security test**: SV08 thực hiện theo checklist OWASP cơ bản ở tuần 3.

### 3.2 Môi trường

| Môi trường | Mô tả | Người quản lý |
|---|---|---|
| Local | Docker Compose từng máy dev | Mỗi thành viên |
| Staging | Tự cập nhật khi merge vào `develop` | SV07 |

Test tự động chạy trên staging. Load test chạy trên local trước khi demo để tránh ảnh hưởng môi trường.

### 3.3 Dữ liệu test

- Sử dụng seed script (SV06) tạo 3 chi nhánh, 1.000 sản phẩm, 10.000 đơn lịch sử.
- Tài khoản demo 5 vai trò × 3 chi nhánh (từ seed script).
- Fixture JSON cho Postman và Playwright tạo ở `tests/api/fixtures/`.

---

## 4. Test cases theo User Story

### US-01 · Bán tại quầy (ORD-01, PAY-01)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-POS-01 | Bán hàng bình thường – tiền mặt | Sản phẩm X có khả dụng 10, giỏ 2 món | Thanh toán tiền mặt, hoàn tất | Đơn COMPLETED, khả dụng còn 8, sổ kho SALE -2 | Must |
| TC-POS-02 | Tính khuyến mại đúng | Giỏ 238.000 đ với KM-GAO và KM-10 | Tính tiền | Tổng phải trả 200.700 đ | Must |
| TC-POS-03 | Idempotency – bấm đúp | Cùng Idempotency-Key | Gửi 2 lần | Chỉ 1 đơn được tạo | Must |
| TC-POS-04 | Tồn bị lấy đúng lúc hoàn tất | Khả dụng 1, đơn khác lấy trước | Hoàn tất | Trả 409 INSUFFICIENT_STOCK, giỏ giữ nguyên | Must |
| TC-POS-05 | Thanh toán thẻ thất bại | Giả lập thẻ thất bại | Bấm Hoàn tất | Không tạo đơn, cho chọn lại | Must |
| TC-POS-06 | Thanh toán QR quá 60 giây | Giả lập QR timeout | Chờ 60 giây | Không tạo đơn | Must |
| TC-POS-07 | Tiền đưa < tổng | Tiền mặt nhỏ hơn tổng | Hoàn tất | Bị chặn, thông báo rõ | Must |
| TC-POS-08 | Sản phẩm không tồn tại / ngừng bán | Quét mã không có | Quét | Báo lỗi, không thêm dòng | Must |

### US-02 · Không bán vượt tồn (INV-07, AC-02)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-CONC-01 | 100 yêu cầu đồng thời, tồn 5 | Khả dụng = 5, mỗi yêu cầu mua 1 | 100 request song song | Đúng 5 thành công, 95 nhận 409, khả dụng = 0 | Must |
| TC-CONC-02 | Đơn hỗn hợp POS + online | Tồn 5, 50 POS + 50 webhook đồng thời | Gửi đồng thời | Tổng thành công ≤ 5, không tồn âm | Must |
| TC-CONC-03 | Không deadlock đơn nhiều dòng | Đơn A có [P1,P2], đơn B có [P2,P1] | Gửi đồng thời | Không deadlock, cả hai thành công nếu đủ tồn | Must |
| TC-CONC-04 | CHECK constraint DB tồn âm | Trực tiếp update DB vượt tồn | SQL trực tiếp | DB từ chối với constraint violation | Must |

### US-03 · Nhận đơn online (ORD-02, SYNC-02)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-ONL-01 | Đặt hàng hợp lệ – giữ chỗ | Khả dụng 10, đơn 3 cái | Webhook hợp lệ | Đơn RESERVED, reserved = 3, khả dụng = 7 | Must |
| TC-ONL-02 | Gửi trùng đơn | Đơn WEB-100 đã nhận | Gửi lại WEB-100 | Trả đơn cũ, không giữ chỗ thêm | Must |
| TC-ONL-03 | Chữ ký HMAC sai | Bất kỳ đơn nào | HMAC sai | HTTP 401, không ghi đơn | Must |
| TC-ONL-04 | SKU chưa ánh xạ | SKU kênh chưa có ánh xạ nội bộ | Webhook | Đơn REJECTED, không giữ chỗ | Must |
| TC-ONL-05 | Phân bổ chi nhánh mặc định đủ tồn | CN mặc định đủ hàng | Webhook | Phân bổ đúng CN mặc định | Must |
| TC-ONL-06 | Phân bổ CN khác khi CN mặc định không đủ | CN mặc định thiếu, CN2 đủ | Webhook | Phân bổ đúng CN2 | Must |
| TC-ONL-07 | Mọi CN đều không đủ | Không CN nào đủ tồn | Webhook | Đơn REJECTED lý do OUT_OF_STOCK | Must |

### US-04 · Hết hạn giữ chỗ (ORD-03)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-EXP-01 | Đơn RESERVED quá 15 phút | Đơn RESERVED giữ 3, lúc 10:00 | Đến 10:15+1 phút | Đơn EXPIRED, reserved giảm 3, kênh nhận tồn mới | Must |
| TC-EXP-02 | Thanh toán trong hạn | Đơn RESERVED | Thanh toán trước 15 phút | Đơn CONFIRMED, giữ chỗ giữ nguyên | Must |

### US-05 · Đồng bộ tồn ra kênh (SYNC-03)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-SYNC-01 | Tồn đến kênh sau giao dịch | Tồn thay đổi | Commit giao dịch | Kênh nhận tồn mới trong 1 phút | Must |
| TC-SYNC-02 | RabbitMQ dừng 5 phút | RabbitMQ ngắt | Nhiều giao dịch xảy ra | Sau khi RabbitMQ chạy lại, mọi cập nhật được gửi bù | Must |
| TC-SYNC-03 | Bỏ qua bản version cũ | Kênh đã có version 5 | Nhận version 3 | Kênh bỏ qua, không cập nhật | Must |

### US-08 · Phân quyền theo chi nhánh (AUTH-02, AC-04)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH-01 | Thu ngân CN1 gọi API CN2 | Thu ngân gán CN1 | GET /orders?branchId=CN2 | HTTP 403 | Must |
| TC-AUTH-02 | Thu ngân CN1 tạo đơn tại CN2 | Thu ngân gán CN1 | POST /orders branchId=CN2 | HTTP 403 | Must |
| TC-AUTH-03 | Gọi API không có token | Chưa đăng nhập | Bất kỳ protected endpoint | HTTP 401 | Must |
| TC-AUTH-04 | Token hết hạn | JWT hết hạn 15 phút | Gọi API | HTTP 401 | Must |
| TC-AUTH-05 | Refresh token còn hạn | Refresh token 7 ngày | POST /auth/refresh | Nhận access token mới | Must |
| TC-AUTH-06 | Đăng nhập sai 5 lần | Sai mật khẩu 5 lần liên tiếp | Lần thứ 6 | Tài khoản bị khóa 15 phút | Must |
| TC-AUTH-07 | Thu ngân không đổi giá | Thu ngân CN1 | PUT /products/{id}/price | HTTP 403 | Must |
| TC-AUTH-08 | Owner truy cập toàn chuỗi | Owner | GET orders mọi chi nhánh | HTTP 200 | Must |
| TC-AUTH-09 | Nhật ký đăng nhập thất bại | Đăng nhập sai | Sai mật khẩu | audit_log có bản ghi | Must |
| TC-AUTH-10 | Người lập phiếu không tự duyệt | Thủ kho A lập phiếu | A tự duyệt | HTTP 422 / lỗi nghiệp vụ | Must |

---

## 5. Kiểm thử bảo mật (NFR-05, NFR-06, NFR-07)

### 5.1 Checklist OWASP cơ bản

| # | Hạng mục | Cách kiểm tra | Kết quả mong đợi |
|---|---|---|---|
| S-01 | SQL Injection | Truyền payload `' OR 1=1--` vào các trường tìm kiếm | HTTP 400, không lộ dữ liệu |
| S-02 | JWT giả mạo | Gửi token tự ký với secret giả | HTTP 401 |
| S-03 | JWT thiếu chữ ký | Gửi token `algorithm: none` | HTTP 401 |
| S-04 | Stack trace trong lỗi | Gửi request sai format | Lỗi trả về chỉ có `code`, `message`, không lộ stack trace |
| S-05 | CORS | Request từ origin không cho phép | CORS header chặn |
| S-06 | Thông tin nhạy cảm trong log | Xem log staging | Không có password, secret key trong log |
| S-07 | Secrets trong repo | Chạy `gitleaks detect` | 0 secrets found |
| S-08 | Mật khẩu hashing | Xem trực tiếp DB | Trường password_hash là BCrypt (bắt đầu `$2`) |
| S-09 | HMAC webhook | Gửi đơn với signature sai | HTTP 401, không ghi đơn |
| S-10 | Brute force login | 5 lần sai liên tiếp | Tài khoản khóa 15 phút |

### 5.2 Gitleaks CI

Cấu hình gitleaks chạy trong GitHub Actions khi mở PR vào `develop`.

---

## 6. Kiểm thử hiệu năng (NFR-01, NFR-02)

### 6.1 Load test – k6 (tests/load/)

| Script | Mục tiêu | Ngưỡng đạt |
|---|---|---|
| `load-read-api.js` | API đọc với 50 VU đồng thời | p95 < 300 ms |
| `load-create-order.js` | API tạo đơn với 50 VU đồng thời | p95 < 500 ms |
| `concurrency-stock.js` | 100 yêu cầu đồng thời, tồn 5 | Đúng 5 thành công, không tồn âm |

### 6.2 Integration test timing

| Hành vi | Ngưỡng |
|---|---|
| Tồn mới đến kênh sau commit | < 1 phút |
| Đơn EXPIRED sau 15 phút chưa thanh toán | < 1 phút sau mốc 15 phút |

---

## 7. Kế hoạch theo tuần

| Tuần | Việc làm | Deliverable |
|---|---|---|
| **1** (28/10 – 03/11) | Viết test plan, tạo cấu trúc thư mục tests, thiết lập Postman collection khung, viết checklist security, khởi tạo k6 skeleton | `docs/testing/test-plan.md`, `tests/api/greenmart.postman_collection.json`, `tests/load/*.js` (skeleton), `docs/testing/security-checklist.md` |
| **2** (04/11 – 10/11) | Viết Postman test cho AUTH, ORD, INV khi API sẵn sàng; viết script k6 đầy đủ; bắt đầu Playwright skeleton | Collection đầy đủ, k6 scripts, Playwright skeleton |
| **3** (11/11 – 17/11) | Chạy full test, viết báo cáo lỗi, thực hiện security checklist OWASP, viết ma trận truy vết | `docs/testing/test-report.md`, `docs/testing/traceability-matrix.md` |
| **4** (18/11 – 24/11) | Regression test, chạy load test cuối, hoàn thiện báo cáo kiểm thử cho demo | Báo cáo kiểm thử cuối |

---

## 8. Quy trình báo lỗi

1. Phát hiện lỗi → mở Issue dùng template `bug.md`.
2. Gán nhãn `test`, gán milestone tuần, gán người phụ trách module.
3. Ghi rõ: mô tả, bước tái hiện, kết quả mong đợi, kết quả thực tế, môi trường, mức độ (chặn demo / nghiêm trọng / nhỏ).
4. Lỗi mức "chặn demo" → báo ngay qua daily hoặc nhắn trực tiếp cho người phụ trách.
5. Sau khi fix → SV08 verify và đóng issue.

---

## 9. Definition of Done cho QA

- [ ] Mọi TC must-have trong kế hoạch này đã chạy và pass.
- [ ] Không còn lỗi mức "chặn demo" chưa xử lý.
- [ ] Coverage report module `sales` và `inventory` ≥ 70 %.
- [ ] Gitleaks CI xanh (không có secrets trong repo).
- [ ] Báo cáo kiểm thử cập nhật trong `docs/testing/test-report.md`.
- [ ] Ma trận truy vết hoàn chỉnh trong `docs/testing/traceability-matrix.md`.

---

*Tài liệu này sẽ được cập nhật sau mỗi tuần khi có thêm API sẵn sàng để test.*
