# Phân quyền người dùng trong GreenMart

**Phạm vi:** năm vai trò nhân viên, phạm vi chi nhánh và các thao tác chính trên backend. Bản đối chiếu ngày 26/11/2026 dùng phiên bản `develop` tại `1272306`. Nhóm phát triển có thể dùng bảng dưới đây khi tích hợp màn hình; nhóm QA có thể dùng các tình huống cuối báo cáo để kiểm tra cả kết quả trả về và dữ liệu sau thao tác.

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

Frontend dùng vai trò để chọn khu vực làm việc và lọc menu. Ma trận dưới đây mô tả quyền của **backend**. Ví dụ, thu ngân có quyền đọc số tồn để bán tại POS dù khu vực Operations dành cho các vai trò khác. Trong bản đối chiếu, trang nhân sự và tài khoản bị khóa thuộc khu ADMIN. Kiểm thử quyền cần đối chiếu cả menu lẫn phản hồi của API cho cùng vai trò. [Quyền truy cập các khu vực](../../frontend/src/features/auth/routePolicy.ts) · [Menu Operations](../../frontend/src/layouts/operations/menuConfig.ts)

## Ma trận theo nghiệp vụ

Mỗi dòng liệt kê các vai trò được thực hiện hành động ở phiên bản đối chiếu. “Theo chi nhánh” nghĩa là xét các chi nhánh đã gán đối với quản lý, thu ngân và thủ kho. Tên viết tắt QL trong bảng chỉ quản lý chi nhánh.

| Nhóm | Hành động | Vai trò được phép | Điều kiện hoặc kết quả |
|---|---|---|---|
| Tài khoản | Xem danh sách, xem chi tiết, tạo, sửa, mở khóa, đặt lại mật khẩu | ADMIN | Mở khóa xóa khóa đăng nhập tạm thời. Việc kích hoạt tài khoản là thao tác riêng. Đặt lại mật khẩu thu hồi các phiên. |
| Danh mục | Tạo, sửa sản phẩm hoặc danh mục; cập nhật giá bán | OWNER | Giá bán và dữ liệu danh mục dùng cho toàn chuỗi. |
| Danh mục | Xem giá vốn trong chi tiết sản phẩm | ADMIN, OWNER, QL | Thu ngân và thủ kho nhận giá trị rỗng ở trường giá vốn. |
| Khuyến mãi | Tạo, sửa và đổi trạng thái chương trình | OWNER | Chương trình còn phải thỏa các điều kiện về thời gian, phạm vi và trạng thái. |
| Khuyến mãi | Đọc danh sách hoặc chi tiết | Cả năm vai trò đã xác thực | Phạm vi áp dụng của chương trình là dữ liệu nghiệp vụ. Quyền ghi yêu cầu OWNER. |
| Khách hàng | Tạo và sửa thông tin chung | OWNER, QL, thu ngân | Hồ sơ dùng chung. Khi yêu cầu sửa có trường trạng thái hoạt động, cần OWNER hoặc QL. |
| Chi nhánh | Đọc danh bạ chi nhánh | Cả năm vai trò | ADMIN và OWNER có thể tra chi nhánh ngừng hoạt động. Các vai trò khác chỉ nhận danh sách đang hoạt động. |
| Khách hàng | Tra cứu khách đang hoạt động và xem chi tiết theo ID | Tài khoản đã xác thực | Liệt kê riêng nhóm ngừng hoạt động yêu cầu OWNER hoặc QL. |
| Đơn hàng | Xem danh sách và chi tiết đơn | OWNER, QL, thu ngân, thủ kho | Backend đối chiếu chi nhánh của dữ liệu với danh sách được cấp. |
| POS | Báo giá và hoàn tất bán tại quầy | QL, thu ngân | Theo chi nhánh bán. Giảm thủ công ngoài chương trình yêu cầu QL và lý do. |
| Đơn online | Xác nhận hoàn tất bàn giao | QL, thủ kho | Theo chi nhánh xử lý đơn và trạng thái đơn. |
| Đơn online | Hủy đơn qua nghiệp vụ hủy online | QL | Kiểm tra chi nhánh, trạng thái và điều kiện hủy. |
| Trả hàng | Lập yêu cầu và xem phiếu trả | OWNER, QL, thu ngân | Xét quyền theo chi nhánh nhận trả ghi trên phiếu. Đơn gốc phải đủ điều kiện trả hàng. Quyền xem đơn bán gốc được xét riêng. |
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

