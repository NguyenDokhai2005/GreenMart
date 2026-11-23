# Chi tiết phát hiện tại commit đã kiểm thử

Commit `4e45b443fb25424554aae68df9a58102335d8f67`; QA riêng ngày 21/11/2026. Đọc [tóm tắt và giới hạn](README.md) trước khi diễn giải kết quả.

Phân công là người phụ trách module/cần phối hợp theo issue, không phải kết luận lỗi cá nhân. FAIL dưới đây là quan sát tại snapshot, có thể là UX/enhancement; trạng thái GitHub và kết quả retest được ghi riêng. Không khẳng định nguyên nhân gốc khi chưa điều tra.

## [#416](https://github.com/NguyenDokhai2005/GreenMart/issues/416) — Callback PAID phát lại được cho đơn khác

- **Phân loại / mức đề xuất:** Lỗi đã tái hiện; High. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV03 @Dat9161 theo issue hiện tại. **Phối hợp:** SV07 @thanhcode118; SV02 @t-boai.
- **Tái hiện:** Tạo hai đơn QA; ký PAID cho A; giữ nguyên body và HMAC, gửi sang đường dẫn B; kiểm tra đơn/payment/refund.
- **Mong đợi:** Chữ ký gắn danh tính đơn và sự kiện; đơn B không được chuyển trạng thái bằng thông điệp của A.
- **Thực tế tại 4e45b44:** Cùng body/HMAC được dùng cho order A và B; cả hai HTTP200 CONFIRMED, hai payment; hủy B tạo REFUNDED. Đây là mô phỏng QA, chưa chứng minh thất thoát tiền tại cổng thật.
- **Hướng xử lý / retest:** Ràng buộc orderId/eventId trong dữ liệu ký, chống replay và kiểm tra gateway reference; migration/contract cần Lead duyệt. Chạy lại ca liên quan trên commit sửa; kiểm tra cả control, DB và quyền; không chỉ HTTP200.
- **Bằng chứng:** payment-replay-results.json; số liệu chọn lọc tại `summary.json`.

## [#384](https://github.com/NguyenDokhai2005/GreenMart/issues/384) — Outbox tồn tại nhưng cập nhật kênh chậm sau phục hồi

- **Phân loại / mức đề xuất:** Lỗi đã tái hiện; High. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV07 @thanhcode118. **Phối hợp:** SV03 tồn kho; SV02 giao dịch.
- **Tái hiện:** Dùng own QA, tạo sự kiện có trace; dừng riêng broker QA 5 phút; khởi động lại; theo dõi trace/outbox và trạng thái kênh.
- **Mong đợi:** Sự kiện được gửi lại và kênh hội tụ trong cửa sổ phục hồi đã thỏa thuận.
- **Thực tế tại 4e45b44:** Positive control callback FAIL; sau broker outage khoảng 5 phút, outbox theo dõi chưa SENT trong 90 giây và hai kênh chưa nhận trong 60 giây; hàng PENDING vẫn còn.
- **Hướng xử lý / retest:** Điều tra backlog publisher/consumer, routing, callback URL và retry; xác định SLA bằng trace. Không xóa backlog để làm test xanh. Chạy lại ca liên quan trên commit sửa; kiểm tra cả control, DB và quyền; không chỉ HTTP200.
- **Bằng chứng:** integration-resume-results.json; db-e2e-and-delivery-observation.json; số liệu chọn lọc tại `summary.json`.

## [#406](https://github.com/NguyenDokhai2005/GreenMart/issues/406) — Promotion nhận phần tử null trả HTTP500

- **Phân loại / mức đề xuất:** Lỗi đã tái hiện; Medium. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV02 @t-boai. **Phối hợp:** SV08 QA.
- **Tái hiện:** Đăng nhập role hợp lệ; dùng promotion draft riêng; gửi từng mảng có phần tử null qua POST và PUT.
- **Mong đợi:** Validation400 có mã lỗi rõ ràng, không đổi dữ liệu khi input sai.
- **Thực tế tại 4e45b44:** Sáu request POST/PUT cho channels, productIds, branchIds chứa null trả INTERNAL_SERVER_ERROR500.
- **Hướng xử lý / retest:** Validate phần tử collection và map validation exception về400; thêm test6biến thể và control hợp lệ. Chạy lại ca liên quan trên commit sửa; kiểm tra cả control, DB và quyền; không chỉ HTTP200.
- **Bằng chứng:** business-results.json; số liệu chọn lọc tại `summary.json`.

