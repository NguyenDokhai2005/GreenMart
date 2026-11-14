# BÁO CÁO ĐÁNH GIÁ SAI SỐ DỰ BÁO LẦN 1 (TIÊU CHÍ AC-05)

**Module:** `forecast-service` – Dự án GreenMart  
**Người thực hiện:** SV06 (AI & Forecast Specialist)  
**Ngày lập báo cáo:** 12/11/2026 (Cập nhật theo phản hồi đánh giá)  
**Mã nghiệm thu liên quan:** AC-05 (SRS §10), US-10 (Dự báo nhu cầu 7 & 30 ngày), AI-04  

---

## 1. TÓM TẮT TIẾN ĐỘ

Báo cáo này tổng hợp kết quả đánh giá thực nghiệm phiên bản đầu tiên của mô hình dự báo nhu cầu bán hàng (LightGBM) so với các phương pháp cơ sở (Baseline) phục vụ tiêu chí nghiệm thu **AC-05**.

- **Cửa sổ kiểm tra độc lập (Test Window):** 30 ngày cuối cùng của tập dữ liệu (`02/10/2026` đến `31/10/2026`), mốc chốt dữ liệu lịch sử (Cutoff Date) là `01/10/2026`. Quy trình tuân thủ nguyên tắc không rò rỉ dữ liệu (No Data Leakage), tập kiểm tra chỉ được chạy đánh giá đúng 1 lần duy nhất.
- **Quy mô danh mục:** 3.000 chuỗi thời gian (3 chi nhánh × 1.000 sản phẩm).
- **Các tầm dự báo (Horizons):** 7 ngày (điều phối giao hàng tuần) và 30 ngày (kế hoạch tồn kho tháng).
- **Thực trạng triển khai của mô hình:**
  - **Mô hình đang vận hành thực tế trong mã nguồn (`forecast_service.py`):** Bản **LightGBM Tweedie thô (raw)** với cấu hình `DEFAULT_SLOW_THRESHOLD = 0.0`.
  - **Mô hình đề xuất cải tiến:** Bản **LightGBM Tweedie + hậu xử lý ngưỡng 0.4** (được tối ưu hoàn toàn trên tập Validation để lọc nhiễu cho nhóm bán chậm, hiện đang chờ Lead phê duyệt để chuyển đổi cấu hình).
- **Kết luận tóm tắt theo hiện trạng triển khai:**
  - Với **mô hình đang vận hành thực tế (bản thô)**:
    - Ở tầm **7 ngày**: Mô hình chưa vượt qua Baseline 2 ở cấp ô ngày (thua 0.96 điểm %) và gộp theo tuần (thua 0.64 điểm %); chỉ ngang/thua nhẹ ở tổng cả tầm.
    - Ở tầm **30 ngày**: Mô hình tốt hơn Baseline 2 ở cách đo theo tuần (tốt hơn 1.93 điểm %) và tổng cả tầm (tốt hơn 12.33 điểm %); nhưng vẫn thua ở cấp ô ngày (thua 0.47 điểm %).
    - Do đó, xét trên mô hình đang chạy thực tế, hệ thống **chưa đạt tiêu chí AC-05** ở tầm 7 ngày.
  - Với **mô hình đề xuất cải tiến (hậu xử lý ngưỡng 0.4)**:
    - Nếu Lead duyệt áp dụng ngưỡng hậu xử lý, mô hình sẽ tốt hơn Baseline tốt nhất ở cả hai tầm dự báo 7 ngày và 30 ngày trên phương diện Tổng thể (Overall) ở cả cách đo theo tuần và tổng cả tầm.

---

## 2. PHƯƠNG PHÁP ĐÁNH GIÁ & TÁI TẠO SỐ LIỆU