Để tra chi tiết từng thao tác, xem [hợp đồng API](../../docs/api/openapi.yaml), [phiếu kho](../../backend/src/main/java/vn/greenmart/inventory/service/StockDocumentServiceImpl.java), [trả hàng](../../backend/src/main/java/vn/greenmart/sales/service/ReturnServiceImpl.java), [đơn online](../../backend/src/main/java/vn/greenmart/sales/service/OnlineOrderServiceImpl.java) và [gợi ý nhập](../../backend/src/main/java/vn/greenmart/inventory/service/InventoryReplenishmentService.java).

## Khác biệt giữa quyền backend và ma trận BA

Đối chiếu bên dưới dùng **mục 2 của Phân tích nghiệp vụ**, trong đó ô trống nghĩa là không cấp quyền, “CN” nghĩa là chi nhánh được gán, “Xem” là chỉ đọc. Cột **Code hiện tại** lấy từ kiểm tra ở lớp HTTP và service trên develop `1272306`. Tên menu và quyền được ghi trong BA không quyết định kết quả ở cột này. Đây là kết quả đọc mã nguồn, chưa chạy lại bộ API cho lần rà soát này. [Ma trận BA, dòng 43–64](../../docs/requirements/business-analysis.md)

### Các thao tác có khác biệt xác định được

| Thao tác | Code hiện tại cho ai làm? | BA ghi gì? | Khác ở đâu? |
|---|---|---|---|
| Tạo/sửa sản phẩm, tạo/sửa danh mục | Chỉ OWNER. Các phương thức ghi yêu cầu vai trò OWNER. | ADMIN và OWNER được tạo/sửa. | **Code hẹp hơn đối với ADMIN.** Riêng sửa giá bán thì hai bên cùng cấp cho OWNER. [Sản phẩm](../../backend/src/main/java/vn/greenmart/catalog/service/ProductServiceImpl.java) · [Danh mục](../../backend/src/main/java/vn/greenmart/catalog/service/CategoryServiceImpl.java) |
| Đọc danh sách, chi tiết tài khoản nhân viên | Chỉ ADMIN được vào API users, bao gồm hai thao tác đọc. | Dòng gộp “Quản lý người dùng, chi nhánh, kênh” ghi OWNER được xem. | **Code hẹp hơn đối với OWNER ở dữ liệu người dùng.** OWNER vẫn xem được nhật ký đồng bộ qua API khác. [Quyền HTTP](../../backend/src/main/java/vn/greenmart/common/security/SecurityConfig.java) |
| Đọc danh sách hoặc chi tiết chương trình khuyến mãi | Cả năm vai trò đã xác thực. Hai phương thức đọc gọi `ensureAuthenticated()`; các thao tác ghi yêu cầu OWNER. | OWNER quản lý, QL và thu ngân được xem; ô ADMIN và thủ kho trống. | **Code rộng hơn đối với ADMIN và thủ kho ở quyền đọc.** Quyền tạo/sửa vẫn khớp. [Danh sách, chi tiết và kiểm tra vai trò](../../backend/src/main/java/vn/greenmart/promotion/service/PromotionCrudServiceImpl.java) |
| Hoàn tất đơn online | QL hoặc thủ kho của chi nhánh xử lý đơn. | Dòng xử lý online bao gồm hoàn tất và cấp cho OWNER, QL, thủ kho. | **Code hẹp hơn đối với OWNER ở bước hoàn tất.** Kết luận này áp cho hành động complete. [completeOrder](../../backend/src/main/java/vn/greenmart/sales/service/OnlineOrderServiceImpl.java) |
| Hủy đơn online | Chỉ QL, có kiểm tra chi nhánh và trạng thái đơn. | OWNER và QL được hủy đơn. | **Code hẹp hơn đối với OWNER ở bước hủy online.** [cancelOrder](../../backend/src/main/java/vn/greenmart/sales/service/OnlineOrderServiceImpl.java) |
| Duyệt phiếu nhập, xuất hủy, kiểm kê | Chỉ QL. Backend kiểm tra chi nhánh của phiếu, trạng thái và danh tính người lập. | Ma trận mục 2 cấp OWNER và QL; bảng tác nhân của từng loại phiếu tại phần nghiệp vụ kho lại ghi QL duyệt. | **Code hẹp hơn ma trận mục 2 đối với OWNER, nhưng khớp bảng tác nhân nghiệp vụ kho.** Phương thức duyệt cũng xử lý chuyển đi; không suy rộng dòng BA về nhập/xuất hủy/kiểm kê sang quyền duyệt chuyển. [approve](../../backend/src/main/java/vn/greenmart/inventory/service/StockDocumentServiceImpl.java) · [Bảng phiếu kho trong BA](../../docs/requirements/business-analysis.md) |
| Tạo/sửa hồ sơ khách hàng | OWNER, QL, thu ngân. Hồ sơ được tạo hoặc tìm để sửa theo ID khách và số điện thoại; hai phương thức ghi không kiểm tra chi nhánh sở hữu hồ sơ. Gửi thuộc tính trạng thái khi sửa yêu cầu OWNER hoặc QL. | OWNER toàn chuỗi; QL và thu ngân được tạo/sửa trong phạm vi “CN”. | **Khớp danh sách vai trò, khác cách giới hạn dữ liệu.** Code quản lý hồ sơ khách dùng chung. Lịch sử đơn của khách có cơ chế lọc riêng theo các chi nhánh được gán. [Tạo/sửa hồ sơ](../../backend/src/main/java/vn/greenmart/customer/service/CustomerServiceImpl.java) · [Lọc lịch sử đơn](../../backend/src/main/java/vn/greenmart/customer/service/CustomerServiceImpl.java) |

