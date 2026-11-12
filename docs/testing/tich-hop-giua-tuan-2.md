# Tích hợp giữa tuần 2: luồng POS từ giao diện tới CSDL

Issue #11. Mục tiêu: một đơn POS đi trọn từ màn hình thu ngân → API → PostgreSQL (đơn, dòng đơn, thanh toán, tồn, sổ kho, outbox) → hóa đơn, trên stack Docker Compose thật.

Ngày rà: 07/11/2026, `develop` tại `64a3b6c`.

## Kết luận

**Chưa chạy trọn được từ giao diện.** Phần API → CSDL đã có bằng chứng chạy thật; phần giao diện POS và API hóa đơn chưa có. Issue #11 giữ mở và chạy lại khi các mục ở "Chặn" xong.

## Đoạn đã có bằng chứng

| Đoạn | Bằng chứng | Kết quả |
| --- | --- | --- |
| Đăng nhập, `/auth/me`, refresh, logout trên API thật | #148, PR #250 (`docs/testing/results/issue-148-*.json`) | 26/26 PASS, 12 tài khoản, 3 chi nhánh |
| Giao diện đăng nhập → backend Docker thật | PR #246, `npm run test:auth-integration` | 7/7 PASS; 5 vai trò, khóa tài khoản sau 5 lần sai |
| Login → quote → `POST /orders/pos` → tồn giảm → logout | PR #254, Newman chạy local trên `d018575` | 13/15 assertion PASS; 2 FAIL ở bước hóa đơn (404) |
| Bán, nhập, xuất hủy đồng thời; CHECK chặn tồn âm | #56 (`MixedStockTransactionTest`, Testcontainers) | PASS trong CI backend |
| Mã đơn theo chi nhánh/ngày, thiếu tồn có `details.lines`, khuyến mãi hết lượt | #32, #33 (unit + V8 chạy trên PostgreSQL 16) | PASS |

## Chặn

| # | Chặn | Issue | Phụ trách | Hạn |
| --- | --- | --- | --- | --- |
| 1 | Chưa có `GET /orders/{orderId}` nên không hiển thị được hóa đơn sau khi bán | #259 | SV02 | 10/11 |
| 2 | Chưa có màn POS dùng API thật (layout, giỏ hàng, thanh toán, hóa đơn) | #71, #77 | SV04 | theo issue |
| 3 | Idempotency-Key chưa chặn bấm Hoàn tất hai lần | #34 (PR #253 đang sửa) | SV02 | theo issue |
| 4 | Khóa tồn theo `productId` tăng dần chưa có trên `develop` (PR #257 đã đóng, chưa merge) | #57 | SV03 | theo issue |
| 5 | Job CI `pos-newman` của PR #254 dừng ở bước nạp seed, Newman chưa chạy trên CI; log bị ẩn (`raw output withheld`) nên không xem được nguyên nhân | #149 (PR #254) | SV08 | trước khi merge #254 |

## Cách chạy lại khi hết chặn

1. Máy chạy: Docker Desktop đang chạy; `.env` có `JWT_SECRET` ≥ 32 byte.
2. `docker compose up -d --wait`, rồi `scripts/seed.ps1` (hoặc `seed.sh`).
3. Ghi lại tồn ban đầu của 2 sản phẩm sẽ bán tại GM-001 (`GET /api/v1/inventory?branchId=…&productId=…`).
4. Trên giao diện `http://localhost:3000`, đăng nhập `thungan.gm001` / `Demo@123`, bán 1 đơn 3 món, trả tiền mặt (đo thời gian, NFR-12: dưới 30 giây).
5. Kiểm tra trong CSDL:
   - `sales_order`: 1 đơn `COMPLETED`, mã `POS-GM001-yyyyMMdd-NNNN`.
   - `order_item`: 3 dòng, giá và chiết khấu đã chụp.
   - `payment`: `PAID`, `change` đúng.
   - `inventory`: `on_hand` giảm đúng số lượng; `inventory_transaction`: 3 dòng `SALE`.
   - `outbox_event`: `order.completed`, `stock.changed` chuyển `SENT`.
6. Màn hóa đơn hiển thị đủ mục ORD-07 và khớp `GET /api/v1/orders/{orderId}`.
7. Bấm Hoàn tất lần hai với cùng `Idempotency-Key` (giả lập mất mạng): không có đơn thứ hai.
8. Ghi kết quả, ảnh chụp màn hình và câu truy vấn vào cuối file này, rồi đóng #11.
