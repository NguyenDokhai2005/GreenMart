# Kiểm chứng issue #74 — gắn và tạo nhanh khách POS

Ngày kiểm tra: 16/11/2026; cập nhật 17/11/2026 sau khi PR #371 được merge. Nhánh: `feature/74-pos-customer`, đã đồng bộ `develop`.

## Đối chiếu

- Issue #74, CUS-01 và UC-01 bước 2 trong `docs/requirements/SRS.md`.
- Figma #174 đã được Lead duyệt và đóng. Đã quan sát trực tiếp frame 174-17..22 và 174-25 trong file thiết kế: đang tìm, tìm thấy, không tìm thấy, đã gắn, tạo nhanh, đã tạo và gắn, trùng SĐT.
- `docs/api/openapi.yaml`: `GET /customers?phone=...`, `POST /customers` với `name` và `phone`, 409 `DUPLICATE_PHONE`, `POST /orders/quote` với `customerId` tùy chọn. Không sửa hợp đồng hoặc `frontend/src/api/schema.ts`.

## Lệnh kiểm tra

Chạy trong `frontend/` sau `npm ci`:

```sh
npm run typecheck
npm run lint
npm run format:check
npm test -- --run
npm run build
```

Kết quả: tất cả đạt; Vitest **266/266** test, build Vite thành công. `git diff --check` không báo lỗi.

## API thật và trình duyệt

Vite local `http://127.0.0.1:5174` nối backend Docker local `http://127.0.0.1:8080` (health 200), tài khoản CASHIER demo tại GM-001:

1. Tra `0900000496` trên màn `/pos`: API trả khách Hoàng Quang Khoa; bấm **Gắn khách** thì hiện khối **Khách hàng đã gắn**. Giỏ một sản phẩm được báo giá lại và nút Thanh toán khả dụng. Bấm **Đổi khách** trở về trạng thái khách lẻ, giỏ vẫn còn.
2. Tra SĐT chưa có `0999000074`: hiện **Không tìm thấy khách hàng với SĐT này**, hai nút **Tạo nhanh khách** và **Khách lẻ**.
3. Trong form tạo nhanh, đổi SĐT sang `0900000496` và gửi: backend trả 409 `DUPLICATE_PHONE`; form giữ tên và SĐT, hiện **Sửa SĐT** / **Tìm khách đã có**. Bấm tìm lại trả về khách hiện có, không tạo thêm bản ghi.
4. Tạo khách giả `POS kiểm thử issue 74` với SĐT `0999000074`: backend tạo thành công, màn POS gắn ngay khách mới và báo giá lại. Sau kiểm tra đã xóa **đúng một** bản ghi giả này khỏi database local; truy vấn xác nhận còn **0** bản ghi với SĐT đó. Không tạo đơn và không trừ tồn.
5. Quan sát bố cục ở 1440×872; khối khách và hộp thoại nằm trong vùng POS và tương ứng cấu trúc Figma.

## Trạng thái tích hợp

- PR #371 (#73) đã merge vào `develop`; diff nhánh #74 so với `develop` hiện chỉ gồm 7 file của #74.
- Gửi `customerId` trong `POST /orders/pos` là phần thanh toán của #75 (PR #376 còn mở); hiện #74 đã lưu khách trong trạng thái màn POS và đưa `customerId` vào yêu cầu báo giá. Cần kiểm chứng khách trên hóa đơn sau khi #75 được tích hợp.
