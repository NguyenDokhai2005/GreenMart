# Hợp đồng API

`openapi.yaml` là hợp đồng duy nhất giữa backend, frontend và QA. Backend code controller đúng file này; frontend sinh API client và chạy mock từ file này; QA viết test theo các mã lỗi trong file này.

## Xem và thử

| Cách | Lệnh hoặc thao tác |
| --- | --- |
| Xem tài liệu trên trình duyệt | `npx @redocly/cli preview-docs docs/api/openapi.yaml` |
| Kiểm tra cú pháp | `npx @redocly/cli lint docs/api/openapi.yaml` |
| Mock server cho frontend | `npx @stoplight/prism-cli mock docs/api/openapi.yaml -p 4010` |
| Sinh kiểu TypeScript | `npx openapi-typescript docs/api/openapi.yaml -o frontend/src/api/schema.ts` |

Có thể dán nội dung file vào https://editor.swagger.io để xem nhanh.

## Phiên bản

| Phiên bản | Hạn | Phạm vi |
| --- | --- | --- |
| v1 | 30/10 | Xác thực, chi nhánh, danh mục, sản phẩm, khách hàng, báo giá và bán tại quầy, xem tồn kho, sổ kho |
| v1.1 | 28/10 | Phần đầu của v2: `InventoryTransaction` đổi `quantity`/`balanceAfter` thành `deltaOnHand`, `onHandAfter`, `deltaReserved`, `reservedAfter` (khớp migration V1) |
| v2 | 04/11 | Đơn online (xác nhận, hoàn tất, hủy), webhook kênh, phiếu nhập/xuất/chuyển/kiểm kê, khuyến mại, quản trị người dùng |
| v3 | tuần 3 | Trả hàng, dashboard, cảnh báo tồn thấp, dự báo và gợi ý nhập, nhật ký đồng bộ |

## Đổi hợp đồng

Chỉ Lead (SV01) merge thay đổi file này. Cần thêm hoặc sửa endpoint, trường, mã lỗi: mở issue nhãn `docs`, ghi rõ endpoint và lý do, tag @NguyenDokhai2005. Không tự đổi tên trường trong code khác với hợp đồng.
