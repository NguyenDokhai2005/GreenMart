# Hướng dẫn và Đặc tả Tham số Sinh dữ liệu (forecast-service)

Tài liệu này đặc tả chi tiết toàn bộ các tham số cấu hình trong [config.py](config.py) phục vụ công việc thiết kế tham số sinh dữ liệu giả lập lịch sử 12 tháng của GreenMart (thuộc **Issue #105**), làm tiền đề cho script sinh dữ liệu (thuộc **Issue #106**).

---

## 1. Tổng quan & Phạm vi

- **Mục tiêu**: Chuẩn hóa toàn bộ tham số, tỷ lệ xác suất, phân phối mùa vụ và các ràng buộc nghiệp vụ bán lẻ cho 10.000 đơn hàng `COMPLETED`, 1.000 SKU và 3 chi nhánh.
- **Múi giờ chuẩn**: Toàn bộ mốc thời gian sự kiện sử dụng múi giờ Việt Nam `Asia/Ho_Chi_Minh` (UTC+7).
- **Phân định rõ ràng**:
  - Các thông số **Đã xác nhận chính thức** từ SRS v1.0, Phân tích nghiệp vụ (BA) và sơ đồ ERD.
  - Các thông số là **Giả định thiết kế** được cấu hình linh hoạt để phục vụ mô hình toán học và kiểm thử, không phải quy định nghiệp vụ bắt buộc của khách hàng.

---

## 2. Từ điển tham số cấu hình (`config.py`)

### 2.1 Múi giờ & Thời gian lịch sử

| Tên biến | Kiểu dữ liệu | Đơn vị / Định dạng | Phân loại | Ý nghĩa nghiệp vụ |
| :--- | :--- | :--- | :--- | :--- |
| `TIMEZONE_NAME` | `str` | Chuỗi IANA (`Asia/Ho_Chi_Minh`) | Đã xác nhận | Tên định danh múi giờ chuẩn của hệ thống GreenMart. |
| `TIMEZONE` | `ZoneInfo` | Múi giờ UTC+7 | Đã xác nhận | Đối tượng múi giờ Python chuẩn để gán vào các `datetime`. |
| `START_DATE` | `datetime` | `2025-11-01 00:00:00+07:00` | Đã xác nhận | Mốc bắt đầu khoảng thời gian dữ liệu lịch sử (12 tháng). |
| `END_DATE` | `datetime` | `2026-10-31 23:59:59+07:00` | Đã xác nhận | Mốc kết thúc khoảng thời gian dữ liệu lịch sử. |
| `MAX_EVENT_DATETIME`| `datetime` | `2026-10-31 23:59:59+07:00` | Đã xác nhận | Ranh giới tuyệt đối: không sinh bất kỳ sự kiện nào sau mốc này. |
| `TET_DATE` | `date` | `2026-02-17` | Đã xác nhận | Mùng 1 Tết Bính Ngọ 2026, điểm hội tụ mùa vụ cao điểm nhất trong năm. |

### 2.2 Mùa vụ, Ngày lễ & Ngành hàng

| Tên biến | Kiểu dữ liệu | Đơn vị / Khoảng giá trị | Phân loại | Ý nghĩa nghiệp vụ |
| :--- | :--- | :--- | :--- | :--- |
| `DAY_OF_WEEK_FACTORS` | `Dict[int, float]` | 0 (Thứ 2) đến 6 (Chủ nhật) | Giả định thiết kế | Hệ số điều chỉnh nhu cầu theo ngày trong tuần. Tổng 7 ngày = `7.00`, trung bình cộng = `1.000` (T2–T5: ~0.85–0.90, T6: 1.10, T7–CN: 1.20). Phản ánh xu hướng mua sắm cuối tuần cao hơn ngày thường. |
| `TET_PRE_START`, `TET_PRE_END` | `date` | `2026-01-27` đến `2026-02-16` | Giả định thiết kế | Khoảng thời gian 3 tuần cao điểm mua sắm chuẩn bị Tết Bính Ngọ. |
| `TET_PRE_DEMAND_LIFT_RANGE` | `Tuple[float, float]` | `(1.50, 2.00)` (tăng 50%–100%) | Giả định thiết kế | Hệ số tăng nhu cầu tiêu dùng trong giai đoạn chuẩn bị Tết. |
| `TET_HOLIDAY_START`, `TET_HOLIDAY_END` | `date` | `2026-02-17` đến `2026-02-19` | Giả định thiết kế | Mùng 1 đến mùng 3 Tết Bính Ngọ. |
| `TET_HOLIDAY_DEMAND_FACTOR`| `float` | `0.10` (giảm 90%) | Giả định thiết kế | Giả định cửa hàng mở bán cầm chừng/trực Tết, nhu cầu giảm sâu so với ngày thường. |
| `SPECIAL_HOLIDAYS` | `Dict[str, Dict]` | Danh sách mốc ngày lễ | Giả định thiết kế | Mức tăng nhu cầu tại các dịp lễ lớn: Giáng sinh (24–25/12, factor 1.25), 30/4–1/5 (factor 1.35), Quốc khánh 2/9 (factor 1.35), Phụ nữ VN 20/10 (factor 1.20). |
| `BRANCH_CATEGORY_DEMAND_WEIGHTS` | `Dict[str, Dict]` | Trọng số ngành hàng theo CN | Giả định thiết kế | Phản ánh cơ cấu khách hàng khác nhau: GM-001 (Q.1) thiên về đồ chế biến sẵn/nước uống (1.30); GM-002 (Bình Thạnh) thiên về tươi sống gia đình (1.30); GM-003 (Tân Bình) thiên về hàng tiêu dùng nhanh/đồ khô (1.30). |
| `DEMAND_NOISE_RANGE` | `Tuple[float, float]` | `(-0.10, 0.10)` | Giả định thiết kế | Biên độ nhiễu ngẫu nhiên hằng ngày ($\pm 10\%$) để dữ liệu có tính ngẫu nhiên tự nhiên. |

### 2.3 Đơn hàng, Kênh và Chi nhánh

| Tên biến | Kiểu dữ liệu | Giá trị | Phân loại | Ý nghĩa nghiệp vụ |
| :--- | :--- | :--- | :--- | :--- |
| `TOTAL_COMPLETED_ORDERS` | `int` | `10_000` | Đã xác nhận | Quy mô mẫu mục tiêu: đúng 10.000 đơn hàng hoàn tất trên cả hệ thống. |
| `CHANNEL_RATIOS` | `Dict[str, float]` | POS: 70%, WEB: 20%, MARKET: 10% | Đã xác nhận | Tỷ lệ phân bổ đơn hàng hoàn tất theo từng kênh bán hàng (tổng = 1.0). |
| `COMPLETED_ONLINE_ORDERS`| `int` | `3_000` | Đã xác nhận | Số đơn online hoàn tất (2.000 đơn WEB + 1.000 đơn MARKET). |
| `BRANCH_RATIOS` | `Dict[str, float]` | GM-001: 45%, GM-002: 35%, GM-003: 20% | Đã xác nhận | Tỷ lệ phân bổ đơn hàng theo chi nhánh trên toàn chuỗi (tổng = 1.0). |
| `CHANNEL_SERVICE_BRANCHES` | `Dict[str, List[str]]`| POS, WEB, MARKET: [GM-001, GM-002, GM-003] | Đã xác nhận (ERD `channel_branch`) | Danh sách các chi nhánh có năng lực phục vụ giao hàng cho từng kênh. |
| `CHANNEL_DEFAULT_BRANCH` | `Dict[str, str]` | WEB: GM-001, MARKET: GM-002 | Đã xác nhận (SV01) | Chi nhánh mặc định ưu tiên kiểm tra tồn kho khi đơn hàng online được gửi đến (BR-14): kênh WEB mặc định GM-001 (Quận 1), kênh MARKET mặc định GM-002 (Bình Thạnh) theo quyết định đã được SV01 xác nhận. |
| `ORDER_ITEMS_RANGE` | `Tuple[int, int]` | `(3, 8)` dòng hàng | Đã xác nhận | Số lượng mặt hàng (line items) trong mỗi giỏ hàng. |
| `ITEM_QUANTITY_RANGE` | `Tuple[int, int]` | `(1, 5)` sản phẩm | Đã xác nhận | Số lượng sản phẩm mua trên mỗi dòng hàng. |

### 2.4 Đơn online thất bại (Cancelled, Expired, Rejected)

| Tên biến | Kiểu dữ liệu | Giá trị | Phân loại | Ý nghĩa nghiệp vụ |
| :--- | :--- | :--- | :--- | :--- |
| `FAILED_ONLINE_BASE` | `str` | `"COMPLETED_ONLINE"` | Đã xác nhận | Mẫu số quy chiếu tính số đơn thất bại (trên 3.000 đơn online hoàn tất). |
| `FAILED_ONLINE_TARGET_TOTAL` | `int` | `200` | Đã xác nhận | Mục tiêu sinh bổ sung đúng 200 đơn online thất bại (ngoài 10.000 đơn COMPLETED). |
| `FAILED_ONLINE_BY_STATUS`| `Dict[str, int]` | CANCELLED: 80, EXPIRED: 80, REJECTED: 40 | Đã xác nhận | Phân bổ số lượng đơn theo 3 trạng thái lỗi của luồng đơn online. |
| `FAILED_ONLINE_RATE` | `float` | $\approx 0.0667$ (6.67%) | Đã xác nhận | Tỷ lệ đơn online lỗi trên tổng đơn online hoàn tất ($200 / 3000$). |
| `ONLINE_PAYMENT_TIMEOUT_MINUTES` | `int` | `15` phút | Đã xác nhận (SRS 3.5 & BR-03) | Thời hạn giữ chỗ tồn kho cho đơn online chưa thanh toán (BR-03). Quá 15 phút chuyển sang EXPIRED và trả lại tồn khả dụng. |

### 2.5 Danh mục sản phẩm & Quy luật Pareto 80/20

| Tên biến | Kiểu dữ liệu | Giá trị | Phân loại | Ý nghĩa nghiệp vụ |
| :--- | :--- | :--- | :--- | :--- |
| `TOTAL_SKU_COUNT` | `int` | `1_000` | Đã xác nhận | Tổng số SKU trong danh mục sản phẩm của toàn chuỗi GreenMart. |
| `PARETO_TARGET_METRIC` | `str` | `"QUANTITY"` | Đã xác nhận | Tiêu chí phân loại Pareto: tính theo số lượng sản phẩm bán ra (`qty_sold`), không tính theo doanh thu tiền tệ. |
| `PARETO_TARGET_SKU_COUNT`| `int` | `200` | Đã xác nhận | Số lượng SKU bán chạy cốt lõi (nhóm 20% SKU hàng đầu). |
| `PARETO_TARGET_QUANTITY_SHARE` | `float` | `0.80` (80%) | Đã xác nhận | Tỷ lệ đóng góp sản lượng của nhóm 200 SKU bán chạy trên tổng sản lượng bán ra. |

### 2.6 Khuyến mại chi tiết

| Tên biến | Kiểu dữ liệu | Giá trị | Phân loại | Ý nghĩa nghiệp vụ |
| :--- | :--- | :--- | :--- | :--- |
| `PROMOTION_CAMPAIGNS_PER_MONTH_RANGE` | `Tuple[int, int]` | `(1, 2)` đợt | Đã xác nhận | Tần suất tổ chức chiến dịch khuyến mại hằng tháng. |
| `HAS_TET_PROMOTION` | `bool` | `True` | Đã xác nhận | Có chiến dịch khuyến mại đặc biệt quy mô lớn trong dịp Tết Bính Ngọ. |
| `PROMOTION_DEMAND_LIFT_RANGE` | `Tuple[float, float]` | `(0.30, 0.80)` | Đã xác nhận | Mức tăng nhu cầu mua sắm trong thời gian có khuyến mại (+30% đến +80%). |
| `PROMOTION_DISCOUNT_TYPES` | `Tuple[str, ...]` | `("PERCENT", "AMOUNT")` | Đã xác nhận (ERD `promotion`) | Hai hình thức giảm giá hỗ trợ trên hệ thống: theo phần trăm và theo số tiền cố định. |
| `PROMOTION_LEVELS` | `Tuple[str, ...]` | `("ORDER", "ITEM")` | Đã xác nhận (ERD & BR-07) | Cấp độ áp dụng: khuyến mại cấp đơn hàng và khuyến mại cấp dòng sản phẩm. |
| `PROMOTION_PERCENT_DISCOUNT_RANGE` | `Tuple[float, float]` | `(0.10, 0.20)` (10%–20%) | Giả định thiết kế | Khoảng phần trăm giảm giá thông dụng khi áp dụng kiểu `PERCENT`. |
| `PROMOTION_MAX_DISCOUNT_CAP` | `int` | `50_000` VND | Giả định thiết kế | Mức giảm tối đa (trần chiết khấu) đối với khuyến mại phần trăm cấp đơn. |
| `PROMOTION_AMOUNT_DISCOUNT_OPTIONS` | `Tuple[int, ...]` | `(10_000, 20_000, 50_000)` VND | Giả định thiết kế | Các mệnh giá tiền giảm trực tiếp thông dụng khi áp dụng kiểu `AMOUNT`. |
| `PROMOTION_MIN_ORDER_AMOUNT_RANGE` | `Tuple[int, int]` | `(150_000, 300_000)` VND | Giả định thiết kế | Ngưỡng giá trị đơn hàng tối thiểu để đủ điều kiện áp dụng khuyến mại cấp đơn. |
| `PROMOTION_CAMPAIGN_DURATION_DAYS_RANGE` | `Tuple[int, int]` | `(3, 7)` ngày | Giả định thiết kế | Thời lượng của mỗi chiến dịch khuyến mại thông thường trong tháng. |
| `TET_PROMOTION_DURATION_DAYS` | `int` | `14` ngày | Giả định thiết kế | Thời lượng chiến dịch khuyến mại dịp Tết Bính Ngọ. |
| `PROMOTION_ITEM_SKU_RATIO_RANGE` | `Tuple[float, float]` | `(0.02, 0.05)` (2%–5%) | Giả định thiết kế | Tỷ lệ SKU được giảm giá trong một chiến dịch khuyến mại cấp sản phẩm. |
| `PROMOTION_ITEM_SKU_COUNT_RANGE` | `Tuple[int, int]` | `(20, 50)` SKU | Giả định thiết kế | Số lượng SKU cụ thể áp dụng khuyến mại cấp sản phẩm ($1000 \times 2\% \rightarrow 5\%$). |
| `PROMOTION_BRANCH_SCOPE_OPTIONS` | `Tuple[str, ...]` | `("ALL", "SPECIFIC")` | Đã xác nhận (ERD `promotion_branch`) | Phạm vi áp dụng chi nhánh: toàn chuỗi hoặc chi nhánh chỉ định. |
| `PROMOTION_CHANNEL_SCOPE_OPTIONS` | `Tuple[str, ...]` | `("ALL", "POS_ONLY", "ONLINE_ONLY")` | Đã xác nhận (ERD `promotion_channel`) | Phạm vi áp dụng kênh bán: mọi kênh, chỉ tại quầy hoặc chỉ online. |

### 2.7 Stockout, Trả hàng & Tái lập dữ liệu

| Tên biến | Kiểu dữ liệu | Giá trị | Phân loại | Ý nghĩa nghiệp vụ |
| :--- | :--- | :--- | :--- | :--- |
| `STOCKOUT_SIMULATION_MODE` | `str` | `"MIXED"` | Đã xác nhận | Chế độ mô phỏng stockout hỗn hợp (vừa hết hàng giữa ngày, vừa cạn kho ngày kế tiếp). |
| `STOCKOUT_DURATION_DAYS_RANGE` | `Tuple[int, int]` | `(1, 3)` ngày | Đã xác nhận | Số ngày mỗi đợt stockout kéo dài trước khi được nhập kho bổ sung. |
| `STOCKOUT_DAYS_RATIO_RANGE` | `Tuple[float, float]` | `(0.02, 0.03)` (2%–3%) | Đã xác nhận | Tỷ lệ số ngày xảy ra stockout trong năm ở nhóm 200 SKU bán nhanh. |
| `RETURN_RATE_RANGE` | `Tuple[float, float]` | `(0.01, 0.02)` (1%–2%) | Đã xác nhận | Tỷ lệ đơn hàng COMPLETED phát sinh yêu cầu trả hàng. |
| `RETURN_WINDOW_DAYS` | `int` | `7` ngày | Đã xác nhận (BR-10) | Cửa sổ thời gian tối đa cho phép phát sinh phiếu trả hàng kể từ ngày mua. |
| `ALLOW_RETURNS_AFTER_END_DATE` | `bool` | `False` | Đã xác nhận | Ranh giới tuyệt đối: không phát sinh phiếu trả hàng sau mốc chặn `31/10/2026`. |
| `RANDOM_SEED` | `int` | `2026` | Đã xác nhận | Hạt giống số ngẫu nhiên cố định đảm bảo khả năng tái lập 100% kết quả sinh dữ liệu. |

### 2.8 Tồn kho khởi tạo và Cảnh báo tồn thấp (Issue #107)

| Tên biến | Kiểu dữ liệu | Giá trị | Phân loại | Ý nghĩa nghiệp vụ |
| :--- | :--- | :--- | :--- | :--- |
| `LEAD_TIME_DAYS` | `int` | `2` ngày | Giả định của nhóm theo BA §8.2 / §12, chưa giảng viên xác nhận | Thời gian chờ hàng về ($L$) tính bằng ngày phục vụ công thức tính điểm đặt hàng lại (ROP). |
| `SERVICE_LEVEL_Z` | `float` | `1.65` | Giả định của nhóm theo BA §8.2 / §12, chưa giảng viên xác nhận | Hệ số phân phối chuẩn $z$ tương ứng với mức phục vụ khách hàng 95%. |
| `ROP_HISTORY_DAYS` | `int` | `28` ngày | Giả định thiết kế | Độ dài cửa sổ lịch sử bán ròng gần nhất (04/10–31/10/2026) dùng để tính trung bình ngày $\bar{d}$ và độ lệch chuẩn $\sigma_d$. |
| `ROP_MINIMUM` | `int` | `2` đơn vị | Giả định thiết kế | Ngưỡng ROP sàn cho các sản phẩm không phát sinh doanh số trong 28 ngày qua. |
| `INVENTORY_TARGET_OUT_RATIO` | `float` | `0.01` (1%) | Giả định thiết kế | Tỷ lệ mục tiêu sản phẩm hết hàng (OUT: khả dụng = 0) tại thời điểm chốt sổ demo toàn chuỗi (~30 dòng). |
| `INVENTORY_TARGET_LOW_RATIO` | `float` | `0.05` (5%) | Giả định thiết kế | Tỷ lệ mục tiêu sản phẩm tồn thấp (LOW: $0 < \text{khả dụng} \le ROP$) tại thời điểm chốt sổ demo toàn chuỗi (~150 dòng). |
| `INVENTORY_MIN_OUT_PER_BRANCH` | `int` | `3` SKU | Giả định thiết kế | Số lượng sản phẩm hết hàng tối thiểu tại mỗi chi nhánh để phục vụ demo dashboard cảnh báo. |
| `INVENTORY_MIN_LOW_PER_BRANCH` | `int` | `10` SKU | Giả định thiết kế | Số lượng sản phẩm tồn thấp tối thiểu tại mỗi chi nhánh để phục vụ demo dashboard cảnh báo. |
| `ANCHOR_MILK_SKU` | `str` | `"SUA-TH-1L"` | Giả định thiết kế | Mã SKU của sản phẩm mốc neo demo (Sữa tươi TH True Milk 1L). |
| `ANCHOR_MILK_BRANCH_CODE` | `str` | `"GM-001"` | Giả định thiết kế | Chi nhánh áp dụng mốc neo demo (GreenMart Quận 1). |
| `ANCHOR_MILK_ON_HAND` | `int` | `35` hộp | Giả định thiết kế | Tồn thực ban đầu phục vụ kịch bản kiểm thử TC-DASH-01 (Lưu ý: test-plan dùng khả dụng 35 để bán 6 -> 29 kích hoạt cảnh báo; BA §8.3/openapi dùng 30 sau khi giữ chỗ 5; đang chờ Lead chốt). |
| `ANCHOR_MILK_RESERVED` | `int` | `0` hộp | Giả định thiết kế | Lượng tồn giữ chỗ ban đầu của sản phẩm mốc neo. |
| `ANCHOR_MILK_ROP` | `int` | `34` hộp | Đã xác nhận | Điểm đặt hàng lại chuẩn của sản phẩm sữa tươi theo BA §8.3 và TC-DASH-01. |

---

## 3. Đặc tả các cơ chế và quy tắc nghiệp vụ trọng yếu

### 3.1 Quy tắc mô phỏng Stockout (Hết hàng)
- **Chế độ mô phỏng**: Hỗn hợp (`MIXED`), kết hợp 2 trạng thái thực tế:
  1. *Hết hàng giữa ngày*: Một SKU bán được một phần nhu cầu trong ngày, sau đó tồn kho thực tế chạm 0 (`on_hand = 0`). Các đơn hàng tiếp theo trong ngày chứa SKU này sẽ không được đáp ứng.
  2. *Cạn kho cả ngày*: Toàn bộ ngày tiếp theo không có doanh số cho SKU đó cho đến khi có phiếu nhập kho (`IMPORT`) hoặc chuyển kho (`TRANSFER_IN`).
- **Thời lượng và tần suất**: Kéo dài từ 1 đến 3 ngày, chỉ áp dụng cho nhóm 200 SKU bán chạy với tần suất 2%–3% tổng số ngày trong năm (khoảng 7–11 ngày/năm).
- **Định nghĩa cờ Stockout trong dữ liệu**: Cột `daily_sales.stockout = true` của dữ liệu sinh ở #106 được lấy trực tiếp từ lịch stockout mô phỏng (file `stockout_schedule.json`), KHÔNG suy từ sổ giao dịch kho `inventory_transaction`. Theo Mô hình B đã thống nhất (Issue #107), sổ kho `inventory_transaction` chỉ chứa giao dịch IMPORT khởi tạo tồn đầu kỳ tại mốc 31/10/2026, không phát lại chuỗi giao dịch kho cho 10.000 đơn hàng lịch sử 12 tháng qua.
- **Ý nghĩa mô hình hóa (Censored Demand)**: Ngày xảy ra stockout đại diện cho hiện tượng **nhu cầu bị cắt cụt (censored demand)** — số lượng bán thực tế ghi nhận được chỉ phản ánh lượng hàng có sẵn trong kho, không phản ánh toàn bộ nhu cầu thực tế của khách hàng. Trong quy trình xây dựng mô hình AI dự báo nhu cầu (Issue #106 trở đi), các bản ghi có `stockout = true` có thể được xử lý bằng cách:
  - Loại bỏ khỏi tập dữ liệu huấn luyện (training set) để tránh làm mô hình học sai lệch xu hướng nhu cầu;
  - Hoặc đưa vào mô hình dưới dạng đặc trưng nhị phân (binary feature) để mô hình nhận biết trạng thái đứt gãy nguồn cung.
  - Tuyệt đối **không diễn giải doanh số thấp trong ngày stockout là nhu cầu thị trường suy giảm**.

### 3.2 Vòng đời đơn online & Cơ chế tồn kho RESERVE / RELEASE (BR-01, BR-03, BR-04)
1. **Khởi tạo đơn (`NEW` $\rightarrow$ `RESERVED`)**:
   - Khi có đơn đặt hàng từ kênh online (WEB hoặc MARKET), hệ thống kiểm tra tồn khả dụng:
     $$\text{Tồn khả dụng} = \text{on\_hand} - \text{reserved}$$
   - Nếu chi nhánh được phân bổ đủ tồn khả dụng cho **toàn bộ các dòng sản phẩm**, hệ thống thực hiện transaction nguyên tử:
     - Tăng cột `reserved` tương ứng trong bảng `inventory` (giao dịch `RESERVE`).
     - Chuyển trạng thái đơn sang `RESERVED`.
     - Thiết lập hạn thanh toán `reserved_until = created_at + 15 \text{ phút}` (**BR-03**).
2. **Thanh toán thành công (`RESERVED` $\rightarrow$ `CONFIRMED` $\rightarrow$ `COMPLETED`)**:
   - Khi khách thanh toán thành công trong vòng 15 phút (hoặc đơn COD được xác nhận), đơn chuyển sang `CONFIRMED`.
   - Khi đơn được giao hoàn tất cho khách, đơn chuyển sang `COMPLETED`. Trong transaction này:
     - Giảm đồng thời cả `on_hand` và `reserved` (giao dịch `SALE`).
     - Tăng số lượt sử dụng khuyến mại (`used_count`) nếu đơn có áp dụng mã giảm giá.
3. **Các trường hợp thất bại và cơ chế nhả kho (`RELEASE`)**:
   - **Đơn hết hạn (`EXPIRED`)**: Sau 15 phút khách không hoàn tất thanh toán trực tuyến, job nền tự động quét và chuyển đơn sang `EXPIRED`. Hệ thống nhả toàn bộ lượng giữ chỗ (`reserved` giảm, giao dịch `RELEASE`) để trả lại tồn khả dụng cho kênh bán (BR-03, BR-04).
   - **Đơn bị hủy (`CANCELLED`)**: Khách hàng hoặc quản trị viên chủ động hủy đơn khi ở trạng thái `RESERVED` hoặc `CONFIRMED`. Hệ thống thực hiện `RELEASE` tồn giữ chỗ và hoàn lại lượt dùng khuyến mại nếu có (BR-04).
   - **Đơn bị từ chối (`REJECTED`)**: Khi đơn gửi từ kênh online đến nhưng sau khi kiểm tra không có chi nhánh nào đáp ứng đủ hàng cho toàn bộ đơn, đơn lập tức nhận trạng thái `REJECTED`. Trường hợp này không phát sinh giữ chỗ kho (BR-14).

### 3.3 Quy tắc trả hàng (BR-10)
- **Thời hạn hợp lệ**: Khách hàng chỉ được phép trả hàng trong vòng 7 ngày kể từ thời điểm đơn hàng đạt trạng thái `COMPLETED`.
- **Tỷ lệ phát sinh**: Chiếm khoảng 1% đến 2% trên tổng số 10.000 đơn hàng hoàn tất.
- **Trạng thái đơn hàng khi phát sinh trả hàng**:
  - *Trả toàn bộ đơn hàng*: Toàn bộ các dòng sản phẩm trong đơn được hoàn trả $\rightarrow$ đơn hàng chuyển sang trạng thái `RETURNED` (`sales_order.status = 'RETURNED'`).
  - *Trả một phần*: Khách hàng chỉ trả một số lượng sản phẩm hoặc một vài dòng hàng $\rightarrow$ đơn hàng vẫn duy trì trạng thái `COMPLETED` (`sales_order.status = 'COMPLETED'`), hệ thống tạo phiếu trả hàng (`return_document`) và các dòng trả hàng (`return_item`) tương ứng để theo dõi.
- **Ràng buộc trả hàng tươi sống (`FRESH_FOOD`)**: Theo quy định nghiêm ngặt tại **BR-10**, các sản phẩm thuộc ngành hàng thực phẩm tươi sống (`FRESH_FOOD`) chỉ được chấp nhận trả hàng khi có lý do chính đáng về **lỗi chất lượng** (hư hỏng, dập nát, ôi thiu, không đạt tiêu chuẩn vệ sinh an toàn thực phẩm). Khách hàng không được phép trả hàng tươi sống với lý do đổi ý hoặc nhu cầu cá nhân.
- **Giá trị hoàn tiền**: Số tiền hoàn được tính theo **giá thực trả** của từng dòng sản phẩm. Nếu đơn hàng có áp dụng khuyến mại cấp đơn (`level = ORDER`), phần chiết khấu đơn hàng sẽ được phân bổ đều cho từng dòng sản phẩm theo tỷ lệ giá trị dòng:
  $$\text{Tiền hoàn} = \text{Số lượng trả} \times \left(\text{Đơn giá} - \text{KM dòng} - \frac{\text{KM đơn} \times \text{Thành tiền dòng}}{\text{Tổng tạm tính đơn}}\right)$$
- **Ràng buộc thời gian**: Không được phép phát sinh phiếu trả hàng có mốc thời gian vượt quá `31/10/2026 23:59:59` (`ALLOW_RETURNS_AFTER_END_DATE = False`). Các đơn hoàn tất vào cuối tháng 10/2026 nếu có trả hàng phải diễn ra trước ranh giới này.

### 3.4 Quy tắc phân bổ đơn online theo chi nhánh (BR-14)
Hệ thống xử lý đơn từ kênh WEB và MARKET theo thứ tự ưu tiên nghiêm ngặt (nguyên tắc **không tách đơn**):
1. **Ưu tiên 1 (Chi nhánh mặc định)**: Kiểm tra tồn khả dụng tại chi nhánh mặc định của kênh (`default_branch_id`). Nếu chi nhánh mặc định có đủ tồn khả dụng cho tất cả các mặt hàng trong đơn $\rightarrow$ phân bổ đơn về chi nhánh này. Theo quyết định chính thức đã được SV01 xác nhận:
   - Kênh **WEB** có chi nhánh mặc định là **`GM-001`** (Quận 1).
   - Kênh **MARKET** (sàn TMĐT) có chi nhánh mặc định là **`GM-002`** (Bình Thạnh).
2. **Ưu tiên 2 (Chi nhánh phục vụ có tồn khả dụng cao nhất)**: Nếu chi nhánh mặc định không đủ hàng, hệ thống quét danh sách các chi nhánh phục vụ kênh (`channel_branch`) có đủ tồn cho mọi dòng sản phẩm. Chọn chi nhánh có **tổng tồn khả dụng lớn nhất** để tiếp nhận đơn.
3. **Ưu tiên 3 (Từ chối đơn)**: Nếu trong toàn bộ chuỗi không có bất kỳ chi nhánh nào đáp ứng đủ 100% giỏ hàng $\rightarrow$ đơn bị từ chối với trạng thái `REJECTED`. Hệ thống tuyệt đối không chia nhỏ đơn để giao từ nhiều nơi.

---

## 4. Hướng dẫn sử dụng cho Generator Script (Issue #106)

Khi triển khai script sinh dữ liệu trong Issue #106:
1. **Import module**:
   ```python
   from scripts import config
   ```
2. **Khởi tạo Random Seed**:
   ```python
   import random
   import numpy as np

   random.seed(config.RANDOM_SEED)
   np.random.seed(config.RANDOM_SEED)
   ```
3. **Áp dụng bộ nhân nhu cầu (Demand Multipliers) - Đề xuất thiết kế minh họa**:
   Để tính toán nhu cầu ngày $t$ tại chi nhánh $b$ cho sản phẩm $p$, một công thức toán học minh họa được đề xuất như sau:
   $$\text{Demand}(t, b, p) = \text{BaseDemand}(p) \times F_{\text{dow}}(t) \times F_{\text{season}}(t) \times F_{\text{promo}}(t, p, b) \times F_{\text{branch\_cat}}(b, \text{cat}(p)) \times (1 + \epsilon_t)$$
   - $F_{\text{dow}}$ lấy từ `config.DAY_OF_WEEK_FACTORS`.
   - $F_{\text{season}}$ xét theo `config.TET_PRE_DEMAND_LIFT_RANGE`, `config.TET_HOLIDAY_DEMAND_FACTOR` hoặc `config.SPECIAL_HOLIDAYS`.
   - $F_{\text{promo}}$ lấy theo `config.PROMOTION_DEMAND_LIFT_RANGE` nếu ngày đó rơi vào chiến dịch khuyến mại hợp lệ.
   - $F_{\text{branch\_cat}}$ lấy theo `config.BRANCH_CATEGORY_DEMAND_WEIGHTS`.
   - $\epsilon_t \sim \text{Uniform}(\text{DEMAND\_NOISE\_RANGE}[0], \text{DEMAND\_NOISE\_RANGE}[1])$.

   > [!IMPORTANT] Lưu ý quan trọng về quy tắc kết hợp hệ số:
   > - Công thức trên là **công thức đề xuất thiết kế mang tính minh họa**, KHÔNG phải công thức đã được BA/SRS chính thức xác nhận.
   > - Nếu nhân trực tiếp đồng thời các hệ số (ví dụ: ngày lễ/Tết trùng cuối tuần, đang có khuyến mại và rơi vào ngành hàng trọng điểm), nhu cầu bán có thể bị nhân dồn tăng rất mạnh (có thể gấp 4–6 lần nhu cầu cơ bản).
   > - Cách thức kết hợp cụ thể (dùng phép nhân, phép cộng, thứ tự ưu tiên hay giới hạn trần) chưa có căn cứ chốt trong Issue #105 và **sẽ được thống nhất chính thức khi triển khai Issue #106**. Không tự ý áp dụng quy tắc cứng khi chưa có quyết định từ BA/Lead.