## [#442](https://github.com/NguyenDokhai2005/GreenMart/issues/442) — Tìm sản phẩm không dấu không khớp

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Medium. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV02 @t-boai. **Phối hợp:** SV04 UI POS/Products.
- **Tái hiện:** Gọi /products với hai từ khóa và cùng scope; so ID; tìm trên POS.
- **Mong đợi:** Tìm tiếng Việt không dấu theo yêu cầu được nhóm xác nhận trả cùng sản phẩm phù hợp.
- **Thực tế tại 4e45b44:** Trà xanh có4 kết quả; tra xanh có0 ở API sản phẩm, UI POS tái hiện.
- **Hướng xử lý / retest:** Sửa chuẩn hóa/search phía backend; frontend kiểm tra truyền query và render. Chạy lại ca liên quan trên commit sửa; kiểm tra cả control, DB và quyền; không chỉ HTTP200.
- **Ảnh mới:** [R2-UI-02A.png](images/R2-UI-02A.png)

## [#443](https://github.com/NguyenDokhai2005/GreenMart/issues/443) — Tìm tồn kho không dấu không khớp

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Medium. **GitHub khi soạn:** CLOSED; PR #473 merge, retest bản sửa NOT RUN.
- **Module:** SV03 @Dat9161. **Phối hợp:** SV05 UI kho.
- **Tái hiện:** Đăng nhập kho/manager đúng branch; gọi inventory với hai dạng từ khóa.
- **Mong đợi:** Kết quả tìm không dấu khớp theo yêu cầu tìm kiếm của dự án.
- **Thực tế tại 4e45b44:** API inventory: tra xanh0, Trà xanh4; UI cùng hiện tượng.
- **Hướng xử lý / retest:** Xử lý chuẩn hóa tìm kiếm inventory và thêm regression tiếng Việt. Chạy lại ca liên quan trên commit sửa; kiểm tra cả control, DB và quyền; không chỉ HTTP200.
- **Ảnh mới:** [R2-UI-02C.png](images/R2-UI-02C.png)

## [#467](https://github.com/NguyenDokhai2005/GreenMart/issues/467) — F4 chỉ chuyển focus, chưa mở thanh toán

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV04 @voxuanvy611-sys. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Giỏ có sản phẩm → nhấn F4; đối chứng bấm nút Thanh toán bằng chuột.
- **Mong đợi:** Nếu Lead chốt F4 là mở thanh toán thì phải mở cùng hộp như click; hiện click hoạt động. Đây là enhancement kỳ vọng shortcut.
- **Thực tế tại 4e45b44:** R2-UI-27: Nhấn F4: focus nút Thanh toán; không có form thu tiền. Enhancement #467, không phải API thanh toán hỏng.
- **Hướng xử lý / retest:** Chốt hành vi F4, thống nhất nhãn nút và shortcut; kiểm tra khi focus ô tìm kiếm hoặc trường nhập, tránh gửi thanh toán lặp.
- **Ảnh mới:** [R2-UI-27.png](images/R2-UI-27.png)

## [#455](https://github.com/NguyenDokhai2005/GreenMart/issues/455) — Tiền mặt nhập dấu phân cách bị từ chối

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV04 @voxuanvy611-sys. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Giỏ hợp lệ → click Thanh toán → nhập tiền khách đưa 62.000; đối chứng 62000.
- **Mong đợi:** Theo đề xuất UX, chấp nhận hoặc chuẩn hóa dấu phân cách hợp lệ; vẫn tính tiền thừa đúng và không chấp nhận giá trị mơ hồ.
- **Thực tế tại 4e45b44:** R2-UI-13: 62.000 báo Nhập số tiền nguyên hợp lệ; Hoàn tất bị khóa
- **Hướng xử lý / retest:** Chốt định dạng tiền Việt Nam, normalize trước validate; test 62000, 62.000, giá trị nhỏ hơn tổng, âm và ký tự sai.
- **Ảnh mới:** [R2-UI-13.png](images/R2-UI-13.png)

