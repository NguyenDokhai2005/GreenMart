# Đối soát kiểm kê với hàng giữ chỗ (#421)

Phiếu ADJUST lưu tồn snapshot khi lập phiếu và số đếm khi nhập. Lưu số đếm ghi
audit `RECORD_STOCK_COUNT` trong cùng transaction; audit lỗi thì sửa phiếu cũng rollback.
Lần duyệt bị chặn không xóa số đếm hoặc audit đã lưu, không có ADJUST/outbox dở dang.

1. Đọc `GET /api/v1/stock-documents/{id}/reconciliation` bằng OWNER, BRANCH_MANAGER
   hoặc STOREKEEPER có quyền chi nhánh. Chỉ dùng cho ADJUST/DRAFT.
2. Xem `difference = countedQuantity - snapshotQuantity`,
   `projectedOnHand = onHand + difference`, `shortfall = max(0, reserved - projectedOnHand)`.
   `inventoryReady` chỉ là điều kiện tồn, không thay quyền duyệt hoặc tách người lập/duyệt.
3. Nếu thiếu: nhập thêm hàng thực bằng phiếu IMPORT hoặc người có quyền xử lý đơn
   theo luồng hủy/hoàn tiền hiện có. Không tự động chọn/hủy đơn, nhất là đơn đã thanh toán.
   Thủ kho không được cấp thêm quyền hủy đơn từ endpoint đối soát.
4. Đọc lại, rồi gọi approve như cũ. Server khóa hàng và kiểm mọi dòng trước ghi tồn;
   thông tin đọc trước đó không phải giấy phép bỏ qua kiểm tra tại thời điểm duyệt.

Ví dụ tồn3/giữ3, snapshot3, đếm2: chênh lệch−1, tồn dự kiến2, thiếu1 nên duyệt409.
Nếu nhập thêm1 hàng thực: tồn4/giữ3, áp chênh lệch−1 thành tồn3/giữ3. Số đếm cũ2
vẫn giữ; không ghi đè tồn về2 làm mất hàng mới. Nếu quản lý hủy đơn đúng quy trình để
nhả giữ3: tồn3/giữ0, duyệt thành tồn2/giữ0. Đơn đã trả tiền dùng luồng hoàn tiền có sẵn.

Mỗi SKU hiển thị tối đa20 đơn RESERVED/CONFIRMED theo id, không có dữ liệu khách hoặc
payment. Tổng số đơn và `reservationGap` xét mọi đơn, gộp dòng trùng SKU trong một đơn.
Gap khác0 cảnh báo dữ liệu không khớp: không tự đặt lại reserved, không coi danh sách
tham chiếu là sổ quản lý reservation thay thế. Xem đơn bằng API/ứng dụng được phân quyền.

Đọc dùng snapshot REPEATABLE_READ, không khóa/sửa hàng và không ghi audit chỉ vì xem.
Duyệt dùng cùng thứ tự product→inventory theo productId tăng dần như các writer kho,
giữ khóa đến commit; DB CHECK và conditional UPDATE vẫn giữ nguyên. Không thêm global
freeze hay migration. Nếu có request giữ hàng mới thắng trước, duyệt có thể409 và cần
đối soát lại; không chứng minh hoặc sửa tồn bằng SQL để ép duyệt thành công.
