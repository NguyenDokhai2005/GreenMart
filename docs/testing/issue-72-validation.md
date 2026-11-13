# Issue #72 — Products & Customers: kiểm tra sau khi tích hợp Operations

## Phạm vi

- CASHIER mở `/pos/products` và `/pos/customers` từ menu POS; dùng lại `ProductsPage` và `CustomersPage`.
- OWNER, BRANCH_MANAGER, STOREKEEPER mở `/operations/products` và `/operations/customers` từ menu Operations #88. Hai trang nằm trong `OperationsLayout`, dùng `AuthBoundary` và `BranchBoundary` hiện có.
- OWNER có thao tác thêm/sửa sản phẩm và giá; CASHIER không thấy giá vốn hay thao tác sửa sản phẩm. Quyền khách hàng giữ theo `catalogPolicy.ts`.
- Đã bỏ `CatalogWorkspace` và route `/catalog/*`; không có menu nào trỏ về route cũ.
- Không thay đổi request/response của API hoặc component dùng chung.

## Kiểm tra

- `npm run typecheck`: PASS, bao gồm test `tests/features/catalog/catalog.test.tsx` trong `tsconfig.app.json`.
- `npm test -- --run tests/features/catalog/catalog.test.tsx`: 37/37 PASS. Bao gồm click menu Operations cho OWNER, BRANCH_MANAGER, STOREKEEPER; route POS cho CASHIER; quyền chỉnh sửa; tìm kiếm, phân trang, form và lỗi.
- `npm test -- --run`: 251/251 PASS trên 27 file (đã cập nhật test POS từ placeholder sang trang thật).
- `npm run lint`, `npm run build`: PASS. Cần chạy lại các lệnh này trên nhánh PR sau khi đồng bộ `develop`.
- Chưa kiểm thử backend thật ở lần sửa route này; test integration dùng tài khoản demo và môi trường riêng, không chạy tự động.

## Bàn giao

Nhánh #72 gốc được tạo từ `e41d35a`, trong khi `OperationsLayout` nằm ở các commit mới hơn của `develop`. Bản sửa được dựng riêng trên `develop` tại `8e7dd77` để tránh ghi đè những thay đổi chưa commit trên nhánh #72. Cần đưa thay đổi vào PR phù hợp trước khi yêu cầu review lại. Không tự push, merge hoặc đóng issue.
