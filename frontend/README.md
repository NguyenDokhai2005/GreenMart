# Frontend – React

React · TypeScript · Vite. Một ứng dụng, hai khu vực: POS (SV04) và Operations (SV05).

| Thư mục | Nội dung | Phụ trách |
| --- | --- | --- |
| `src/api` | API client sinh từ `docs/api/openapi.yaml`, mock khi backend chưa xong | SV04 |
| `src/components` | Component dùng chung: bảng, form, hộp thoại, thông báo | SV05 |
| `src/features/auth` | Đăng nhập, lưu token, chặn route theo vai trò | SV04 |
| `src/features/pos` | Màn bán hàng, hóa đơn, trả hàng | SV04 |
| `src/features/orders` | Danh sách và chi tiết đơn | SV04 |
| `src/features/catalog` | Sản phẩm, khách hàng, khuyến mại | SV04 |
| `src/features/inventory` | Tồn kho, phiếu nhập/xuất/chuyển/kiểm kê | SV05 |
| `src/features/dashboard` | Dashboard, cảnh báo, dự báo | SV05 |
| `src/layouts` | Layout khu POS và khu Operations | SV04, SV05 |
| `src/hooks`, `src/utils` | Hook và hàm tiện ích dùng chung | |
| `tests` | Unit test giao diện | |

Khung dự án (package.json, vite.config.ts) do SV04 khởi tạo trong tuần 1.
