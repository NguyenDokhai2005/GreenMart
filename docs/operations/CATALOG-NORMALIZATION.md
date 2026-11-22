# Chuẩn hóa danh mục và SKU kênh (#430)

## Trước tích hợp/triển khai

V19 từ #416/PR #477 đã merge vào develop và nằm trong nền của nhánh này. Flyway áp dụng V19 trước V20; không triển khai riêng V20 vào database dùng chung rồi bổ sung V19 sau. Khi develop thay đổi, rà lại toàn bộ số Flyway trước merge; không sửa checksum của migration đã triển khai. Các test nâng schema của nhánh hiện tại chạy chuỗi V18 → V19 → V20.

Chạy file `catalog-normalization-preflight.sql` trên bản sao dữ liệu bằng tài khoản quản trị. Script chỉ đọc; kết quả ba truy vấn phải rỗng. V20 lặp lại các kiểm tra và giữ khóa ghi trong giao dịch để dữ liệu không đổi giữa kiểm tra và tạo index. Bảng danh mục/mapping demo nhỏ; index được tạo trong transaction để toàn bộ schema rollback khi lỗi. Với bảng lớn, đo thời gian trên bản sao và lên lịch triển khai phù hợp.

Nếu có lỗi lịch sử: xuất ID và đối soát người phụ trách trước. Không tự gộp danh mục/xóa tham chiếu hoặc trim/đổi SKU kênh trong database đang hoạt động: mã ngoài cần thống nhất với provider và các mapping khác. Không xóa dữ liệu để migration chạy qua.

## Hành vi

- Tên danh mục mới/sửa được NFC + gộp Unicode whitespace + trim, rồi kiểm 1–120 ký tự. Database so khóa tên bằng lowercase, giữ dấu; unique theo parentId, gồm root. Khác parent được trùng; đổi parent cũng kiểm trùng. Tên lịch sử hợp lệ không bị viết lại.
- Mọi create/update/delete qua service lấy khóa giao dịch cây hiện có. Unique index `uq_category_parent_name_key` với `NULLS NOT DISTINCT` còn bảo vệ writer ngoài ứng dụng. Request trùng trả 409 CONFLICT, giữ nguyên dữ liệu. Quy tắc chống chu trình và không xóa danh mục đang dùng giữ nguyên.
- SKU WEB/MARKET được trim Unicode whitespace ngoài, giữ case, kiểm `[A-Za-z0-9][A-Za-z0-9._:-]{0,59}`. Cùng quy tắc ở API mapping, hai đường lookup và item đơn online. SKU sai trả 400 VALIDATION_ERROR; đúng dạng nhưng chưa có mapping giữ 422 UNMAPPED_SKU. SKU sản phẩm nội bộ không đổi.
- Webhook xác thực nguyên byte body trước parse/normalize; không chuẩn hóa body rồi mới kiểm chữ ký. Response/callback dùng SKU đã lưu đúng chuẩn.
- `@Validated` trên ChannelMappingController dùng cơ chế validation qua proxy và handler ConstraintViolationException/MethodArgumentNotValidException sẵn có. Không đổi global handler. Điểm này liên quan hiện tượng validation #420, nên người phụ trách #420 cần retest trên bản tích hợp để tránh sửa trùng.
- Danh mục là text tự do, không cấm chuỗi trông giống HTML bằng regex SKU. Frontend render bằng React text trong bảng và option; test kiểm không tạo thẻ img/onerror. Đây là regression DOM/jsdom, không phải bằng chứng đã tìm thấy XSS hoặc đã chạy browser thật.

## Khi cần quay lui

Migration không đổi dữ liệu cũ. Revert code không tự gỡ index/check/function. Nếu cần gỡ ràng buộc, viết migration mới theo quy trình và đánh giá writer cũ; không sửa/xóa V20 đã chạy, không gộp/đổi dữ liệu để né constraint. Khi cho phép tên trùng trở lại rồi bật lại ràng buộc, phải đối soát dữ liệu mới phát sinh.

BA/OpenAPI trong nhánh là chính sách ông đã đồng ý triển khai; vẫn cần Lead review/merge contract theo CONTRIBUTING. Không tự sửa adapter provider thật hoặc mở rộng thiết kế UI.