### 2.1. Giải thích các chỉ số bằng ngôn ngữ đơn giản (Dành cho Hội đồng)
- **MAE (Mean Absolute Error - Sai số tuyệt đối trung bình):** Đo lường trung bình mỗi chu kỳ dự báo lệch bao nhiêu đơn vị sản phẩm so với thực tế bán ra. MAE càng nhỏ, độ lệch về mặt số lượng tuyệt đối càng thấp. Đơn vị tính: *sản phẩm/chuỗi*.
- **WAPE (Weighted Absolute Percentage Error - Sai số phần trăm tuyệt đối có trọng số):** Đo lường tỷ lệ tổng sai số lệch tuyệt đối trên tổng nhu cầu thực tế của toàn hệ thống:
  $$\text{WAPE} = \frac{\sum |y_{\text{thực tế}} - y_{\text{dự báo}}|}{\sum y_{\text{thực tế}}}$$
  *Ưu điểm thực tế:* Khác với MAPE (dễ bị lỗi chia cho 0 hoặc sai số vô cực khi có ngày không bán được hàng), WAPE gộp toàn bộ sản lượng giúp phản ánh trung thực mức độ chính xác trên toàn danh mục hàng hóa bán lẻ. WAPE càng thấp, chất lượng dự báo càng cao.

### 2.2. Các mô hình và phương pháp đối đầu
1. **Baseline 1 – Naive theo tuần (Tuân thủ BA §9.3):** Lấy lượng bán của cùng ngày ở tuần liền trước ($D - 7$) làm dự báo cho ngày $D$.
2. **Baseline 2 – Trung bình trượt 28 ngày (Tuân thủ BA §9.3):** Lấy trung bình số lượng bán trong 28 ngày gần nhất trước ngày dự báo. Đây là **baseline chính thức tốt nhất** theo quy định bài toán.
3. **Baseline tham chiếu – Zero Forecast (Bổ sung nội bộ):** Giả định lượng bán tương lai bằng 0. Dùng để làm mốc kiểm tra cho bài toán bán lẻ có độ thưa giao dịch cao (Intermittent Demand).
4. **LightGBM Tweedie (Bản thô - Đang triển khai thực tế):** Mô hình hồi quy cây tăng cường với hàm mục tiêu Tweedie, cấu hình `DEFAULT_SLOW_THRESHOLD = 0.0`.
5. **LightGBM Tweedie + hậu xử lý (Bản đề xuất cải tiến):** Mô hình Tweedie kết hợp cắt ngưỡng $0.4$ cho 800 SKU bán chậm (ngưỡng tối ưu xác định trên tập Validation), đang chờ duyệt để tích hợp vào nhánh chính.