### Các dòng BA gộp nhiều quyền cần đọc theo hành động cụ thể

- **Danh bạ chi nhánh.** API danh sách cho ADMIN và OWNER tra cả chi nhánh ngừng hoạt động; QL, thu ngân và thủ kho được đọc danh sách chi nhánh đang hoạt động, kể cả để chọn đích chuyển kho. Khả năng đọc danh bạ rộng hơn cách hiểu “ô trống = không được xem” của dòng quản lý chi nhánh. Việc xuất hiện trong danh bạ không cấp quyền xem tồn hoặc duyệt phiếu tại chi nhánh đó. [BranchServiceImpl](../../backend/src/main/java/vn/greenmart/branch/service/BranchServiceImpl.java)
- **Trả hàng.** BA ghi OWNER là X, QL là “CN, D” và thu ngân “CN (tạo yêu cầu)”. Code cho OWNER và QL duyệt hoặc hủy; thu ngân được lập, đọc chi tiết và liệt kê phiếu theo đơn trong phạm vi được phép. Vì BA ghi X cho OWNER, không thể kết luận quyền duyệt của OWNER lệch chỉ vì ô này không có chữ D. Quyền đọc phiếu của thu ngân có trong code và chưa được tách thành một dòng riêng trong BA. [ReturnServiceImpl](../../backend/src/main/java/vn/greenmart/sales/service/ReturnServiceImpl.java)
- **Các quyền khớp rõ.** POS cho QL và thu ngân; tra số dư tồn cho cả năm vai trò; báo cáo doanh thu cho OWNER và QL; xem dự báo/gợi ý nhập cho OWNER, QL và thủ kho; chạy lại dự báo cho ADMIN và OWNER; xem nhật ký đồng bộ cho ADMIN và OWNER nhưng gửi lại chỉ ADMIN. Các thao tác kho ở chi nhánh vẫn phải thỏa điều kiện bản ghi trong ma trận phía trên. [Quyền ở lớp HTTP](../../backend/src/main/java/vn/greenmart/common/security/SecurityConfig.java) · [Tra tồn](../../backend/src/main/java/vn/greenmart/inventory/service/InventoryQueryServiceImpl.java) · [Xem dự báo](../../backend/src/main/java/vn/greenmart/forecast/service/ForecastQueryService.java)

## Quyền của kênh bán và dịch vụ nội bộ

