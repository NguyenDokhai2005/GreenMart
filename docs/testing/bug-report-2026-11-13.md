# Báo cáo lỗi tồn đọng – GreenMart DT03 (13/11/2026)

- **Phạm vi:** nhánh `develop`. Đợt 1 tại commit `cdef04c` (sau khi merge #302): Lỗi 1–12. Đợt 2 tại commit `8d46773` (sau khi merge #303): Lỗi 13–20; ở đợt này Lỗi 1–12 chưa có commit nào sửa.
- **Cách rà soát:** chạy toàn bộ test tự động (đợt 1); dựng stack Docker Compose với seed demo; gọi API thật bằng các tài khoản demo; đọc code backend, infra và forecast-service. Đợt 2 không chạy lại test tự động vì commit mới chỉ thêm tài liệu và script QA.
- **Người lập:** Lead (SV01). Người xử lý ghi trong từng lỗi là **đề xuất**, Lead chốt khi tạo issue.

## 1. Tóm tắt

| Hạng mục | Kết quả |
| --- | --- |
| Backend `mvn verify` (Testcontainers) | 429/429 PASS |
| Frontend lint, format, test, build | PASS, 214/214 test |
| Forecast-service `pytest` | 172 PASS, 1 skip |
| Mock-connector | 8/8 PASS |
| Kiểm thử API thực tế đợt 1 (39 ca) | 33 đạt, 6 lỗi (xem mục 3) |
| Kiểm thử API thực tế đợt 2 (khoảng 110 ca) | Thêm 8 lỗi (Lỗi 13–20) |
| QA #152 – RBAC chéo chi nhánh | 591 checks, 19 FAIL (Lỗi 3, Lỗi 4) |
| QA #153 – Khuyến mại và đơn hết hạn | 38 checks: 28 PASS, 6 FAIL, 4 BLOCKED (liên quan Lỗi 9, Lỗi 13) |

Đã xác nhận chạy đúng trên stack thật:
- Bán POS và chống trùng bằng `Idempotency-Key`.
- Chống bán vượt tồn: tồn 23, gửi 33 request song song → 23 đơn thành công, 10 bị từ chối 409, tồn về 0, không âm.
- Chặn chéo chi nhánh ở đơn, báo cáo, tồn kho, sổ kho và phiếu kho.
- Ẩn `costPrice` với thu ngân; cấm thu ngân giảm giá thủ công; cấm tự duyệt phiếu kho; khóa tài khoản sau 5 lần đăng nhập sai.
- Webhook đơn online: từ chối chữ ký sai, thiếu hoặc ký bằng secret của kênh khác (401); 8 request trùng gửi song song chỉ tạo 1 đơn; thiếu tồn thì lưu `REJECTED` và trả 409.
- Báo cáo doanh thu tháng 10 khớp tuyệt đối với DB (825 đơn, 585.853.600 đ, 14 phiếu trả); nhóm theo tuần và tháng đúng.
- Phiên đăng nhập: refresh token xoay vòng, token cũ bị từ chối; đăng xuất thu hồi refresh token; tài khoản bị vô hiệu hóa bị chặn ngay cả khi access token còn hạn.
- Chuyển kho: không nhận trước khi duyệt; chi nhánh đích không duyệt được; không hủy khi đang vận chuyển; không nhận quá số gửi; nhận thiếu được ghi chênh lệch.

## 2. Danh sách lỗi

| # | Mức | Lỗi | Đề xuất xử lý | Trạng thái |
| --- | --- | --- | --- | --- |
| 1 | 🔴 P1 | Không có ánh xạ SKU kênh, đồng bộ đa kênh không chạy | SV07, SV06 | Mở |
| 2 | 🟠 P1 | Role `forecast_ro` đọc được `password_hash` | SV03, SV07 | Mở từ 01/11 |
| 3 | 🟠 P1 | `/products/lookup` lộ tồn chi nhánh không được gán | SV02, SV03 | Mở (QA #152) |
| 4 | 🟡 P2 | Không ghi audit khi bị chặn `BRANCH_FORBIDDEN` | SV03 | Mở (QA #152) |
| 5 | 🟡 P2 | Forecast-service không có xác thực | SV06 | Mở |
| 6 | 🟡 P2 | Sổ kho không ghi người bán (`created_by = NULL`) | SV03 | Mở |
| 7 | 🟡 P2 | Có thể tạo trùng đơn POS khi Redis lỗi hoặc xử lý chậm | SV02 | Mở |
| 8 | 🟡 P2 | Thiếu endpoint danh sách đã đặc tả trong OpenAPI | SV02, SV03 | Mở |
| 9 | 🟡 P3 | Lượt dùng khuyến mại sẽ bị đếm theo dòng hàng | SV02 | Tiềm ẩn (chờ #39) |
| 10 | 🟢 P3 | `items: [null]` trả 500 thay vì 400 | SV02 | Mở |
| 11 | 🟢 P3 | Thủ kho duyệt phiếu nhận sai mã lỗi | SV03 | Mở |
| 12 | 🟢 P3 | Surefire phải ép kill JVM sau khi chạy test | SV07 | Mở |
| 13 | 🔴 P1 | Đơn online giữ hàng vô thời hạn | SV02 | Mở (chờ #40, #41, #42) |
| 14 | 🟠 P2 | Cảnh báo tồn thấp gần như trống sau khi seed | SV03, SV06 | Mở |
| 15 | 🟠 P2 | Báo cáo doanh thu không giới hạn khoảng ngày | SV06 | Mở |
| 16 | 🟡 P2 | Hai báo cáo tính tiền trả hàng theo hai cách | SV06 | Mở |
| 17 | 🟡 P2 | Phiếu kiểm kê (`ADJUST`) chưa hỗ trợ | SV03 | Mở |
| 18 | 🟡 P3 | Webhook nhận `orderedAt` bất kỳ | SV07 | Mở |
| 19 | 🟢 P3 | Đổi mật khẩu trùng mật khẩu cũ vẫn được chấp nhận | SV08 | Mở |
| 20 | 🟢 P3 | Log in mật khẩu bảo mật tự sinh mỗi lần khởi động | SV08 | Mở |

## 3. Chi tiết

### Lỗi 1 – Không có ánh xạ SKU kênh, đồng bộ đa kênh không chạy (P1)

**Ảnh hưởng:** chặn AC-03 và toàn bộ demo đa kênh. Webhook #132 vừa merge sẽ trả `UNMAPPED_SKU` cho mọi đơn WEB/MARKET; tồn kho không được đẩy ra kênh.

**Bằng chứng:** sau khi seed và bán thử trên stack:

```sql
SELECT count(*) FROM channel_product;   -- 0
SELECT status, count(*) FROM outbox_event GROUP BY 1;  -- SENT | 50
SELECT count(*) FROM channel_delivery;  -- 0
SELECT count(*) FROM sync_log;          -- 0
```

**Nguyên nhân:**
- Seed ([generate_data.py](../../forecast-service/scripts/generate_data.py)) không sinh dòng `channel_product` nào.
- [ChannelService.mapProduct](../../backend/src/main/java/vn/greenmart/integration/channel/ChannelService.java#L48) không được gọi ở đâu, cũng không có API nào gọi tới.
- [ChannelStockPublisher](../../backend/src/main/java/vn/greenmart/integration/stocksync/ChannelStockPublisher.java) chỉ đẩy tồn cho sản phẩm đã ánh xạ, nên bỏ qua mọi sự kiện.

Issue #130 đã đóng nhưng thiếu phần dữ liệu này.

**Đề xuất:** seed ánh xạ SKU cho WEB và MARKET (toàn bộ hoặc một tập sản phẩm demo). Nếu cần quản trị thì thêm API ADMIN gọi `mapProduct`. Thêm bước kiểm đếm `channel_product > 0` vào `verify_seed.sql`.

### Lỗi 2 – Role `forecast_ro` đọc được `password_hash` (P1, bảo mật)

**Tái hiện:**

```sh
docker compose exec -T postgres sh -c \
  'PGPASSWORD="$FORECAST_DB_PASSWORD" psql -h 127.0.0.1 -U "$FORECAST_DB_USER" -d "$POSTGRES_DB" \
   -At -c "SELECT count(password_hash) FROM app_user"'
# → 12
```

**Nguyên nhân:** [01-forecast-role.sh:10](../../infra/postgres/init/01-forecast-role.sh#L10) dùng `ALTER DEFAULT PRIVILEGES … GRANT SELECT ON TABLES`. Lệnh này cấp quyền SELECT trên mọi bảng do Flyway tạo, kể cả `app_user` và `sales_channel.webhook_secret`.

**Đề xuất:** thêm migration V13 `REVOKE SELECT ON app_user, sales_channel, audit_log FROM forecast_user`, sau đó chỉ GRANT SELECT các bảng dự báo thực sự cần. Không sửa V2 hay file init đã chạy. Thêm test migration kiểm tra role không đọc được `app_user`.

### Lỗi 3 – `/products/lookup` lộ tồn chi nhánh không được gán (P1, QA #152)

Nguồn: [issue-152-recheck-2026-11-13.md](issue-152-recheck-2026-11-13.md#L59), Lỗi 1.

**Tái hiện:** đăng nhập `thungan.gm001`, gọi:

```
GET /api/v1/products/lookup?code=COM-SUON-BI-CHA-0001&branchId=2
```

- **Mong đợi:** 403 `BRANCH_FORBIDDEN`.
- **Thực tế:** 200, trả về số `available` của GM-002.
- Cùng phiên, `GET /inventory?branchId=2` bị chặn đúng 403.
- Có 18 ca FAIL (9 tài khoản BRANCH_MANAGER/CASHIER/STOREKEEPER × 2 chi nhánh ngoài phạm vi).

**Nguyên nhân:** `ProductServiceImpl.lookupProduct` gọi thẳng `InventoryService.getAvailable` mà không kiểm tra `SecurityUtils.getAccessibleBranchIds`.

**Đề xuất:** kiểm tra chi nhánh ngay khi vào lookup, giữ quyền đọc toàn chuỗi cho ADMIN/OWNER. Thêm test hồi quy role × chi nhánh.

### Lỗi 4 – Không ghi audit khi bị chặn `BRANCH_FORBIDDEN` (P2, QA #152)

Nguồn: [issue-152-recheck-2026-11-13.md](issue-152-recheck-2026-11-13.md#L68), Lỗi 2.

**Tái hiện:** `thungan.gm001` gọi `GET /api/v1/inventory?branchId=2`, nhận đúng 403, nhưng số dòng `audit_log` mới là 0. traceId lần tái kiểm tra: `35052d86-f608-487b-88e0-5bfb700671ee`.

**Yêu cầu vi phạm:** EX-11, AUTH-03 và mô tả trong OpenAPI ("Sai chi nhánh trả 403 `BRANCH_FORBIDDEN` và được ghi nhật ký").

**Đề xuất:** ghi audit tập trung ở một chỗ, ví dụ `GlobalExceptionHandler` hoặc một listener, và ghi trong transaction riêng (`REQUIRES_NEW`) để không bị mất khi transaction nghiệp vụ rollback. Lead chốt tên action và metadata (câu hỏi Q-27).

### Lỗi 5 – Forecast-service không có xác thực (P2, bảo mật)

`POST /forecast/run` và `GET /forecast/runs/{runId}` ([router.py](../../forecast-service/app/api/router.py#L43)) nhận request không có token.

- Kiểm chứng: `GET http://localhost:8001/forecast/runs/1` trả 404 (đã xử lý request), không có lỗi 401.
- Cổng chỉ mở cho `127.0.0.1`, nhưng mọi container trong mạng `greenmart` đều kích hoạt được lượt chạy dự báo (tác vụ nặng).

**Đề xuất:** yêu cầu một token nội bộ chung giữa backend và forecast-service (biến môi trường mới trong `.env`), hoặc chỉ cho backend gọi qua mạng nội bộ riêng.

### Lỗi 6 – Sổ kho không ghi người bán (P2)

**Bằng chứng:** sau khi bán POS bằng `thungan.gm001`:

```sql
SELECT type, delta_on_hand, created_by FROM inventory_transaction
WHERE ref_type = 'ORDER' AND ref_id = <orderId>;
-- SALE | -1 | (NULL)
```

Trên stack kiểm thử có 24 dòng `ref_type='ORDER'` đều để trống `created_by`.

**Nguyên nhân:** [InventoryTransactionWriter.appendOrder](../../backend/src/main/java/vn/greenmart/inventory/repository/InventoryTransactionWriter.java#L34) không nhận và không ghi người thực hiện. Comment trong code còn ghi "chờ Identity", trong khi Identity đã xong. Phiếu kho (IMPORT/EXPORT/TRANSFER) thì có ghi đủ.

**Đề xuất:** thêm tham số `actorId` cho `reserve/release/commitSale/sellDirect` và truyền `SecurityUtils.getCurrentUserId()` từ `PosOrderServiceImpl`. Riêng job hệ thống (hết hạn giữ chỗ, webhook) có thể để NULL.

### Lỗi 7 – Có thể tạo trùng đơn POS (P2)

Vị trí: [PosIdempotencyService.java](../../backend/src/main/java/vn/greenmart/sales/service/PosIdempotencyService.java#L30-L75).

- **Kịch bản A:** đơn đã commit vào DB, nhưng ghi bản ghi `COMPLETED` vào Redis lỗi. Code chỉ ghi log (dòng 75). Khóa `PROCESSING` hết hạn sau 30 giây (dòng 30); client gửi lại cùng key thì **đơn thứ hai được tạo**, tồn kho bị trừ hai lần.
- **Kịch bản B:** một request chạy quá 30 giây (DB chậm hoặc chờ khóa). Khóa hết hạn khi request đầu chưa xong, request thứ hai được xử lý song song.

**Đề xuất:** lưu `idempotency_key` vào `sales_order`, kèm UNIQUE theo `(created_by, idempotency_key)` hoặc lưu sẵn trong cột/bảng V11, để DB là chốt chặn cuối. Khi trùng thì trả lại đơn cũ.

### Lỗi 8 – Thiếu endpoint danh sách đã đặc tả trong OpenAPI (P2)

| Endpoint | Thực tế | Màn hình bị chặn |
| --- | --- | --- |
| `GET /branches` | 404 | Chọn chi nhánh, bộ lọc |
| `GET /orders` | 404 | #80 Danh sách đơn |
| `GET /stock-documents` | 405 | #92, #93 Danh sách phiếu nhập và xuất hủy |
| `GET /inventory/{branchId}/{productId}` | 404 | Chi tiết tồn |

Còn chưa có: `/orders/{id}/complete`, `/orders/{id}/cancel`, `/promotions/**`, `/users/**` (đúng theo kế hoạch Tuần 3).

### Lỗi 9 – Lượt dùng khuyến mại sẽ bị đếm theo dòng hàng (P3, tiềm ẩn)

[PosOrderServiceImpl.java:85-90](../../backend/src/main/java/vn/greenmart/sales/service/PosOrderServiceImpl.java#L85-L90) gọi `recordUsage` cho khuyến mại cấp đơn và **cho từng dòng hàng**. Một khuyến mại áp cho 3 sản phẩm trong cùng đơn sẽ bị trừ 3 lượt `used_count`.

Hiện chưa thấy lỗi vì [DefaultPromotionCalculator](../../backend/src/main/java/vn/greenmart/sales/service/DefaultPromotionCalculator.java) luôn trả `null`. Cần sửa cùng #39: gom các `promotionId` khác nhau rồi mỗi khuyến mại chỉ ghi 1 lượt cho mỗi đơn (hoặc theo đúng định nghĩa "lượt" trong SRS).

### Lỗi 10 – `items: [null]` trả 500 (P3)

```
POST /api/v1/orders/quote
{"branchId": 1, "items": [null]}
→ 500 INTERNAL_SERVER_ERROR (mong đợi 400 VALIDATION_ERROR)
```

Nguyên nhân: [PosOrderRequest.java:18](../../backend/src/main/java/vn/greenmart/sales/dto/PosOrderRequest.java#L18) và [QuoteRequest.java:18](../../backend/src/main/java/vn/greenmart/sales/dto/QuoteRequest.java#L18) khai báo `List<@Valid OrderLineRequest>` nhưng thiếu `@NotNull` cho phần tử. Sửa thành `List<@NotNull @Valid OrderLineRequest>`.

### Lỗi 11 – Thủ kho duyệt phiếu nhận sai mã lỗi (P3)

[StockDocumentServiceImpl.java:140-141](../../backend/src/main/java/vn/greenmart/inventory/service/StockDocumentServiceImpl.java#L140-L141) kiểm tra người lập (`requireApprovableBy`) trước khi kiểm tra vai trò `BRANCH_MANAGER`. Vì vậy thủ kho duyệt phiếu do chính mình lập nhận `422 SELF_APPROVAL_FORBIDDEN`, trong khi lý do đúng hơn là `403 FORBIDDEN` (không có quyền duyệt). Đề xuất đảo thứ tự hai dòng.

### Lỗi 12 – Surefire phải ép kill JVM sau khi chạy test (P3)

Cuối log `mvn verify` có dòng:

```
[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).
```

Build vẫn SUCCESS nhưng mỗi lần chạy chậm thêm khoảng 30 giây. Nghi do các `ThreadPoolTaskScheduler` tự khai báo trong `ChannelDelivery` không dừng khi tắt ứng dụng. Đề xuất đặt `setWaitForTasksToCompleteOnShutdown` hoặc tắt scheduler trong profile test.

### Lỗi 13 – Đơn online giữ hàng vô thời hạn (P1)

**Ảnh hưởng:** mỗi đơn online làm tồn khả dụng giảm vĩnh viễn; POS cũng không bán được phần đang bị giữ. Chặn EX-05, EX-06 (QA #153 đang BLOCKED) và luồng UC-02.

Webhook #132 ([ChannelOrderIntake](../../backend/src/main/java/vn/greenmart/integration/webhook/ChannelOrderIntake.java)) giữ chỗ tồn kho cho đơn `RESERVED` và `CONFIRMED`, nhưng chưa có đường nào nhả ra:

| Đường nhả giữ chỗ | Thực tế |
| --- | --- |
| Job hết hạn 15 phút → `EXPIRED` (ORD-03, BR-03, BR-04) | Chưa có; không có code nào xử lý `reserved_until` |
| `POST /orders/{orderId}/complete` | 404 |
| `POST /orders/{orderId}/cancel` | 404 |
| `POST /integrations/{channelCode}/orders/{externalOrderId}/events` (kênh báo đã thanh toán) | 404, nên đơn ONLINE không bao giờ sang `CONFIRMED` |

**Bằng chứng:** sau khi thêm tạm một ánh xạ SKU và gửi 6 đơn hợp lệ qua webhook (5 `RESERVED`, 1 `CONFIRMED`):

```sql
SELECT on_hand, reserved FROM inventory WHERE branch_id = 1 AND product_id = 1000;
-- 32 | 7   (trước khi thử: 32 | 0)
```

Quá `reservedUntil` vẫn không có gì thay đổi và không có API nào giải phóng được 7 đơn vị này.

**Đề xuất:** hoàn thành #40, #41, #42 trước khi bật webhook trong demo. Job hết hạn nên quét theo chỉ mục `idx_order_reserved` có sẵn, gọi `InventoryService.release` cho từng dòng và phát `order.expired` qua outbox.

### Lỗi 14 – Cảnh báo tồn thấp gần như trống sau khi seed (P2)

**Bằng chứng:**

```sql
SELECT count(*) FROM inventory
WHERE on_hand - reserved = 0
   OR (reorder_point IS NOT NULL AND on_hand - reserved <= reorder_point);  -- 181
SELECT count(*) FROM stock_alert WHERE closed_at IS NULL;                    -- 1
```

`GET /api/v1/alerts/low-stock` (OWNER) chỉ trả 1 cảnh báo, trong khi `GET /inventory?branchId=1&stockStatus=LOW` báo 50 dòng và `stockStatus=OUT` báo 11 dòng.

**Nguyên nhân:** cảnh báo chỉ được tạo trong [StockAlertService.reconcileAfterStockUpdate](../../backend/src/main/java/vn/greenmart/inventory/service/StockAlertService.java), tức khi tồn kho thay đổi qua API. Seed nạp `inventory` thẳng bằng SQL nên không sinh cảnh báo. Phần backfill trong `V12__stock_alert.sql` chạy lúc DB còn trống.

**Ảnh hưởng:** màn cảnh báo tồn thấp (#98) và dashboard (#97) không có dữ liệu demo, dù #107 đã sinh sẵn sản phẩm tồn thấp.

**Đề xuất:** thêm bước dựng lại `stock_alert` từ `inventory` vào cuối seed (cùng câu INSERT như backfill của V12) và kiểm đếm trong `verify_seed.sql`.

### Lỗi 15 – Báo cáo doanh thu không giới hạn khoảng ngày (P2)

**Tái hiện:** đăng nhập `owner`, gọi:

```
GET /api/v1/reports/revenue?from=0001-01-01&to=2026-10-31
→ 200, 739.920 điểm dữ liệu, 4 giây
```

**Nguyên nhân:** [ReportServiceImpl.getRevenueReport](../../backend/src/main/java/vn/greenmart/reporting/service/ReportServiceImpl.java) tạo một bucket cho mỗi ngày trong khoảng `from`–`to` mà không có giới hạn trên. Một tài khoản OWNER hoặc BRANCH_MANAGER gọi lặp lại là đủ làm backend hết bộ nhớ (container giới hạn 768 MiB).

**Đề xuất:** giới hạn khoảng ngày theo `groupBy` (ví dụ 366 ngày với `day`, 5 năm với `month`), vượt thì trả 400 `VALIDATION_ERROR`. Áp dụng tương tự cho `/reports/top-products`.

### Lỗi 16 – Hai báo cáo tính tiền trả hàng theo hai cách (P2)

| Báo cáo | Cách tính tiền trả hàng | Tổng trên dữ liệu seed |
| --- | --- | --- |
| `/reports/revenue` | `sum(return_document.refund_amount)` | 59.350.952 đ |
| `/reports/top-products` | `sum(return_item.quantity × order_item.unit_price)` | 59.871.000 đ |

Chênh 520.048 đ. [ReportQueryRepository.queryTopProducts](../../backend/src/main/java/vn/greenmart/reporting/repository/ReportQueryRepository.java) dùng đơn giá gốc, bỏ qua giảm giá của dòng hàng, trong khi doanh thu bán (`line_total`) đã trừ giảm giá. Hậu quả: `netRevenue` theo sản phẩm thấp hơn thực tế và không cộng khớp với báo cáo tổng.

**Đề xuất:** tính tiền trả theo giá thực trả của dòng (`line_total / quantity × số lượng trả`), hoặc phân bổ `refund_amount` theo dòng. Thêm test đối chiếu tổng của hai báo cáo trên cùng khoảng ngày.

### Lỗi 17 – Phiếu kiểm kê (`ADJUST`) chưa hỗ trợ (P2)

```
POST /api/v1/stock-documents
{"type": "ADJUST", "branchId": 1, "items": [{"productId": 960, "quantity": 1, "countedQuantity": 1}]}
→ 400 VALIDATION_ERROR "Loại phiếu kho chưa được hỗ trợ"
```

OpenAPI v2 đã đặc tả kiểm kê (UC-08, INV-05): hệ thống chụp tồn hiện tại vào `quantity`, thủ kho nhập `countedQuantity` qua PUT, duyệt thì ghi giao dịch `ADJUST`. [StockDocumentServiceImpl](../../backend/src/main/java/vn/greenmart/inventory/service/StockDocumentServiceImpl.java) hiện từ chối loại phiếu này ở cả tạo, xem và sửa. Hệ quả kèm theo: 3 đơn vị nhận thiếu khi chuyển kho (ghi nhận ở trường chênh lệch) chưa có cách điều chỉnh sổ.

### Lỗi 18 – Webhook nhận `orderedAt` bất kỳ (P3)

Đơn gửi qua webhook với `orderedAt` là `2099-01-01T00:00:00+07:00` hoặc `1990-01-01T00:00:00+07:00` đều được nhận (202). Giá trị này ghi thẳng vào `sales_order.created_at` ([ChannelOrderStore.insert](../../backend/src/main/java/vn/greenmart/sales/service/ChannelOrderStore.java)).

**Ảnh hưởng:** đơn sai vị trí khi sắp xếp hoặc lọc theo ngày tạo; đơn năm 2099 sẽ có `completed_at` trước `created_at` khi hoàn tất.

**Đề xuất:** từ chối `orderedAt` lệch quá một ngưỡng so với thời điểm nhận (ví dụ sớm hơn 7 ngày hoặc muộn hơn 5 phút), hoặc lưu thời điểm nhận vào `created_at` và giữ `orderedAt` ở cột riêng.

### Lỗi 19 – Đổi mật khẩu trùng mật khẩu cũ vẫn được chấp nhận (P3)

```
POST /api/v1/auth/change-password
{"currentPassword": "Demo@123", "newPassword": "Demo@123"}
→ 204
```

[AuthServiceImpl.changePassword](../../backend/src/main/java/vn/greenmart/identity/service/AuthServiceImpl.java) không so sánh mật khẩu mới với mật khẩu hiện tại. Mật khẩu yếu (ví dụ `1`) thì đã bị chặn đúng (400). Đề xuất trả 400 `VALIDATION_ERROR` khi mật khẩu mới trùng mật khẩu cũ.

### Lỗi 20 – Log in mật khẩu bảo mật tự sinh mỗi lần khởi động (P3)

Log backend lúc khởi động có dòng `Using generated security password: …` của Spring Boot. Tài khoản mặc định này không đăng nhập được vì [SecurityConfig](../../backend/src/main/java/vn/greenmart/common/security/SecurityConfig.java) không bật Basic hay form login, nhưng dòng log gây nhiễu và dễ bị hiểu nhầm là lộ mật khẩu.

**Đề xuất:** loại `UserDetailsServiceAutoConfiguration` khỏi auto-configuration, hoặc khai báo một `UserDetailsService` rỗng.

## 4. Pull request đang chờ (13/11)

| PR | Nội dung | Trạng thái |
| --- | --- | --- |
| #295 | Báo cáo MAE/WAPE AC-05 | Đã approve, chờ merge |
| #305 | Ràng buộc và chỉ mục CSDL theo ERD (#62) | Chờ review |
| #304 | Hợp đồng API cảnh báo tồn thấp (#61) | Chờ review |
| #284 | Staging tự triển khai | Chờ review |
| #264 | Màn sản phẩm và khách hàng | Changes requested |

## 5. Thứ tự xử lý đề xuất

1. **Lỗi 1, Lỗi 13:** mở khóa demo đa kênh, cần trước khi test #157. Hai lỗi phải sửa cùng nhau: có ánh xạ SKU mà chưa có đường nhả giữ chỗ thì mỗi đơn online sẽ khóa tồn vĩnh viễn.
2. **Lỗi 3, Lỗi 2:** bảo mật; Lỗi 3 đang chặn nghiệm thu AC-04 (#152).
3. **Lỗi 4, Lỗi 6:** truy vết, cần cho AC-04 và AUTH-03.
4. **Lỗi 14, Lỗi 15, Lỗi 16:** trước khi frontend làm dashboard và cảnh báo tồn thấp (#97, #98, #99).
5. **Lỗi 7, Lỗi 8, Lỗi 17:** trước khi frontend nối API thật (#77, #80, #92).
6. **Lỗi 5, 9, 10, 11, 12, 18, 19, 20:** đưa vào danh sách sửa trước feature freeze (#19).

Sau khi sửa, QA chạy lại các runner [issue152_rbac.py](../../tests/api/issue152_rbac.py), [issue153_edges.py](../../tests/api/issue153_edges.py) và Newman #149, ghi bằng chứng theo commit tích hợp.
