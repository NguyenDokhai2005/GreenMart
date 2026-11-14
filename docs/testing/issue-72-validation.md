# Issue #72 — Sản phẩm và khách hàng

Ngày kiểm chứng: 14/11/2026. PR chính: [#264](https://github.com/NguyenDokhai2005/GreenMart/pull/264), nhánh `feature/72-products-customers`.

## Phạm vi đã triển khai

- Thiết kế Figma #172 đã được Lead duyệt. Màn sản phẩm, khách hàng và biểu mẫu dùng component và token của frontend.
- CASHIER vào `/pos/products` và `/pos/customers` từ menu POS #71. OWNER, BRANCH_MANAGER và STOREKEEPER vào `/operations/products` và `/operations/customers` từ menu Operations #88. Không còn khu `/catalog/*` riêng.
- Tìm sản phẩm theo tên, SKU hoặc mã vạch bằng `q`; lọc danh mục/trạng thái và phân trang phía server. CASHIER không thấy giá vốn hoặc nút sửa sản phẩm dù API trả `costPrice`.
- OWNER thêm/sửa sản phẩm và giá bán; sau lưu, danh sách tải lại giá từ server. Quyền thêm/sửa khách hàng tuân theo `catalogPolicy.ts`.
- Khách hàng tìm theo tên/một phần SĐT bằng `q`, hoặc tra cứu SĐT chính xác bằng `phone`. Chỉ gọi API với tham số trong `docs/api/openapi.yaml`; không sửa generated API types.
- Trạng thái tải, rỗng, lỗi trong khối, lỗi form và lỗi trang dùng các shared UI của #228. Lệnh ghi quá hạn không được tự phát lại.

## Kiểm chứng

| Kiểm tra                                                                     | Kết quả                                                                                                                                |
| ---------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `npm run typecheck`, `npm run lint`, `npm run format:check`, `npm run build` | PASS                                                                                                                                   |
| `npm test -- --run`                                                          | 251/251 PASS trên 27 file; gồm test click menu Operations cho OWNER, BRANCH_MANAGER, STOREKEEPER và menu POS cho CASHIER               |
| `npm run test:auth-integration` với backend Docker và seed demo local        | 11/11 PASS trên 2 file; OWNER chọn chi nhánh, sửa giá và khôi phục giá gốc trong `finally`; danh sách khách hàng không lọc trả dữ liệu |
| CI của PR #264 tại commit `fb09d3b`                                          | 7/7 job PASS: backend, frontend, forecast, mock connector, Docker build, gitleaks, QA POS Newman                                       |
| `git diff origin/develop --check` khi chuẩn bị bản sửa route                 | PASS                                                                                                                                   |

Backend `GET /api/v1/customers?page=0&size=3` từng trả HTTP 500 với image cũ. Issue #267 đã sửa truy vấn `q=null` trên `develop`; lần chạy integration 14/11 với backend mới đạt 11/11. Không thêm `q` giả trong frontend.

## Đối chiếu giao diện

Đã quan sát các frame #172 trong [Figma chung](https://www.figma.com/design/m5cwuanAuUjX6QaTzrRCvP/frontend), gồm danh sách sản phẩm OWNER (`529:1500`), khách hàng OWNER (`529:2911`) và các biểu mẫu. Đã kiểm tra bố cục danh sách ở viewport Chrome 1440 × 1080 bằng Prism mock. Prism dùng dữ liệu tĩnh; ảnh này không chứng minh dữ liệu OWNER/CASHIER hoặc thao tác ghi với backend thật.

Figma MCP hết hạn mức đọc chi tiết, nên chưa xác nhận token từng node hoặc mức khớp pixel. Lead cần đối chiếu lại viewport 1440 px và 360 px, gồm trạng thái tải, rỗng, lỗi, khi review PR.

## Trạng thái bàn giao

PR #264 đã Ready for review, không có xung đột và có `Closes #72`. Issue vẫn mở cho đến khi Lead duyệt, PR được merge vào `develop` và bằng chứng nghiệm thu được chấp nhận. PR trùng #345 đã đóng để chỉ còn một nguồn review cho issue #72.
