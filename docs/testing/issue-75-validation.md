# Kiểm chứng issue #75 — thanh toán POS

Ngày kiểm tra: 16/11/2026. Nhánh: `feature/75-pos-payment`, tách từ `feature/74-pos-customer` vì #73 và #74 chưa có trên `develop`.

## Đối chiếu

- Issue #75, SRS PAY-01 / UC-01 / EX-04, `docs/requirements/user-stories.md`.
- Thiết kế #174 đã được Lead duyệt và đóng ngày 15/11. Đã xem trực tiếp các frame tiền mặt (174-08), thẻ (174-09), lỗi thẻ (174-14) trên Figma. Hộp thoại triển khai dùng component `Dialog`, có ba phương thức, tổng thanh toán, tiền khách đưa, tiền thối, trạng thái gửi và lỗi.
- `docs/api/openapi.yaml`: chỉ gửi `POST /orders/pos` với các trường của `PosOrderRequest` và header UUID `Idempotency-Key`. Không sửa hợp đồng hoặc kiểu API sinh tự động.

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

Kết quả: tất cả đạt; Vitest **271/271** test, Vite build thành công. Test #75 xác nhận tiền mặt thiếu tiền khóa Hoàn tất, tiền thối đúng, lỗi mạng thử lại với **cùng key và cùng request**, 422 `PAYMENT_FAILED`/`PAYMENT_TIMEOUT` giữ giỏ và cho chọn QR với key mới, và `TOTAL_CHANGED` gọi lại báo giá mà không xóa giỏ.

## API thật và trình duyệt

Backend Docker local `http://127.0.0.1:8080` có health HTTP 200. Frontend chạy tại `http://127.0.0.1:5175`; đăng nhập tài khoản CASHIER demo ở GM-001.

1. Thêm một `COM-SUON-BI-CHA-0001` vào giỏ. API thật trả báo giá 38.000 đ, nút Thanh toán khả dụng.
2. Mở hộp thoại, nhập 50.000 đ: giao diện hiện tiền thối 12.000 đ. Đổi sang Thẻ: trường tiền mặt ẩn, nút Hoàn tất khả dụng. Đổi lại Tiền mặt: số đã nhập vẫn được giữ.
3. Gửi tiền mặt qua API thật: đơn `POS-GM001-20261116-0001` hoàn tất, phản hồi hiện tổng 38.000 đ và tiền thối 12.000 đ; giỏ trở về rỗng. Đơn thử này đã giảm tồn local của sản phẩm một đơn vị và được giữ lại để truy vết.
4. Gửi `CARD` với `simulate: FAILED` lên API thật: HTTP 422 `PAYMENT_FAILED`, không tạo đơn. Gửi `QR` với `simulate: TIMEOUT`: HTTP 422 `PAYMENT_TIMEOUT`, không tạo đơn.
5. Gửi `CARD` thành công bằng UUID mới: HTTP 201, đơn `POS-GM001-20261116-0002`. Gửi lại **cùng UUID và cùng request**: HTTP 200 trả cùng ID đơn, tồn khả dụng chỉ giảm từ 22 xuống 21. Gửi `QR` thành công: HTTP 201, đơn `POS-GM001-20261116-0003`, tồn còn 20. Hai đơn thử này được giữ trong database local để truy vết.

## Phần chưa kiểm chứng

- Chưa gây lỗi mạng thật trong trình duyệt; test giao diện đã xác nhận client gửi lại cùng UUID và cùng request. API thật đã xác nhận request lặp trả đúng đơn cũ mà không trừ tồn lần hai.
- Nhánh #75 chứa nền mã #73 và #74. Khi các PR phụ thuộc được merge vào `develop`, cần cập nhật nhánh #75 để diff PR chỉ còn phạm vi thanh toán.
