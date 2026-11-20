# Kiểm chứng issue #77 — hóa đơn POS qua API thật

Ngày kiểm tra lại: 19/11/2026. Nhánh `codex/77-real-api-receipt` dựa trên `develop` tại commit `27117fc` ngày 19/11. Các issue phụ thuộc #73, #74 và #75 đã merge.

## Phạm vi

- Thiết kế #174 đã được Lead comment **Đã duyệt** và đóng ngày 15/11. Đã mở trực tiếp frame 174-12 (`641:6294`) trong Chrome ngày 19/11: hóa đơn nằm trong hộp thoại **Thanh toán thành công**, có dòng hàng, tổng giảm, tiền thừa, nút **In hóa đơn** và **Đơn hàng mới**. Màn thành công đã được sửa theo cấu trúc này.
- Hộp thoại thành công dùng ngay `Order` từ `POST /orders/pos`. Màn Hóa đơn trong menu dùng `GET /orders?branchId=...&channel=POS` và `GET /orders/{orderId}` để tra cứu/in lại. Các route và trường đều thuộc `docs/api/openapi.yaml`; không sửa hợp đồng hay API types sinh tự động.
- Hóa đơn hiển thị mã đơn, chi nhánh, thu ngân, khách (nếu có), dòng hàng, giảm theo dòng/đơn, tổng, tiền khách đưa, tiền thối. Nút in dùng stylesheet `@media print`.

## Kiểm tra tự động

Trong `frontend/`:

```sh
npm run typecheck
npm run lint
npm run format:check
npm test -- --run
npm run build
git diff --check
```

Kết quả kiểm tra lại trên nền `develop` mới: typecheck, lint, format, build và `git diff --check` đạt; Vitest **298/298** test. Test #77 kiểm tra hóa đơn ngay sau bán, giảm theo dòng/đơn, in, tải danh sách POS theo chi nhánh, phân trang, mở chi tiết từ API, truy cập trực tiếp và Thử lại sau lỗi 500.

## API thật và giới hạn

- Backend demo local `http://127.0.0.1:8080`: đăng nhập CASHIER HTTP 200; tìm sản phẩm, báo giá và tạo đơn tiền mặt bằng API thật thành công. Một đơn thử `id=10204` trả HTTP 201, trạng thái `COMPLETED`, đủ các trường `code`, `branch`, `createdBy`, `items`, `subtotal`, `orderDiscount`, `discount`, `total`, `payments`, tiền thừa 10.000 đ. Đơn thử làm giảm tồn local một sản phẩm một đơn vị và được giữ lại để truy vết.
- Kiểm tra trực tiếp trong Chrome với Vite không bật mock: bán thêm một sản phẩm ở GM-001, `POST /orders/pos` thành công; hộp thoại hóa đơn hiển thị mã `POS-GM001-20261119-0002`, chi nhánh, thu ngân, dòng hàng, tổng 38.000 đ, khách đưa 50.000 đ, tiền thừa 12.000 đ, và hai nút theo thiết kế. Đơn thử này cũng được giữ trong database demo local.
- Backend local cổng 8080 vẫn trả HTTP **404** cho `GET /orders`; image đang chạy chưa khớp code. Để kiểm tra mà không thay dịch vụ này, đã tạo bản sao database demo và chạy image backend build từ nhánh #77 ở cổng 8081, cho Flyway cập nhật **chỉ bản sao**.
- Trên backend cổng 8081: CASHIER đăng nhập HTTP 200, `GET /orders?branchId=1&channel=POS&page=0&size=20` HTTP 200 với 20 dòng, `GET /orders/10204` HTTP 200 và đủ trường hóa đơn. Vite cổng 5178 proxy sang backend này, không bật mock; Chrome mở trực tiếp `/pos/receipts?orderId=10204` hiện chi nhánh, thu ngân, sản phẩm, tổng 38.000 ₫, khách đưa 48.000 ₫ và tiền thối 10.000 ₫. Bấm **Danh sách hóa đơn** hiện danh sách 20 đơn POS thật, gồm hai đơn vừa bán.
- Bản sao database và container thử nghiệm chỉ phục vụ kiểm chứng; backend cổng 8080 và dữ liệu gốc không bị migration. Khi tích hợp chính thức, môi trường chạy backend cần được cập nhật lên code hiện tại để menu Hóa đơn hoạt động.
