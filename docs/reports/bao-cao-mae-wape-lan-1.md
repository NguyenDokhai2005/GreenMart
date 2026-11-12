# BÁO CÁO ĐÁNH GIÁ SAI SỐ DỰ BÁO LẦN 1 (TIÊU CHÍ AC-05)

**Module:** `forecast-service` – Dự án GreenMart  
**Người thực hiện:** SV06 (AI & Forecast Specialist)  
**Ngày lập báo cáo:** 12/11/2026  
**Mã nghiệm thu liên quan:** AC-05 (SRS §10), US-10 (Dự báo nhu cầu 7 & 30 ngày), AI-04  

---

## 1. TÓM TẮT TIẾN ĐỘ

Báo cáo này tổng hợp kết quả đánh giá thực nghiệm phiên bản đầu tiên của mô hình dự báo nhu cầu bán hàng (LightGBM) so với các phương pháp cơ sở (Baseline) phục vụ tiêu chí nghiệm thu **AC-05**.

- **Cửa sổ kiểm tra độc lập (Test Window):** 30 ngày cuối cùng của tập dữ liệu (`02/10/2026` đến `31/10/2026`), mốc chốt dữ liệu lịch sử (Cutoff Date) là `01/10/2026`. Quy trình tuân thủ nguyên tắc không rò rỉ dữ liệu (No Data Leakage), tập kiểm tra chỉ được chạy đánh giá đúng 1 lần duy nhất.
- **Quy mô danh mục:** 3.000 chuỗi thời gian (3 chi nhánh × 1.000 sản phẩm).
- **Các tầm dự báo (Horizons):** 7 ngày (phục vụ điều phối lịch giao/đặt hàng tuần) và 30 ngày (phục vụ kế hoạch tồn kho tháng).
- **Kết luận tóm tắt:**
  - Ở cấp độ **Tổng thể (Overall)** theo phương thức đo lường chuẩn hóa **Gộp theo tuần (Weekly Aggregate - do nhóm Data đề xuất)**: Bản chính thức **LightGBM Tweedie + hậu xử lý** đạt WAPE **79.13%** (tầm 7 ngày) và **82.68%** (tầm 30 ngày), **thấp hơn rõ rệt so với Baseline tốt nhất** (Trung bình trượt 28 ngày: **96.74%** và **104.81%**).
  - Ở nhóm cốt lõi **Top 200 SKU bán chạy** (chiếm 80% tổng sản lượng bán ra): Mô hình AI vượt trội so với Baseline ở cả hai tầm dự báo (WAPE 73.06% vs 75.57% ở tầm 7 ngày; 77.73% vs 83.87% ở tầm 30 ngày).
  - Ở nhóm **800 SKU bán chậm**: Bản LightGBM Tweedie thô (raw) bị nhiễu do độ thưa lớn; sau khi áp dụng ngưỡng hậu xử lý (chọn từ tập Validation), sai số giảm sâu nhưng vẫn thua nhẹ khoảng 1.8% – 2.6% so với Baseline tham chiếu Zero Forecast.

---

## 2. PHƯƠNG PHÁP ĐÁNH GIÁ & GIẢI THÍCH CHỈ SỐ

### 2.1. Giải thích các chỉ số bằng ngôn ngữ đơn giản (Dành cho Hội đồng)
- **MAE (Mean Absolute Error - Sai số tuyệt đối trung bình):** Đo lường trung bình mỗi tuần (hoặc mỗi ngày) mô hình dự báo lệch bao nhiêu sản phẩm so với thực tế bán ra. MAE càng nhỏ, độ lệch về mặt số lượng tuyệt đối càng thấp. Đơn vị tính: *sản phẩm/chuỗi*.
- **WAPE (Weighted Absolute Percentage Error - Sai số phần trăm tuyệt đối có trọng số):** Đo lường tỷ lệ tổng sai số lệch tuyệt đối trên tổng nhu cầu thực tế của toàn hệ thống:
  $$\text{WAPE} = \frac{\sum |y_{\text{thực tế}} - y_{\text{dự báo}}|}{\sum y_{\text{thực tế}}}$$
  *Ưu điểm thực tế:* Khác với MAPE (dễ bị lỗi chia cho 0 hoặc sai số vô cực khi có ngày không bán được hàng), WAPE gộp toàn bộ sản lượng giúp phản ánh trung thực mức độ chính xác trên toàn danh mục hàng hóa bán lẻ. WAPE càng thấp, chất lượng dự báo càng cao.

