# Kế hoạch kiểm thử – GreenMart (DT03)

**Phiên bản:** 1.1
**Ngày soạn:** 27/10/2026 · **Cập nhật:** 30/10/2026
**Người soạn:** SV08 – QA/Security (Huỳnh Phúc Đạt)
**Trạng thái:** Tuần 1 – Đã bổ sung AC-01..AC-06 đầy đủ

---

## 1. Mục tiêu kiểm thử

Đảm bảo hệ thống GreenMart đáp ứng toàn bộ 6 tiêu chí nghiệm thu (AC-01..AC-06), 14 yêu cầu phi chức năng (NFR-01..14) và không có lỗi thuộc 19 quy tắc nghiệp vụ (BR-01..19). Trọng tâm đặc biệt vào:

- **Dữ liệu nền** — seed đủ 3 chi nhánh, 1.000 sản phẩm, 10.000 đơn COMPLETED (AC-01).
- **Tính nguyên tử của tồn kho** — không tồn âm trong mọi tình huống đồng thời (AC-02, INV-07).
- **Đồng bộ hai chiều** — đơn online và tồn kho đến kênh đúng, không mất dữ liệu (AC-03).
- **Phân quyền và bảo mật** — mọi API kiểm tra vai trò + chi nhánh ở backend (AC-04, NFR-05/06).
- **Dự báo nhu cầu** — WAPE mô hình thấp hơn baseline tốt nhất ở cả 2 tầm (AC-05).
- **Cảnh báo tồn thấp** — dashboard hiện/đóng cảnh báo đúng theo ROP (AC-06).

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
| Idempotency | Gửi trùng đơn và gửi body khác key | Postman |
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

## 4. Test cases theo tiêu chí nghiệm thu (AC-01..AC-06)

### AC-01 · Dữ liệu nền – seed script (AI-01)

> Cách chứng minh: chạy script seed, truy vấn đếm trước hội đồng.

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AC01-01 | Đủ 3 chi nhánh | Seed script đã chạy | `SELECT COUNT(*) FROM branch WHERE is_active = true` | = 3 | Must |
| TC-AC01-02 | Đủ 1.000 sản phẩm | Seed script đã chạy | `SELECT COUNT(*) FROM product` | = 1.000 | Must |
| TC-AC01-03 | Đủ 10.000 đơn COMPLETED | Seed script đã chạy | `SELECT COUNT(*) FROM sales_order WHERE status = 'COMPLETED'` | = 10.000 | Must |
| TC-AC01-04 | Seed chạy lại cho kết quả giống nhau | Xóa DB, chạy seed lần 2 cùng random seed | Đếm lại | Số lượng giống hệt lần 1 | Must |

### AC-02 · Không tồn âm khi giao dịch đồng thời (INV-07, US-02)

> Cách chứng minh: k6 script 100 yêu cầu song song.

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-CONC-01 | 100 yêu cầu đồng thời, tồn 5 | Khả dụng = 5, mỗi yêu cầu mua 1 | 100 `POST /orders/pos` song song (k6) | Đúng 5 trả HTTP 201, 95 trả HTTP 409 `INSUFFICIENT_STOCK`; `GET /inventory/{branchId}/{productId}` → `available=0` | Must |
| TC-CONC-02 | Đơn hỗn hợp POS + online | Tồn 5, 50 POS + 50 webhook đồng thời | Gửi đồng thời (v2 webhook) | Tổng thành công ≤ 5, `available` ≥ 0 | Must |
| TC-CONC-03 | Không deadlock đơn nhiều dòng | Đơn A [P1,P2], đơn B [P2,P1] | 2 `POST /orders/pos` đồng thời | Không deadlock, cả hai thành công nếu đủ tồn | Must |
| TC-CONC-04 | CHECK constraint DB tồn âm | Update DB trực tiếp vượt tồn | `UPDATE inventory SET on_hand=-1` | DB từ chối, `available` không bao giờ âm | Must |

