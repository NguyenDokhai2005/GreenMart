# Issue #72 — Sản phẩm và khách hàng

Ngày kiểm chứng: 07/11/2026. Nhánh `feature/72-products-customers`.

## Baseline và phạm vi

Tạo từ `origin/develop` sau khi fetch: `e41d35a94c95abc42640bcce165368505eaa0cb4`.
Không lấy commit Draft #71 làm baseline. PR #262 vẫn chưa merge ở thời điểm bắt đầu;
nhánh #71 giữ nguyên commit `16f52f9a46f055c0c2c85eec88e45248cf3ffe5d`.
Không commit, push hoặc tạo PR cho #72.

Nguồn: Issue #72/#172, CONTRIBUTING, frontend README, SRS §3.2/3.3/8.1/8.2,
ma trận quyền trong business-analysis, docs/api/openapi.yaml.
Issue #172 đã đóng completed; comment đọc được chỉ chứa link gửi Lead,
không tự nhận có comment “Đã duyệt”.

## Thiết kế đã đọc và giới hạn

File chung: https://www.figma.com/design/m5cwuanAuUjX6QaTzrRCvP/frontend

- Section #172: node `489:6335`.
- Products: `489:6336`; OWNER normal 1440: `489:6337`.
- Customers: `489:6528`; Forms: `489:6605`.
- Đã quan sát trực tiếp bằng Chrome các màn sản phẩm OWNER/CASHIER, danh sách khách,
  nhóm form, states và prototype. OWNER có Thêm/Sửa và menu ngang; CASHIER chỉ xem sản phẩm,
  không giá vốn, dùng POS sidebar. Khách có tìm tên/SĐT, phân trang và thêm/sửa theo quyền.
- MCP hết hạn mức; người dùng đã đồng ý phương án Chrome. Chưa kiểm chứng toàn bộ token,
  từng frame form/error hoặc prototype interaction. Không khẳng định pixel-perfect.
- Route `/catalog/products`, `/catalog/customers` do người dùng duyệt làm phương án kỹ thuật,
  chưa phải quyết định Lead. Navigation riêng chỉ liên kết các route thật đã có; không tạo
  chức năng Bán hàng/Đơn online/Trả hàng giả để sao chép menu OWNER trong Figma.
- CASHIER trên route catalog đang dùng khung danh mục độc lập. Chưa tích hợp sidebar POS
  của #71 vì PR đó chưa merge; không coi đã khớp frame CASHIER hoàn toàn.

## Ma trận yêu cầu → code → kiểm chứng

| Yêu cầu                                  | Code                                                      | Kiểm chứng / kết quả                                                                                                                                 |
| ---------------------------------------- | --------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| Tìm sản phẩm tên/SKU/barcode, phân trang | ProductsPage, catalogApi, useCatalogSearch                | Unit và HTTP/backend thật PASS cho cả ba loại tìm và trang 0/1                                                                                       |
| CASHIER không thấy giá vốn/nút sửa       | catalogPolicy, cột Table và guard form                    | Test vẫn ẩn khi response fixture chứa giá vốn; API thật trả null; Chrome xác nhận không có thao tác sửa                                              |
| Chỉ OWNER lưu giá, giá mới hiển thị      | CatalogEditor → PUT ProductRequest → tải lại danh sách    | Unit và UI integration thật PASS. Đổi tạm giá demo +1 đồng, GET xác nhận, finally khôi phục và GET kiểm tra giá gốc                                  |
| Tìm/lọc/phân trang khách                 | CustomersPage                                             | Unit PASS. Tìm theo SĐT từ seed qua API thật và render UI PASS. Danh sách không lọc đang bị backend 500                                              |
| Thêm/sửa sản phẩm và khách               | CatalogEditor, catalogForm                                | Form theo contract, không xóa cứng; unit kiểm tra validation, lỗi field 400 và trùng SĐT 409 giữ dữ liệu. Chưa kiểm thử tạo bản ghi mới trên DB thật |
| Phân quyền ghi                           | canEditProducts/canEditCustomers                          | OWNER ghi sản phẩm; OWNER/BRANCH_MANAGER/CASHIER ghi khách; Admin/Storekeeper chỉ xem theo contract. Không mở rộng POS_ROLES                         |
| Loading/empty/error                      | CatalogStates, useCatalogQuery                            | Im lặng dưới 300 ms; Skeleton sau 300 ms; 15 giây abort GET và ErrorState + Thử lại; không hiện Empty/Table rỗng trước response                      |
| Lỗi cục bộ                               | Query riêng categories/list, ErrorState message + traceId | Lỗi danh mục không chặn tra cứu; filter và dữ liệu nhập vẫn dùng được khi API khối lỗi                                                               |
| 404/lỗi render/session                   | CatalogWorkspace, #70 AuthBoundary                        | URL lạ 404; lỗi render dùng shared 500 trong khung, menu còn dùng được; hết phiên login giữ path/search/hash; logout bỏ response ghi cũ              |
| Response cũ/gửi trùng                    | useCatalogQuery và CatalogEditor                          | Abort/unmount + bỏ response cũ; khóa gửi lặp; timeout ghi không tự replay và yêu cầu kiểm tra danh sách trước khi gửi lại                            |
| Điều hướng trực tiếp/refresh             | AppRoutes, routePolicy; search params                     | Unit và Chrome refresh giữ từ khóa. Không đổi route hoặc guard POS/Operations/Admin hiện có                                                          |