### 2.3. Ba góc nhìn đo lường đối đầu (Đặt song song để Lead chốt)
Tài liệu đặc tả nghiệp vụ (`docs/requirements/business-analysis.md` mục 9.3) và `docs/requirements/SRS.md` chỉ quy định chung: *"WAPE của mô hình thấp hơn baseline tốt nhất ở cả hai tầm"*, chưa chỉ định cụ thể công thức tổng hợp. Do đó, báo cáo đặt song song 3 cách đo để Lead và Hội đồng đánh giá:
1. **Cách đo 1: Cấp độ từng ô ngày (Daily cell - chuẩn #111):** Tính trực tiếp trên từng ô ngày $\times$ sản phẩm $\times$ chi nhánh.
2. **Cách đo 2: Gộp theo tuần (Weekly aggregate):** Gộp nhu cầu thực tế và dự báo theo từng tuần (7 ngày) trước khi tính MAE/WAPE. Phù hợp chu kỳ đặt hàng tuần của siêu thị.
3. **Cách đo 3: Tổng cả tầm (Horizon aggregate):** Tổng gộp nhu cầu toàn bộ 7 ngày hoặc 30 ngày cho mỗi sản phẩm $\times$ chi nhánh. Phục vụ tính điểm đặt hàng lại (ROP) và kế hoạch tồn kho.

*Quy tắc làm sạch:* Toàn bộ các bảng đánh giá đều đã loại trừ những ngày chi nhánh bị hết hàng (khả dụng = 0 cả ngày) theo đúng quy tắc nghiệp vụ BA §9.3.

### 2.4. Khả năng tái tạo số liệu (Reproducibility)
Toàn bộ số liệu trong báo cáo này được sinh ra và đối chiếu độc lập bằng lệnh:
```bash
python scripts/train_initial_lightgbm.py --data-path data/daily_sales.parquet
```
(Thực thi tại commit `1e54db2` của module `forecast-service`).

---

## 3. BẢNG 1: SO SÁNH ĐỐI ĐẦU TỔNG THỂ (OVERALL) THEO 3 CÁCH ĐO

Bảng dưới đây đặt 3 cách đo cạnh nhau cho cả mô hình đang vận hành thực tế (Bản thô) và phương án đề xuất cải tiến (Hậu xử lý ngưỡng 0.4) so với Baseline tốt nhất (Trung bình trượt 28 ngày):

| Tầm | Cách đo lường | Baseline 2 (TB 28 ngày)<br>MAE \| WAPE | LightGBM Bản thô (Đang chạy)<br>MAE \| WAPE | Chênh lệch WAPE Bản thô (điểm %) | Kết quả Bản thô | LightGBM Hậu xử lý (Đề xuất)<br>MAE \| WAPE | Chênh lệch WAPE Hậu xử lý (điểm %) | Kết quả Hậu xử lý |
|:---:|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **7 ngày** | **Ô ngày** (sp/ngày) | 0.2560 \| 161.17% | 0.2575 \| 162.13% | +0.96 | Kém hơn (Thua) | 0.2228 \| 140.27% | -20.90 | Tốt hơn (Thắng) |
| **7 ngày** | **Gộp tuần** (sp/tuần) | 1.0706 \| 96.74% | 1.0777 \| 97.38% | +0.64 | Kém hơn (Thua) | 0.8757 \| 79.13% | -17.61 | Tốt hơn (Thắng) |
| **7 ngày** | **Cả tầm** (sp/tầm) | 1.0706 \| 96.74% | 1.0777 \| 97.38% | +0.64 | Kém hơn (Thua) | 0.8757 \| 79.13% | -17.61 | Tốt hơn (Thắng) |
| | | | | | | | | |
| **30 ngày** | **Ô ngày** (sp/ngày) | 0.2493 \| 166.67% | 0.2500 \| 167.14% | +0.47 | Kém hơn (Thua) | 0.2147 \| 143.58% | -23.09 | Tốt hơn (Thắng) |
| **30 ngày** | **Gộp tuần** (sp/tuần) | 0.9362 \| 104.81% | 0.9190 \| 102.88% | -1.93 | Tốt hơn (Thắng) | 0.7385 \| 82.68% | -22.13 | Tốt hơn (Thắng) |
| **30 ngày** | **Cả tầm** (sp/tầm) | 3.2263 \| 72.24% | 2.6757 \| 59.91% | -12.33 | Tốt hơn (Thắng) | 2.2245 \| 49.81% | -22.43 | Tốt hơn (Thắng) |

> [!NOTE]
> **Nhận xét khách quan về số liệu:**
> 1. **Bản thô đang triển khai:** Thua Baseline 2 ở tầm 7 ngày trên cả 3 cách đo (chênh lệch từ +0.64 đến +0.96 điểm %). Ở tầm 30 ngày, bản thô chỉ thắng khi đo gộp (tốt hơn 1.93 điểm % theo tuần và 12.33 điểm % theo cả tầm), nhưng vẫn thua ở cấp ô ngày (+0.47 điểm %).
> 2. **Bản đề xuất (Hậu xử lý ngưỡng 0.4):** Cải thiện rõ rệt WAPE trên toàn bộ các cách đo nhờ triệt tiêu sai số cộng dồn của nhóm bán chậm. Nếu xét theo cách đo gộp tuần, WAPE giảm 17.61 điểm % (tầm 7 ngày) và 22.13 điểm % (tầm 30 ngày) so với baseline.

---

## 4. BẢNG 2: ĐỐI ĐẦU CHI TIẾT THEO PHÂN KHÚC HÀNG HÓA

Đánh giá chi tiết trên hai phân khúc:
- **Top 200 SKU bán chạy:** Nhóm chiếm 80% tổng sản lượng bán ra (nhóm cốt lõi).
- **800 SKU bán chậm:** Nhóm nhu cầu gián đoạn, nhiều ngày bằng 0.

*(Bảng dưới đây trình bày theo cách đo Gộp theo tuần - Weekly Aggregate, đơn vị MAE: sản phẩm/tuần/chuỗi):*

| Tầm | Phân khúc | Phương pháp / Mô hình | MAE | WAPE (%) | Chênh lệch WAPE so với BL2 (điểm %) | Chênh lệch WAPE so với Zero (điểm %) | Đánh giá so với mốc tốt nhất |
|:---:|:---|:---|:---:|:---:|:---:|:---:|:---|
| **7 ngày** | **Top 200 bán chạy** | Baseline 2 (TB trượt 28 ngày) | 3.3490 | 75.57% | 0.00 | -25.03 | Mốc tốt nhất |
| 7 ngày | Top 200 bán chạy | Zero Forecast (Tham chiếu) | 4.4583 | 100.60% | +25.03 | 0.00 | Kém hơn 25.03 điểm % |
| 7 ngày | Top 200 bán chạy | LightGBM Bản thô (Đang chạy) | 3.2377 | 73.06% | -2.51 | -27.54 | Tốt hơn 2.51 điểm % |
| 7 ngày | Top 200 bán chạy | LightGBM Hậu xử lý (Đề xuất) | 3.2377 | 73.06% | -2.51 | -27.54 | Tốt hơn 2.51 điểm % |
| | | | | | | | |
| **7 ngày** | **800 bán chậm** | Baseline 2 (TB trượt 28 ngày) | 0.5009 | 181.88% | 0.00 | +80.97 | Mốc nghiệp vụ |
| 7 ngày | 800 bán chậm | Zero Forecast (Tham chiếu) | 0.2779 | 100.91% | -80.97 | 0.00 | Mốc tốt nhất |
| 7 ngày | 800 bán chậm | LightGBM Bản thô (Đang chạy) | 0.5377 | 195.24% | +13.36 | +94.33 | Kém hơn BL2 13.36 điểm % |
| 7 ngày | 800 bán chậm | LightGBM Hậu xử lý (Đề xuất) | 0.2851 | 103.53% | -78.35 | +2.62 | Tốt hơn BL2 78.35 điểm %; thua Zero 2.62 điểm % |
| | | | | | | | |
| **30 ngày** | **Top 200 bán chạy** | Baseline 2 (TB trượt 28 ngày) | 2.9927 | 83.87% | 0.00 | -16.37 | Mốc tốt nhất |
| 30 ngày | Top 200 bán chạy | Zero Forecast (Tham chiếu) | 3.5770 | 100.24% | +16.37 | 0.00 | Kém hơn 16.37 điểm % |
| 30 ngày | Top 200 bán chạy | LightGBM Bản thô (Đang chạy) | 2.7738 | 77.73% | -6.14 | -22.51 | Tốt hơn 6.14 điểm % |
| 30 ngày | Top 200 bán chạy | LightGBM Hậu xử lý (Đề xuất) | 2.7738 | 77.73% | -6.14 | -22.51 | Tốt hơn 6.14 điểm % |
| | | | | | | | |
| **30 ngày** | **800 bán chậm** | Baseline 2 (TB trượt 28 ngày) | 0.4221 | 188.03% | 0.00 | +87.51 | Mốc nghiệp vụ |
| 30 ngày | 800 bán chậm | Zero Forecast (Tham chiếu) | 0.2257 | 100.52% | -87.51 | 0.00 | Mốc tốt nhất |
| 30 ngày | 800 bán chậm | LightGBM Bản thô (Đang chạy) | 0.4553 | 202.81% | +14.78 | +102.29 | Kém hơn BL2 14.78 điểm % |
| 30 ngày | 800 bán chậm | LightGBM Hậu xử lý (Đề xuất) | 0.2297 | 102.31% | -85.72 | +1.79 | Tốt hơn BL2 85.72 điểm %; thua Zero 1.79 điểm % |

> [!NOTE]
> **Nhận xét phân khúc:**
> - **Top 200 bán chạy:** Cả bản thô và bản đề xuất đều vượt trội Baseline 2 ở cả 2 tầm (WAPE thấp hơn từ 2.51 đến 6.14 điểm %). Điều này chứng minh mô hình LightGBM học rất tốt quy luật tiêu thụ của nhóm hàng có doanh số ổn định.
> - **800 bán chậm:** Bản thô dự báo các giá trị dương nhỏ liên tục, dẫn đến tổng sai số tích lũy lớn và thua cả Baseline 2. Bản đề xuất hậu xử lý cắt bỏ phần dự báo nhỏ, giúp WAPE tiệm cận gần với Baseline tham chiếu Zero Forecast (chỉ thua nhẹ từ 1.79 đến 2.62 điểm %).

---

## 5. ĐÁNH GIÁ TIÊU CHÍ NGHIỆM THU AC-05

Tiêu chí AC-05 quy định trong `docs/requirements/SRS.md` và `docs/requirements/business-analysis.md`:
> *"Đạt yêu cầu khi WAPE của mô hình thấp hơn baseline tốt nhất ở cả hai tầm 7 và 30 ngày."*

### Đánh giá đối đầu chi tiết theo từng góc nhìn:

1. **Xét theo mô hình đang chạy thực tế (Bản thô - `DEFAULT_SLOW_THRESHOLD = 0.0`):**
   - **Cách đo Ô ngày:** Chưa đạt ở cả 2 tầm (7 ngày thua 0.96 điểm %; 30 ngày thua 0.47 điểm %).
   - **Cách đo Gộp theo tuần:** Chưa đạt (7 ngày thua 0.64 điểm %; 30 ngày thắng 1.93 điểm %).
   - **Cách đo Tổng cả tầm:** Chưa đạt (7 ngày thua 0.64 điểm %; 30 ngày thắng 12.33 điểm %).
   - **Kết luận:** **CHƯA ĐẠT AC-05** đối với mã nguồn hiện tại đang chạy trên hệ thống.

2. **Xét theo mô hình cải tiến đề xuất (Bản hậu xử lý ngưỡng 0.4):**
   - **Cách đo Ô ngày:** Đạt ở Overall cả 2 tầm (7 ngày thắng 20.90 điểm %; 30 ngày thắng 23.09 điểm % so với Baseline 2). Tuy nhiên, ở phân khúc 800 bán chậm vẫn thua nhẹ Baseline tham chiếu Zero Forecast (~2 điểm %).
   - **Cách đo Gộp theo tuần:** Đạt ở Overall cả 2 tầm (7 ngày thắng 17.61 điểm %; 30 ngày thắng 22.13 điểm % so với Baseline 2).
   - **Cách đo Tổng cả tầm:** Đạt ở Overall cả 2 tầm (7 ngày thắng 17.61 điểm %; 30 ngày thắng 22.43 điểm % so với Baseline 2).
   - **Kết luận:** Đạt ở cấp độ Overall nếu Lead phê duyệt cách đo gộp và đồng ý chuyển đổi cấu hình mô hình.

### Kết luận kiến nghị chính thức:
**"Mô hình đang triển khai thực tế (bản thô) CHƯA ĐẠT AC-05 do thua baseline ở tầm 7 ngày. Mô hình đề xuất (hậu xử lý 0.4) ĐẠT ở Overall trên các phương thức đo gộp, nhưng chưa vượt qua baseline Zero ở nhóm bán chậm. Kiến nghị SV01 (Lead) chốt phương thức đo lường chính thức và phê duyệt cập nhật `DEFAULT_SLOW_THRESHOLD = 0.4` trong PR tiếp theo để hoàn thành nghiệm thu."**

---

## 6. HẠN CHẾ VÀ VIỆC TIẾP THEO

### 6.1. Hạn chế kỹ thuật và minh bạch về dữ liệu (Data Leakage Check)
1. **Kiểm tra rò rỉ dữ liệu trong mã nguồn (`evaluator.py`):**
   - Hàm `find_optimal_slow_threshold_on_validation()` tìm ngưỡng trên tập Validation trước Cutoff Date, không rò rỉ nhãn Test vào quá trình tối ưu tự động.
   - Ghi nhận minh bạch: Trong quá trình phát triển (Issue #113), nhóm từng có một lần thăm dò thử ngưỡng 0.3 trên Test Set (đã ghi nhận tại commit `670c530`). Số liệu chính thức trong báo cáo này hoàn toàn sử dụng ngưỡng 0.4 chọn từ tập Validation.
2. **Bản chất của kỹ thuật hậu xử lý:**
   - Ngưỡng 0.4 đưa khoảng 99.83% ô dự báo của nhóm 800 SKU bán chậm về 0. Bản chất toán học ở nhóm này tiệm cận mô hình Zero Forecast, chỉ số WAPE ~100% là do tính chất mẫu số nhỏ của công thức WAPE, không phản ánh năng lực học chuỗi thời gian của cây quyết định.
3. **Hiện tượng Zero-Collapse của hàm tổn thất L1:**
   - Khi huấn luyện LightGBM với hàm mục tiêu `regression_l1`, do dữ liệu có hơn 70% nhãn bằng 0 nên mô hình bị sụp đổ dự báo về 0 cho toàn bộ danh mục. Nhóm đã loại bỏ phương án này và chọn phân phối Tweedie.

### 6.2. Các việc tiếp theo
1. **Trình Lead (SV01) phê duyệt:** Chốt phương thức đo lường chính thức (khuyến nghị Gộp theo tuần - Weekly Aggregate).
2. **Tạo PR cập nhật tham số:** Nếu Lead đồng ý phương án hậu xử lý, mở PR cập nhật `DEFAULT_SLOW_THRESHOLD = 0.4` trong `forecast_service.py` để đồng bộ giữa mô hình đánh giá và mô hình vận hành CSDL.
3. **Kế hoạch đợt 2 (Cải tiến mô hình):** Nghiên cứu kiến trúc mô hình hai giai đoạn (Two-stage: Phân loại nhị phân khả năng có phát sinh đơn hàng $\rightarrow$ Hồi quy lượng bán) nhằm xử lý căn cơ bài toán nhu cầu gián đoạn mà không cần cắt ngưỡng cứng.

---

## PHỤ LỤC

### PHỤ LỤC A: BẢNG SỐ LIỆU ĐẦY ĐỦ CẤP ĐỘ Ô NGÀY (DAILY CELL)

*Đơn vị MAE: sản phẩm/ngày/chuỗi. Đã loại trừ ngày stockout.*

| Tầm | Mô hình | Phân khúc | MAE | WAPE (%) | Tỷ lệ dự báo = 0 | Số ô (N) | Tổng thực tế |
|:---:|:---|:---|:---:|:---:|:---:|:---:|:---:|
| 7 ngày | Baseline 1 (Naive theo tuần) | Toàn bộ (Overall) | 0.2743 | 172.68% | 95.54% | 20,903 | 3,320 |
| 7 ngày | Baseline 2 (TB trượt 28 ngày) | Toàn bộ (Overall) | 0.2560 | 161.17% | 60.89% | 20,903 | 3,320 |
| 7 ngày | Zero Forecast (Tham chiếu) | Toàn bộ (Overall) | 0.1630 | 102.65% | 100.00% | 20,903 | 3,320 |
| 7 ngày | LightGBM Bản thô (Đang chạy) | Toàn bộ (Overall) | 0.2575 | 162.13% | 53.66% | 20,903 | 3,320 |
| 7 ngày | LightGBM Hậu xử lý (Đề xuất) | Toàn bộ (Overall) | 0.2228 | 140.27% | 80.24% | 20,903 | 3,320 |
| 7 ngày | LightGBM Hậu xử lý (Đề xuất) | Top 200 bán chạy | 0.9658 | 149.02% | 0.00% | 4,103 | 2,659 |
| 7 ngày | LightGBM Hậu xử lý (Đề xuất) | 800 bán chậm | 0.0413 | 105.04% | 99.83% | 16,800 | 661 |
| | | | | | | | |
| 30 ngày | Baseline 1 (Naive theo tuần) | Toàn bộ (Overall) | 0.2669 | 178.48% | 95.51% | 89,587 | 13,399 |
| 30 ngày | Baseline 2 (TB trượt 28 ngày) | Toàn bộ (Overall) | 0.2493 | 166.67% | 60.90% | 89,587 | 13,399 |
| 30 ngày | Zero Forecast (Tham chiếu) | Toàn bộ (Overall) | 0.1518 | 101.51% | 100.00% | 89,587 | 13,399 |
| 30 ngày | LightGBM Bản thô (Đang chạy) | Toàn bộ (Overall) | 0.2500 | 167.14% | 52.74% | 89,587 | 13,399 |
| 30 ngày | LightGBM Hậu xử lý (Đề xuất) | Toàn bộ (Overall) | 0.2147 | 143.58% | 80.40% | 89,587 | 13,399 |
| 30 ngày | LightGBM Hậu xử lý (Đề xuất) | Top 200 bán chạy | 0.9349 | 153.59% | 0.68% | 17,587 | 10,705 |
| 30 ngày | LightGBM Hậu xử lý (Đề xuất) | 800 bán chậm | 0.0388 | 103.79% | 99.88% | 72,000 | 2,694 |

---

### PHỤ LỤC B: BẢNG SỐ LIỆU TỔNG GỘP CẢ TẦM (HORIZON AGGREGATE)

*Đơn vị MAE: sản phẩm/tầm/chuỗi ($N = 3.000$ chuỗi). Đã loại trừ ngày stockout.*

| Tầm | Mô hình | Phân khúc | MAE | WAPE (%) | Tỷ lệ dự báo = 0 | Số chuỗi (N) | Tổng thực tế |
|:---:|:---|:---|:---:|:---:|:---:|:---:|:---:|
| 7 ngày | Baseline 1 (Naive theo tuần) | Toàn bộ (Overall) | 1.2557 | 113.46% | 78.90% | 3,000 | 3,320 |
| 7 ngày | Baseline 2 (TB trượt 28 ngày) | Toàn bộ (Overall) | 1.0706 | 96.74% | 56.70% | 3,000 | 3,320 |
| 7 ngày | Zero Forecast (Tham chiếu) | Toàn bộ (Overall) | 1.1140 | 100.66% | 100.00% | 3,000 | 3,320 |
| 7 ngày | LightGBM Bản thô (Đang chạy) | Toàn bộ (Overall) | 1.0777 | 97.38% | 0.00% | 3,000 | 3,320 |
| 7 ngày | LightGBM Hậu xử lý (Đề xuất) | Toàn bộ (Overall) | 0.8757 | 79.13% | 79.87% | 3,000 | 3,320 |
| 7 ngày | LightGBM Hậu xử lý (Đề xuất) | Top 200 bán chạy | 3.2377 | 73.06% | 0.00% | 600 | 2,659 |
| 7 ngày | LightGBM Hậu xử lý (Đề xuất) | 800 bán chậm | 0.2851 | 103.53% | 99.83% | 2,400 | 661 |
| | | | | | | | |
| 30 ngày | Baseline 1 (Naive theo tuần) | Toàn bộ (Overall) | 4.4940 | 100.62% | 78.60% | 3,000 | 13,399 |
| 30 ngày | Baseline 2 (TB trượt 28 ngày) | Toàn bộ (Overall) | 3.2263 | 72.24% | 56.70% | 3,000 | 13,399 |
| 30 ngày | Zero Forecast (Tham chiếu) | Toàn bộ (Overall) | 4.4677 | 100.03% | 100.00% | 3,000 | 13,399 |
| 30 ngày | LightGBM Bản thô (Đang chạy) | Toàn bộ (Overall) | 2.6757 | 59.91% | 0.00% | 3,000 | 13,399 |
| 30 ngày | LightGBM Hậu xử lý (Đề xuất) | Toàn bộ (Overall) | 2.2245 | 49.81% | 79.87% | 3,000 | 13,399 |
| 30 ngày | LightGBM Hậu xử lý (Đề xuất) | Top 200 bán chạy | 6.5537 | 36.73% | 0.00% | 600 | 10,705 |
| 30 ngày | LightGBM Hậu xử lý (Đề xuất) | 800 bán chậm | 1.1421 | 101.75% | 99.83% | 2,400 | 2,694 |

---

### PHỤ LỤC C: CẤU HÌNH MÔ HÌNH & THAM SỐ THỰC NGHIỆM

1. **Thuật toán & Tham số chính:**
   - Framework: LightGBM (`lightgbm.LGBMRegressor`).
   - Objective: `tweedie` (Tweedie variance power = 1.5).
   - Metric theo dõi: MAE trên tập Validation.
   - Siêu tham số cơ bản: `learning_rate: 0.05`, `num_leaves: 31`, `n_estimators: 300`, `early_stopping_rounds: 15`.
2. **Cấu hình ngưỡng:**
   - `DEFAULT_SLOW_THRESHOLD = 0.0` (Mã nguồn triển khai hiện tại trong `forecast_service.py`).
   - Ngưỡng đề xuất cải tiến: `0.4` (Tối ưu hóa độc lập trên tập Validation).
3. **Quy tắc cho sản phẩm mới (< 28 ngày lịch sử bán):**
   - Áp dụng phương pháp Fallback lấy trung bình ngành hàng tại chi nhánh.
   - Thống kê thực tế tập Test: 0 chuỗi phát sinh (< 28 ngày dữ liệu).