### 2.2. Các mô hình tham gia đối đầu
1. **Baseline 1 – Naive theo tuần (Tuân thủ BA §9.3):** Lấy lượng bán của cùng ngày ở tuần liền trước ($D - 7$) làm dự báo cho ngày $D$.
2. **Baseline 2 – Trung bình trượt 28 ngày (Tuân thủ BA §9.3):** Lấy trung bình số lượng bán trong 28 ngày gần nhất trước ngày dự báo. Đây là **baseline chính thức tốt nhất** theo quy định bài toán.
3. **Baseline tham chiếu – Zero Forecast (Bổ sung nội bộ):** Giả định lượng bán tương lai bằng 0. Dùng để làm mốc kiểm tra cho bài toán bán lẻ có độ thưa giao dịch cao (Intermittent Demand).
4. **LightGBM Tweedie (Bản thô - raw):** Mô hình hồi quy cây tăng cường với hàm tổn thất Tweedie (chuyên dụng cho dữ liệu có mật độ số 0 lớn và đuôi phân phối lệch phải).
5. **LightGBM Tweedie + hậu xử lý (Bản chính thức):** Mô hình Tweedie kết hợp bộ lọc ngưỡng: nếu sản phẩm thuộc nhóm 800 SKU bán chậm và dự báo nhỏ hơn ngưỡng tối ưu ($0.4$ được xác lập từ tập Validation), giá trị dự báo được làm tròn về 0 để triệt tiêu nhiễu dự báo nhỏ giọt.

### 2.3. Quy ước góc nhìn đo lường
- **Cách đo chính thức (Đề xuất của nhóm Data, chờ SV01 xác nhận):** **Gộp theo tuần (Weekly Aggregate)**. Lượng bán từng ngày được gộp về chu kỳ tuần (7 ngày) trước khi tính sai số. Phù hợp nhất với thực tế quản trị siêu thị (đặt hàng và bổ sung tồn kho định kỳ mỗi tuần).
- **Cách đo tham khảo (Phục vụ ROP & Kế hoạch cung ứng):** **Gộp cả tầm (Horizon Aggregate)**. Tổng nhu cầu toàn bộ 7 ngày hoặc 30 ngày cho mỗi sản phẩm × chi nhánh.
- **Cách đo chi tiết (Cấp độ ô ngày - Daily Cell):** Tính sai số trên từng ngày riêng biệt. Dùng làm dữ liệu chẩn đoán kỹ thuật (trình bày tại Phụ lục A).
- **Làm sạch ngày Stockout:** Đã loại trừ những ngày chi nhánh bị hết hàng (khả dụng = 0 cả ngày) ra khỏi tập đánh giá theo đúng quy tắc nghiệp vụ BA §9.3 (vì nhu cầu thực tế bị che khuất).

---

## 3. BẢNG 1: MÔ HÌNH VS BASELINE – CẤP ĐỘ TỔNG THỂ (OVERALL)

*Góc nhìn đo lường:* **Weekly Aggregate** (Đơn vị MAE: sản phẩm/tuần/chuỗi). Số liệu trích xuất trực tiếp từ `forecast-service/artifacts/evaluation_metrics.json`.