## [#462](https://github.com/NguyenDokhai2005/GreenMart/issues/462) — Hóa đơn thiếu ô tìm mã đơn/SĐT

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV04 @voxuanvy611-sys. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Mở Hóa đơn ở POS → kiểm tra công cụ tìm mã đơn/SĐT, đối chiếu phân trang đang có dữ liệu.
- **Mong đợi:** Có khả năng tìm hóa đơn theo phạm vi được phân quyền nếu nhóm duyệt phạm vi này; không tự yêu cầu endpoint ngoài OpenAPI.
- **Thực tế tại 4e45b44:** R2-UI-21: Danh sách và phân trang có dữ liệu thật nhưng không có ô tìm kiếm
- **Hướng xử lý / retest:** Chốt filter API/UI rồi triển khai; test mã đúng/sai, SĐT, phân trang và khác chi nhánh.
- **Ảnh mới:** [R2-UI-21.png](images/R2-UI-21.png)

## [#445](https://github.com/NguyenDokhai2005/GreenMart/issues/445) — Giỏ mất khi chuyển Hóa đơn rồi quay lại

- **Phân loại / mức đề xuất:** Lỗi/thiếu giao diện đã quan sát; High. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV04 @voxuanvy611-sys. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Đăng nhập thu ngân, thêm một sản phẩm vào giỏ chưa thanh toán → mở Hóa đơn → trở lại Bán hàng. Sau khi sửa, thử thêm reload và đổi user/branch.
- **Mong đợi:** Giỏ đang bán được giữ theo chính sách nhóm; không lẫn giỏ giữa người dùng/chi nhánh. Nếu rời trang làm mất giỏ phải có xác nhận rõ.
- **Thực tế tại 4e45b44:** R2-UI-01: Đã thêm 1 sản phẩm, chưa thanh toán; quay lại POS thấy 0 loại hàng
- **Hướng xử lý / retest:** Quản lý trạng thái/lưu giỏ theo phiên, user và branch; regression chuyển tab, reload, logout và thanh toán xong.
- **Ảnh mới:** [R2-UI-01.png](images/R2-UI-01.png)

## [#453](https://github.com/NguyenDokhai2005/GreenMart/issues/453) — Ctrl Alt 2 bị bỏ qua khi focus ô tìm kiếm

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV04 @voxuanvy611-sys. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Focus ô tìm sản phẩm POS → nhấn Ctrl+Alt+2; so URL và tab được chọn.
- **Mong đợi:** Nếu shortcut được công bố hỗ trợ lúc focus ô tìm kiếm, chuyển sang Hóa đơn; không cản nhập liệu hoặc mất giỏ.
- **Thực tế tại 4e45b44:** R2-UI-11: URL vẫn /pos, không chuyển Hóa đơn
- **Hướng xử lý / retest:** Thống nhất điều kiện bắt phím; test có/không focus input và cả chuyển trang giữ giỏ.
- **Ảnh mới:** [R2-UI-11.png](images/R2-UI-11.png)

## [#446](https://github.com/NguyenDokhai2005/GreenMart/issues/446) — Quản lý không có link POS trong menu Operations

- **Phân loại / mức đề xuất:** Lỗi/thiếu giao diện đã quan sát; High. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Đăng nhập quanly.gm001 → tìm lối vào POS trong menu và menu tài khoản; đối chứng mở trực tiếp /pos.
- **Mong đợi:** Manager có quyền POS phải có lối điều hướng phù hợp; việc thiếu link không đồng nghĩa endpoint chặn sai.
- **Thực tế tại 4e45b44:** R2-UI-03A: Menu quản lý và tài khoản chưa có đường sang POS, dù role có quyền endpoint POS
- **Hướng xử lý / retest:** Thêm link theo role đã chốt; test manager vào POS, cashier và role không có quyền.
- **Ảnh mới:** [R2-UI-03A.png](images/R2-UI-03A.png)

## [#97](https://github.com/NguyenDokhai2005/GreenMart/issues/97) — Dashboard còn số liệu mock

