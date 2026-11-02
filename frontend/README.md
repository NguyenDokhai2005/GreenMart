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

## API client và mock

`src/api/schema.ts` được sinh từ hợp đồng `../docs/api/openapi.yaml`; không sửa
file này trực tiếp. Khi hợp đồng thay đổi, chạy:

```sh
npm run generate:api-types
npm run typecheck
```

Chạy Prism và Vite ở hai terminal riêng:

```sh
npm run mock:api
npm run dev:mock
```

Prism đọc trực tiếp OpenAPI contract trên cổng `4010` và phục vụ routes từ `/`;
Vite bỏ prefix `/api/v1` khi chuyển tiếp `/api/v1/*` tới mock ở chế độ
`dev:mock`. Chạy `npm run dev` vẫn chuyển tiếp tới backend tại
`http://localhost:8080` mà không đổi path. Có thể đặt
`GREENMART_API_PROXY_TARGET` để ghi đè đích proxy; `VITE_API_BASE_URL` để ghi đè
base URL của client.

Prism dùng ví dụ tĩnh trong OpenAPI, đặc biệt quote mẫu không được tính theo
giỏ hàng được gửi. Vì vậy mock chỉ kiểm tra transport/request-response shape,
không chứng minh giá, tồn kho, thanh toán hoặc tích hợp backend thật chính xác.
Prism 5.16.0 cũng ghi `sl-violations` cho `lines[1].promotion: null` trong quote
mẫu dù schema khai báo `nullable: true`; HTTP response vẫn là 200. Contract không
được sửa trong task này, cần Lead xác nhận cách xử lý khác biệt validator này.

Client lưu cả access token và refresh token trong `sessionStorage` để phiên
không tồn tại sau khi đóng tab. JavaScript chạy cùng origin có thể đọc chúng;
XSS vì thế có thể làm lộ token. Cần giảm thiểu XSS và cân nhắc cookie `HttpOnly`,
`Secure`, `SameSite` khi backend hỗ trợ.

## Kiểm tra

```sh
npm run generate:api-types
npm run typecheck
npm run lint
npm run format:check
npm test -- --run
npm run build
```

Test API trong `tests/api/` không yêu cầu backend đang chạy.

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
| `tests/api`              | Unit test API client và luồng gọi API bán hàng                    |            |

`../docs/api/openapi.yaml` là hợp đồng API dùng chung. Chỉ thay đổi hợp đồng
thông qua quy trình được mô tả trong `../docs/api/README.md`.
