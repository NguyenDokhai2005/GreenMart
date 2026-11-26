# Phân quyền người dùng trong GreenMart

**Phạm vi:** năm vai trò nhân viên, phạm vi chi nhánh và các thao tác chính trên backend. Bản đối chiếu ngày 26/11/2026 dùng phiên bản `develop` tại `0d3cd76`. Nhóm phát triển có thể dùng bảng dưới đây khi tích hợp màn hình; nhóm QA có thể dùng các tình huống cuối báo cáo để kiểm tra cả kết quả trả về và dữ liệu sau thao tác.

## Cách GreenMart quyết định một yêu cầu

Một nhân viên được phép thực hiện thao tác khi phiên đăng nhập còn hiệu lực, vai trò được phép thực hiện nghiệp vụ đó, dữ liệu thuộc phạm vi nhân viên phụ trách và chứng từ đang ở trạng thái phù hợp. Ví dụ, thu ngân có thể bán sản phẩm tại chi nhánh được gán. Quyền bán hàng cho phép giảm tồn thông qua một đơn POS hợp lệ; quyền điều chỉnh số tồn qua kiểm kê được xét riêng.

1. **Xác định người thao tác.** Backend kiểm tra access token, trạng thái phiên và vai trò hiện tại của tài khoản, rồi gắn danh tính vào yêu cầu. Khi quản trị viên đổi vai trò, chi nhánh được gán hoặc trạng thái hoạt động, các phiên của tài khoản bị thu hồi. Nhân viên đăng nhập lại để sử dụng quyền mới.
2. **Xét vai trò cho từng hành động.** Ví dụ, quyền xem nhật ký đồng bộ và quyền gửi lại một lượt đồng bộ là hai quyền riêng. Backend kiểm tra quyền tại cấu hình bảo mật và tại dịch vụ nghiệp vụ.
3. **Xét chi nhánh của dữ liệu.** Quản lý, thu ngân và thủ kho có thể được gán một hoặc nhiều chi nhánh qua bảng `user_branch`. Backend đối chiếu phạm vi này với chi nhánh ghi trên đơn hoặc phiếu. Khi API danh sách hỗ trợ tra cứu nhiều chi nhánh, kết quả vẫn phải nằm trong phạm vi được cấp. ADMIN và OWNER có phạm vi toàn chuỗi đối với những nghiệp vụ vai trò của họ được sử dụng.
4. **Xét điều kiện của bản ghi.** Chứng từ phải đúng trạng thái. Ví dụ, chỉ phiếu chuyển đang vận chuyển mới có thể nhận tại đích. Các nghiệp vụ duyệt phiếu còn kiểm tra người lập và người duyệt là hai tài khoản khác nhau.

Nguồn triển khai: [xác thực JWT](../../backend/src/main/java/vn/greenmart/identity/security/JwtAuthenticationFilter.java), [quyền ở lớp HTTP](../../backend/src/main/java/vn/greenmart/common/security/SecurityConfig.java), [phạm vi chi nhánh](../../backend/src/main/java/vn/greenmart/common/security/SecurityUtils.java) và [quản trị tài khoản](../../backend/src/main/java/vn/greenmart/identity/service/UserAdminService.java).

## Năm vai trò và nơi làm việc

| Vai trò | Trách nhiệm | Khu vực giao diện |
|---|---|---|
| Quản trị viên (ADMIN) | Quản lý tài khoản, phân công quyền và chi nhánh, xử lý vận hành kỹ thuật của hệ thống. | Quản trị |
| Chủ chuỗi (OWNER) | Theo dõi kinh doanh toàn chuỗi, quản lý sản phẩm và chính sách khuyến mãi, xem báo cáo và dự báo. | Operations |
| Quản lý chi nhánh (BRANCH_MANAGER) | Điều hành bán hàng và kho trong các chi nhánh được gán, phê duyệt chứng từ và theo dõi doanh thu. | Operations và POS |
| Thu ngân (CASHIER) | Bán tại quầy, tra khách hàng, thanh toán và lập yêu cầu trả hàng. | POS |
| Thủ kho (STOREKEEPER) | Tra tồn, lập chứng từ kho, nhận hàng chuyển đến và bàn giao đơn online. | Operations |

Frontend dùng vai trò để chọn khu vực làm việc và lọc menu. Ma trận dưới đây mô tả quyền của **backend**. Ví dụ, thu ngân có quyền đọc số tồn để bán tại POS dù khu vực Operations dành cho các vai trò khác. Khi kiểm thử giao diện, cần kiểm tra cả kết quả từ server sau khi bấm nút. [Quyền truy cập các khu vực](../../frontend/src/features/auth/routePolicy.ts) · [Menu Operations](../../frontend/src/layouts/operations/menuConfig.ts)

