# Báo cáo cá nhân SV04 — Frontend Sales

**Tài khoản thực hiện:** `@voxuanvy611-sys` · **Mốc tổng hợp:** 20/11/2026 · **Dự án:** GreenMart DT03.

## Vai trò và phạm vi

SV04 phụ trách frontend bán hàng: ứng dụng React/TypeScript, API client theo OpenAPI, đăng nhập và phân quyền, POS, sản phẩm/khách hàng, đơn hàng và các trạng thái giao diện. Công việc tuân theo SRS, thiết kế Figma đã duyệt và hợp đồng `docs/api/openapi.yaml`. Chương mô tả chức năng nằm tại [SV04-giao-dien-ban-hang.md](SV04-giao-dien-ban-hang.md) trong PR #439.

## Kết quả đã merge vào `develop`

| Issue | Kết quả | PR |
| --- | --- | --- |
| #68–#69 | Khởi tạo React/Vite, cấu hình kiểm tra code, API client sinh kiểu và Prism mock | #205, #225 |
| #70–#72 | Đăng nhập/session/RBAC/chọn chi nhánh, layout POS, màn sản phẩm và khách hàng | #246, #262, #264 |
| #73–#74 | Quét/tìm sản phẩm, giỏ và tồn khả dụng; tìm/gắn/tạo khách theo số điện thoại | #371, #374 |
| #75–#77 | Thanh toán tiền mặt/thẻ/QR với Idempotency-Key, kiểm tra form, hóa đơn từ phản hồi API | #376, #375, #408 |

Các PR trong bảng có trạng thái **MERGED** theo GitHub tại mốc tổng hợp. Điều đó chứng minh code đã vào nhánh tích hợp; từng tiêu chí nghiệp vụ vẫn cần bằng chứng chạy với backend thật khi nghiệm thu hệ thống.

## Công việc tuần 4 đang qua review

| Issue | Bàn giao hiện có | Kiểm chứng và giới hạn |
| --- | --- | --- |
| #78 | PR #411: khuyến mại theo dòng và cấp đơn | CI xanh; còn review và kiểm tra dữ liệu API/ảnh 1440 px |
| #79 | PR #413: thiếu tồn, đổi tổng, thanh toán lỗi và khôi phục nút in hóa đơn sau merge | CI/QA POS Newman xanh; chờ review và xác nhận lại Figma đã cập nhật |
| #80 | PR #435: lọc danh sách đơn, URL và phân trang | 384 test frontend local, CI xanh; Chrome 1440 px hiển thị 1.766 đơn WEB từ API thật; chờ review |
| #81 | PR #436: chi tiết đơn online, hoàn tất/hủy theo vai trò/trạng thái | 382 test frontend local, CI xanh; GET chi tiết và hai thao tác Hoàn tất/Hủy chạy với API thật trong QA cô lập; chờ review và tích hợp #80 |
| #83 | PR nháp #437: POS và đơn hàng ở 360 px, thao tác bàn phím | 384 test frontend local; Chrome/Prism mock xác nhận không cuộn ngang trang và hoàn tất một đơn bằng bàn phím |
| #84 | PR nháp #438: kiểm tra quyền, ngăn thao tác cũ khi đổi đơn hoặc nhận 409 | 392 test frontend local; ma trận quyền theo OpenAPI, chờ CI/review và API thật |
| #85 | PR nháp #439: chương giao diện bán hàng | Đối chiếu SRS, OpenAPI, code và PR; tài liệu chưa merge |

Mỗi issue có nhánh và PR riêng. PR #437 xếp sau #435 vì dùng màn danh sách đơn của #80; PR #438 xếp sau #436 vì sửa trang chi tiết của #81. Các PR này phải được retarget về `develop` sau khi nhánh phụ thuộc merge. Không issue nào trong bảng này được coi là hoàn thành theo Definition of Done trước review, CI xanh và merge.

## Việc còn phụ thuộc

- #82 (màn trả hàng): backend PR #410 và hợp đồng `/returns` chưa merge vào `develop` ở mốc tổng hợp. Frontend chưa được viết trên endpoint chưa có trong OpenAPI chính thức.
- #418: quy tắc khách ngừng hoạt động/cấm gắn `customerId` vào POS cần BA/backend thống nhất.
- #425: khuyến mại `ITEM-AMOUNT`, `minQuantity` và phạm vi danh mục cần cập nhật hợp đồng/logic do Lead quyết định.
- #78–#84 còn cần review và so ảnh desktop với thiết kế đã duyệt. #80/#81 đã chạy với API thật trong QA cô lập; các trường hợp còn lại dùng mock/Prism chỉ chứng minh hình dạng API và luồng UI với dữ liệu tĩnh.

## Bằng chứng và cách kiểm tra lại

Lệnh dùng trên frontend: `npm run typecheck`, `npm run lint`, `npm run format:check`, `npm test -- --run`, `npm run build`. CI của mỗi PR kiểm tra thêm việc sinh `src/api/schema.ts` từ OpenAPI và các job liên quan. Với #83, Chrome ở 360 × 780 px ghi `document.documentElement.scrollWidth === 360` cho `/pos` và `/operations/orders`; luồng F2 → quét mã → Enter → F4 → thanh toán bằng Tab/Enter kết thúc ở hóa đơn thành công. Với #80/#81, Chrome 1440 px đọc danh sách/chi tiết từ backend QA cô lập; hai đơn WEB `CONFIRMED` ID 10201/10202 được hoàn tất/hủy bằng UI và GET sau thao tác xác nhận `COMPLETED`/`CANCELLED`. Ảnh kiểm tra được giữ trong môi trường làm việc cục bộ; PR tương ứng ghi lệnh và kết quả. Chưa có phép đo POS ba món dưới 30 giây bằng API thật.

## Tự đánh giá và bước tiếp theo

Phần đã merge tạo được nền tảng bán hàng POS có kiểm tra kiểu dữ liệu và xử lý lỗi/idempotency. Phần danh sách/chi tiết đơn và responsive đã có code, test, PR để review nhưng còn ở nhánh riêng. Ưu tiên tiếp theo là nhận review và xác nhận thiết kế, hợp nhất các PR theo thứ tự phụ thuộc, chạy luồng với backend thật, sau đó mới đóng issue kèm bằng chứng. Khi `/returns` được Lead merge vào OpenAPI, triển khai #82 trên nhánh riêng và cập nhật báo cáo theo kết quả thực tế.
