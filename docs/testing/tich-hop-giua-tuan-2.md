# Tích hợp giữa tuần 2: luồng POS từ giao diện tới CSDL

Issue #11. Mục tiêu: một đơn POS đi trọn từ màn hình thu ngân → API → PostgreSQL (đơn, dòng đơn, thanh toán, tồn, sổ kho, outbox) → hóa đơn, trên stack Docker Compose thật.

Lần rà 1: 07/11/2026, `develop` tại `64a3b6c`. Lần rà 2: 13/11/2026, `develop` tại `15d136b` (kết quả ở mục "Lần chạy lại 13/11").

## Kết luận

**Đoạn API → CSDL → hóa đơn đã chạy trọn trên stack thật** (13/11): Newman 8 request, 15/15 assertion PASS, kể cả bước hóa đơn; dữ liệu trong PostgreSQL khớp; gửi lại cùng `Idempotency-Key` không tạo đơn thứ hai.

**Chưa chạy được từ giao diện**, vì màn POS dùng API thật (#73–#77) chưa có. Issue #11 giữ mở cho đoạn giao diện.

## Đoạn đã có bằng chứng

| Đoạn | Bằng chứng | Kết quả |
| --- | --- | --- |
| Đăng nhập, `/auth/me`, refresh, logout trên API thật | #148, PR #250 (`docs/testing/results/issue-148-*.json`) | 26/26 PASS, 12 tài khoản, 3 chi nhánh |
| Giao diện đăng nhập → backend Docker thật | PR #246, `npm run test:auth-integration` | 7/7 PASS; 5 vai trò, khóa tài khoản sau 5 lần sai |
| Login → quote → `POST /orders/pos` → tồn giảm → logout | PR #254, Newman chạy local trên `d018575` | 13/15 assertion PASS; 2 FAIL ở bước hóa đơn (404) |
| Bán, nhập, xuất hủy đồng thời; CHECK chặn tồn âm | #56 (`MixedStockTransactionTest`, Testcontainers) | PASS trong CI backend |
| Mã đơn theo chi nhánh/ngày, thiếu tồn có `details.lines`, khuyến mãi hết lượt | #32, #33 (unit + V8 chạy trên PostgreSQL 16) | PASS |

## Chặn

| # | Chặn | Issue | Phụ trách | Trạng thái 13/11 |
| --- | --- | --- | --- | --- |
| 1 | Chưa có `GET /orders/{orderId}` nên không hiển thị được hóa đơn sau khi bán | #259 | SV02 | **Hết chặn**: #289 đã merge, bước 06 PASS |
| 2 | Chưa có màn POS dùng API thật (giỏ hàng, thanh toán, hóa đơn) | #73–#77 | SV04 | **Còn chặn**: layout #71 đã có, các màn bán chưa bắt đầu |
| 3 | Idempotency-Key chưa chặn bấm Hoàn tất hai lần | #34 | SV02 | **Hết chặn**: #253 đã merge, chạy lại thấy không tạo đơn thứ hai |
| 4 | Khóa tồn theo `productId` tăng dần chưa có trên `develop` | #57, #268 | SV03 | **Hết chặn**: #269 và #273 đã merge |
| 5 | Job CI `pos-newman` của PR #254 chưa chạy được trên CI | #149 (PR #254) | SV08 | **Còn mở**: chạy local PASS (dưới đây), PR #254 cần gộp `develop` để CI chạy lại |

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

## Lần chạy lại 13/11

Môi trường: máy Lead, Docker Desktop 29.8.1, `develop` tại `15d136b` (migration tới V12). Dùng bộ QA của PR #254 (`tests/api/pos149`, project Compose riêng `greenmart-qa149`, cổng 18081, dữ liệu dev không bị đụng tới).

```powershell
npm --prefix tests/api/pos149 ci --ignore-scripts --no-audit --no-fund
npm --prefix tests/api/pos149 test          # 3/3 kịch bản runner PASS
python tests/api/pos149/prepare-qa.py --source-root .
npm --prefix tests/api/pos149 run pos
python tests/api/pos149/prepare-qa.py --stop
```

**Newman:** 8 request, 15 assertion, 0 lỗi, exit 0. Bằng chứng: [issue-11-2026-11-13-newman-summary.json](results/issue-11-2026-11-13-newman-summary.json).

**Kiểm tra trong CSDL** sau đơn `POS-GM001-20261113-0001` (1 gạo 180.000 đ + 2 sữa 29.000 đ, khách đưa 250.000 đ):

| Bảng | Kết quả |
| --- | --- |
| `sales_order` | `COMPLETED`, subtotal 238.000, giảm 0, total 238.000 |
| `order_item` | 2 dòng, giá chụp 180.000 và 29.000, thành tiền 180.000 và 58.000 |
| `payment` | `CASH`, `PAID`, amount 238.000, `cash_received` 250.000, thối 12.000 |
| `inventory_transaction` | 2 dòng `SALE`: −1 gạo, −2 sữa; tồn còn 9 và 18 |
| `outbox_event` | 1 `order.completed` + 2 `stock.changed`, cả 3 đã `SENT` |

**Bấm Hoàn tất hai lần (bước 7):** gửi `POST /orders/pos` hai lần cùng `Idempotency-Key`. Lần 1 trả 201 (đơn `POS-GM001-20261113-0002`, 33 ms); lần 2 trả 200 với **cùng id và mã đơn**. Trong CSDL chỉ có 2 đơn của ngày (1 từ Newman, 1 từ lần thử này), không có đơn thứ ba; tồn gạo giảm đúng 1.

**Còn lại để đóng #11:** bước 4 và 6 trên giao diện (bán từ màn thu ngân, đo thời gian theo NFR-12, màn hóa đơn) khi #73–#77 xong.
