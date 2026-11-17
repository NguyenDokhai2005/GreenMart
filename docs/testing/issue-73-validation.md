# Kiểm chứng issue #73 — màn bán hàng POS

- Thiết kế: [#174](https://github.com/NguyenDokhai2005/GreenMart/issues/174) đã được Lead duyệt và đóng ngày 15/11/2026.
- Phạm vi: quét/tìm sản phẩm, giỏ hàng, tồn khả dụng, gọi quote và trạng thái đang tải/rỗng/lỗi. Gắn khách và hoàn tất thanh toán thuộc #74 và #75.
- Nhánh kiểm chứng: `feature/73-pos-cart`, trên `develop` tại `a5f5b7d`.

## Kết quả

| Kiểm tra | Kết quả |
| --- | --- |
| `npm run typecheck` | PASS |
| `npm run lint` | PASS |
| `npm run format:check` | PASS |
| `npm test -- --run` | PASS — 28 file, 260 test |
| `npm run build` | PASS |
| `git diff HEAD --check` | PASS |
| Chromium 157, 1440×872, frontend + API thật | PASS — đăng nhập CASHIER, thêm hàng, quét lại, quote, thiếu tồn khóa Thanh toán; không có lỗi trang |
| Chromium 157, 360 px | PASS — không tràn ngang, giỏ và cảnh báo thiếu tồn hiển thị |

Test POS kiểm tra quét lặp tăng số lượng trên cùng dòng, tìm theo tên rồi thêm bằng SKU, vượt tồn khóa Thanh toán, quote lại sau thay đổi giỏ, hiển thị giảm giá dòng/đơn, trạng thái rỗng sau API và giữ giỏ khi quote lỗi.

Backend local tại `127.0.0.1:8080` trả HTTP 200 cho đăng nhập tài khoản demo được ghi trong OpenAPI, `GET /products`, `GET /products/lookup` và `POST /orders/quote`. Response lookup/quote có đủ trường giao diện sử dụng. Không in mật khẩu hoặc token trong kết quả kiểm tra.

Ảnh từ frontend chạy với backend thật: [giỏ rỗng 1440 px](images/issue-73/empty-1440.png), [giỏ có hàng 1440 px](images/issue-73/cart-1440.png), [thiếu tồn 1440 px](images/issue-73/shortage-1440.png), [thiếu tồn 360 px](images/issue-73/shortage-360.png). Ở 1440×872, nút Thanh toán nằm trong khung nhìn (y = 645 px); ở 360 px, chiều rộng tài liệu bằng 360 px.

## Giới hạn

- Figma MCP báo hết hạn lượt gọi; đã xem bố cục trong trình duyệt và đối chiếu nội dung với comment duyệt #174, nhưng chưa có so sánh ảnh 1440 px trực tiếp với bản render Figma.
- #73 dừng ở nút Thanh toán; luồng hoàn tất đơn và hóa đơn thuộc #75/#77, chưa được kiểm tra trong bằng chứng này.
- [PR #371](https://github.com/NguyenDokhai2005/GreenMart/pull/371) đã mở vào `develop`. CI của commit triển khai `549558a` qua 7/7 check, gồm frontend, backend, Docker build, QA POS Newman và Gitleaks; còn chờ review và merge.