- **Phân loại / mức đề xuất:** Lỗi/thiếu giao diện đã quan sát; High. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Đăng nhập Operations → mở Tổng quan → đọc nhãn dữ liệu mẫu và các chỉ số; đối chiếu giao dịch QA thật.
- **Mong đợi:** Chỉ số và cảnh báo phục vụ nghiệm thu phải lấy dữ liệu thật; nếu chưa tích hợp phải thể hiện rõ chưa hoàn thành, không coi số mẫu là doanh thu.
- **Thực tế tại 4e45b44:** R2-UI-04A: 17745 đơn, 1.029.085.000 đ; giao diện ghi rõ dữ liệu mock cố định chưa nối API
- **Hướng xử lý / retest:** Tích hợp dashboard đúng scope chi nhánh; test doanh thu và vòng đời cảnh báo dưới/trên ROP theo AC-06.
- **Ảnh mới:** [R2-UI-04A.png](images/R2-UI-04A.png)

## [#460](https://github.com/NguyenDokhai2005/GreenMart/issues/460) — Bộ lọc trạng thái hiển thị placeholder khi đang tải tất cả

- **Phân loại / mức đề xuất:** UX hoặc enhancement đã quan sát; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Mở Phiếu nhập/xuất → để bộ lọc trạng thái mặc định → so chữ placeholder với danh sách có nhiều trạng thái.
- **Mong đợi:** Giá trị hiển thị phản ánh bộ lọc thực tế; nếu đang tải tất cả thì ghi Tất cả.
- **Thực tế tại 4e45b44:** R2-UI-19A: Trạng thái=Vui lòng chọn, danh sách vẫn có đủ trạng thái
- **Hướng xử lý / retest:** Đồng bộ default value, URL query và API; test reload/back và từng trạng thái.
- **Ảnh mới:** [R2-UI-19A.png](images/R2-UI-19A.png)

## [#450](https://github.com/NguyenDokhai2005/GreenMart/issues/450) — Số lượng quá giới hạn báo tiếng Anh

- **Phân loại / mức đề xuất:** Lỗi/thiếu giao diện đã quan sát; Medium. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Tạo phiếu nhập QA → nhập số lượng 100000 → gửi; đối chiếu lỗi và dữ liệu không được lưu.
- **Mong đợi:** Giới hạn được kiểm tra rõ trên UI, thông báo tiếng Việt phù hợp; backend tiếp tục từ chối input vượt giới hạn.
- **Thực tế tại 4e45b44:** R2-UI-07: 100000 được gửi, alert must be less than or equal to 99999; không lưu phiếu
- **Hướng xử lý / retest:** Thêm upper-bound theo contract và map validation sang tiếng Việt; test max, max+1, 0, số âm và request trực tiếp.
- **Ảnh mới:** [R2-UI-07.png](images/R2-UI-07.png)

## [#452](https://github.com/NguyenDokhai2005/GreenMart/issues/452) — Phiếu IMPORT bị hiển thị như TRANSFER

- **Phân loại / mức đề xuất:** Lỗi/thiếu giao diện đã quan sát; Medium. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Đăng nhập kho/manager → mở /operations/transfers/1, trong QA ID1 là phiếu nhập NK-INIT-GM001.
- **Mong đợi:** Không trình bày phiếu IMPORT như TRANSFER; route/detail cần xác minh loại phiếu và hiển thị thông tin đúng.
- **Thực tế tại 4e45b44:** R2-UI-09: /operations/transfers/1 hiển thị Phiếu chuyển kho NK-INIT-GM001, Bước3 Nhận hàng, 990 dòng; scrollHeight141248px
- **Hướng xử lý / retest:** Kiểm loại phiếu trước render; test import/export/transfer, ID không tồn tại và khác chi nhánh; phân trang dữ liệu dài.
- **Ảnh mới:** [R2-UI-09.png](images/R2-UI-09.png)

## [#101](https://github.com/NguyenDokhai2005/GreenMart/issues/101) — Operations tràn ngang ở 360px; Bảng tồn kho 1366px cần cuộn ngang để xem cột cuối