Khối lỗi danh sách dùng ErrorState (scope block), không thay cả trang bằng 500 chỉ vì một GET lỗi.
Shared special 404 dùng cho URL không tồn tại, 500 cho lỗi render và session-expired theo #70.
Mất mạng của GET cũng là lỗi khối, có retry; không tự tạo endpoint hoặc dữ liệu thay thế.

## Kết quả kiểm thử

- Typecheck, lint, build: PASS.
- `npm test -- --run`: 131/131 tests, 11 file discovery thực sự = 97 baseline + 34 catalog.
- `npm run test:auth-integration`: **10 PASS, 1 FAIL / 11 tests / 2 file**.
  Auth regression cũ 7/7 PASS; catalog 3/4 PASS. Suite này vẫn exit 1 đúng vì lỗi backend bên dưới.
- Không bỏ test lỗi, không đổi expected result thành 500 và không ghi toàn bộ integration PASS.
- Ba file SV05 trong src/hooks, src/lib và src/components/ui/tsx vẫn không được unit config discovery.
  Không tính chúng PASS và không sửa cấu hình discovery hoặc AsyncBlock.
  Các test #71 nằm trên nhánh/PR riêng, chưa có trong baseline develop của #72.
- Unit dùng providers thật, HTTP fixture kiểm soát. Integration dùng backend localhost:8080,
  không Prism; dữ liệu/mật khẩu demo lấy từ tài liệu, không ghi token vào artifact.

## Blocker backend cần bàn giao

Request đã tái hiện độc lập với UI bằng tài khoản CASHIER demo:

```http
GET /api/v1/customers?page=0&size=3
```

Trả HTTP 500, code `INTERNAL_SERVER_ERROR`, message
“Đã có lỗi hệ thống xảy ra, vui lòng thử lại sau”. Trace mẫu:
`11d44083-3d1c-45b0-9df6-30d6665e4335`.
Thêm `sort=id,asc` cũng 500. Request có q trả 200; test tìm theo SĐT từ seed đã render thật thành công.
Chưa kết luận nguyên nhân nội bộ hoặc sửa backend. Không thêm q giả vào request không lọc để né lỗi.
Đề nghị Lead/SV02 kiểm tra endpoint không có q và chạy lại integration sau khi sửa.

## File thêm/sửa và lý do