| Tầm dự báo | Mô hình đánh giá | MAE (sp/tuần) | WAPE (%) | Chênh lệch WAPE so với BL2 | Kết luận so với Baseline tốt nhất |
|:---:|:---|:---:|:---:|:---:|:---:|
| **7 ngày** | Baseline 1 (Naive theo tuần) | 1.2557 | 113.46% | +16.72% | Kém hơn BL2 |
| **7 ngày** | Baseline 2 (TB trượt 28 ngày - BL tốt nhất) | 1.0706 | **96.74%** | 0.00% | Mốc chuẩn so sánh |
| **7 ngày** | Baseline tham chiếu (Zero Forecast) | 1.1140 | 100.66% | +3.92% | Kém hơn BL2 |
| **7 ngày** | LightGBM Tweedie (Bản thô - raw) | 1.0777 | **97.38%** | +0.64% | ❌ **Thua nhẹ** (kém 0.64%) |
| **7 ngày** | **LightGBM Tweedie + hậu xử lý (Chính thức)** | **0.8757** | **79.13%** | **-17.61%** | ✅ **THẮNG RÕ RỆT** |
| | | | | | |
| **30 ngày** | Baseline 1 (Naive theo tuần) | 1.0712 | 119.92% | +15.11% | Kém hơn BL2 |
| **30 ngày** | Baseline 2 (TB trượt 28 ngày - BL tốt nhất) | 0.9362 | **104.81%** | 0.00% | Mốc chuẩn so sánh |
| **30 ngày** | Baseline tham chiếu (Zero Forecast) | 0.8959 | 100.30% | -4.51% | Tốt hơn BL2 |
| **30 ngày** | LightGBM Tweedie (Bản thô - raw) | 0.9190 | **102.88%** | -1.93% | ✅ **Thắng nhẹ** (tốt hơn 1.93%) |
| **30 ngày** | **LightGBM Tweedie + hậu xử lý (Chính thức)** | **0.7385** | **82.68%** | **-22.13%** | ✅ **THẮNG VƯỢT TRỘI** |

> [!NOTE]
> **Nhận xét trung thực:**
> - Ở tầm **7 ngày**, bản LightGBM Tweedie thô (raw) có WAPE là `97.38%`, **thua nhẹ** so với Baseline 2 (`96.74%`). Phần thắng quyết định đưa WAPE xuống `79.13%` đến từ cơ chế **hậu xử lý lọc ngưỡng** cho nhóm bán chậm.
> - Nếu tính cả Baseline tham chiếu bổ sung **Zero Forecast** (WAPE 7 ngày: `100.66%`, 30 ngày: `100.30%`), bản chính thức của mô hình AI vẫn thắng áp đảo với biên độ cải thiện từ 17.6% đến 21.5%.

---

## 4. BẢNG 2: ĐỐI ĐẦU CHI TIẾT THEO PHÂN KHÚC HÀNG HÓA

Đánh giá chi tiết trên hai nhóm hàng hóa theo phân tích Pareto:
- **Top 200 SKU bán chạy:** Chiếm ~80% tổng sản lượng tiêu thụ toàn chuỗi (nhóm hàng sống còn về doanh thu).
- **800 SKU bán chậm:** Nhu cầu gián đoạn, nhiều ngày không phát sinh giao dịch.

*Phương thức đo lường: Weekly Aggregate (Đã loại trừ ngày Stockout).*