## Ma trận theo nghiệp vụ

Mỗi dòng liệt kê các vai trò được thực hiện hành động ở phiên bản đối chiếu. “Theo chi nhánh” nghĩa là xét các chi nhánh đã gán đối với quản lý, thu ngân và thủ kho. Tên viết tắt QL trong bảng chỉ quản lý chi nhánh.

| Nhóm | Hành động | Vai trò được phép | Điều kiện hoặc kết quả |
|---|---|---|---|
| Tài khoản | Xem danh sách, xem chi tiết, tạo, sửa, mở khóa, đặt lại mật khẩu | ADMIN | Mở khóa xóa khóa đăng nhập tạm thời. Việc kích hoạt tài khoản là thao tác riêng. Đặt lại mật khẩu thu hồi các phiên. |
| Danh mục | Tạo, sửa sản phẩm hoặc danh mục; cập nhật giá bán | OWNER | Giá bán và dữ liệu danh mục dùng cho toàn chuỗi. |
| Danh mục | Xem giá vốn trong chi tiết sản phẩm | ADMIN, OWNER, QL | Thu ngân và thủ kho nhận giá trị rỗng ở trường giá vốn. |
| Khuyến mãi | Tạo, sửa và đổi trạng thái chương trình | OWNER | Chương trình còn phải thỏa các điều kiện về thời gian, phạm vi và trạng thái. |
| POS | Báo giá và hoàn tất bán tại quầy | QL, thu ngân | Theo chi nhánh bán. Giảm thủ công ngoài chương trình yêu cầu QL và lý do. |
| Đơn online | Xác nhận hoàn tất bàn giao | QL, thủ kho | Theo chi nhánh xử lý đơn và trạng thái đơn. |
| Đơn online | Hủy đơn qua nghiệp vụ hủy online | QL | Kiểm tra chi nhánh, trạng thái và điều kiện hủy. |
| Trả hàng | Lập yêu cầu và xem phiếu trả | OWNER, QL, thu ngân | Xét phạm vi đơn gốc. |
| Trả hàng | Duyệt hoặc hủy phiếu trả | OWNER, QL | Khi duyệt, người duyệt phải khác người lập. |
| Kho | Tra số dư tồn | Cả năm vai trò | Theo chi nhánh. Tồn khả dụng bằng thực tồn trừ lượng đã giữ. |
| Kho | Xem sổ giao dịch và phiếu kho | OWNER, QL, thủ kho | Xét phạm vi dữ liệu. Với phiếu chuyển, nguồn và đích đều có nhu cầu tra cứu. |
| Kho | Lập, sửa hoặc hủy phiếu kho nháp | QL, thủ kho | Theo chi nhánh lập; phiếu chuyển xét kho nguồn. |
| Kho | Duyệt nhập, xuất hủy, chuyển đi, kiểm kê | QL | Chi nhánh lập phiếu, đúng trạng thái và khác người lập. |
| Kho | Nhận phiếu chuyển đang vận chuyển | QL, thủ kho | Kiểm tra quyền tại **kho đích**; ghi số lượng thực nhận. |
| Kho | Xem cảnh báo tồn thấp | OWNER, QL | OWNER xem toàn chuỗi; QL xem trong phạm vi được gán. |
| Kinh doanh | Xem báo cáo doanh thu và top sản phẩm | OWNER, QL | Báo cáo áp dụng bộ lọc chi nhánh theo người xem. |
| Dự báo | Xem kết quả theo sản phẩm, xem gợi ý nhập | OWNER, QL, thủ kho | Theo sản phẩm và chi nhánh. |
| Dự báo | Cấu hình ngưỡng đặt hàng lại và quy cách nhập | QL | Theo chi nhánh cấu hình. |
| Dự báo | Khởi chạy hoặc tra cứu lượt chạy mô hình | ADMIN, OWNER | Quyền vận hành lượt chạy được xét riêng với quyền đọc kết quả theo SKU. |
| Tích hợp | Xem nhật ký đồng bộ | ADMIN, OWNER | Gửi lại một lượt đồng bộ lỗi và cập nhật ánh xạ sản phẩm kênh yêu cầu ADMIN. |

