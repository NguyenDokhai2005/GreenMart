# HƯỚNG DẪN ĐỌC HIỂU MÃ NGUỒN [generate_data.py](generate_data.py) (DÀNH CHO SV06)

Tài liệu này được biên soạn nhằm giúp bạn (SV06 – phụ trách Data/AI, module `forecast-service`) nắm vững toàn bộ kiến trúc, mục đích, yêu cầu nghiệp vụ và cách tự chạy kiểm thử cho từng hàm trong bộ sinh dữ liệu lịch sử demo GreenMart ([generate_data.py](generate_data.py) - Issue #106 / AI-01).

---

## 1. TỔNG QUAN LUỒNG THỰC THI (EXECUTION PIPELINE)

Quy trình sinh dữ liệu được điều phối tuần tự qua 5 giai đoạn chính trong hàm `main()`:
1. **Master Data** (Dữ liệu nền): Chi nhánh (`Branch`) $\rightarrow$ Danh mục 2 cấp (`Category`) $\rightarrow$ 1.000 sản phẩm (`Product`) $\rightarrow$ 12 tài khoản demo (`AppUser`).
2. **Lịch hết hàng** (`Stockout Schedule`): Mô phỏng trước lịch hết hàng cho 200 SKU bán chạy cốt lõi (2%–3% số ngày/năm).
3. **Nghiệp vụ bán hàng** (`Sales & Orders`): 1.500 khách hàng $\rightarrow$ Khuyến mại $\rightarrow$ 10.000 đơn hàng xử lý (áp dụng kiểm tra stockout và khuyến mại).
4. **Hậu mãi & Ngoại lệ** (`Post-sales & Failures`): 150 phiếu đổi trả hàng $\rightarrow$ 200 đơn online lỗi (hủy, hết hạn, từ chối) $\rightarrow$ 120 nhật ký kiểm toán (`AuditLog`).
5. **Xuất file** (`Exporters`): Xuất `stockout_schedule.json` và `seed_data.sql` (hỗ trợ chạy lặp lại an toàn - Idempotent).

---

## 2. CHI TIẾT CÁC HÀM VÀ MÔ HÌNH DỮ LIỆU

### 2.1. Cấu trúc Đối tượng (Data Models)
- **Định nghĩa**: Các lớp `@dataclass` tương ứng 1:1 với các bảng trong cơ sở dữ liệu PostgreSQL (quy định tại schema `V1__init_schema.sql`).
- **Mục đích**: Lưu trữ dữ liệu trong bộ nhớ (in-memory) có kiểu dữ liệu rõ ràng (`type hints`), giúp dễ kiểm tra logic trước khi xuất ra câu lệnh SQL.
- **Bảng đối chiếu**:
  - `Branch` $\leftrightarrow$ `branch`
  - `Category` $\leftrightarrow$ `category`
  - `Product` $\leftrightarrow$ `product`
  - `AppUser` $\leftrightarrow$ `app_user` & `user_branch`
  - `Customer` $\leftrightarrow$ `customer`
  - `Promotion` $\leftrightarrow$ `promotion`, `promotion_product`, `promotion_branch`, `promotion_channel`
  - `SalesOrder` $\leftrightarrow$ `sales_order`
  - `OrderItem` $\leftrightarrow$ `order_item`
  - `Payment` $\leftrightarrow$ `payment`
  - `ReturnDocument` $\leftrightarrow$ `return_document`
  - `ReturnItem` $\leftrightarrow$ `return_item`
  - `AuditLog` $\leftrightarrow$ `audit_log`

---

### 2.2. Nhóm hàm Master Data (Chặng 1)

#### Hàm `generate_branches()`
- **Mục đích**: Tạo danh sách 3 chi nhánh vật lý của GreenMart tại TP.HCM.
- **Yêu cầu đáp ứng**: Tiêu chí AC-01 và kiểm thử `TC-AC01-01` trong SRS §3:
  - `GM-001`: GreenMart Quận 1 (123 Lê Lợi).
  - `GM-002`: GreenMart Bình Thạnh (45 Điện Biên Phủ).
  - `GM-003`: GreenMart Tân Bình (78 Hoàng Hoa Thám).
- **Cách tự kiểm tra**:
  ```python
  from scripts.generate_data import generate_branches
  branches = generate_branches()
  print([b.code for b in branches]) # ['GM-001', 'GM-002', 'GM-003']
  ```

#### Hàm `generate_categories()`
- **Mục đích**: Tạo cây danh mục 2 cấp gồm 3 danh mục cha Cấp 1 và 15 danh mục con Cấp 2.
- **Yêu cầu đáp ứng**: Cấu trúc bảng `category` tự tham chiếu (`parent_id`) và phân loại thành 3 nhóm ngành kinh doanh chính trong [config.py](config.py):
  - `READY_TO_EAT_BEVERAGE`: Thực phẩm chế biến & Đồ uống (5 danh mục con).
  - `FRESH_FOOD`: Thực phẩm tươi sống (5 danh mục con).
  - `DRY_FOOD_FMCG`: Đồ khô & Tiêu dùng nhanh (5 danh mục con).
- **Cách tự kiểm tra**:
  ```python
  from scripts.generate_data import generate_categories
  cats = generate_categories()
  assert len(cats) == 18
  assert len([c for c in cats if c.parent_id is None]) == 3
  ```

#### Hàm `generate_products()`
- **Mục đích**: Sinh đúng 1.000 mã hàng (SKU) sản phẩm, phân bổ đều vào 15 danh mục con và xác định đúng 200 SKU bán chạy cốt lõi (Top 20% Pareto).
- **Yêu cầu đáp ứng**:
  - Tiêu chí AC-01, `TC-AC01-02` trong SRS.
  - Tái hiện chính xác 2 sản phẩm ví dụ từ tài liệu:
    - ID 101: `GAO-ST25-5KG` (Gạo ST25 túi 5kg, giá bán 180.000 đ, giá vốn 140.000 đ).
    - ID 205: `SUA-TH-1L` (Sữa tươi TH True Milk 1L, giá bán 29.000 đ, giá vốn 22.000 đ).
  - Đảm bảo toàn bộ 1.000 sản phẩm có lãi gộp dương (`sale_price > cost_price > 0`).
  - Barcode duy nhất chuẩn EAN-13 (tiền tố `893` của Việt Nam).
- **Cách tự kiểm tra**:
  ```python
  from scripts.generate_data import generate_products
  products, top_ids = generate_products()
  assert len(products) == 1000
  assert len(top_ids) == 200
  assert 101 in top_ids and 205 in top_ids
  ```

#### Hàm `generate_demo_users()`
- **Mục đích**: Khởi tạo 12 tài khoản nhân sự mẫu phục vụ chạy thử và kiểm thử hệ thống.
- **Yêu cầu đáp ứng**:
  - Phân quyền theo 5 vai trò (`ADMIN`, `OWNER`, `BRANCH_MANAGER`, `CASHIER`, `STOREKEEPER`) trên 3 chi nhánh.
  - Khớp chuẩn OpenAPI contract: Thu ngân CN1 có ID 7, username `thungan.gm001`, tên "Nguyễn Thu Ngân".
  - Slot ID 6 được gán cho nhân sự thật ("Trần Thị Phó Chủ Chuỗi", vai trò `OWNER`), không bị khuyết ID.
  - Mật khẩu cố định: `Demo@123` được băm bằng chuẩn Spring Security BCrypt (`$2a$10$...`).
- **Cách tự kiểm tra**:
  ```python
  from scripts.generate_data import generate_demo_users
  users = generate_demo_users()
  u7 = next(u for u in users if u.id == 7)
  assert u7.username == "thungan.gm001" and u7.role == "CASHIER"
  ```

---

### 2.3. Nhóm hàm Khách hàng & Khuyến mại (Chặng 2)

#### Hàm `generate_customers(count=1500)`
- **Mục đích**: Tạo 1.500 khách hàng thân thiết có số điện thoại Việt Nam hợp lệ và duy nhất.
- **Yêu cầu đáp ứng**:
  - Tái hiện chính xác khách hàng mẫu trong tài liệu: ID 125, tên "Trần Thị Mai", SĐT `0901234567`.
- **Cách tự kiểm tra**:
  ```python
  from scripts.generate_data import generate_customers
  custs = generate_customers(1500)
  c125 = next(c for c in custs if c.id == 125)
  assert c125.name == "Trần Thị Mai" and c125.phone == "0901234567"
  ```

#### Hàm `generate_promotions()`
- **Mục đích**: Tạo các chiến dịch khuyến mại trong 12 tháng lịch sử (khuyến mại sản phẩm, khuyến mại đơn hàng, đợt Tết và các đợt định kỳ hàng tháng).
- **Yêu cầu đáp ứng**:
  - Tái hiện chính xác 2 chương trình mẫu:
    - ID 11: `GAO-15K` (giảm 15.000 đ cho Gạo ST25 5kg, cấp `ITEM`, số tiền `AMOUNT`).
    - ID 12: `DON200K-10` (giảm 10% cho đơn từ 200.000 đ, tối đa 30.000 đ, cấp `ORDER`, `PERCENT`).
  - Đợt Tết Bính Ngọ: ID 13 `TET2026-15` (14 ngày trước Tết, giảm 15% đơn từ 300k).
  - 12 đợt khuyến mại hàng tháng (mỗi đợt 3–7 ngày).
- **Cách tự kiểm tra**:
  ```python
  from scripts.generate_data import generate_promotions
  promos = generate_promotions()
  p11 = next(p for p in promos if p.id == 11)
  assert p11.code == "GAO-15K" and p11.value == 15000
  ```

---

### 2.4. Nhóm hàm Stockout & Sinh Đơn hàng (Bước 0 & Chặng 2)

#### Hàm `get_stockout_schedule(products, top_sku_ids)`
- **Mục đích**: Lập lịch giả lập các ngày hết hàng cho 200 SKU bán chạy trên 3 chi nhánh (tổng cộng 600 chuỗi thời gian).
- **Yêu cầu đáp ứng**:
  - Mỗi cặp (chi nhánh $\times$ top SKU) bị stockout từ 2%–3% số ngày trong năm (7 đến 11 ngày/năm).
  - Mỗi đợt stockout kéo dài 1–3 ngày liên tiếp.
  - Hỗ trợ 2 chế độ:
    - `FULL_DAY`: Hết hàng nguyên ngày.
    - `MID_DAY`: Bán được buổi sáng (trước 11:00), hết hàng từ 11:00 trở đi.
  - Dữ liệu này được lưu thành file JSON để task #107 (tồn kho đầu kỳ) và task #109 (tính doanh số thuần) đọc lại đúng trạng thái.
- **Cách tự kiểm tra**:
  ```python
  from scripts.generate_data import generate_products, get_stockout_schedule
  p, top = generate_products()
  sched = get_stockout_schedule(p, top)
  # Kiểm tra số lượng cặp SKU x CN
  pairs = {(b, pid) for (b, pid, d) in sched.keys()}
  assert len(pairs) == 600
  ```

#### Hàm `calculate_daily_demand_weights()`
- **Mục đích**: Tính toán trọng số nhu cầu mua sắm cho từng ngày trong 365 ngày (01/11/2025 đến 31/10/2026).
- **Yêu cầu đáp ứng**:
  - Hệ số thứ trong tuần: Cuối tuần (T7, CN) có hệ số 1.20, ngày thường 0.85–0.90 (trung bình tuần đúng bằng 1.00).
  - Giai đoạn trước Tết (27/01/2026 – 16/02/2026): Nhu cầu tăng từ 50% đến 100% (+92.8% thực tế).
  - 3 ngày Tết (Mùng 1–3): Nhu cầu giảm sâu còn 0.10.
  - Các ngày lễ lớn (30/4–1/5, 2/9, 20/10, Giáng sinh): Có hệ số tăng trưởng từ 1.20 đến 1.35.
  - Áp dụng trần chặn `MAX_DEMAND_LIFT_CAP = 3.0` để tránh bùng nổ đơn hàng bất thường.

#### Hàm `generate_orders_and_items(...)`
- **Mục đích**: Sinh ra đúng 10.000 đơn hàng đã xử lý thành công (`COMPLETED`) và toàn bộ các dòng hàng (`order_item`), giao dịch thanh toán (`payment`).
- **Yêu cầu đáp ứng**:
  - Tiêu chí AC-01, `TC-AC01-03`: Đúng 10.000 đơn hàng xử lý.
  - Tỷ lệ kênh: POS 70% (7.000), WEB 20% (2.000), MARKET 10% (1.000).
  - Tỷ lệ chi nhánh: GM-001 45% (4.500), GM-002 35% (3.500), GM-003 20% (2.000).
  - Luật Pareto 80/20: 200 SKU bán chạy chiếm 79.68% tổng sản lượng bán ra (`qty_sold`).
  - Ràng buộc giỏ hàng: Mỗi đơn có 3 đến 8 món (`ORDER_ITEMS_RANGE`), số lượng mỗi món từ 1 đến 5 (`ITEM_QUANTITY_RANGE`).
  - Kiểm tra Stockout: Chặn không chọn sản phẩm nếu ngày đó rơi vào `FULL_DAY`, hoặc nếu tạo đơn sau 11:00 vào ngày `MID_DAY`.
  - Khuyến mại: Tối đa 1 KM cấp dòng/item, tối đa 1 KM cấp đơn tính trên tổng sau giảm dòng, làm tròn xuống đến đồng (`math.floor` theo BR-07).
  - Thời hạn đơn online: Thanh toán trong hạn 15 phút (`paid_at <= created_at + 15 phút` theo BR-03).
  - Đơn POS có thu ngân tạo (`created_by`), đơn online `created_by = NULL`.
- **Cách tự kiểm tra**:
  Chạy unit test:
  ```powershell
  cd forecast-service; python -m pytest tests/test_generator.py -k "test_order_quotas"
  ```

---

### 2.5. Nhóm hàm Đổi trả & Đơn online lỗi (Chặng 3)

#### Hàm `generate_returns(orders, products)`
- **Mục đích**: Sinh 150 phiếu đổi trả hàng (`return_document`) và 510 dòng hàng trả lại (`return_item`) (gồm 400 dòng trả toàn bộ và 110 dòng trả một phần).
- **Yêu cầu đáp ứng**:
  - Tỷ lệ trả hàng: 150 đơn / 10.000 đơn xử lý = 1.5% (nằm trong biên độ 1%–2% của [config.py](config.py)).
  - Phân loại trạng thái:
    - **75 đơn trả toàn bộ**: Trả hết 100% các dòng mua $\rightarrow$ Đơn gốc chuyển trạng thái thành **`RETURNED`**, hoàn trả 100% tổng tiền đơn (`refund_amount == sales_order.total`).
    - **75 đơn trả một phần**: Trả 1–2 sản phẩm $\rightarrow$ Đơn gốc giữ nguyên trạng thái **`COMPLETED`**, hoàn trả tiền theo giá trị thực trả của từng dòng.
    - $\rightarrow$ Tổng `sales_order` có trạng thái `IN ('COMPLETED', 'RETURNED')` vẫn là **đúng 10.000 đơn**.
  - Áp dụng công thức hoàn tiền chuẩn BA §5.2:
    $$\text{refund} = \left\lfloor \frac{\text{SL trả}}{\text{SL mua}} \times \left( \text{line\_total} - \frac{\text{KM đơn} \times \text{line\_total}}{\text{Tổng sau giảm dòng}} \right) \right\rfloor$$
    (Ví dụ mua 3 bó rau cải 54k hoàn 1 bó ra đúng 16.200 đ).
  - Hàng tươi sống (`FRESH_FOOD`): Bắt buộc ghi nhận tình trạng `condition = 'DAMAGED'` (lỗi dập úng/chất lượng, không tái bán).
  - Nguyên tắc kiểm soát kép (SoD - BR-13): Thu ngân lập phiếu (`created_by`) $\neq$ Quản lý duyệt phiếu (`approved_by`), cả hai cùng thuộc chi nhánh tiếp nhận.
  - Cho phép 10% khách trả chéo tại chi nhánh khác trong chuỗi.
- **Cách tự kiểm tra**:
  ```powershell
  cd forecast-service; python -m pytest tests/test_generator.py -k "test_return"
  ```

#### Hàm `generate_failed_online_orders(...)`
- **Mục đích**: Sinh thêm 200 đơn online thất bại nằm ngoài 10.000 đơn thành công.
- **Yêu cầu đáp ứng**:
  - Phân bổ trạng thái:
    - **80 đơn `CANCELLED`**: Khách hủy đơn. Trong đó 40 đơn hủy khi chưa trả tiền (`payment = None`), 40 đơn đã thanh toán rồi hủy $\rightarrow$ hoàn tiền (`payment.status = 'REFUNDED'`). Ghi nhận `audit_log` với hành động `CANCEL_ORDER`.
    - **80 đơn `EXPIRED`**: Khách không thanh toán trong hạn 15 phút (`reserved_until = created_at + 15 phút`, `payment.status = 'PENDING'`).
    - **40 đơn `REJECTED`**: Hết hàng tại kho chi nhánh phục vụ mặc định (BR-14: WEB gán GM-001, MARKET gán GM-002) $\rightarrow$ Từ chối đơn, ghi `audit_log` lý do `OUT_OF_STOCK`.
- **Cách tự kiểm tra**:
  ```powershell
  cd forecast-service; python -m pytest tests/test_generator.py -k "test_failed_online_orders"
  ```

---

### 2.6. Nhóm hàm Thống kê & Xuất file (Chặng 4)

#### Hàm `compute_sales_statistics(...)`
- **Mục đích**: Tính toán các chỉ số thống kê toán học phục vụ nghiệm thu và báo cáo:
  - Tỷ lệ Pareto thực tế: $80.09\%$ sản lượng bán thuộc về 200 top SKU (132.601 / 165.566 đơn vị bán).
  - Bình quân bán hàng:
    - 200 Top SKU toàn chuỗi: $1.82$ sản phẩm / ngày / SKU.
    - 200 Top SKU theo chi nhánh: $0.61$ sản phẩm / ngày / (SKU $\times$ chi nhánh).
  - Độ thưa ma trận bán hàng (`daily_sales` Sparsity): $95.40\%$ số ô bán hàng bằng 0 (1.044.613 ô trống trên 1.095.000 ô tiềm năng).

#### Hàm `export_stockout_schedule(...)`
- **Mục đích**: Ghi dữ liệu 5.423 bản ghi lịch stockout ra file JSON chuẩn UTF-8 tại `forecast-service/data/stockout_schedule.json`.

#### Hàm `export_to_sql(...)`
- **Mục đích**: Xuất toàn bộ dữ liệu mẫu thành script SQL hoàn chỉnh tại `forecast-service/data/seed_data.sql` (kích thước ~5.18 MB).
- **Yêu cầu đáp ứng**:
  - Tính khả lặp (Idempotent): Chạy nhiều lần không bị lỗi trùng khóa hoặc phình to dữ liệu.
  - Bảo toàn tham chiếu V3: Tạm gỡ `default_branch_id = NULL` trên `sales_channel`, sau đó `TRUNCATE` các bảng con, `DELETE FROM branch`, nạp lại 3 chi nhánh và `UPDATE` lại chi nhánh mặc định cho 3 kênh (POS, WEB, MARKET).
  - Chèn dữ liệu theo lô (`batch_size = 500`) giúp tăng tốc độ nạp dữ liệu vào PostgreSQL.
  - Khôi phục Sequence: Tự động chạy lệnh `setval(pg_get_serial_sequence('...', 'id'), ...)` cho tất cả 12 bảng ở cuối file để tránh xung đột khóa chính khi backend thêm bản ghi mới.
  - Kết thúc bằng lệnh `COMMIT;` đảm bảo toàn vẹn giao dịch.

---

## 3. CÁCH CHẠY VÀ XÁC THỰC TOÀN DIỆN

### 3.1. Chạy sinh dữ liệu
Từ thư mục gốc dự án:
```powershell
cd forecast-service
python -m scripts.generate_data
```
Kết quả in ra màn hình phải thông báo hoàn tất 5 bước và hiển thị bảng thống kê khớp chính xác.

### 3.2. Chạy toàn bộ 41 unit test
```powershell
cd forecast-service
python -m pytest
```
Kết quả yêu cầu: `41 passed` (100% xanh).

### 3.3. Kiểm tra tính toàn vẹn mã băm (SHA-256 Checksums)
Được cố định bằng kiểm thử `test_golden_sha256_checksums` (sinh trực tiếp trong bộ nhớ với `newline='\n'` và `encoding='utf-8'` chuẩn POSIX/Linux):
- `seed_data.sql`: `68e17e2528c2daacae1552ec6336cefb4d13798d2937082e52780b6656e77680`
- `stockout_schedule.json`: `d0226a3d258fa87649db164118a5d600052de8f422e94e5beecb8a3e05d1dc91`
Bất kỳ thay đổi nào làm lệch các giá trị băm này đều sẽ bị unit test cảnh báo ngay lập tức.