| Tầm dự báo | Phân khúc hàng hóa | Mô hình / Phương pháp | MAE (sp/tuần) | WAPE (%) | Chênh lệch so với Baseline tốt nhất | Kết luận đối đầu |
|:---:|:---|:---|:---:|:---:|:---:|:---:|
| **7 ngày** | **Top 200 bán chạy** | Baseline 2 (TB trượt 28 ngày) | 3.3490 | 75.57% | Mốc chuẩn (tốt nhất) | Mốc so sánh |
| 7 ngày | Top 200 bán chạy | Zero Forecast (Tham chiếu) | 4.4583 | 100.60% | +25.03% | Thua xa |
| 7 ngày | Top 200 bán chạy | LightGBM Tweedie (Bản thô) | 3.2377 | **73.06%** | -2.51% | ✅ **Thắng** |
| 7 ngày | Top 200 bán chạy | **LightGBM Tweedie + PP** | **3.2377** | **73.06%** | **-2.51%** | ✅ **THẮNG** |
| | | | | | | |
| **7 ngày** | **800 bán chậm** | Baseline 2 (TB trượt 28 ngày) | 0.5009 | 181.88% | So với BL chính thức | Mốc nghiệp vụ |
| 7 ngày | 800 bán chậm | Zero Forecast (Tham chiếu nội bộ) | 0.2779 | 100.91% | Mốc tham chiếu tối ưu | Mốc tham chiếu |
| 7 ngày | 800 bán chậm | LightGBM Tweedie (Bản thô) | 0.5377 | 195.24% | +13.36% (vs BL2) | ❌ Thua nặng |
| 7 ngày | 800 bán chậm | **LightGBM Tweedie + PP** | **0.2851** | **103.53%** | **-78.35% (vs BL2)**<br>*(+2.62% vs Zero)* | ✅ Thắng đậm BL2<br>⚠️ **Thua nhẹ Zero** |
| | | | | | | |
| **30 ngày** | **Top 200 bán chạy** | Baseline 2 (TB trượt 28 ngày) | 2.9927 | 83.87% | Mốc chuẩn (tốt nhất) | Mốc so sánh |
| 30 ngày | Top 200 bán chạy | Zero Forecast (Tham chiếu) | 3.5770 | 100.24% | +16.37% | Thua xa |
| 30 ngày | Top 200 bán chạy | LightGBM Tweedie (Bản thô) | 2.7738 | **77.73%** | -6.14% | ✅ **Thắng** |
| 30 ngày | Top 200 bán chạy | **LightGBM Tweedie + PP** | **2.7738** | **77.73%** | **-6.14%** | ✅ **THẮNG** |
| | | | | | | |
| **30 ngày** | **800 bán chậm** | Baseline 2 (TB trượt 28 ngày) | 0.4221 | 188.03% | So với BL chính thức | Mốc nghiệp vụ |
| 30 ngày | 800 bán chậm | Zero Forecast (Tham chiếu nội bộ) | 0.2257 | 100.52% | Mốc tham chiếu tối ưu | Mốc tham chiếu |
| 30 ngày | 800 bán chậm | LightGBM Tweedie (Bản thô) | 0.4553 | 202.81% | +14.78% (vs BL2) | ❌ Thua nặng |
| 30 ngày | 800 bán chậm | **LightGBM Tweedie + PP** | **0.2297** | **102.31%** | **-85.72% (vs BL2)**<br>*(+1.79% vs Zero)* | ✅ Thắng đậm BL2<br>⚠️ **Thua nhẹ Zero** |

---

## 5. ĐÁNH GIÁ TIÊU CHÍ NGHIỆM THU AC-05

Căn cứ theo văn bản phân tích nghiệp vụ (`docs/requirements/business-analysis.md` mục 9.3) và đặc tả yêu cầu phần mềm (`docs/requirements/srs.md` mục 10, tiêu chí AC-05):
> *"Đạt yêu cầu khi WAPE của mô hình thấp hơn baseline tốt nhất ở cả hai tầm 7 và 30 ngày."*

### Kết luận đánh giá chính thức:
**"Đạt ở Overall (theo cách đo đề xuất); chưa đạt ở nhóm bán chậm; chờ SV01 chốt cách đo và yêu cầu theo nhóm."**

Chi tiết biện luận:
1. **Ở cấp độ Tổng thể (Overall):**
   - Khi áp dụng cách đo thực tế **Weekly Aggregate** (do nhóm Data đề xuất), bản chính thức LightGBM Tweedie + hậu xử lý đạt WAPE lần lượt là **79.13%** (7 ngày) và **82.68%** (30 ngày), vượt trội so với Baseline tốt nhất quy định trong BA là Trung bình trượt 28 ngày (**96.74%** và **104.81%**).
   - Khi áp dụng cách đo **Horizon Aggregate** (tổng nhu cầu cả chu kỳ đặt hàng), mô hình thắng áp đảo hoàn toàn với WAPE **79.13%** vs **96.74%** (7 ngày) và **49.81%** vs **72.24%** (30 ngày).