Năm vai trò trên áp dụng cho tài khoản nhân viên. Với đơn và sự kiện đến từ kênh bán, backend xác minh chữ ký của kênh trước khi tiếp nhận. Quyền cấu hình ánh xạ SKU của kênh trên API nhân viên thuộc ADMIN. Đối với Forecast, ADMIN hoặc OWNER có quyền yêu cầu chạy dự báo qua backend; API nội bộ của dịch vụ dự báo kiểm tra thông tin xác thực dịch vụ riêng. Không gán vai trò nhân viên cho các lời gọi nội bộ này. [Cấu hình bảo mật](../../backend/src/main/java/vn/greenmart/common/security/SecurityConfig.java) · [Tiếp nhận đơn kênh](../../backend/src/main/java/vn/greenmart/integration/webhook/ChannelOrderController.java) · [Quyền truy cập dịch vụ Forecast](../../forecast-service/app/api/router.py)

## Đề nghị QA kiểm thử và xác nhận căn cứ nghiệm thu

**Câu hỏi cần chốt trong PR:** Với từng dòng khác biệt ở bảng trên, nhóm sẽ giữ quyền backend hiện tại và cập nhật BA, hay dùng BA làm kỳ vọng và yêu cầu sửa backend? Đề nghị QA ghi nhận quyền quan sát được ở cả hai chiều cho phép và từ chối. Với mục đã có quyết định được duyệt, ghi đường dẫn quyết định làm căn cứ. Với mục chưa thống nhất, đề nghị Lead/BA chốt kỳ vọng trước khi QA kết luận đạt hoặc lỗi phân quyền. [Quyết định QA đã ghi nhận](../testing/qa-decisions-2026-11-25.md) · [Bộ ca phân quyền](../testing/rbac-branch-filter-test-cases.md)

| Quyền cần thử | Cặp trường hợp để đối chiếu | Kỳ vọng theo code đọc được |
|---|---|---|
| Ghi sản phẩm hoặc danh mục | OWNER và ADMIN cùng thử một thao tác hợp lệ | OWNER được phép; ADMIN bị từ chối. |
| Đọc tài khoản nhân viên | ADMIN và OWNER mở danh sách rồi mở chi tiết | Chỉ ADMIN được đọc. |
| Đọc và ghi khuyến mãi | ADMIN hoặc thủ kho xem danh sách, xem chi tiết, sau đó thử sửa | Được đọc; quyền sửa thuộc OWNER. |
| Hoàn tất và hủy đơn online | Chọn hai đơn đúng trạng thái; thử lần lượt với OWNER và QL đúng chi nhánh | OWNER bị chặn ở cả hai hành động. QL được phép trong phạm vi được gán. |
| Duyệt phiếu kho | Dùng cùng loại phiếu nháp, người duyệt khác người lập; thử OWNER, QL đúng chi nhánh và QL ngoài chi nhánh | Chỉ QL đúng chi nhánh được duyệt. |
| Sửa thông tin khách và đọc lịch sử đơn | Dùng QL hoặc thu ngân chỉ được gán một chi nhánh; chọn khách dùng chung có đơn ở hai chi nhánh | Sửa thông tin chung theo ID khách; lịch sử đơn chỉ trả dữ liệu thuộc phạm vi được cấp. |
| Đọc danh bạ và thao tác tại chi nhánh | Nhân viên được gán một chi nhánh tra danh bạ và yêu cầu xem tồn tại một chi nhánh khác | Được xem tên chi nhánh đang hoạt động; thao tác kho ngoài quyền bị chặn. |
| Thu hồi quyền đã cấp | Đổi danh sách chi nhánh của tài khoản thử, kiểm tra phiên cũ và phiên đăng nhập lại | Phiên cũ bị thu hồi; phiên mới dùng phạm vi vừa cập nhật. |

Với mỗi ca, ghi phiên bản, vai trò, chi nhánh được gán, chi nhánh bản ghi, hành động, kỳ vọng theo code, kỳ vọng theo BA, phản hồi thực tế và kết luận. Với thao tác ghi bị từ chối, đối chiếu dữ liệu trước và sau để xác nhận không phát sinh thay đổi. Phân biệt thiếu xác thực (401), sai vai trò (403 FORBIDDEN), sai chi nhánh (403 BRANCH_FORBIDDEN) và ràng buộc người lập tự duyệt (422 SELF_APPROVAL_FORBIDDEN). Bảng này là yêu cầu kiểm thử cho QA, chưa phải kết quả chạy API trên phiên bản đối chiếu.