### AC-03 · Đơn online đồng bộ hai chiều (SYNC-01..04, US-03..05)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-ONL-01 | Đặt hàng hợp lệ – giữ chỗ | Khả dụng 10, đơn 3 cái | `POST /integrations/WEB/orders` HMAC đúng (v2) | HTTP 202, đơn RESERVED, `reserved=3`, `available=7` | Must |
| TC-ONL-02 | Gửi trùng đơn (idempotent) | Đơn WEB-100 đã nhận | Gửi lại WEB-100 | HTTP 200 trả đơn cũ, không tạo mới, không giữ chỗ thêm | Must |
| TC-ONL-03 | Chữ ký HMAC sai | Bất kỳ đơn nào | Header `X-Signature` sai | HTTP 401 `UNAUTHORIZED` | Must |
| TC-ONL-04 | SKU chưa ánh xạ | SKU kênh chưa có trong `channel_product` | Webhook | Đơn REJECTED | Must |
| TC-ONL-05 | Tồn mới đến kênh sau giao dịch | Tồn thay đổi sau `POST /orders/pos` | Commit giao dịch | Kênh nhận `PUT /mock/{channelCode}/stock` với tồn mới trong 1 phút | Must |
| TC-ONL-06 | RabbitMQ dừng 5 phút rồi chạy lại | RabbitMQ ngắt | Nhiều `POST /orders/pos` xảy ra | Sau khi RabbitMQ chạy lại, outbox gửi bù đủ, không mất cập nhật | Must |
| TC-ONL-07 | Đơn RESERVED quá 15 phút tự EXPIRED | Đơn RESERVED giữ 3 lúc 10:00 | Đến 10:16 | Đơn EXPIRED, `reserved` giảm 3 trong vòng 1 phút, kênh nhận tồn mới | Must |

### AC-04 · Phân quyền đúng theo chi nhánh (AUTH-01..02, US-08)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-AUTH-01 | Thu ngân CN1 gọi API CN2 | CASHIER gán CN1 | `GET /orders?branchId=2` | HTTP 403 `BRANCH_FORBIDDEN`, `details.branchId=2` | Must |
| TC-AUTH-02 | Thu ngân CN1 tạo đơn tại CN2 | CASHIER gán CN1 | `POST /orders/pos` body `branchId=2` | HTTP 403 `BRANCH_FORBIDDEN` | Must |
| TC-AUTH-03 | Gọi API không có token | Không có header Authorization | `GET /orders` | HTTP 401 `UNAUTHORIZED` | Must |
| TC-AUTH-04 | Token hết hạn sau 15 phút | JWT hết hạn | Gọi bất kỳ API | HTTP 401 `UNAUTHORIZED` | Must |
| TC-AUTH-05 | Refresh token còn hạn 7 ngày | Refresh token hợp lệ | `POST /auth/refresh` `{refreshToken}` | HTTP 200, nhận `accessToken` mới; refresh token cũ thu hồi | Must |
| TC-AUTH-06 | Khóa tài khoản sau 5 lần sai | Sai mật khẩu 5 lần liên tiếp | `POST /auth/login` lần thứ 6 | HTTP 401 `ACCOUNT_LOCKED`, có `details.lockedUntil` | Must |
| TC-AUTH-07 | CASHIER không đổi giá sản phẩm | CASHIER | `PUT /products/{productId}` với `salePrice` mới | HTTP 403 `FORBIDDEN` | Must |
| TC-AUTH-08 | OWNER truy cập mọi chi nhánh | OWNER | `GET /orders` không truyền `branchId` | HTTP 200, thấy đơn mọi chi nhánh | Must |
| TC-AUTH-09 | Nhật ký đăng nhập thất bại | Đăng nhập sai password | `POST /auth/login` | HTTP 401, `audit_log` có bản ghi `LOGIN_FAILED` | Must |

### AC-05 · Dự báo WAPE thấp hơn baseline (AI-03..04, US-10)