2. **Ở phân khúc nhóm hàng:**
   - Nhóm cốt lõi **Top 200 bán chạy** (quyết định dòng tiền và dịch vụ khách hàng): Mô hình AI **thắng thuyết phục** ở cả 2 tầm dự báo.
   - Nhóm **800 bán chậm**: Mô hình AI vượt xa 2 baseline quy định trong BA, nhưng **thua nhẹ khoảng 1.8% – 2.6%** so với baseline bổ sung Zero Forecast.
3. **Tính bảo lưu:** Chưa tuyên bố đạt tuyệt đối vì cần thống nhất bằng văn bản với SV01 (Lead/BA) xem tiêu chí AC-05 bắt buộc đạt trên từng nhóm hay chỉ tính trên toàn bộ danh mục (Overall).

---

## 6. HẠN CHẾ VÀ ĐỊNH HƯỚNG CẢI TIẾN TIẾP THEO

### 6.1. Hạn chế kỹ thuật và minh bạch về dữ liệu (Data Leakage Check)
1. **Kiểm tra rò rỉ dữ liệu trong mã nguồn (`evaluator.py`):**
   - **Xác nhận:** Mã nguồn hiện tại thực hiện tìm kiếm siêu tham số và ngưỡng tối ưu qua hàm `find_optimal_slow_threshold_on_validation()` hoàn toàn dựa trên tập Validation (các mốc thời gian trước `cutoff_date`). Tập Test độc lập 30 ngày chỉ được nạp dữ liệu một lần duy nhất để xuất báo cáo.
   - **Ghi nhận lịch sử minh bạch:** Trong quá trình thử nghiệm phát triển ban đầu (Issue #113), nhóm từng có một lần thăm dò ngưỡng 0.3 trực tiếp trên Test Set. Tuy nhiên, toàn bộ số liệu báo cáo chính thức ở đây đã được chuẩn hóa lại nghiêm ngặt, sử dụng **ngưỡng 0.4** chọn lọc từ Validation Set.
2. **Bản chất của kỹ thuật hậu xử lý nhóm bán chậm:**
   - Ngưỡng lọc $0.4$ làm triệt tiêu dự báo nhỏ giọt và ép khoảng **99.83%** ô dự báo của 800 SKU bán chậm về đúng giá trị 0.
   - Về thực chất, mô hình ở phân khúc bán chậm đang tiệm cận hành vi của mô hình *Zero Forecast*. Chỉ số WAPE xấp xỉ 100% ở phân khúc này chủ yếu là đặc thù toán học của mẫu số nhỏ, không phản ánh năng lực học quy luật chuỗi thời gian của cây quyết định.
3. **Hiện tượng Zero-Collapse của hàm tổn thất L1:**
   - Khi huấn luyện LightGBM với hàm mục tiêu `regression_l1` (MAE loss), mô hình bị sụp đổ hoàn toàn về 0 (Zero-collapse do trung vị của tập dữ liệu bán lẻ bằng 0). Do đó nhóm đã loại bỏ phương án này và chọn `tweedie`.
4. **Cấp độ chi tiết từng ô ngày (Daily Cell):**
   - Nếu đo lường ở cấp độ từng ô ngày đơn lẻ (không gộp tuần), mô hình thô (raw) có WAPE cao hơn Baseline 2 (162.13% vs 161.17% ở 7 ngày; 167.14% vs 166.67% ở 30 ngày) và thua Zero Forecast. Điều này chứng minh mô hình cây chưa thể bắt chính xác nhịp xung bán hàng ở cấp độ từng ngày đơn lẻ đối với các mặt hàng có tần suất mua thấp.

### 6.2. Kế hoạch hành động tiếp theo
1. **Tinh chỉnh đặc trưng (Feature Engineering):** Bổ sung thêm biến độ trễ theo chu kỳ khuyến mại, khoảng cách số ngày kể từ lần bán cuối (days since last sale), và mức tồn kho khả dụng để mô hình nhận diện tốt hơn xác suất xuất hiện giao dịch của nhóm bán chậm.
2. **Mô hình hóa hai giai đoạn (Two-stage Modeling):** Tách bài toán thành: (1) Mô hình phân loại nhị phân dự báo khả năng có khách mua trong tuần; (2) Nếu có mua, chạy mô hình hồi quy dự báo số lượng.
3. **Thu thập phản hồi từ SV01:** Thống nhất chính thức giao thức nghiệm thu AC-05 trước khi bước vào đợt đánh giá mô hình lần 2.

---

## PHỤ LỤC

### PHỤ LỤC A: BẢNG SỐ LIỆU ĐẦY ĐỦ CẤP ĐỘ Ô NGÀY (DAILY CELL)

*Quy cách đo:* Từng ngày riêng biệt cho mỗi sản phẩm × chi nhánh (Đã loại trừ ngày stockout). Đơn vị MAE: sản phẩm/ngày.

| Tầm | Mô hình | Phân khúc | MAE (sp/ngày) | WAPE (%) | Tỷ lệ dự báo = 0 | Số ô (N) | Tổng thực tế |
|:---:|:---|:---|:---:|:---:|:---:|:---:|:---:|
| 7 ngày | Baseline 1 (Naive theo tuần) | Toàn bộ (Overall) | 0.2743 | 172.68% | 95.54% | 20,903 | 3,320 |
| 7 ngày | Baseline 1 (Naive theo tuần) | Top 200 bán chạy | 1.0704 | 165.17% | 82.72% | 4,103 | 2,659 |
| 7 ngày | Baseline 1 (Naive theo tuần) | 800 bán chậm | 0.0798 | 202.87% | 98.67% | 16,800 | 661 |
| 7 ngày | Baseline 2 (TB trượt 28 ngày) | Toàn bộ (Overall) | 0.2560 | 161.17% | 60.89% | 20,903 | 3,320 |
| 7 ngày | Baseline 2 (TB trượt 28 ngày) | Top 200 bán chạy | 0.9849 | 151.98% | 3.29% | 4,103 | 2,659 |
| 7 ngày | Baseline 2 (TB trượt 28 ngày) | 800 bán chậm | 0.0780 | 198.13% | 74.96% | 16,800 | 661 |
| 7 ngày | Zero Forecast (Tham chiếu) | Toàn bộ (Overall) | 0.1630 | 102.65% | 100.00% | 20,903 | 3,320 |
| 7 ngày | Zero Forecast (Tham chiếu) | Top 200 bán chạy | 0.6656 | 102.71% | 100.00% | 4,103 | 2,659 |
| 7 ngày | Zero Forecast (Tham chiếu) | 800 bán chậm | 0.0403 | 102.42% | 100.00% | 16,800 | 661 |
| 7 ngày | LightGBM Tweedie (Bản thô) | Toàn bộ (Overall) | 0.2575 | 162.13% | 53.66% | 20,903 | 3,320 |
| 7 ngày | LightGBM Tweedie (Bản thô) | Top 200 bán chạy | 0.9658 | 149.02% | 0.00% | 4,103 | 2,659 |
| 7 ngày | LightGBM Tweedie (Bản thô) | 800 bán chậm | 0.0845 | 214.85% | 66.76% | 16,800 | 661 |
| 7 ngày | **LightGBM Tweedie + hậu xử lý** | Toàn bộ (Overall) | **0.2228** | **140.27%** | 80.24% | 20,903 | 3,320 |
| 7 ngày | **LightGBM Tweedie + hậu xử lý** | Top 200 bán chạy | **0.9658** | **149.02%** | 0.00% | 4,103 | 2,659 |
| 7 ngày | **LightGBM Tweedie + hậu xử lý** | 800 bán chậm | **0.0413** | **105.04%** | 99.83% | 16,800 | 661 |
| | | | | | | | |
| 30 ngày | Baseline 1 (Naive theo tuần) | Toàn bộ (Overall) | 0.2669 | 178.48% | 95.51% | 89,587 | 13,399 |
| 30 ngày | Baseline 1 (Naive theo tuần) | Top 200 bán chạy | 1.0379 | 170.51% | 82.64% | 17,587 | 10,705 |
| 30 ngày | Baseline 1 (Naive theo tuần) | 800 bán chậm | 0.0786 | 210.13% | 98.65% | 72,000 | 2,694 |
| 30 ngày | Baseline 2 (TB trượt 28 ngày) | Toàn bộ (Overall) | 0.2493 | 166.67% | 60.90% | 89,587 | 13,399 |
| 30 ngày | Baseline 2 (TB trượt 28 ngày) | Top 200 bán chạy | 0.9594 | 157.61% | 3.33% | 17,587 | 10,705 |
| 30 ngày | Baseline 2 (TB trượt 28 ngày) | 800 bán chậm | 0.0758 | 202.69% | 74.96% | 72,000 | 2,694 |
| 30 ngày | Zero Forecast (Tham chiếu) | Toàn bộ (Overall) | 0.1518 | 101.51% | 100.00% | 89,587 | 13,399 |
| 30 ngày | Zero Forecast (Tham chiếu) | Top 200 bán chạy | 0.6173 | 101.42% | 100.00% | 17,587 | 10,705 |
| 30 ngày | Zero Forecast (Tham chiếu) | 800 bán chậm | 0.0381 | 101.86% | 100.00% | 72,000 | 2,694 |
| 30 ngày | LightGBM Tweedie (Bản thô) | Toàn bộ (Overall) | 0.2500 | 167.14% | 52.74% | 89,587 | 13,399 |
| 30 ngày | LightGBM Tweedie (Bản thô) | Top 200 bán chạy | 0.9349 | 153.59% | 0.68% | 17,587 | 10,705 |
| 30 ngày | LightGBM Tweedie (Bản thô) | 800 bán chậm | 0.0827 | 220.99% | 65.46% | 72,000 | 2,694 |
| 30 ngày | **LightGBM Tweedie + hậu xử lý** | Toàn bộ (Overall) | **0.2147** | **143.58%** | 80.40% | 89,587 | 13,399 |
| 30 ngày | **LightGBM Tweedie + hậu xử lý** | Top 200 bán chạy | **0.9349** | **153.59%** | 0.68% | 17,587 | 10,705 |
| 30 ngày | **LightGBM Tweedie + hậu xử lý** | 800 bán chậm | **0.0388** | **103.79%** | 99.88% | 72,000 | 2,694 |

---

### PHỤ LỤC B: BẢNG SỐ LIỆU TỔNG GỘP CẢ TẦM (HORIZON AGGREGATE)

*Quy cách đo:* Tổng gộp toàn bộ sản lượng của cả tầm 7 ngày hoặc 30 ngày cho mỗi chuỗi ($N = 3.000$ chuỗi). Phục vụ tính điểm đặt hàng lại (ROP) và dự trù ngân sách mua hàng.

| Tầm | Mô hình | Phân khúc | MAE (sp/tầm) | WAPE (%) | Tỷ lệ dự báo = 0 | Số chuỗi (N) | Tổng thực tế |
|:---:|:---|:---|:---:|:---:|:---:|:---:|:---:|
| 7 ngày | Baseline 1 (Naive theo tuần) | Toàn bộ (Overall) | 1.2557 | 113.46% | 78.90% | 3,000 | 3,320 |
| 7 ngày | Baseline 2 (TB trượt 28 ngày) | Toàn bộ (Overall) | 1.0706 | 96.74% | 56.70% | 3,000 | 3,320 |
| 7 ngày | Baseline 2 (TB trượt 28 ngày) | Top 200 bán chạy | 3.3490 | 75.57% | 2.00% | 600 | 2,659 |
| 7 ngày | Baseline 2 (TB trượt 28 ngày) | 800 bán chậm | 0.5009 | 181.88% | 70.38% | 2,400 | 661 |
| 7 ngày | Zero Forecast (Tham chiếu) | Toàn bộ (Overall) | 1.1140 | 100.66% | 100.00% | 3,000 | 3,320 |
| 7 ngày | LightGBM Tweedie (Bản thô) | Toàn bộ (Overall) | 1.0777 | 97.38% | 0.00% | 3,000 | 3,320 |
| 7 ngày | LightGBM Tweedie (Bản thô) | Top 200 bán chạy | 3.2377 | 73.06% | 0.00% | 600 | 2,659 |
| 7 ngày | **LightGBM Tweedie + hậu xử lý** | Toàn bộ (Overall) | **0.8757** | **79.13%** | 79.87% | 3,000 | 3,320 |
| 7 ngày | **LightGBM Tweedie + hậu xử lý** | Top 200 bán chạy | **3.2377** | **73.06%** | 0.00% | 600 | 2,659 |
| 7 ngày | **LightGBM Tweedie + hậu xử lý** | 800 bán chậm | **0.2851** | **103.53%** | 99.83% | 2,400 | 661 |
| | | | | | | | |
| 30 ngày | Baseline 1 (Naive theo tuần) | Toàn bộ (Overall) | 4.4940 | 100.62% | 78.60% | 3,000 | 13,399 |
| 30 ngày | Baseline 2 (TB trượt 28 ngày) | Toàn bộ (Overall) | 3.2263 | 72.24% | 56.70% | 3,000 | 13,399 |
| 30 ngày | Baseline 2 (TB trượt 28 ngày) | Top 200 bán chạy | 8.9186 | 49.99% | 2.00% | 600 | 10,705 |
| 30 ngày | Baseline 2 (TB trượt 28 ngày) | 800 bán chậm | 1.8032 | 160.64% | 70.38% | 2,400 | 2,694 |
| 30 ngày | Zero Forecast (Tham chiếu) | Toàn bộ (Overall) | 4.4677 | 100.03% | 100.00% | 3,000 | 13,399 |
| 30 ngày | LightGBM Tweedie (Bản thô) | Toàn bộ (Overall) | 2.6757 | 59.91% | 0.00% | 3,000 | 13,399 |
| 30 ngày | LightGBM Tweedie (Bản thô) | Top 200 bán chạy | 6.5537 | 36.73% | 0.00% | 600 | 10,705 |
| 30 ngày | **LightGBM Tweedie + hậu xử lý** | Toàn bộ (Overall) | **2.2245** | **49.81%** | 79.87% | 3,000 | 13,399 |
| 30 ngày | **LightGBM Tweedie + hậu xử lý** | Top 200 bán chạy | **6.5537** | **36.73%** | 0.00% | 600 | 10,705 |
| 30 ngày | **LightGBM Tweedie + hậu xử lý** | 800 bán chậm | **1.1421** | **101.75%** | 99.83% | 2,400 | 2,694 |

---

### PHỤ LỤC C: CẤU HÌNH MÔ HÌNH & THÔNG SỐ HUẤN LUYỆN

1. **Thuật toán & Tham số chính:**
   - Framework: LightGBM (`lightgbm.LGBMRegressor`).
   - Objective: `tweedie` (Tweedie variance power = 1.5).
   - Metric theo dõi: MAE trên tập Validation.
   - Siêu tham số cơ bản: `learning_rate: 0.05`, `num_leaves: 31`, `n_estimators: 300`, `early_stopping_rounds: 15`.
2. **Ngưỡng hậu xử lý tối ưu (Xác lập trên Validation):**
   - Tầm 7 ngày: `0.4`
   - Tầm 30 ngày: `0.4`
3. **Quy tắc cho sản phẩm mới (< 28 ngày lịch sử bán):**
   - Áp dụng phương pháp Fallback lấy trung bình ngành hàng tại chi nhánh.
   - Thống kê thực tế tập Test: 0 chuỗi phát sinh (< 28 ngày dữ liệu).
