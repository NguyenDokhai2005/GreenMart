# Kiểm tra ràng buộc PostgreSQL — #62

Đối chiếu mục **Ràng buộc bắt buộc** và **Chỉ mục cần có** trong [ERD](../design/erd/README.md) với các migration Flyway.

## Ràng buộc

| Hạng mục | Kết quả |
| --- | --- |
| FK trong ERD | V1 tạo FK cho danh mục, tài khoản và chi nhánh, kênh bán, đơn và trả hàng, phiếu kho, sổ kho, doanh số ngày và kết quả dự báo. Các cặp khóa ở bảng liên kết có PK ghép. |
| Chống trùng đơn và SKU kênh | V1 có `UNIQUE (channel_id, external_order_id)` trên `sales_order` và `UNIQUE (channel_id, external_sku)` trên `channel_product`. Đơn POS vẫn có thể có `external_order_id = NULL`. |
| Tồn kho | V1 chặn `on_hand < 0`, `reserved < 0` và `reserved > on_hand` bằng ba CHECK. |
| Duyệt phiếu | V1 chặn người lập tự duyệt phiếu kho và phiếu trả hàng. V4 bắt buộc thời điểm duyệt ở các trạng thái đã duyệt và kiểm tra thứ tự thời gian. Phiếu kho đã hủy có thể giữ thời điểm duyệt theo quy tắc V4. |
| Sổ kho và nhật ký thao tác | V5 chặn UPDATE/DELETE trên `inventory_transaction`. V13 bổ sung bảo vệ tương tự cho `audit_log`. Hibernate dùng `@Immutable` cho `AuditLog` để lưu bản ghi mới bằng INSERT và bỏ qua kiểm tra thay đổi sau khi lưu, kể cả trường JSON. Xóa người dùng có audit cũng bị chặn nếu FK phải xóa liên kết người thao tác trong nhật ký. |
| Tiền và phiếu xuất hủy | V1 dùng BIGINT cho tiền VND. V7 kiểm tra lý do xuất hủy, ghi chú khi chọn OTHER và giá vốn xuất hủy không âm. |

Chính sách ngừng sử dụng sản phẩm, khách hàng và tài khoản là quy tắc vòng đời nghiệp vụ. V1 có `is_active` cho sản phẩm và tài khoản, còn bảng `customer` chưa có cột này và FK của đơn dùng `ON DELETE SET NULL`. #62 ghi nhận khác biệt; thêm trạng thái khách hàng cần thống nhất cùng luồng khách hàng. Giới hạn tổng số lượng trả so với số đã mua cần kiểm tra qua các dòng trả hàng và đơn gốc tại luồng trả hàng, vì CHECK của PostgreSQL kiểm tra dữ liệu trên một dòng.

## Chỉ mục

| Thứ tự cột theo ERD | Chỉ mục sau migration |
| --- | --- |
| `sales_order (branch_id, created_at)` | V13 `idx_order_branch_created_at` |
| `sales_order (status, reserved_until)` | V13 `idx_order_status_reserved_until`. V1 vẫn có chỉ mục riêng cho đơn RESERVED. |
| `inventory_transaction (branch_id, product_id, created_at)` | V13 `idx_inv_tx_branch_product_created_at` |
| `daily_sales (product_id, branch_id, sale_date)` | V13 `idx_daily_sales_product_branch_date`. V1 có chỉ mục bắt đầu bằng chi nhánh. |
| `forecast_result (run_id, branch_id, product_id)` | V1 có UNIQUE bắt đầu bằng ba cột này, sau đó là `horizon` và `target_date`. |

V13 giữ các chỉ mục V1 và không sửa dữ liệu. Các lệnh tạo chỉ mục chạy trong transaction Flyway và giữ khóa chặn ghi trên bảng liên quan trong lúc tạo chỉ mục.

## Chạy lại kiểm thử

Trong thư mục `backend`, với JDK 21 và Docker đang chạy:

```sh
mvn -B -ntp -Dtest=SchemaConstraintsMigrationTest test
```

Test dùng PostgreSQL riêng, chạy Flyway đến V12, chèn dữ liệu mẫu, rồi nâng lên V13. Test đọc danh mục chỉ mục thực tế và chạy SQL trực tiếp. Tồn sai trả `23514` (CHECK), FK sai trả `23503` và SKU kênh trùng trả `23505`. Thử sửa hoặc xóa dòng nhật ký trả `55000`; dòng cũ được giữ và vẫn ghi thêm được dòng mới.
