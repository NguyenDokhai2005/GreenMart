# Frontend GreenMart

Ứng dụng React + TypeScript + Vite cho hai khu vực POS (SV04) và Operations (SV05).

## Yêu cầu

- Node.js 22
- npm đi kèm Node.js

## Cài đặt và chạy local

Từ thư mục `frontend`:

```sh
npm ci
npm run dev
```

Mở URL Vite in ra trong terminal. Khi backend chạy tại `http://localhost:8080`,
request có đường dẫn `/api/*` được Vite chuyển tiếp tới backend, giữ nguyên
đường dẫn theo hợp đồng trong `../docs/api/openapi.yaml`.

Backend hiện chưa có đầy đủ API nghiệp vụ; các endpoint chưa được triển khai có
thể trả 404. Không dùng dữ liệu demo để kết luận tích hợp API đã hoàn tất.

## Kiểm tra

```sh
npm run lint
npm run format:check
npm run build
```

CI chạy các kiểm tra trên cùng với `npm test --if-present`. Hiện scaffold chưa
có test runner hoặc test giao diện.

## Cấu trúc

| Thư mục                  | Nội dung                                                          | Phụ trách  |
| ------------------------ | ----------------------------------------------------------------- | ---------- |
| `src/api`                | Kiểu dữ liệu/client theo `../docs/api/openapi.yaml`; mock khi cần | SV04       |
| `src/components`         | Component dùng chung: bảng, form, hộp thoại, thông báo            | SV05       |
| `src/features/auth`      | Đăng nhập, lưu token, chặn route theo vai trò                     | SV04       |
| `src/features/pos`       | Màn bán hàng, hóa đơn, trả hàng                                   | SV04       |
| `src/features/orders`    | Danh sách và chi tiết đơn                                         | SV04       |
| `src/features/catalog`   | Sản phẩm, khách hàng, khuyến mại                                  | SV04       |
| `src/features/inventory` | Tồn kho, phiếu nhập/xuất/chuyển/kiểm kê                           | SV05       |
| `src/features/dashboard` | Dashboard, cảnh báo, dự báo                                       | SV05       |
| `src/layouts`            | Layout khu POS và khu Operations                                  | SV04, SV05 |
| `src/hooks`, `src/utils` | Hook và hàm tiện ích dùng chung                                   |            |
| `tests`                  | Unit test giao diện                                               |            |

`../docs/api/openapi.yaml` là hợp đồng API dùng chung. Chỉ thay đổi hợp đồng
thông qua quy trình được mô tả trong `../docs/api/README.md`.
