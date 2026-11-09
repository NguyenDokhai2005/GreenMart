# Kịch bản demo nghiệm thu AC-01 đến AC-06 (bản nháp)

Bản nháp cho issue #13. Nguồn: SRS §10 (6 tiêu chí nghiệm thu), BA §3, §4, §8, §9, §11. Bản chính thức chốt ở #24 (tổng duyệt và quay video 10–15 phút).

Mỗi tiêu chí có: người trình bày, dữ liệu chuẩn bị, các bước, kết quả hội đồng phải thấy, và trạng thái sẵn sàng tính đến 07/11/2026.

## Tổng quan

| Mã | Tiêu chí | Người trình bày | Thời lượng | Sẵn sàng (07/11) | Còn chờ |
| --- | --- | --- | --- | --- | --- |
| AC-01 | 3 chi nhánh, 1.000 sản phẩm, 10.000 đơn lịch sử | SV06 | 1 phút | Đạt | – |
| AC-02 | Không tồn âm khi giao dịch đồng thời | SV03, SV08 | 2 phút | Một phần | Script k6 #150; màn POS #71, #77 |
| AC-03 | Đơn online đồng bộ hai chiều | SV07, SV02 | 3 phút | Chưa | #132, #40, #133, #137, #59 |
| AC-04 | Phân quyền đúng | SV08 | 1,5 phút | Đạt qua API | Màn 403 trên giao diện (#70, #228) |
| AC-05 | Dự báo 7 và 30 ngày tốt hơn baseline | SV06 | 2 phút | Một phần | Mô hình #112, #113; chốt cách tính WAPE |
| AC-06 | Dashboard và cảnh báo tồn thấp | SV05, SV03 | 2,5 phút | Chưa | #60, #64, #97, #98, #119 |

Tổng khoảng 12 phút, cộng 1 phút mở đầu (kiến trúc, `docker compose ps`) và 1 phút kết.

## Chuẩn bị chung (làm trước buổi demo)

1. Máy demo: `docker compose up -d --wait`, kiểm tra `backend`, `postgres`, `redis`, `rabbitmq`, `mock-connector`, `forecast-service`, `frontend` đều `healthy`.
2. Database sạch: chạy seed một lệnh `scripts/seed.ps1` (Windows) hoặc `scripts/seed.sh` (#108). Không sửa dữ liệu bằng tay sau seed.
3. Tài khoản demo (README, mật khẩu chung `Demo@123`):

   | Dùng cho | Tài khoản | Vai trò | Chi nhánh |
   | --- | --- | --- | --- |
   | Bán POS | `thungan.gm001` | CASHIER | GM-001 |
   | Duyệt phiếu, xử lý đơn online | `quanly.gm001` | BRANCH_MANAGER | GM-001 |
   | Lập phiếu nhập | `thukho.gm001` | STOREKEEPER | GM-001 |
   | Dashboard, báo cáo dự báo | `owner` | OWNER | Mọi chi nhánh |

4. Sản phẩm demo cố định (ghi `productId`, SKU vào phiếu chuẩn bị):
   - **SP-DEMO-A**: còn đúng 5 tại GM-001, dùng cho AC-02.
   - **SP-DEMO-B**: có ánh xạ SKU trên kênh WEB và MARKET, tồn GM-001 đủ 10, dùng cho AC-03.
   - **SP-DEMO-C**: có ROP, tồn cao hơn ROP 2 đơn vị, dùng cho AC-06.
   Cách tạo dữ liệu này (fixture riêng hay chọn từ seed) chốt ở #24, không sửa seed.
5. Mở sẵn tab: frontend `http://localhost:3000`, mock-connector `http://localhost:8090`, terminal có `psql` vào database.

## AC-01 · Dữ liệu lịch sử đủ quy mô

- **Trình bày:** SV06.
- **Bước:**
  1. Chạy `scripts/seed.ps1 --verify-only` (hoặc `seed.sh --verify-only`).
  2. Đọc bảng kiểm đếm trên terminal.
- **Hội đồng phải thấy:** 3 chi nhánh, 1.000 sản phẩm, 10.000 đơn đã xử lý trong 12 tháng, và dòng `[PASS] … TẤT CẢ CÁC TIÊU CHÍ CỨNG AC-01 ĐẠT 100%`.
- **Dự phòng:** nếu hội đồng muốn tự đếm, chạy `SELECT count(*) FROM branch;`, `… FROM product;`, `… FROM sales_order WHERE status = 'COMPLETED';`.

## AC-02 · Không tồn âm khi giao dịch đồng thời (EX-01)

- **Trình bày:** SV03 (giải thích cơ chế), SV08 (chạy k6).
- **Bước:**
  1. Cho hội đồng xem tồn SP-DEMO-A tại GM-001: `on_hand = 5`, `reserved = 0`.
  2. Chạy script k6 #150: 100 yêu cầu `POST /orders/pos` song song, mỗi yêu cầu mua 1 SP-DEMO-A, mỗi yêu cầu một `Idempotency-Key` khác nhau.
  3. Đọc kết quả k6 và truy vấn lại tồn và sổ kho.
  4. Trên màn POS (#71, #77), thử bán thêm 1 SP-DEMO-A: giao diện báo hết hàng, giỏ giữ nguyên.
- **Hội đồng phải thấy:** đúng 5 lần 201, 95 lần 409 `INSUFFICIENT_STOCK`; `on_hand = 0`, không âm; `inventory_transaction` có đúng 5 dòng SALE cho sản phẩm này.
- **Cơ chế cần nói:** UPDATE có điều kiện `on_hand − reserved ≥ qty` trong cùng transaction với sổ kho; CHECK constraint chặn tồn âm ở database (đã có test #56); khóa theo `productId` tăng dần để không deadlock (EX-02, #57).
- **Đã có:** API POS (#32, #33), test đồng thời Testcontainers (#56). **Còn chờ:** script k6 (#150, SV08); màn POS frontend; #57 (PR #257 đã bị đóng, cần SV03 làm lại).

## AC-03 · Đơn online đồng bộ hai chiều (EX-03)

- **Trình bày:** SV07 (kênh, đồng bộ), SV02 (vòng đời đơn).
- **Bước:**
  1. Mở mock-connector, xem tồn SP-DEMO-B đang hiển thị trên WEB và MARKET (ví dụ 8 = 10 − tồn an toàn 2).
  2. Trên WEB, tạo đơn 2 SP-DEMO-B (COD).
  3. Đăng nhập `quanly.gm001`: đơn xuất hiện trong "Cần xử lý" ở trạng thái CONFIRMED; tồn GM-001 `reserved = 2`.
  4. Quay lại mock-connector: MARKET hiển thị tồn mới 6 (không cần thao tác gì thêm).
  5. Bấm "Gửi lại" cùng đơn trên WEB 2 lần (giả lập mạng chập chờn).
  6. Quản lý bấm Hoàn tất: đơn COMPLETED, WEB nhận trạng thái mới.
- **Hội đồng phải thấy:** chỉ 1 đơn trong hệ thống dù gửi 3 lần, giữ chỗ 1 lần; kênh còn lại nhận tồn mới trong vài giây; trạng thái đơn về kênh.
- **Có thể nói thêm (nếu còn giờ):** dừng RabbitMQ 1 phút rồi bật lại, sự kiện trong outbox được gửi bù (EX-12).
- **Đã có:** mock-connector (#128), cấu hình kênh và ánh xạ SKU (#130), outbox và DLQ (#127). **Còn chờ (Tuần 3):** webhook nhận đơn #132, vòng đời và phân bổ chi nhánh #40, phát `stock.changed` #59, đẩy tồn #133, báo trạng thái #137, màn đơn online frontend.

## AC-04 · Phân quyền đúng (EX-11)

- **Trình bày:** SV08.
- **Bước:**
  1. Đăng nhập `thungan.gm001` trên giao diện: chỉ thấy khu POS của GM-001.
  2. Mở thẳng `/operations/stock-documents`: chuyển sang trang 403.
  3. Trên terminal, dùng token của `thungan.gm001` gọi:
     - `GET /api/v1/inventory?branchId=<id GM-002>` → 403 `BRANCH_FORBIDDEN`.
     - `POST /api/v1/orders/pos` với `branchId` của GM-002 → 403 `BRANCH_FORBIDDEN`.
  4. Gọi lại với `branchId` của GM-001 → 200.
- **Hội đồng phải thấy:** 403 kèm `ErrorResponse` có `code`, `message`, `traceId`; dữ liệu GM-002 không lộ.
- **Đã có:** JWT, khóa tài khoản, kiểm tra chi nhánh (#241, PR #243), kiểm thử 12 tài khoản trên API thật (#148, PR #250), route guard frontend (#70, PR #246 đang review). **Cần kiểm tra trước demo:** EX-11 yêu cầu "ghi nhật ký truy cập bị từ chối" – xác nhận backend có ghi `audit_log` cho 403, nếu chưa thì bổ sung hoặc bỏ câu này khỏi lời thoại.

## AC-05 · Dự báo tốt hơn baseline

- **Trình bày:** SV06.
- **Bước:**
  1. Đăng nhập `owner`, mở báo cáo lần chạy dự báo gần nhất (hoặc `GET /api/v1/forecast/runs/{runId}`).
  2. Đọc bảng MAE, WAPE của mô hình và 2 baseline (naive theo tuần, trung bình trượt 28 ngày) cho tầm 7 và 30 ngày, tổng và theo nhóm bán chạy / bán chậm.
  3. Mở biểu đồ dự báo so với thực tế của 1 sản phẩm bán chạy.
- **Hội đồng phải thấy:** WAPE mô hình thấp hơn baseline tốt nhất ở cả hai tầm; tập kiểm tra là 30 ngày cuối, không trộn ngẫu nhiên.
- **Đã có:** 2 baseline và hàm MAE/WAPE (#111, PR #255). **Còn chờ:** LightGBM #112, so sánh #113, API forecast #119, màn báo cáo dự báo; **Lead chốt cách tính WAPE** (theo tổng nhu cầu cả tầm của mỗi chi nhánh × sản phẩm hay theo từng ngày) trước khi so sánh.

## AC-06 · Dashboard và cảnh báo tồn thấp

- **Trình bày:** SV05 (dashboard), SV03 (ROP, cảnh báo).
- **Bước:**
  1. Đăng nhập `owner`, mở dashboard: thẻ số, doanh thu 30 ngày, theo chi nhánh.
  2. Mở danh sách cảnh báo tồn thấp: chưa có SP-DEMO-C.
  3. Đăng nhập `thungan.gm001`, bán 3 SP-DEMO-C (tồn xuống dưới ROP).
  4. Quay lại màn cảnh báo: SP-DEMO-C xuất hiện, kèm số lượng gợi ý nhập.
  5. `thukho.gm001` lập phiếu nhập SP-DEMO-C, `quanly.gm001` duyệt.
  6. Màn cảnh báo: cảnh báo của SP-DEMO-C đã đóng.
- **Hội đồng phải thấy:** cảnh báo mở khi tồn khả dụng < ROP, đóng khi nhập vượt ROP; dashboard cập nhật doanh thu sau đơn vừa bán.
- **Còn chờ:** cảnh báo theo ROP #60, tính ROP từ dự báo #64, API forecast #119, dashboard #97, màn cảnh báo #98. Phiếu nhập và duyệt đã có (#53).

## Việc cần chốt trước tổng duyệt (#24)

1. Dữ liệu SP-DEMO-A/B/C: tạo bằng fixture riêng sau seed hay chọn sản phẩm có sẵn trong seed.
2. Ai điều khiển máy, ai nói; thứ tự 6 tiêu chí trong video (đề xuất theo thứ tự bảng trên).
3. Phương án dự phòng khi một phần chưa xong: chạy bằng API và terminal thay giao diện, nói rõ phần nào là API.
4. Cập nhật cột "Sẵn sàng" hằng tuần trong buổi review tuần (#14, Tuần 3).