- **Phân loại / mức đề xuất:** Lỗi/thiếu giao diện đã quan sát; Medium. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Mở Operations ở viewport360px, rồi bảng tồn ở1366px; kiểm tra chiều rộng document/table/container.
- **Mong đợi:** Trang vẫn thao tác được trên360px theo thiết kế nhóm. Bảng rộng có thể cần cuộn có chủ ý nhưng không để menu che nội dung chính.
- **Thực tế tại 4e45b44:** R2-UI-10: Viewport360, documentWidth814, table1170.75; menu chiếm phần lớn màn hình R2-UI-20: Bảng1170.75px, container1078px, cột Cảnh báo bị cắt
- **Hướng xử lý / retest:** Điều chỉnh sidebar/layout và vùng cuộn bảng; regression360/768/1366/1440, zoom và bàn phím.
- **Ảnh mới:** [R2-UI-10.png](images/R2-UI-10.png), [R2-UI-20.png](images/R2-UI-20.png)

## [#454](https://github.com/NguyenDokhai2005/GreenMart/issues/454) — Nút chọn phiếu không đủ tương phản chữ

- **Phân loại / mức đề xuất:** Lỗi/thiếu giao diện đã quan sát; Medium. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Mở Phiếu nhập/xuất → đo foreground/background của nút tab đang chọn và cỡ chữ.
- **Mong đợi:** Chữ thường14px/400 cần tương phản ít nhất4,5:1 theo WCAG AA; đo được khoảng2,716:1.
- **Thực tế tại 4e45b44:** R2-UI-12: Computed color rgb23,43,38 / background4,120,87; contrast 2.716:1, chữ14px/400 dưới4.5
- **Hướng xử lý / retest:** Đổi màu chữ/nền phù hợp design; đo lại các trạng thái active, hover, focus và disabled.
- **Ảnh mới:** [R2-UI-12.png](images/R2-UI-12.png)

## [#447](https://github.com/NguyenDokhai2005/GreenMart/issues/447) — POS quản lý thiếu thao tác giảm giá thủ công

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; High. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV04 @voxuanvy611-sys. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Đăng nhập manager → mở trực tiếp /pos → kiểm tra thao tác giảm giá thủ công trên giỏ.
- **Mong đợi:** Có thao tác giảm giá cho role được phép theo BR-19 và contract đã chốt; role không được phép không sử dụng được.
- **Thực tế tại 4e45b44:** R2-UI-03B: Không có ô/nút nhập manual discount dù quản lý vào POS thành công
- **Hướng xử lý / retest:** Tích hợp input/validation và quyền; test loại giảm, giới hạn, hóa đơn/thu tiền và request trực tiếp từ role bị cấm.
- **Ảnh mới:** [R2-UI-03B.png](images/R2-UI-03B.png)

## [#456](https://github.com/NguyenDokhai2005/GreenMart/issues/456) — SĐT có khoảng trắng bị từ chối; Tên đăng nhập không cắt khoảng trắng

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV04 @voxuanvy611-sys. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Nhập SĐT 0901 234 567 tại POS; thử đăng nhập với tên owner có khoảng trắng hai đầu rồi đối chứng owner.
- **Mong đợi:** Theo đề xuất UX, normalize khoảng trắng tên đăng nhập/SĐT; không trim password hoặc nới xác thực tùy ý.
- **Thực tế tại 4e45b44:** R2-UI-14A: 0901 234 567 báo chỉ nhận 10–11 chữ số; đề xuất usability, không phải auth bypass R2-UI-14B:  owner  + đúng mật khẩu nhận Sai tên đăng nhập hoặc mật khẩu; đối chứng owner không khoảng trắng tiếp theo
- **Hướng xử lý / retest:** Chốt chuẩn hóa input và test số có khoảng trắng/+84 theo chính sách; không coi hiện tượng này là auth bypass.
- **Ảnh mới:** [R2-UI-14A.png](images/R2-UI-14A.png), [R2-UI-14B.png](images/R2-UI-14B.png)