> Cách chứng minh: báo cáo MAE, WAPE từ `forecast_run` sau khi SV06 chạy mô hình.

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-FC-01 | Dự báo 7 ngày tốt hơn baseline | Dữ liệu 12 tháng, tập test = 30 ngày cuối | Chạy `POST /forecast/run`, chờ hoàn tất | `GET /forecast/runs/{runId}` → WAPE mô hình < WAPE baseline tốt nhất (naive 7 ngày hoặc MA-28) | Must |
| TC-FC-02 | Dự báo 30 ngày tốt hơn baseline | Như TC-FC-01 | Như TC-FC-01 | WAPE 30 ngày của mô hình < WAPE baseline tốt nhất | Must |
| TC-FC-03 | Mỗi sản phẩm × chi nhánh có kết quả | Sau TC-FC-01 | `SELECT COUNT(*) FROM forecast_result WHERE run_id = ?` | Số dòng = số sản phẩm đủ dữ liệu × 3 chi nhánh × (7+30) ngày tầm | Must |
| TC-FC-04 | Dự báo chạy tự động lúc 02:00 | Scheduler đã cấu hình | Kiểm tra `forecast_run` ngày hôm sau | Có bản ghi `created_at` trong khoảng 02:00–02:30 | Must |

### AC-06 · Dashboard và cảnh báo tồn thấp (DASH-01, DASH-03, US-09)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-DASH-01 | Cảnh báo hiện khi khả dụng ≤ ROP | ROP sữa tươi CN1 = 34, khả dụng = 35 | Bán 6 cái → khả dụng = 29 | Dashboard hiện cảnh báo "Tồn thấp" cho sữa tươi CN1 | Must |
| TC-DASH-02 | Cảnh báo tự đóng khi nhập đủ | Đang có cảnh báo tồn thấp, khả dụng = 29 | Nhập 96 cái → khả dụng = 125 > ROP | Cảnh báo tự đóng, không còn trong danh sách | Must |
| TC-DASH-03 | Cảnh báo hết hàng khi available = 0 | Khả dụng = 1 | Bán 1 cái → khả dụng = 0 | Dashboard hiện cảnh báo "Hết hàng" (đỏ) | Must |
| TC-DASH-04 | Mỗi sản phẩm × CN chỉ có 1 cảnh báo mở | Đã có cảnh báo tồn thấp | Bán thêm | Vẫn chỉ 1 cảnh báo, không tạo thêm | Must |

---

## 5. Test cases bổ sung – POS (ORD-01, PAY-01)

| ID | Tiêu đề | Given | When | Then | Mức độ |
|---|---|---|---|---|---|
| TC-POS-01 | Bán hàng bình thường – tiền mặt | Sản phẩm X có khả dụng 10, giỏ 2 món | `POST /orders/pos` `payment.method=CASH` | HTTP 201, đơn COMPLETED, khả dụng còn 8, sổ kho SALE -2 | Must |
| TC-POS-02 | Tính khuyến mại đúng | Giỏ 238.000 đ với KM-GAO và KM-10 | `POST /orders/quote` | `total=200700` trong `Quote` response | Must |
| TC-POS-03 | Idempotency – cùng key, cùng body | Cùng `Idempotency-Key`, cùng nội dung | `POST /orders/pos` gửi 2 lần | HTTP 200 lần 2, trả đơn cũ, chỉ 1 đơn được tạo | Must |
| TC-POS-04 | Idempotency – cùng key, body khác | Cùng `Idempotency-Key`, body khác | `POST /orders/pos` | HTTP 409 `IDEMPOTENCY_CONFLICT` | Must |
| TC-POS-05 | Tồn bị lấy đúng lúc hoàn tất | Khả dụng 1, đơn khác lấy trước | `POST /orders/pos` | HTTP 409 `INSUFFICIENT_STOCK`, `details.lines` có dòng thiếu | Must |
| TC-POS-06 | Thanh toán thẻ thất bại | `payment.simulate=FAILED` | `POST /orders/pos` `payment.method=CARD` | HTTP 422 `PAYMENT_FAILED`, không tạo đơn | Must |
| TC-POS-07 | Thanh toán QR quá 60 giây | `payment.simulate=TIMEOUT` | `POST /orders/pos` `payment.method=QR` | HTTP 422 `PAYMENT_TIMEOUT`, không tạo đơn | Must |
| TC-POS-08 | Tiền đưa < tổng | `payment.cashReceived=1`, `payment.method=CASH` | `POST /orders/pos` | HTTP 422 `CASH_INSUFFICIENT`, có `details.total` và `details.cashReceived` | Must |
| TC-POS-09 | Sản phẩm không tồn tại | Barcode không có trong hệ thống | `GET /products/lookup?code=INVALID&branchId=1` | HTTP 404 `NOT_FOUND` | Must |
| TC-POS-10 | Sản phẩm ngừng bán | `active=false` | `GET /products/lookup?code=SKU&branchId=1` | HTTP 200, `active=false` – frontend hiển thị cảnh báo | Must |
| TC-POS-11 | Tổng tính lại khác expectedTotal | KM hết hạn đúng lúc hoàn tất | `POST /orders/pos` với `expectedTotal` cũ | HTTP 422 `TOTAL_CHANGED`, có `details.quote` báo giá mới | Must |