| File                                                      | Thay đổi                                                                                 |
| --------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| frontend/src/features/auth/AppRoutes.tsx                  | Chỉ thêm route catalog trong AuthBoundary cũ; không đổi thân guard hoặc mount point khác |
| frontend/src/features/auth/routePolicy.ts                 | Đăng ký catalog vào return-path policy để login lại giữ URL; quyền route cũ giữ nguyên   |
| frontend/src/features/catalog/catalogPolicy.ts            | Tập trung route và quyền theo contract                                                   |
| frontend/src/features/catalog/catalogApi.ts               | Adapter gọi request() hiện có, types từ schema sinh sẵn                                  |
| frontend/src/features/catalog/useCatalogQuery.ts          | Abort, timeout GET và chặn response cũ                                                   |
| frontend/src/features/catalog/useCatalogSearch.ts         | Draft input, debounce 300 ms, URL filters/paging và POP history                          |
| frontend/src/features/catalog/ProductsPage.tsx            | Danh sách/tìm/lọc sản phẩm, nút OWNER và mở editor                                       |
| frontend/src/features/catalog/CustomersPage.tsx           | Danh sách/tìm/phân trang khách, nút theo quyền                                           |
| frontend/src/features/catalog/CatalogEditor.tsx           | Form sản phẩm/khách, save có khóa, lỗi field/Toast, xác nhận Quay lại khi chưa lưu       |
| frontend/src/features/catalog/catalogForm.ts              | Validation theo ProductRequest/CustomerRequest, số nguyên đồng và SĐT 10–11 số           |
| frontend/src/features/catalog/CatalogPagination.tsx       | Số trang/dòng, previous/next và trạng thái disabled                                      |
| frontend/src/features/catalog/CatalogStates.tsx           | Adapter shared loading/empty/error; tránh empty flash của AsyncBlock mà không sửa SV05   |
| frontend/src/features/catalog/CatalogWorkspace.tsx        | Khung route danh mục độc lập, identity/logout và lỗi render                              |
| frontend/src/features/catalog/catalog.css                 | CSS trong phạm vi catalog, dùng shared tokens, không sửa shared CSS                      |
| frontend/tests/features/catalog/catalog.test.tsx          | 34 regression/UI tests thuộc #72                                                         |
| frontend/tests/integration/catalogBackend.integration.tsx | 4 test backend thật, giữ test danh sách khách đang FAIL                                  |
| docs/testing/images/issue-72/products-cashier-browser.jpg | Ảnh Chrome từ backend thật, tìm SKU và bảng sản phẩm                                     |
| docs/testing/issue-72-validation.md                       | Ma trận AC, bằng chứng, giới hạn và bàn giao                                             |

Không file bị xóa. Không sửa API client, generated schema, dependency/lockfile, test config,
AuthProvider/ActiveBranchProvider, shared components/pages/hooks, backend hoặc OpenAPI.
Các form #72 không gắn khách vào giỏ, quét mã, quote, thanh toán, khuyến mại hoặc lịch sử đơn.

## Demo và bước tiếp theo

1. Backend demo local đã chạy; không reset/seed lại database. Tại frontend: `npm run dev -- --port 5175`.
2. Mở `/catalog/products`, login CASHIER: tìm tên/SKU/barcode, lọc, phân trang, refresh.
3. OWNER mở cùng route: Sửa giá, Lưu, xác minh giá; thao tác demo thực tế cần tự khôi phục giá sau khi thử.
4. Mở `/catalog/customers`: thấy ErrorState từ backend. Nhập SĐT demo có sẵn để thử tìm kiếm;
   xóa từ khóa sẽ gọi lại request không lọc và vẫn có thể tái hiện 500.
5. Mở route catalog sai, thử Quay lại khi form chưa lưu, thử logout và đăng nhập lại.
6. Chạy quality scripts như frontend README. Integration cần GREENMART_DEMO_PASSWORD;
   **catalog integration OWNER ghi giá demo tạm thời và khôi phục trong finally**, không chạy trên môi trường dùng chung/production.

[Ảnh Chrome](images/issue-72/products-cashier-browser.jpg) là ảnh app 2880×1706 pixel,
có sidebar trình duyệt, không phải bằng chứng viewport nội dung đúng 1440 CSS px.
Chưa chụp/đối chiếu đầy đủ các màn form, lỗi và OWNER ở viewport 1440 px.

Sau #71 merge: cập nhật baseline, gắn ProductsPage/CustomersPage vào loader POS hiện có,
giữ nguyên guard/layout. Hai page import CSS riêng và không sở hữu auth/provider/POS shell,
nên tích hợp không cần viết lại nghiệp vụ. Chưa thực hiện tích hợp này trên code Draft.

Chưa đủ DoD/đóng issue: còn lỗi API khách không lọc, tích hợp POS sau #71 và review hình ảnh/route của Lead.
Không tự coi implementation đã hoàn tất hoặc đủ điều kiện merge.
