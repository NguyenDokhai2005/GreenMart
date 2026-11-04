# Kênh bán và ánh xạ SKU (#130)

Schema chuẩn đã có trong V1: `sales_channel`, `channel_branch`, `channel_product`;
V3 đã tạo POS/WEB/MARKET. Không tạo lại bảng và không sửa checksum migration cũ.
`PRIMARY KEY(channel_id, product_id)` và `UNIQUE(channel_id, external_sku)` chặn
ánh xạ trùng ngay trong PostgreSQL, kể cả cập nhật đồng thời. Một SKU có thể xuất
hiện ở hai kênh khác nhau.

`ChannelCatalog` là interface để webhook #132 dùng `findChannel(code)` và
`resolveProductId(code, externalSku)`. WEB + W-SUA-TH-1L ánh xạ 205 thì kết quả là
205; thiếu ánh xạ trả `UNMAPPED_SKU` (422), không tự đoán theo SKU nội bộ.
Webhook chịu trách nhiệm ghi đơn REJECTED theo hợp đồng ở #132/#40.

`ChannelService.mapProduct` cho phép ADMIN thêm/đổi ánh xạ. `configure` cập nhật
chi nhánh mặc định, danh sách chi nhánh phục vụ và safety stock trong một transaction,
khóa dòng kênh khi thay cấu hình. Chi nhánh mặc định phải thuộc danh sách phục vụ.
Không cung cấp endpoint REST quản trị mới ngoài OpenAPI; consumer nghiệp vụ gọi
service này, endpoint quản trị bổ sung cần Lead cập nhật hợp đồng.
Service không trả/log webhook secret. Khóa HMAC dùng secrets môi trường, không ghi
giá trị thật vào seed hoặc PR; phần xác minh webhook thuộc #132.

```powershell
mvn -f backend/pom.xml -Dtest=ChannelServiceTest test
```

Testcontainers PostgreSQL + Flyway kiểm ánh xạ 205, tách kênh, thay SKU, ràng buộc
UNIQUE, cấu hình chi nhánh, sai quyền và SKU chưa ánh xạ.