---

## 6. Kiểm thử bảo mật (NFR-05, NFR-06, NFR-07)

Xem chi tiết trong `docs/testing/security-checklist.md`.

---

## 7. Kiểm thử hiệu năng (NFR-01, NFR-02)

| Script | Mục tiêu | Ngưỡng đạt |
|---|---|---|
| `load-read-api.js` | `GET /products`, `GET /orders`, `GET /inventory` với 50 VU | p95 < 300 ms |
| `load-create-order.js` | `POST /orders/pos` với 50 VU | p95 < 500 ms |
| `concurrency-stock.js` | 100 `POST /orders/pos` đồng thời, tồn 5 | Đúng 5 HTTP 201, `available` không âm |

| Hành vi | Ngưỡng |
|---|---|
| Tồn mới đến kênh sau commit | < 1 phút |
| Đơn EXPIRED sau 15 phút chưa thanh toán | < 1 phút sau mốc 15 phút |

---

## 8. Kế hoạch theo tuần

| Tuần | Việc làm | Deliverable |
|---|---|---|
| **1** (28/10 – 03/11) | Viết test plan AC-01..06 đầy đủ, checklist security, câu hỏi với nhóm | `docs/testing/test-plan.md`, `docs/testing/security-checklist.md` |
| **2** (04/11 – 10/11) | Viết Postman test cho AUTH, ORD, INV khi API v1 sẵn sàng; k6 scripts; Playwright skeleton | Collection đầy đủ, k6 scripts, Playwright skeleton |
| **3** (11/11 – 17/11) | Chạy full test, báo cáo lỗi, security checklist OWASP, ma trận truy vết | `docs/testing/test-report.md`, `docs/testing/traceability-matrix.md` |
| **4** (18/11 – 24/11) | Regression test, load test cuối, hoàn thiện báo cáo cho demo | Báo cáo kiểm thử cuối |

---

## 9. Quy trình báo lỗi

1. Phát hiện lỗi → mở Issue dùng template `bug.md`.
2. Gán nhãn `test`, gán milestone tuần, gán người phụ trách module.
3. Ghi rõ: mô tả, bước tái hiện, kết quả mong đợi, kết quả thực tế, môi trường, mức độ.
4. Lỗi mức "chặn demo" → báo ngay qua daily.
5. Sau khi fix → SV08 verify và đóng issue.

---

## 10. Definition of Done cho QA

- [ ] Mọi TC must-have đã chạy và pass.
- [ ] Không còn lỗi mức "chặn demo" chưa xử lý.
- [ ] Coverage report module `sales` và `inventory` ≥ 70 %.
- [ ] Gitleaks CI xanh.
- [ ] Báo cáo kiểm thử cập nhật trong `docs/testing/test-report.md`.
- [ ] Ma trận truy vết hoàn chỉnh trong `docs/testing/traceability-matrix.md`.

---

*Phiên bản 1.1 – 30/10/2026: bổ sung AC-01, AC-05, AC-06; thêm TC-POS-04 (IDEMPOTENCY_CONFLICT); làm rõ TC-POS-06/07 (payment.simulate); cập nhật tổng 47 test case.*