## [#444](https://github.com/NguyenDokhai2005/GreenMart/issues/444) — Products không trim truy vấn toàn khoảng trắng

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV04 @voxuanvy611-sys. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Mở Products → nhập ba dấu cách → Tìm kiếm; đối chứng POS tìm toàn khoảng trắng vẫn hiện gợi ý.
- **Mong đợi:** Nếu chốt trim query thì toàn khoảng trắng tương đương query rỗng; chính sách wildcard % cần quyết định riêng.
- **Thực tế tại 4e45b44:** R2-UI-15B: Ba dấu cách + Tìm kiếm: Không có kết quả phù hợp, 0/0
- **Hướng xử lý / retest:** Trim nhất quán ở caller; test rỗng/space/Unicode, phân trang và scope; không kết luận wildcard là SQL injection.
- **Ảnh mới:** [R2-UI-15B.png](images/R2-UI-15B.png)

## [#458](https://github.com/NguyenDokhai2005/GreenMart/issues/458) — Thông báo thành công không tự tắt

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Lưu một sản phẩm QA hợp lệ → đợi22giây → quan sát thông báo thành công.
- **Mong đợi:** Theo đề xuất UX, thông báo có thời hạn hoặc cơ chế đóng rõ, không gây nhầm với thao tác tiếp theo.
- **Thực tế tại 4e45b44:** R2-UI-17: Đã lưu sản phẩm thành công vẫn tồn tại sau22giây; fixture QA-R2-UI-1795201749493 chỉ trong QA
- **Hướng xử lý / retest:** Chốt timeout/accessibility; reset thông báo khi thao tác mới hoặc navigation, kiểm tra không mất lỗi cần người dùng đọc.
- **Ảnh mới:** [R2-UI-17.png](images/R2-UI-17.png)

## [#459](https://github.com/NguyenDokhai2005/GreenMart/issues/459) — Màn 403 chuyển kho vẫn đưa Thử lại

- **Phân loại / mức đề xuất:** Lỗi/thiếu giao diện đã quan sát; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Vào chi tiết chuyển kho thuộc chi nhánh không được phép → backend403 → kiểm tra khối lỗi và nút Thử lại.
- **Mong đợi:** 403 cần hướng dẫn phù hợp; nút thử lại không nên gợi ý rằng lặp request sẽ có quyền. Trang vẫn có Quay lại danh sách.
- **Thực tế tại 4e45b44:** R2-UI-18: Chi nhánh khác bị chặn đúng: Bạn không được thao tác trên chi nhánh này; UI chỉ có Thử lại
- **Hướng xử lý / retest:** Phân biệt403 với lỗi mạng/5xx; giữ backend deny, cung cấp hướng quay lại và test các loại lỗi riêng.
- **Đính chính phạm vi:** Trang có liên kết “Quay lại danh sách”; finding chỉ là “Thử lại” trong khối 403 không giải quyết thiếu quyền. Backend đã chặn đúng, không phải vượt quyền.
- **Ảnh mới:** [R2-UI-18.png](images/R2-UI-18.png)

## [#451](https://github.com/NguyenDokhai2005/GreenMart/issues/451) — Picker báo hết hàng sau chọn hết trang đầu

- **Phân loại / mức đề xuất:** Lỗi/thiếu giao diện đã quan sát; Medium. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Mở tạo chuyển kho → chọn9SKU còn tồn trong10dòng đầu → quan sát picker báo hết; tìm Yến mạch để đối chứng SKU khác vẫn có tồn40.
- **Mong đợi:** Không báo kho hết sản phẩm chỉ vì hết trang kết quả hiện tại; cho tìm/tải tiếp sản phẩm còn tồn.
- **Thực tế tại 4e45b44:** R2-UI-08A: Đã chọn9SKU còn tồn trong10dòng đầu; picker báo Kho gửi chưa có sản phẩm còn tồn để chuyển dù chưa tìm trang sau
- **Hướng xử lý / retest:** Xử lý pagination/hasMore và empty-state chính xác; test >1trang, chọn/bỏ chọn và search.
- **Ảnh mới:** [R2-UI-08A.png](images/R2-UI-08A.png)

## [#449](https://github.com/NguyenDokhai2005/GreenMart/issues/449) — Khoảng ngày đảo ngược gây lỗi kỹ thuật và retry vô ích

