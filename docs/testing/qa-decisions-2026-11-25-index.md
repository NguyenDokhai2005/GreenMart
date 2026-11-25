# Bảng tra cứu các issue do SV08 tạo

Ngày chụp metadata: **25/11/2026**. Bảng gồm **61 issue**, cả OPEN và CLOSED, để tra nguồn cho [bản tổng hợp phương án](qa-decisions-2026-11-25.md). Đây là danh mục truy vết, không phải 61 quyết định nghiệp vụ mới.

**OPEN/CLOSED là trạng thái issue trên GitHub, không thay cho kết luận PASS/FIXED.** Danh sách người dưới đây là assignee hiện tại, không tự xác định ai gây lỗi hoặc thay đổi phân công. Các chi tiết và quyết định nằm trong issue/bình luận; phần mâu thuẫn hoặc bị rút được nêu trong bản tổng hợp.

| Issue | Nội dung công khai | Trạng thái issue | Assignee hiện tại |
|---|---|---|---|
| [#258](https://github.com/NguyenDokhai2005/GreenMart/issues/258) | test[QA][ORD-07] GET chi tiết hóa đơn trả 404 sau khi tạo đơn POS — chặn #149 | CLOSED | Chưa gán |
| [#267](https://github.com/NguyenDokhai2005/GreenMart/issues/267) | test[bug][Customer] GET /customers trả HTTP 500 khi không truyền q (#151) | CLOSED | @t-boai |
| [#358](https://github.com/NguyenDokhai2005/GreenMart/issues/358) | [bug][Catalog/Auth] Lookup chéo chi nhánh trả 403 nhưng audit thiếu entity_id và branchId (#152) | CLOSED | @t-boai |
| [#384](https://github.com/NguyenDokhai2005/GreenMart/issues/384) | [bug][Integration] Outbox backlog sau tải 50 VU làm đồng bộ tồn quá 1 phút | OPEN | Chưa gán |
| [#386](https://github.com/NguyenDokhai2005/GreenMart/issues/386) | [bug][Catalog] Hai cập nhật đồng thời tạo vòng lặp danh mục | CLOSED | Chưa gán |
| [#387](https://github.com/NguyenDokhai2005/GreenMart/issues/387) | [bug][Sales] Quote tràn số int64 trả tổng tiền âm hoặc bằng0 | CLOSED | Chưa gán |
| [#388](https://github.com/NguyenDokhai2005/GreenMart/issues/388) | [bug][Reporting] Ngừng bán sản phẩm làm mất số bán trong báo cáo lịch sử | CLOSED | @Dat9161 |
| [#389](https://github.com/NguyenDokhai2005/GreenMart/issues/389) | [bug][Reporting] Doanh thu sản phẩm bỏ sót giảm giá cấp đơn | CLOSED | @Dat9161 |
| [#406](https://github.com/NguyenDokhai2005/GreenMart/issues/406) | [bug][Promotion] Phần tử null trong phạm vi gây HTTP500 khi tạo/cập nhật khuyến mại | CLOSED | Chưa gán |
| [#416](https://github.com/NguyenDokhai2005/GreenMart/issues/416) | [bug][Payment/Security] Chữ ký PAID không ràng buộc đơn, cùng providerRef xác nhận hai đơn | CLOSED | @thanhcode118, @Dat9161, @t-boai |
| [#417](https://github.com/NguyenDokhai2005/GreenMart/issues/417) | [bug][Forecast] Nightly job chưa nối ETL và dùng cutoff lịch sử làm dự báo vận hành bị cũ | CLOSED | @thanhcode118, @hongthienhao |
| [#418](https://github.com/NguyenDokhai2005/GreenMart/issues/418) | [enhancement][Customer/POS] Hoàn thiện xử lý khách ngừng hoạt động và chặn gắn customerId tại POS | CLOSED | @Dat9161, @t-boai, @voxuanvy611-sys |
| [#419](https://github.com/NguyenDokhai2005/GreenMart/issues/419) | [enhancement][Integration] Bổ sung phục hồi hàng đợi callback sau khi retry hết lượt | OPEN | @thanhcode118 |
| [#420](https://github.com/NguyenDokhai2005/GreenMart/issues/420) | [bug][Integration] API ánh xạ SKU trả 500 cho body hoặc productId không hợp lệ | CLOSED | @thanhcode118, @Dat9161 |
| [#421](https://github.com/NguyenDokhai2005/GreenMart/issues/421) | [enhancement][Inventory] Bổ sung đối soát kiểm kê khi tồn thực tế nhỏ hơn hàng đang giữ | CLOSED | @Dat9161, @t-boai |
| [#422](https://github.com/NguyenDokhai2005/GreenMart/issues/422) | [bug][Seed/Promotion] used_count KM theo sản phẩm được seed theo số dòng thay vì số đơn | CLOSED | @hongthienhao, @Dat9161, @t-boai |
| [#423](https://github.com/NguyenDokhai2005/GreenMart/issues/423) | [enhancement][Forecast] Chuẩn hóa báo cáo metric theo horizon và bước postprocess của mô hình phục vụ | OPEN | @hongthienhao, @Dat9161 |
| [#424](https://github.com/NguyenDokhai2005/GreenMart/issues/424) | [enhancement][Users] Hoàn thiện API quản trị người dùng còn thiếu trên develop | CLOSED | @Dat9161 |
| [#425](https://github.com/NguyenDokhai2005/GreenMart/issues/425) | [enhancement][Promotion] Thống nhất ITEM-AMOUNT theo số lượng và bổ sung minQuantity/phạm vi danh mục | CLOSED | @Dat9161, @t-boai, @voxuanvy611-sys |
| [#426](https://github.com/NguyenDokhai2005/GreenMart/issues/426) | [enhancement][Promotion] Hoàn thiện vòng đời thời hạn KM và trạng thái seed đã hết hạn | CLOSED | @hongthienhao, @Dat9161, @t-boai |
| [#427](https://github.com/NguyenDokhai2005/GreenMart/issues/427) | [bug][OrderMapper] SQL tham chiếu promotion.name không tồn tại trong schema | CLOSED | @Dat9161, @t-boai |
| [#428](https://github.com/NguyenDokhai2005/GreenMart/issues/428) | [bug][Order/Inventory] Late PAID lỗi sau re-reserve để lại giữ chỗ mồ côi do transaction riêng | CLOSED | @Dat9161, @t-boai |
| [#429](https://github.com/NguyenDokhai2005/GreenMart/issues/429) | [enhancement][Security] Gia cố JWT lifetime, refresh-family và thu hồi phiên sau logout | CLOSED | @thanhcode118, @Dat9161, @voxuanvy611-sys |
| [#430](https://github.com/NguyenDokhai2005/GreenMart/issues/430) | [enhancement][Validation] Chuẩn hóa SKU kênh và tính duy nhất tên danh mục theo cấp cha | CLOSED | @thanhcode118, @Dat9161, @t-boai |
| [#431](https://github.com/NguyenDokhai2005/GreenMart/issues/431) | [enhancement][Forecast/Security] Tắt runtime OpenAPI/docs trên cấu hình production | CLOSED | @thanhcode118, @hongthienhao |
| [#432](https://github.com/NguyenDokhai2005/GreenMart/issues/432) | [bug][ETL/Data] Stockout từ nguồn DB toàn false khi thiếu lịch sử tồn kho, lệch dữ liệu seed | OPEN | @hongthienhao, @Dat9161 |
| [#433](https://github.com/NguyenDokhai2005/GreenMart/issues/433) | [enhancement][Forecast/UX] Hiển thị trạng thái chưa có dự báo rõ ràng, giữ 404 theo OpenAPI | OPEN | @Dat9161, @TranVanVu2502 |
| [#442](https://github.com/NguyenDokhai2005/GreenMart/issues/442) | [enhancement][Catalog/Search] Tìm sản phẩm không dấu chưa khớp tên tiếng Việt trong POS và danh mục | CLOSED | @t-boai |
| [#443](https://github.com/NguyenDokhai2005/GreenMart/issues/443) | [enhancement][Inventory/Search] Tìm tồn kho bằng tên sản phẩm không dấu chưa ra kết quả | CLOSED | @Dat9161 |
| [#444](https://github.com/NguyenDokhai2005/GreenMart/issues/444) | [enhancement][Frontend/Catalog] Ô tìm kiếm Sản phẩm chưa bỏ khoảng trắng đầu/cuối | CLOSED | @voxuanvy611-sys |
| [#445](https://github.com/NguyenDokhai2005/GreenMart/issues/445) | [bug][POS] Giỏ hàng mất khi chuyển mục hoặc tải lại trang | CLOSED | @voxuanvy611-sys |
| [#446](https://github.com/NguyenDokhai2005/GreenMart/issues/446) | [bug][Operations] Quản lý chi nhánh thiếu liên kết mở POS | OPEN | @TranVanVu2502 |
| [#447](https://github.com/NguyenDokhai2005/GreenMart/issues/447) | [enhancement][POS] Bổ sung nhập giảm giá thủ công và lý do cho quản lý theo BR-19 | CLOSED | @voxuanvy611-sys |
| [#448](https://github.com/NguyenDokhai2005/GreenMart/issues/448) | [task][Operations] Tích hợp Khuyến mại, Kiểm kê và Báo cáo còn placeholder; Admin chờ phân công | OPEN | @TranVanVu2502 |
| [#449](https://github.com/NguyenDokhai2005/GreenMart/issues/449) | [bug][Stock UI] Khoảng ngày đảo ngược trả lỗi chung và nút Thử lại không hữu ích | OPEN | @TranVanVu2502 |
| [#450](https://github.com/NguyenDokhai2005/GreenMart/issues/450) | [bug][Stock UI] Số lượng vượt 99999 hiện lỗi tiếng Anh và thiếu giới hạn nhập | OPEN | @TranVanVu2502 |
| [#451](https://github.com/NguyenDokhai2005/GreenMart/issues/451) | [bug][Stock UI] Picker chỉ xét trang đầu và báo hết hàng sai trong luồng chuyển kho | OPEN | @TranVanVu2502 |
| [#452](https://github.com/NguyenDokhai2005/GreenMart/issues/452) | [bug][Transfer UI] Route chuyển kho hiển thị chứng từ IMPORT và render 990 dòng | OPEN | @TranVanVu2502 |
| [#453](https://github.com/NguyenDokhai2005/GreenMart/issues/453) | [enhancement][POS/Keyboard] Phím Ctrl+Alt+1–5 bị bỏ qua khi ô tìm kiếm giữ focus | CLOSED | @voxuanvy611-sys |
| [#454](https://github.com/NguyenDokhai2005/GreenMart/issues/454) | [bug][Stock UI/Accessibility] Chữ tab Phiếu nhập đang chọn chỉ đạt tương phản 2,716:1 | OPEN | @TranVanVu2502 |
| [#455](https://github.com/NguyenDokhai2005/GreenMart/issues/455) | [enhancement][POS] Hỗ trợ nhập tiền khách đưa với dấu phân cách hàng nghìn | CLOSED | @voxuanvy611-sys |
| [#456](https://github.com/NguyenDokhai2005/GreenMart/issues/456) | [enhancement][Sales UI] Chuẩn hóa khoảng trắng tên đăng nhập và cách nhập số điện thoại | CLOSED | @voxuanvy611-sys |
| [#457](https://github.com/NguyenDokhai2005/GreenMart/issues/457) | [enhancement][Login UI] Làm rõ thời gian đếm ngược trong thông báo khóa tài khoản | CLOSED | @voxuanvy611-sys |
| [#458](https://github.com/NguyenDokhai2005/GreenMart/issues/458) | [enhancement][Stock UI] Thông báo lưu phiếu thành công chưa tự đóng sau 31 giây | OPEN | @TranVanVu2502 |
| [#459](https://github.com/NguyenDokhai2005/GreenMart/issues/459) | [bug][Operations/ErrorState] Chi tiết chuyển kho và đơn khác chi nhánh hiện Thử lại cho lỗi 403 | OPEN | @TranVanVu2502 |
| [#460](https://github.com/NguyenDokhai2005/GreenMart/issues/460) | [bug][Shared Select] Bộ lọc Tất cả trạng thái hiển thị Vui lòng chọn | OPEN | @TranVanVu2502 |
| [#461](https://github.com/NguyenDokhai2005/GreenMart/issues/461) | [enhancement][POS/Receipt] Thống nhất ký hiệu tiền và không hiện giảm giá âm 0 | CLOSED | @voxuanvy611-sys |
| [#462](https://github.com/NguyenDokhai2005/GreenMart/issues/462) | [enhancement][POS/Receipts] Bổ sung tra cứu hóa đơn bằng mã đơn hoặc số điện thoại | CLOSED | @voxuanvy611-sys |
| [#463](https://github.com/NguyenDokhai2005/GreenMart/issues/463) | [bug][Inventory Ledger UI] Tìm giao dịch chỉ lọc trang hiện tại và báo không có kết quả sai | OPEN | @TranVanVu2502 |
| [#464](https://github.com/NguyenDokhai2005/GreenMart/issues/464) | [enhancement][POS/Design] Gợi ý sản phẩm chưa hiện Tồn khả dụng như Figma đã đối chiếu | CLOSED | @voxuanvy611-sys |
| [#465](https://github.com/NguyenDokhai2005/GreenMart/issues/465) | [enhancement][POS/Design] Nút Thêm cao 34px khác 44px trong thiết kế đối chiếu | CLOSED | @voxuanvy611-sys |
| [#466](https://github.com/NguyenDokhai2005/GreenMart/issues/466) | [enhancement][Stock UI/Design] Rà cấu trúc danh sách phiếu kho khác Figma được duyệt | OPEN | @TranVanVu2502 |
| [#467](https://github.com/NguyenDokhai2005/GreenMart/issues/467) | [enhancement][POS/Keyboard] F4 chỉ focus nút Thanh toán; xác minh kỳ vọng mở hộp thanh toán | CLOSED | @voxuanvy611-sys |
| [#468](https://github.com/NguyenDokhai2005/GreenMart/issues/468) | [task][Frontend/Admin] Các màn quản trị còn placeholder, cần chốt phạm vi và người triển khai | CLOSED | Chưa gán |
| [#483](https://github.com/NguyenDokhai2005/GreenMart/issues/483) | [bug][Returns] POST /returns nhận phần tử null gây HTTP 500 | CLOSED | @Dat9161 |
| [#484](https://github.com/NguyenDokhai2005/GreenMart/issues/484) | [bug][API Validation] Trường integer nhận số thập phân và tự cắt phần lẻ | CLOSED | @Dat9161 |
| [#485](https://github.com/NguyenDokhai2005/GreenMart/issues/485) | [bug][Forecast API] GET /forecasts/runs trả 405 dù có trong OpenAPI | CLOSED | @thanhcode118, @hongthienhao |
| [#515](https://github.com/NguyenDokhai2005/GreenMart/issues/515) | [bug][POS/Auth] Cache idempotency trả đơn sau khi quyền chi nhánh hoặc vai trò đã bị thu hồi | OPEN | @t-boai |
| [#516](https://github.com/NguyenDokhai2005/GreenMart/issues/516) | [bug][API Pagination] Offset vượt int32 gây HTTP 500 ở products customers và promotions | OPEN | @t-boai |
| [#517](https://github.com/NguyenDokhai2005/GreenMart/issues/517) | [change][Reporting] Giữ doanh thu bán lịch sử khi trả hết đơn và trừ hoàn theo ngày duyệt | OPEN | @hongthienhao |
| [#518](https://github.com/NguyenDokhai2005/GreenMart/issues/518) | [change][Reporting] Xử lý tổng tiền vượt int64 bằng lỗi validation rõ ràng thay vì 409 chung | OPEN | @hongthienhao |

Nguồn danh mục: issue do tài khoản Huynh-Phuc-Dat tạo trong repository NguyenDokhai2005/GreenMart. Các task/PR của người khác được dẫn riêng trong bản tổng hợp khi có quyết định liên quan. Những lỗi thuần validation/hồi quy được giữ trong bảng để tránh bỏ sót lịch sử, không biến thành chính sách mới.
