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
npm run typecheck
npm test -- --run
npm run build
```

CI chạy các kiểm tra trên cùng với `npm test --if-present`. Vitest chạy test API trong `tests/api/`; chưa có test giao diện.

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

## API client và mock (#69)

Chạy hai terminal trong `frontend/`:

```sh
npm run mock:api
npm run dev:mock
```

Prism đọc trực tiếp `../docs/api/openapi.yaml`, chạy ở `127.0.0.1:4010`.
Vite chuyển `/api/v1/*` tới Prism và bỏ prefix `/api/v1`. Với API thật,
`npm run dev` giữ prefix và chuyển tới `localhost:8080`. Có thể đặt biến
`GREENMART_API_PROXY_TARGET` để chọn backend phát triển khác; đây là cấu hình
proxy phía Vite, không chứa token. Production cần reverse proxy `/api/v1`.

Sinh lại type sau khi Lead cập nhật contract:

```sh
npm run generate:api-types
```

Không sửa tay `src/api/schema.ts`. Generator và Prism được ghim phiên bản qua
npx, cần mạng ở lần chạy đầu. Generator chạy riêng để tránh peer dependency
TypeScript 5 của openapi-typescript 7.13.0 xung đột TypeScript 6 của ứng dụng.
Types chỉ kiểm tra lúc biên dịch, không thay thế validation response runtime.

`src/api/client.ts` cung cấp `request(path, method, options)` với kiểu tham số,
body và response suy ra từ OpenAPI. Ví dụ:

```ts
import { request } from './api/client';

const orders = await request('/orders', 'get', {
  query: { status: ['RESERVED', 'CONFIRMED'], page: 0, size: 20 },
});
```

Các hàm `login`, `getCurrentUser`, `logout`, `searchProducts`, `lookupProduct`,
`listBranches`, `quoteOrder`, `completePosOrder`, `listOrders`, `getOrder`
phục vụ Sales. Dùng `request('/orders/quote', 'post', { body })` khi cần truyền
đầy đủ `customerId` hoặc `manualDiscount` theo contract.
Client lưu phiên trong sessionStorage như bản đang làm dở, không log token.
Các request cùng gặp 401 chia sẻ một refresh; chỉ thử lại một lần. Logout xóa
phiên local và không cho refresh đến muộn đăng nhập lại. Lỗi mạng không tự retry.

Client giữ `status`, `code`, `message`, `details`, `traceId` của lỗi backend.
Chưa có enum ErrorCode trong contract: chưa triển khai phân nhánh mã nghiệp vụ.
`SessionExpiredError` là lỗi phiên phía client; không phát sinh code backend giả
`SESSION_EXPIRED`. Nơi tích hợp dùng `instanceof SessionExpiredError`.

Mỗi lần hoàn tất mới, bên gọi tạo UUID cho `Idempotency-Key`. Gửi lại giao dịch
sau lỗi mạng phải giữ cùng key và body. Không tạo đơn lại với báo giá thay đổi
khi người dùng chưa xác nhận. Backend quyết định giá, khuyến mại, quyền và tồn;
client không mô phỏng các quy tắc đó.

### Giới hạn và nghiệm thu

Prism phục vụ kiểm tra request/response theo schema, không phải backend có
trạng thái: không chứng minh xoay token, trừ tồn hay chống tạo đơn trùng.
Prism mặc định có thể chọn response 200 của POST /orders/pos; dùng header
`Prefer: code=201` để kiểm tra nhánh tạo mới và
`Prefer: code=409, example=insufficientStock` để kiểm tra lỗi mẫu. Các header
Prefer này chỉ dùng khi thử mock, không đưa vào client production.
Test dùng fetch giả lập chỉ kiểm tra hành vi client. Chưa xác minh API thật.
Không dùng kết quả mock để kết luận hoàn thành tiêu chí tích hợp của Issue #69.

Đề nghị SV04 mở Issue `docs`, tag Lead: bổ sung enum ErrorCode vào contract và
làm rõ tiền điều kiện mở ca (tài liệu nghiệp vụ có nhưng API chưa hỗ trợ).
Không thêm endpoint `/promotions` hoặc ca làm việc khi contract chưa có.

Khi backend sẵn sàng: dùng tài khoản thử được cấp, gọi login → auth/me → products
→ orders/quote → orders/pos → orders/{orderId}; xác nhận POST lần đầu 201,
gửi cùng key/body trả 200 và cùng id, sai chi nhánh trả 403. Ghi kết quả đã che
token và thông tin nhạy cảm. Việc bán thử cần môi trường dữ liệu thử.