- **Phân loại / mức đề xuất:** Lỗi/thiếu giao diện đã quan sát; Medium. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV05 @TranVanVu2502. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Mở URL danh sách phiếu với from=22/11/2026 và to=21/11/2026 → quan sát lỗi; không coi thao tác date picker bằng IAB đã được xác minh.
- **Mong đợi:** Chỉ rõ ngày bắt đầu phải trước hoặc bằng ngày kết thúc; cho sửa bộ lọc, không lặp request sai qua Thử lại.
- **Thực tế tại 4e45b44:** R2-UI-06B: Mở URL bộ lọc from22/11>to21/11: from phải nhỏ hơn hoặc bằng to, đồng thời lỗi chung và nút Thử lại. Chưa chứng minh thao tác date picker ở IAB.
- **Hướng xử lý / retest:** Validate range trước request, giữ contract validation phía server; test đảo ngày/cùng ngày/rỗng và thao tác picker thật sau sửa.
- **Ảnh mới:** [R2-UI-06B.png](images/R2-UI-06B.png)

## [#457](https://github.com/NguyenDokhai2005/GreenMart/issues/457) — Đồng hồ đếm ngược thiếu nhãn phút giây

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV04 @voxuanvy611-sys. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Dùng tài khoản QA bị khóa theo test5lần sai → đọc mốc được thử lại và chuỗi đếm ngược.
- **Mong đợi:** Nếu hiển thị đồng hồ đếm ngược thì có nhãn phút:giây hoặc cách diễn đạt rõ; backend lockout vẫn phải được giữ.
- **Thực tế tại 4e45b44:** R2-UI-16: Thông báo có mốc02:29 21/11 rõ, nhưng phần Thử lại sau14:54 chưa ghi mm:ss/phút giây; đây là UX, khóa backend đã kiểm tra PASS.
- **Hướng xử lý / retest:** Ghi rõ đơn vị đếm ngược; test thời điểm trước/sau unlock, refresh trang và múi giờ.
- **Ảnh mới:** [R2-UI-16.png](images/R2-UI-16.png)

## [#461](https://github.com/NguyenDokhai2005/GreenMart/issues/461) — Hóa đơn thành công hiển thị giảm giá −0

- **Phân loại / mức đề xuất:** Enhancement / cần xác nhận kỳ vọng; Low. **GitHub khi soạn:** OPEN; chưa retest trên develop mới.
- **Module:** SV04 @voxuanvy611-sys. **Phối hợp:** SV08 QA; backend theo endpoint nếu cần.
- **Tái hiện:** Thanh toán giỏ không giảm giá → xem hóa đơn thành công → đối chiếu ký hiệu tiền ở POS và danh sách hóa đơn.
- **Mong đợi:** Theo đề xuất hiển thị, số0 không mang dấu âm và ký hiệu tiền nhất quán; không suy đây là tính tiền sai khi tổng/payment đúng.
- **Thực tế tại 4e45b44:** R2-UI-19B: Giảm giá đơn hàng −0đ; POS dùng đ còn trang danh sách hóa đơn dùng ₫
- **Hướng xử lý / retest:** Dùng formatter thống nhất; test0, giảm giá thật, tiền lẻ và refund với đối chiếu số liệu API/SQL.
- **Ảnh mới:** [R2-UI-19B.png](images/R2-UI-19B.png)

## Thiếu giao diện và kiểm tra bị chặn

Ba màn Khuyến mãi, Dự báo, Kiểm kê chưa tích hợp (#448), nên E2E tương ứng BLOCKED dù API được test riêng. Backend Forecast run proxy 404 là phụ thuộc/chức năng chưa xác minh, chưa tự gán người sửa. Print preview không có bằng chứng bố cục; không báo lại lỗi “in cả trang” bằng kết quả cũ.

## Bằng chứng bổ sung

Ảnh FAIL/BLOCKED được chép nguyên bản, không chỉnh nội dung. Tên file chứa ID quan sát; ảnh tĩnh không tự chứng minh toàn bộ thao tác. File thiếu ảnh hoặc ảnh chỉ thấy một vùng được hỗ trợ bởi mô tả và assertion, không thay bằng ảnh tạo mới. Timestamp JSON nguồn dùng UTC; ngày báo cáo theo Asia/Saigon.
