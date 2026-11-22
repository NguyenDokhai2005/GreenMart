# Khuyến mại theo số lượng (#425)

Contract được Lead chốt trong PR #505 và comment issue #425. Chỉ triển khai backend; form/hiển thị của SV04 làm theo thiết kế được Lead duyệt. Phạm vi sản phẩm vẫn là productIds, chưa có categoryIds/danh mục con.

## Cách hoạt động

1. Từng dòng SKU được kiểm ngưỡng minQuantity riêng. Bỏ trống/null với ITEM nghĩa là1; không cộng số lượng SKU khác để đủ ngưỡng. ORDER không dùng ngưỡng; gửi số minQuantity cho ORDER trả400 VALIDATION_ERROR.
2. ITEM-AMOUNT giảm value cho mỗi đơn vị; tổng giảm không vượt tiền dòng. ITEM-PERCENT tính trên tiền dòng và làm tròn xuống đồng, cap chỉ áp một lần cho cả dòng.
3. Chọn một chương trình giảm nhiều nhất cho dòng; hòa chọn hết hạn sớm, giữ tie-break ID hiện có khi vẫn hòa. Tính xong các dòng mới xét minOrder/khuyến mại ORDER. ORDER-AMOUNT vẫn giảm một lần cho cả đơn.
4. Hoàn tất POS tính lại báo giá. Mỗi campaign được ghi một lượt/đơn dù áp nhiều SKU; không nhân lượt theo số lượng. Khi chờ khóa campaign, kiểm lại trạng thái/thời hạn/lượt và ngưỡng cho dòng nhỏ nhất đang hưởng campaign. Không đủ nữa trả TOTAL_CHANGED và rollback toàn bộ giao dịch.
5. Snapshot của đơn đã bán, tiền thanh toán/hoàn tiền và usedCount lịch sử không bị tính lại. Chính sách mới áp ở lần tính báo giá/hoàn tất mới, không sửa giá trị campaign cũ hoặc nhân lại các dòng lịch sử.

Ví dụ cùng bảng BA của Lead: rau18.000/đơn vị, KM-AMOUNT5.000 với minQuantity3; KM-PERCENT15%, trần6.000/dòng.

| Số lượng | AMOUNT | PERCENT | Chọn |
|---|---|---|---|
| 1 | Chưa đủ | 2.700 | PERCENT |
| 2 | Chưa đủ | 5.400 | PERCENT |
| 3 | 15.000 | 6.000 | AMOUNT |

Tính trung gian bằng BigInteger, chặn theo tiền dòng trước khi chuyển về long để value×quantity hoặc subtotal×percent không tràn số. Tổng tiền dòng vẫn phải nằm trong giới hạn tiền hiện có của API.

## Dữ liệu và tích hợp

- V21 thêm min_quantity nullable và CHECK: chỉ ITEM được có số≥1. NULL là biểu diễn ngưỡng mặc định1 của ITEM cũ; query dùng COALESCE và response ITEM trả1. ORDER giữnull. API mới lưu1 khi ITEM bỏ trống. Không UPDATE bảng hóa đơn/counter hoặc đổi SQL seed để tính lại lịch sử.
- V20 dành cho #430/PR #503, cần tích hợp/triển khai V20 trước V21; không bật Flyway outOfOrder để bỏ qua thứ tự. Rà lại các số migration trên develop trước merge. PR #505 contract phải được tích hợp trước bản code này.
- Seed và các writer cũ bỏ trống cột mới vẫn hoạt động, mặc định ngầm1 cho ITEM. Client cũ không gửi field mới vẫn dùng được. minQuantity nhận integer đúng kiểu, không tự cắt số thập phân/chuỗi số theo mẫu validation hiện có.
- Revert code không tự gỡ schema; nếu cần quay lui schema, viết migration mới. Không sửa migration đã chạy hoặc cập nhật usedCount/giá lịch sử để làm test đạt.

## Vị trí chính và kiểm tra

- PromotionRequest/Response, PromotionCrudServiceImpl, Promotion: API, validation, default và lưu/đọc ngưỡng.
- DefaultPromotionCalculator, PromotionDiscounts: lọc ngưỡng và tính/chọn giảm giá.
- PromotionService, PosOrderServiceImpl: một lượt/campaign/đơn và kiểm ngưỡng sau khi chờ khóa.
- PromotionQuantityIntegrationTest: API/quote/complete, biên ngưỡng/cap, từng SKU, counter và rollback, tranh chấp ngưỡng.
- PromotionQuantityMigrationTest: nâng schema giữ snapshot cũ và ràng buộc DB; PromotionDiscountsTest kiểm toán tiền độc lập.

Không thay cấu hình Jackson toàn backend, thứ tự làm tròn, UI, nghiệp vụ trả hàng hoặc thuật toán forecast trong issue này.
