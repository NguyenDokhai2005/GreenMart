# Báo cáo phân quyền GreenMart theo BA

QA dùng [BA mục 2](../requirements/business-analysis.md) để xác định người được thao tác và [OpenAPI](../api/openapi.yaml) để chọn yêu cầu kiểm thử. Quyền phụ thuộc đồng thời vào vai trò, chi nhánh và trạng thái bản ghi.

## Ai được thực hiện từng công việc?

**CN** là chi nhánh được gán cho tài khoản. **Toàn chuỗi** cho phép thao tác tại bất kỳ chi nhánh nào đối với chức năng ghi trong ô. Dấu **—** nghĩa là vai trò không có quyền thực hiện thao tác đó.

| Công việc | Admin | Chủ cửa hàng | Quản lý CN | Thu ngân | Thủ kho |
|---|---|---|---|---|---|
| Tài khoản nhân viên | Xem và quản trị | Xem | — | — | — |
| Chi nhánh và kênh bán | Quản trị theo API hiện có | Xem | Tra tên CN hoạt động | Tra tên CN hoạt động | Tra tên CN hoạt động |
| Sản phẩm, danh mục | Tạo, sửa | Tạo, sửa | Xem | Xem | Xem |
| Đổi giá bán của sản phẩm đã có | — | Toàn chuỗi | — | — | — |
| Hồ sơ khách | — (ghi) | Tạo, sửa | Tạo, sửa theo CN | Tạo, sửa theo CN | — (ghi) |
| Ngừng hoạt động hoặc kích hoạt lại khách | — | Toàn chuỗi | Theo CN | — | — |
| Chương trình khuyến mại | — | Xem và quản lý | Xem | Xem | — |
| Bán tại quầy | — | — | CN | CN | — |
| Hoàn tất đơn online | — | Toàn chuỗi | CN | — | CN |
| Hủy đơn online | — | Toàn chuỗi | CN | — | — |
| Trả hàng | — | Lập, duyệt, hủy | Lập, duyệt, hủy tại CN | Lập yêu cầu tại CN | — |
| Lập phiếu nhập, xuất hủy, kiểm kê | — | — | CN | — | CN |
| Duyệt phiếu nhập, xuất hủy, kiểm kê | — | Toàn chuỗi | CN | — | — |
| Chuyển kho | — | Xem phiếu | Lập và duyệt tại nguồn, nhận tại đích | — | Lập tại nguồn, nhận tại đích |
| Xem số dư tồn kho | Toàn chuỗi | Toàn chuỗi | CN | CN | CN |
| Xem sổ giao dịch kho | — | Toàn chuỗi | CN | — | CN |
| Dashboard, báo cáo doanh thu | — | Toàn chuỗi | CN | — | — |
| Dự báo nhu cầu theo ngày | — | Toàn chuỗi | CN | — | — |
| Xem gợi ý nhập hàng | — | Toàn chuỗi | CN | — | CN |
| Yêu cầu chạy lại dự báo, xem lượt chạy | Có | Có | — | — | — |
| Nhật ký đồng bộ | Xem, gửi lại bản ghi lỗi | Xem | — | — | — |

## Các trường hợp dễ kiểm tra nhầm

