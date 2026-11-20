# Chương giao diện bán hàng — SV04

**Mốc tài liệu:** 20/11/2026. **Phạm vi:** giao diện POS, tra cứu đơn và xử lý đơn online của GreenMart DT03. Chương này mô tả code và PR đã có tại mốc ghi trên; trạng thái merge được ghi riêng ở cuối.

## 1. Yêu cầu và nguồn thiết kế

- SRS: ORD-01 (bán tại quầy), ORD-03 (vòng đời đơn online), ORD-06 (tra cứu đơn), ORD-07 (hóa đơn), BR-19 (quyền giảm giá), NFR-12 (dùng được từ 360 px). Nguồn: [`docs/requirements/SRS.md`](../requirements/SRS.md).
- Hợp đồng dữ liệu, quyền ghi và lỗi API: [`docs/api/openapi.yaml`](../api/openapi.yaml). Kiểu TypeScript trong `frontend/src/api/schema.ts` được sinh từ hợp đồng, không sửa tay.
- Thiết kế POS #174 và danh sách/chi tiết đơn #176 đã được Lead duyệt trong file Figma chung. Các issue thiết kế này ghi rõ frame 1440 px; tiêu chí 360 px của #83 được đối chiếu riêng theo NFR-12.

## 2. Tổ chức giao diện

`AppRoutes` đặt ranh giới đăng nhập, chọn chi nhánh và quyền theo vai trò trước khi vào `PosLayout` hoặc `OperationsLayout`. POS dành cho `CASHIER` và `BRANCH_MANAGER`. Khu Operations dành cho `OWNER`, `BRANCH_MANAGER` và `STOREKEEPER`. Menu được lọc theo vai trò; API vẫn là nơi quyết định quyền cuối cùng. Các màn dùng chung `Button`, `Input`, `Select`, `Dialog`, `Table`, trạng thái tải/rỗng/lỗi và thông báo từ `frontend/src/components/ui`.

| Khu vực | Thành phần chính | Vai trò sử dụng |
| --- | --- | --- |
| POS | `PosLayout`, `PosSalePage`, `PosCustomerPanel`, `PosCheckoutDialog`, `PosReceiptsPage` | Thu ngân, quản lý chi nhánh |
| Đơn hàng | `OrdersPage`, `OnlineOrderDetailPage` | Chủ sở hữu, quản lý chi nhánh, thủ kho |
| Đăng nhập và phạm vi | `AuthProvider`, `BranchBoundary`, `OperationsScopeProvider` | Theo quyền phiên và chi nhánh được giao |

## 3. Bán hàng tại quầy

Thu ngân quét mã vạch hoặc tìm theo tên/SKU. Danh sách gợi ý gọi `GET /products`; tra cứu chính xác gọi `GET /products/lookup` với `code` và `branchId`. Giỏ hàng giữ sản phẩm và số lượng; khi giỏ đổi, giao diện gọi `POST /orders/quote` để nhận tồn khả dụng, khuyến mại theo dòng/đơn và tổng tiền. Nút thanh toán chỉ mở khi báo giá hợp lệ, không thiếu tồn và số lượng hợp lệ.

Khách hàng có thể được tìm/gắn bằng số điện thoại hoặc tạo nhanh theo API khách hàng. Hộp thanh toán hỗ trợ tiền mặt, thẻ và QR. Với tiền mặt, số tiền khách đưa phải là số nguyên không nhỏ hơn tổng báo giá; giao diện tính tiền thối để người dùng kiểm tra. `POST /orders/pos` gửi `expectedTotal`, mặt hàng, chi nhánh, khách đã gắn nếu có và phương thức thanh toán. Mỗi lần thử dùng `Idempotency-Key`; khi kết quả mạng không chắc chắn, giao diện giữ nguyên key và body để tránh tạo đơn trùng. Lỗi giá/tồn thay đổi yêu cầu báo giá lại, còn lỗi thanh toán giữ giỏ để xử lý tiếp.

Sau phản hồi thành công, hộp thoại hiển thị hóa đơn từ dữ liệu đơn đã tạo: mã đơn, chi nhánh, thu ngân, khách hàng, từng dòng, các khoản giảm, tổng thanh toán và tiền thối. Người dùng có thể in hoặc bắt đầu đơn mới. Hóa đơn cũ có trang tra cứu riêng. Các thao tác POS đã có phím tắt F2 (tìm sản phẩm), F4 (đưa focus tới thanh toán), Esc (xóa tìm kiếm) và phím chuyển mục trong layout.

## 4. Tra cứu và xử lý đơn online

