# Báo cáo cá nhân SV06 — Phân hệ Dự báo nhu cầu & AI

**Tài khoản thực hiện:** `@hongthienhao` (Hồ Nguyễn Thiên Hạo) · **Mốc tổng hợp:** 21/11/2026 · **Dự án:** GreenMart DT03.

---

## 1. Vai trò và phạm vi

SV06 phụ trách phân hệ Dữ liệu & Trí tuệ nhân tạo (Data & AI Specialist):
- Thiết kế tham số và hiện thực trình sinh dữ liệu lịch sử 12 tháng (AC-01, AI-01).
- Xây dựng pipeline tổng hợp doanh số bán ròng và cờ khuyến mại/stockout vào bảng `daily_sales` (AI-02).
- Trích xuất đặc trưng chuỗi thời gian dựa trên Ngày Gốc (Origin Date), ngăn chặn rò rỉ dữ liệu.
- Xây dựng 2 mô hình cơ sở (Baseline 1 Weekly Naive, Baseline 2 Rolling Mean 28D) và phát triển mô hình học máy LightGBM Tweedie kết hợp cắt ngưỡng tối ưu 0.4 cho nhóm bán chậm (AI-03, AI-04, AC-05).
- Triển khai vi dịch vụ FastAPI bất đồng bộ (`POST /forecast/run`, `GET /forecast/runs/{runId}`), xử lý concurrency lock, dọn dẹp run kẹt và bảo mật token nội bộ (#311).
- Lập lịch tự động chạy đêm 02:00 (APScheduler), cơ chế chạy bù (Startup Catch-up) và phát sự kiện `forecast.completed` lên RabbitMQ topic exchange (AI-03, SRS §8.4).
- Phối hợp với Backend (SV03, SV07) và Frontend (SV04, SV05) để tích hợp tính ROP, gợi ý nhập hàng và hiển thị biểu đồ dự báo.

Chương mô tả chi tiết giải pháp kỹ thuật và kết quả thực nghiệm học thuật nằm tại [SV06-chuong-ai.md](SV06-chuong-ai.md) thuộc Issue #121.

---

## 2. Kết quả đã merge vào `develop`

Toàn bộ các gói công việc cốt lõi của phân hệ `forecast-service` do SV06 chủ trì đã được hoàn thành, kiểm thử tự động và **MERGED** vào nhánh chính `develop`:

| Issue | Nội dung bàn giao | PR tương ứng | Trạng thái Git |
| :---: | :---| :---: | :---: |
| **#105** | Thiết kế 345 dòng tham số sinh dữ liệu giả lập (12 tháng, mùa vụ Tết, Pareto 80/20, stockout, random seed 2026) | PR #199 | **MERGED** |
| **#106** | Trình sinh dữ liệu lịch sử demo cho 3 chi nhánh, 1.000 SKU, 10.000 đơn hàng `COMPLETED` | PR #209 | **MERGED** |
| **#107** | Sinh tồn kho khởi tạo, neo sản phẩm sữa tươi TH True Milk (ROP = 34, tồn = 35) kích hoạt cảnh báo theo US-09 | PR #219 | **MERGED** |
| **#108** | Bộ điều phối seed dữ liệu một lệnh (`seed.py`), kiểm tra SHA256SUMS, script tự kiểm đếm AC-01 (`verify_seed.sql`) | PR #220 | **MERGED** |
| **#109** | Pipeline tổng hợp bảng `daily_sales` (tích Descartes ngày 0, bán ròng, cờ khuyến mại và xử lý ngày stockout) | PR #231 | **MERGED** |
| **#110** | Notebook EDA phân tích xu hướng bán hàng, xuất dữ liệu chuẩn `daily_sales.parquet` (1,095 triệu dòng) | PR #247 | **MERGED** |
| **#111** | Hiện thực Baseline 1 (Weekly Naive), Baseline 2 (Rolling Mean 28D) và module đo lường chuẩn MAE/WAPE | PR #255 | **MERGED** |
| **#112** | Xây dựng ma trận đặc trưng ngày gốc và huấn luyện mô hình LightGBM (Tweedie/L1) ban đầu | PR #261 | **MERGED** |
| **#113** | Đánh giá đa chiều (ô ngày, tuần, tổng tầm ROP), so sánh baseline theo nhóm và pipeline ghi PostgreSQL | PR #272 | **MERGED** |
| **#114** | API FastAPI (`POST /forecast/run`, `GET /forecast/runs/{runId}`), concurrency lock, timeout dọn dẹp run kẹt | PR #290 | **MERGED** |
| **#116** | Bộ ca kiểm thử tính tay (Hand-calculated Test Cases) đối soát tính đúng đắn cho `daily_sales`, MAE và WAPE | PR #294 | **MERGED** |
| **#117** | Báo cáo đánh giá sai số MAE/WAPE lần 1 phục vụ tiêu chí AC-05 | PR #295 | **MERGED** |
| **#118** | Lập lịch tự động 02:00 hằng đêm (APScheduler), cơ chế chạy bù và phát sự kiện `forecast.completed` lên RabbitMQ | PR #391 | **MERGED** |
| **#120** | Báo cáo đánh giá mô hình cuối cùng đạt chuẩn AC-05 và script trích xuất đa chiều `evaluate_segments.py` | PR #120 | **MERGED** |

---

## 3. Công việc tuần 4 đang qua review và hoàn thiện

| Issue | Nội dung bàn giao | Kiểm chứng và Tình trạng |
| :---: | :---| :---|
| **#121** | Viết chương AI và Báo cáo cá nhân SV06 | Đã soạn thảo hoàn chỉnh [SV06-chuong-ai.md](SV06-chuong-ai.md) và [SV06-week4.md](SV06-week4.md) trên nhánh `docs/121-ai-chapter-and-individual-report`; chuẩn bị mở PR vào `develop`. |
| **#311** | Bảo mật API Token nội bộ (`FORECAST_INTERNAL_TOKEN`) | Cơ chế Fail-Closed chống timing attack bằng `secrets.compare_digest`; đã tích hợp với Backend `ForecastClient.java` (PR #344). |
| **#344** | Backend gọi Forecast-service có Bearer Token | Đã phối hợp cùng SV07 kiểm thử integration; Backend đã merge vào `develop` tại commit `310c55b`. |
| **Hỗ trợ Bug Hunt** | Khắc phục các vấn đề phản hồi kiểm thử ngày 17/11 (BH03 #115, #323, #388, #389) | Cố định logic tính doanh thu thuần và tiền hoàn trả đồng bộ giữa bảng `daily_sales` và báo cáo doanh thu backend. |

---

## 4. Việc còn phụ thuộc và phối hợp nhóm

1. **Tự động hóa nạp `daily_sales` sau khi seed:**
   - Script `seed.py` hiện tại mới chỉ nạp 24 bảng nghiệp vụ. Bảng `daily_sales` cần chạy qua pipeline `daily_sales.py`.
   - *Phối hợp:* Đã kiến nghị SV01 chốt kịch bản tích hợp lệnh `python app/pipelines/daily_sales.py` vào cuối script `seed.py` hoặc tài liệu hóa rõ ràng trong kịch bản nghiệm thu demo.
2. **Cấu hình `FORECAST_INTERNAL_TOKEN` trong `.env`:**
   - Cần đảm bảo file `.env` thực tế của các thành viên và trên máy demo điền giá trị token bí mật ngẫu nhiên để tránh lỗi 401 khi Backend gọi sang.
3. **Consumer lắng nghe RabbitMQ ở Backend:**
   - Phân hệ dự báo đã hoàn thành phát sự kiện `forecast.completed` lên exchange `greenmart.events`.
   - *Phối hợp:* Đề xuất SV03/SV07 bổ sung `@RabbitListener` trong backend để tự động kích hoạt tính lại ROP kho ngay khi dự báo hoàn tất.

---

## 5. Bằng chứng kiểm chứng và Cách tái hiện (Verification Evidence)

Các lệnh kiểm chứng độc lập trên môi trường `forecast-service` (Python 3.11):

```bash
# 1. Chạy toàn bộ 25 ca kiểm thử đơn vị và mock của forecast-service:
pytest forecast-service/tests -v --tb=short -m "not integration"
# Kết quả: 25 passed

# 2. Kiểm thử tính tái lập của script seed dữ liệu và kiểm đếm AC-01:
python forecast-service/scripts/seed.py --dry-run
# Kết quả: Khớp hoàn toàn SHA-256 trong file SHA256SUMS

# 3. Tái lập bộ số liệu đánh giá mô hình AC-05:
python forecast-service/scripts/train_initial_lightgbm.py --data-path forecast-service/data/daily_sales.parquet
python forecast-service/scripts/evaluate_segments.py
# Kết quả: Sinh ra evaluation_metrics.json và segment_metrics.json, xác nhận:
# - WAPE 7 ngày: Mô hình 79.13% vs Baseline 2 96.74% (Thắng -17.61 điểm %)
# - WAPE 30 ngày: Mô hình 82.68% vs Baseline 2 104.81% (Thắng -22.13 điểm %)
# - WAPE 30 ngày tổng tầm ROP: Mô hình 49.81% vs Baseline 2 72.24% (Thắng -22.43 điểm %)

# 4. Kiểm tra sức khỏe dịch vụ FastAPI:
curl -i http://localhost:8001/healthz
curl -i http://localhost:8001/livez
```

---

## 6. Tự đánh giá và Đóng góp cá nhân

- **Mức độ hoàn thành nhiệm vụ:**
  - **Đạt 100% tiêu chí AC-01:** Tạo lập thành công bộ dữ liệu lịch sử chuẩn mực 10.000 đơn hàng, 1.000 SKU cho 3 chi nhánh; cung cấp toàn bộ nền tảng dữ liệu cho nhóm Backend kiểm thử tải (k6), phân quyền, đồng bộ đa kênh và nhóm Frontend xây dựng giao diện POS, kho, báo cáo.
  - **Đạt 100% tiêu chí AC-05:** Huấn luyện thành công mô hình `LightGBM-Tweedie-H7-H30` kết hợp kỹ thuật cắt ngưỡng tối ưu 0.4 cho nhóm bán chậm, chiến thắng áp đảo Baseline tốt nhất ở cả hai tầm 7 và 30 ngày trên toàn bộ 3 chi nhánh và 15 ngành hàng.
- **Tác phong và phối hợp:**
  - Nghiêm túc tuân thủ quy trình Git flow, viết tài liệu kỹ thuật chi tiết, cung cấp hợp đồng giao tiếp rõ ràng cho Backend và Frontend.
  - Thẳng thắn chỉ ra các giới hạn thực tế của mô hình (sai số ô ngày, bản chất cắt ngưỡng) nhằm đảm bảo tính trung thực học thuật trước Hội đồng nghiệm thu.