Để tra chi tiết từng thao tác, xem [hợp đồng API](../api/openapi.yaml), [phiếu kho](../../backend/src/main/java/vn/greenmart/inventory/service/StockDocumentServiceImpl.java), [trả hàng](../../backend/src/main/java/vn/greenmart/sales/service/ReturnServiceImpl.java), [đơn online](../../backend/src/main/java/vn/greenmart/sales/service/OnlineOrderServiceImpl.java) và [gợi ý nhập](../../backend/src/main/java/vn/greenmart/inventory/service/InventoryReplenishmentService.java).

## Đọc ma trận khi đặc tả và bản triển khai khác nhau

Bảng tổng quan ở mục 2 của tài liệu Phân tích nghiệp vụ ghi ADMIN có quyền tạo/sửa sản phẩm, danh mục và OWNER có quyền duyệt một số phiếu kho, xử lý hoặc hủy đơn online. Trong bản backend đã đối chiếu, thao tác tạo/sửa sản phẩm và danh mục yêu cầu OWNER, duyệt phiếu kho yêu cầu QL, hoàn tất đơn online cho phép QL hoặc thủ kho và hủy đơn online yêu cầu QL. Vì vậy, khi chuẩn bị màn hình hoặc tiêu chí nghiệm thu cho những dòng này, nhóm cần đối chiếu **đúng endpoint, hành động và phiên bản**. Thay đổi quyền so với bản triển khai cần được thống nhất trong đặc tả và có ca kiểm thử tương ứng. [Ma trận nghiệp vụ](../requirements/business-analysis.md) · [Sản phẩm](../../backend/src/main/java/vn/greenmart/catalog/service/ProductServiceImpl.java) · [Danh mục](../../backend/src/main/java/vn/greenmart/catalog/service/CategoryServiceImpl.java)

## Tình huống kiểm tra dùng chung

| Mục đích | Thao tác trên dữ liệu thử | Điều cần quan sát |
|---|---|---|
| Kiểm tra phạm vi chi nhánh | Dùng QL chỉ được gán GM-001 để xem danh sách tồn, sau đó yêu cầu tồn của GM-002. | Danh sách đúng phạm vi. Yêu cầu chỉ định chi nhánh ngoài quyền bị từ chối với 403 BRANCH_FORBIDDEN. |
| Phân biệt quyền đọc và ghi | OWNER xem nhật ký đồng bộ rồi thử thao tác gửi lại; ADMIN thực hiện trên một bản ghi đủ điều kiện. | OWNER đọc được nhật ký. Quyền gửi lại thuộc ADMIN; trạng thái bản ghi còn phải cho phép gửi lại. |
| Kiểm tra phân tách trách nhiệm trong kho | Thủ kho nguồn lập phiếu chuyển; QL nguồn duyệt; thủ kho đích xác nhận số thực nhận. | Phiếu nháp chưa tác động số dư. Nguồn giảm lúc duyệt xuất; đích tăng theo thực nhận. Kiểm tra mã phiếu và bút toán ở từng bên. |
| Kiểm tra quyền sau khi đổi phân công | ADMIN đổi vai trò hoặc danh sách chi nhánh của một tài khoản thử. Thử dùng phiên cũ, sau đó đăng nhập lại. | Phiên cũ bị thu hồi. Phiên mới chỉ thao tác trong phạm vi mới. |
| Kiểm tra giá vốn | Mở chi tiết cùng sản phẩm bằng quản lý và thu ngân. | Quản lý được đọc giá vốn; phản hồi cho thu ngân trả null ở trường này. |

Khi ghi nhận lỗi, lưu vai trò, chi nhánh được gán, chi nhánh của bản ghi, trạng thái chứng từ và mã phản hồi. **401** thường yêu cầu xác thực lại; **403 FORBIDDEN** biểu thị thao tác ngoài vai trò, còn **403 BRANCH_FORBIDDEN** biểu thị vượt phạm vi chi nhánh. Một yêu cầu đã qua kiểm tra quyền vẫn có thể bị từ chối vì trạng thái hoặc quy tắc nghiệp vụ. Ví dụ, tự duyệt phiếu trả về **422 SELF_APPROVAL_FORBIDDEN**. Các tình huống trên là hướng dẫn kiểm tra; kết quả các đợt chạy được lưu trong tài liệu QA tương ứng. [Kế hoạch kiểm thử RBAC](../testing/rbac-branch-filter-test-cases.md) · [Biên bản kiểm tra API](../testing/issue-152-recheck-2026-11-14.md) · [Các mã lỗi](../../backend/src/main/java/vn/greenmart/common/exception/ErrorCode.java)