- **Khách mua ở nhiều nơi.** Khách đã có đơn hoàn tất ở A và B thì nhân viên đủ vai trò được gán A hoặc B đều sửa được hồ sơ chung. Nhân viên chỉ được gán C bị từ chối, kể cả khi biết mã khách. Đơn đã trả hàng vẫn là bằng chứng từng mua. Đơn mới, giữ chỗ, hủy hoặc bị từ chối không cấp quyền sửa. Khi khách chưa từng mua, chỉ chủ cửa hàng sửa được. Thu ngân vẫn chỉ được sửa thông tin liên hệ. Lịch sử đơn hiển thị theo phạm vi chi nhánh của người xem.
- **Quản lý lập phiếu.** Quản lý A tạo phiếu nhập thì một quản lý khác có quyền tại A hoặc chủ cửa hàng có thể duyệt. Chính người lập tự duyệt nhận lỗi 422. Với phiếu chuyển A → B, người duyệt phải là quản lý nguồn khác người lập. Quản lý hoặc thủ kho bên B xác nhận nhận hàng. Với phiếu trả hàng, phạm vi kiểm tra tính theo chi nhánh nhận trả.
- **Quản trị và giá bán.** Admin được nhập giá ban đầu khi thêm sản phẩm. Sau khi tạo, quyền đổi giá thuộc chủ cửa hàng. Backend đối chiếu với giá đang lưu trong lúc khóa bản ghi. Nếu Admin mở màn hình trước khi chủ cửa hàng đổi giá, việc gửi lại giá cũ sẽ bị từ chối. Xóa danh mục là thao tác riêng dành cho chủ cửa hàng.
- **Danh bạ chi nhánh.** Nhân viên có thể tra tên chi nhánh đang hoạt động để chọn đích chuyển kho. Khi đọc số tồn, duyệt phiếu hoặc nhận hàng, backend kiểm tra chi nhánh trên dữ liệu thực tế.
- **Dự báo và gợi ý nhập.** Quản lý A được xem chuỗi dự báo theo ngày tại A. Thủ kho A được xem bảng gợi ý nhập theo BA mục 8.3 và 9. Khi thủ kho mở bảng, giao diện chỉ lấy số tồn và dữ liệu gợi ý, không yêu cầu chuỗi dự báo. Quyền vận hành mô hình của Admin và chủ cửa hàng được kiểm tra riêng.
- **Kênh bán và đơn online.** Xác nhận tự động theo thanh toán hoặc COD là sự kiện hệ thống. Với thao tác của nhân viên, backend còn kiểm tra trạng thái đơn trước khi hoàn tất, hủy, hoàn tiền hoặc nhả giữ chỗ. Quyền đọc tài khoản nhân viên trả về thông tin nhân sự, vai trò, chi nhánh và trạng thái tài khoản.

## QA kiểm tra trên bản đã triển khai

| Tình huống | Kết quả cần thấy |
|---|---|
| Admin đổi tên sản phẩm, sau đó thử đổi giá hiện tại | Lưu được tên. Lần đổi giá trả 403, giá cũ giữ nguyên. |
| Chủ cửa hàng mở Nhân sự và Nhật ký đồng bộ | Đọc được dữ liệu. Lệnh ghi tài khoản và lệnh gửi lại đồng bộ trả 403. |
| Thủ kho và thu ngân cùng tra một chương trình khuyến mại | Thủ kho nhận 403 trên cả danh sách và chi tiết. Thu ngân đọc được, thao tác ghi bị từ chối. |
| Chủ cửa hàng duyệt phiếu nhập do người khác lập, rồi thử duyệt phiếu chuyển | Phiếu nhập hợp lệ được duyệt. Phiếu chuyển bị từ chối. |
| Nhân viên B và C cùng sửa khách đã mua tại A, B | B sửa được, C nhận 403. Kiểm tra hồ sơ sau lần bị từ chối để xác nhận dữ liệu được giữ nguyên. |
| Thủ kho A mở Gợi ý nhập, sau đó thử tra dữ liệu của B | Xem được gợi ý của A. Gợi ý của B trả 403 do sai CN. API dự báo theo ngày trả 403 kể cả khi chọn A. |

Thử với dữ liệu có trạng thái hợp lệ cho từng thao tác để phân biệt lỗi quyền với lỗi nghiệp vụ. Kiểm tra cả màn hình và API vì nút bị ẩn chỉ phản ánh giao diện. Chi tiết các ca chéo chi nhánh nằm trong [bộ ca RBAC](../testing/rbac-branch-filter-test-cases.md). Các quyền quản trị trong bảng áp dụng cho các thao tác dự án đã triển khai.
