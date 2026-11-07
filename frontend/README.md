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

## Trạng thái giao diện (Đang tải / Rỗng / Lỗi)

Nguồn quy ước: issue #228, thiết kế: Figma #227. Dùng cho layout (#71, #88) và mọi màn hình.

### Chọn trạng thái nào?

| Tình huống                                               | Dùng                                                               |
| -------------------------------------------------------- | ------------------------------------------------------------------ |
| Đổi tab, lọc, phân trang, mở một khối                    | `<AsyncBlock>` (hiện `Skeleton` sau 300 ms)                        |
| Chuyển trang trong cùng khu POS / Operations             | `<PageLoader />` trong vùng nội dung, **giữ header + menu trái**   |
| Mở app, vừa đăng nhập, đổi khu, đổi chi nhánh, đăng xuất | `<FullScreenLoading reason="..." />` — **chỉ 5 trường hợp này**    |
| POS quét mã / thêm hàng                                  | Không khóa màn hình, không loading toàn trang, giỏ hàng giữ nguyên |

### Một khối dữ liệu

```tsx
import { AsyncBlock } from '../components/ui';

<AsyncBlock
  isLoading={query.isLoading}
  error={query.error} // ApiErrorLike: { status, body: ErrorResponse, isNetworkError }
  isEmpty={data?.length === 0}
  onRetry={query.refetch} // gọi lại ĐÚNG API của khối
  empty={{
    message: 'Chưa có phiếu nhập nào',
    actionLabel: canCreate ? 'Tạo phiếu nhập' : undefined, // chỉ truyền khi có quyền
    onAction: openCreate,
    filtered: hasActiveFilters, // true -> "Không có kết quả phù hợp" + Xóa bộ lọc
    onClearFilters: resetFilters,
  }}
>
  <Table data={data} />
</AsyncBlock>;
```

`AsyncBlock` đảm bảo: không hiện loading nếu API trả trong 300 ms; "rỗng" không bao giờ hiện lúc đang tải; quá 15 giây chuyển sang lỗi có Thử lại.

### Ánh xạ lỗi

```ts
import { mapErrorToState } from '../lib/errors';

const s = mapErrorToState(error, 'block'); // hoặc 'page'
switch (s.kind) {
  case 'form':
    setFieldErrors(s.fieldErrors);
    setFormError(s.message);
    break; // lỗi theo ô / lỗi chung của form
  case 'dialog':
    /* Dialog hoặc Toast với s.message */ break; // 409/422: KHÔNG reset form
  case 'block':
    /* ErrorState + Thử lại */ break;
  case 'page':
    /* hiện trang đặc biệt s.page */ break;
}
```

| Lỗi                                                                      | scope `block`                                                 | scope `page`               |
| ------------------------------------------------------------------------ | ------------------------------------------------------------- | -------------------------- |
| 400 `VALIDATION_ERROR`                                                   | form (lỗi theo từng ô từ `details`)                           | form                       |
| 409 / 422                                                                | dialog (`details` giữ nguyên, ví dụ `{ lines }`, `{ quote }`) | dialog                     |
| 401 `UNAUTHORIZED` / `INVALID_REFRESH_TOKEN` (refresh token đã thất bại) | session-expired                                               | session-expired            |
| 401 mã khác, ví dụ `INVALID_CREDENTIALS` (đăng nhập sai)                 | form: `message` + `details` (ví dụ `{ remainingAttempts }`)   | form                       |
| 403 / `BRANCH_FORBIDDEN`                                                 | block                                                         | forbidden                  |
| 404                                                                      | block                                                         | not-found                  |
| 5xx                                                                      | block (Thử lại)                                               | server-error (+ `traceId`) |
| Không có phản hồi                                                        | block                                                         | offline                    |
| Quá 15 giây                                                              | block (Thử lại)                                               | block (Thử lại)            |

Lưu ý:

- `ErrorResponse.details` có kiểu `unknown` vì mỗi mã lỗi trả một dạng khác nhau. Chỉ `VALIDATION_ERROR` được đọc thành danh sách lỗi theo ô (và chỉ khi là mảng); với mã khác, nơi gọi tự ép kiểu từ `state.details`.
- Màn đăng nhập và đổi mật khẩu có thể gọi `mapErrorToState`: sai mật khẩu (401 `INVALID_CREDENTIALS`) trả `form`, không bị đưa sang trang "Phiên hết hạn".

### Trang đặc biệt (`src/pages`)

`NotFoundPage`, `ForbiddenPage`, `ServerErrorPage`, `OfflinePage`, `SessionExpiredPage` render **bên trong layout** (header + menu còn dùng được). Route bắt-mọi-đường-dẫn → `NotFoundPage`; `ErrorBoundary` → `ServerErrorPage`.

### Quay lại trang cũ sau khi hết phiên (thống nhất với #246)

Dùng `location.state.from`, như `LoginPage` của #246 đang đọc. Không dùng `?returnTo=` (tránh lộ đường dẫn lên URL và không cần kiểm tra open redirect).

- `SessionExpiredPage` chỉ nhận `onLogin`; không tự tạo URL.
- `AuthBoundary` (#246) điều hướng tới trang đăng nhập kèm `state: { from: location }`; `LoginPage` đọc `state.from` để về đúng trang cũ.

```tsx
<SessionExpiredPage onLogin={() => navigate('/login', { state: { from: location } })} />
```

### Áp dụng ở layout (#71, #88)

- Layout không bọc `<FullScreenLoading>` khi chuyển trang; dùng `<Suspense fallback={<PageLoader />}>` chỉ quanh vùng nội dung.
- Ví dụ mọi trạng thái xem mục 11–16 của `ComponentShowcase`.