Danh sách đơn gửi `GET /orders` với bộ lọc kênh, chi nhánh, nhiều trạng thái, khoảng ngày, khách hàng/từ khóa và phân trang. Bộ lọc được lưu vào URL để tải lại hoặc chia sẻ. Màn có trạng thái đang tải, danh sách rỗng, rỗng do bộ lọc và lỗi với thao tác thử lại. Trên màn hẹp, mỗi đơn được trình bày thành thẻ có nhãn trường để không phải cuộn ngang bảng.

Trang chi tiết gọi `GET /orders/{orderId}`. Nút **Hoàn tất** chỉ xuất hiện cho `BRANCH_MANAGER` hoặc `STOREKEEPER` khi đơn online ở trạng thái `CONFIRMED`; nút **Hủy** chỉ xuất hiện cho `BRANCH_MANAGER` khi đơn ở `RESERVED` hoặc `CONFIRMED`. Giao diện dùng đúng `POST /orders/{orderId}/complete` và `POST /orders/{orderId}/cancel`. Lý do hủy lấy từ enum `CancelOrderRequest`; lý do `OTHER` bắt buộc ghi chú. Khi đổi ID đơn hoặc server báo 409 vì trạng thái đã thay đổi, hành động cũ không được gửi lại cho đơn khác hoặc trạng thái không còn hợp lệ.

Việc tạo/xác nhận đơn online từ kênh và tự hết hạn là trách nhiệm backend/tích hợp kênh. Giao diện chỉ trình bày trạng thái và cho phép hai thao tác nêu trong hợp đồng; không tự tạo endpoint xác nhận.

## 5. Khả dụng và kiểm tra

POS và danh sách đơn đã được thử trên Chrome ở viewport 360 × 780 px bằng Prism mock theo OpenAPI, sau đó thử lại với backend QA thật. `document.documentElement.scrollWidth` bằng 360 px trên cả hai route. Một ca POS dùng bàn phím đã đi từ F2, nhập barcode, Enter thêm hàng, F4, Enter mở thanh toán, Tab/Enter chọn thẻ và hoàn tất; backend QA tạo hóa đơn `POS-GM001-20261120-0001`, độ rộng tài liệu vẫn là 360 px. Danh sách đơn 360 px cũng lấy được đơn WEB thật. Chưa đo tốc độ ba món dưới 30 giây của NFR-12.

Lệnh kiểm tra frontend: `npm run typecheck`, `npm run lint`, `npm run format:check`, `npm test -- --run`, `npm run build`. CI cũng chạy kiểm tra sinh kiểu API. Với backend QA cô lập từ commit `develop` 4e45b44 và seed SV06, Chrome 1440 px đã hiển thị 1.766 đơn WEB từ `GET /orders` và chi tiết đơn từ `GET /orders/{orderId}`. Hai đơn WEB `CONFIRMED` được tạo riêng trong QA: thao tác Hoàn tất và Hủy qua giao diện lần lượt trả trạng thái `COMPLETED` và `CANCELLED`. Đối chiếu ảnh chính xác với frame Figma 1440 px và đo thời gian ba món theo NFR-12 vẫn cần trước nghiệm thu cuối.

## 6. Trạng thái bàn giao tại mốc tài liệu

| Issue | Bàn giao | Trạng thái tại 20/11/2026 |
| --- | --- | --- |
| #78 | Hiển thị khuyến mại POS, PR #411 | Đã thử báo giá ITEM/ORDER với API thật và ảnh 1440 px; chờ Lead review |
| #79 | Xử lý lỗi POS và khôi phục nút hóa đơn, PR #413 | CI xanh; chờ review và xác nhận lại thiết kế đã cập nhật |
| #80 | Danh sách đơn/bộ lọc, PR #435 | CI xanh, đã chạy với API thật; chờ Lead review và merge |
| #81 | Chi tiết và hoàn tất/hủy đơn online, PR #436 | CI xanh, đã chạy GET/Hoàn tất/Hủy với API thật; chờ Lead review và tích hợp #80 |
| #82 | Giao diện trả hàng | Chờ PR backend #410 và hợp đồng `/returns` được merge vào `develop` |
| #83 | Bố cục 360 px và bàn phím, PR #437 | PR nháp xếp sau #80; CI nhánh xanh, đã thử trên Chrome với mock và API thật |
| #84 | Kiểm tra quyền và lỗi trạng thái, PR #438 | PR nháp xếp sau #81; ma trận quyền và test hồi quy chạy local |

Các issue chỉ đạt Definition of Done sau khi PR được review, CI xanh, merge vào `develop`, và có bằng chứng chạy với API thật hoặc nghiệm thu tương ứng. Bảng trên không coi PR nháp hay mock là hoàn tất tích hợp.
