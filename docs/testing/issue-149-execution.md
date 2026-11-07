# #149 — Postman POS và Newman trong CI

Phụ trách: Huynh-Phuc-Dat (SV08 — QA/Security). Issue: https://github.com/NguyenDokhai2005/GreenMart/issues/149. Hạn trên issue: 06/11/2026; thực hiện ngày 07/11/2026.

## Yêu cầu và phạm vi

AC: mỗi PR chạy Newman cho luồng đăng nhập → quote → POS → hóa đơn; assertion/request fail phải khiến CI đỏ. Đối chiếu SRS ORD-01/ORD-07, BA §3, user story #149 và OpenAPI 2.0.0. `Order` dùng `payments[]`, `branch.id`, `code`, không dùng `payment` hoặc `branchId` ở response.

Nguồn GitHub đọc: develop `d018575944dcb1d96378b3f3d96864776a508fe1`, issue #149 và comments (chưa có comment), CONTRIBUTING, README, CODEOWNERS, PR template, CI hiện tại, SRS/BA/user stories, API contract, test plan/security checklist/câu hỏi QA và hướng dẫn secrets. #32/#33 đã đóng; #34 idempotency còn mở.

Local lúc bắt đầu: `1f9bc7afcfa741287d6058d526ff0b32af4c5643`, nhánh `feature/148-demo-accounts-qa`, chưa có API POS. Không pull/merge/đổi nhánh. Backend được build từ snapshot develop `d018575` trong `.local-tools/issue149-source`, không thay source của SV02. Chỉ fetch Git objects và giải nén snapshot vào thư mục bị Git bỏ qua.

Không sửa các file nháp có sẵn `docs/testing/pos-flow-postman-test-cases.md`, `docs/testing/demo-accounts-test-cases.md`, `scripts/run-tc-148.ps1`. Đây là bộ core cho AC #149, không nhận đã triển khai toàn bộ các test mở rộng trong bản nháp. Negative/RBAC đầy đủ thuộc #242; replay idempotency thuộc #34; mẫu 200.700đ có khuyến mại thuộc #35/#39.

## Thành phần bàn giao

- `tests/api/pos149/pos.postman_collection.json`: collection Postman v2.1, 8 request, 15 assertion, import được vào Postman.
- `package.json`/`package-lock.json`: Newman 6.2.1; dependency chỉ trong bộ QA, không đổi frontend/backend.
- `run-newman.cjs`: chạy Newman; trả exit 1 nếu assertion/script/request fail hoặc collection không chạy đủ; báo cáo JSON/JUnit đã loại request/response body, header, token và raw error.
- `test-runner.cjs`: Newman thật trên HTTP double kiểm tra success exit 0, assertion sai exit 1, hóa đơn 404 exit 1; kiểm tra vẫn logout và không ghi token vào artifact. Không phải bằng chứng backend đúng.
- `prepare-qa.py`, `compose.qa.yml`, `fixture.sql`: backend/DB/broker/Redis thật trong project riêng `greenmart-qa149`, cổng 18081, volume riêng, secrets ngẫu nhiên chỉ trong môi trường process. Refuse seed nếu project/volume đã tồn tại. Không xóa volume hoặc reset dev/QA148.
- `.github/workflows/qa-pos-newman.yml`: workflow QA mới, không sửa CI chung. Trigger PR vào develop/main hoặc workflow_dispatch; npm ci → test runner → build/seed QA → Newman thật → upload artifact đã lọc → stop riêng QA149. Không suppress exit code, không continue-on-error, không skip receipt.

Fixture thêm riêng 2 sản phẩm QA 900001/900002 sau khi nạp generator SV06 nguyên bản: giá 180.000 và 29.000, mua 1 và 2, không khuyến mại; tổng 238.000, đưa 250.000, thối 12.000. Không dùng ví dụ có KM 200.700đ khi DefaultPromotionCalculator hiện chưa áp KM. Đây là dataset kiểm thử không KM, không thay đổi kỳ vọng nghiệp vụ để tránh lỗi.

## Chạy lại

Yêu cầu: Python 3.11+, Node 22, Docker Linux containers và Compose 2.24.4+. Chỉ chạy trên QA riêng vì POS tạo đơn thật và trừ tồn thật. Không tái seed môi trường đang tồn tại; mỗi lần chạy collection tiêu thụ 1 gạo và 2 sữa từ fixture, tồn ban đầu đủ 10 lượt nếu không có giao dịch khác.

```powershell
# Từ repo root, cài dependency riêng theo lockfile
npm --prefix tests/api/pos149 ci --ignore-scripts --no-audit --no-fund
npm --prefix tests/api/pos149 test

# Chỉ trên checkout đã có backend POS hoặc source snapshot đã xác định
python tests/api/pos149/prepare-qa.py --source-root .local-tools/issue149-source

$env:QA_SOURCE_COMMIT='d018575944dcb1d96378b3f3d96864776a508fe1'
$env:QA_REPORT_DIR='D:/GreenMart/GreenMart/.local-tools/issue149-real-run'
npm --prefix tests/api/pos149 run pos

# Khi cần dừng QA149; không xóa volume
python tests/api/pos149/prepare-qa.py --stop
```

Trong CI, source mặc định là checkout của PR, không fetch develop khác để che lỗi PR. `QA_SOURCE_COMMIT` lấy từ github.sha. GitHub Actions chỉ có thể xác nhận sau khi được phép push/tạo PR và workflow thực sự chạy. Chưa mở PR cho #149 trong lần thực hiện này.

Trong Postman, import collection và đặt collection variable `baseUrl=http://127.0.0.1:18081/api/v1`, `forceFailure=false`; chạy toàn collection đúng thứ tự. Token chỉ lưu trong biến phiên khi chạy, không export collection/environment sau đăng nhập. Để thử cố ý làm test fail, dùng HTTP double qua `npm test`, không sửa source backend hoặc dữ liệu dev chung.

## Kết quả thực tế ngày 07/11/2026

Newman chạy với API/DB thật tại snapshot `d018575`: **8 request, 15 assertion, 13 PASS, 2 FAIL; exit 1**. Không có lỗi kết nối.

| Bước | HTTP | Kết quả |
|---|---|---|
| Login, `/auth/me` | 200 | Đúng cashier GM-001 và session |
| Inventory trước | 200 | Fixture có hàng |
| Quote | 200 | Tổng 238.000, không KM, đủ tồn |
| POS CASH | 201 | COMPLETED, payment PAID, thối 12.000 |
| GET `/orders/{orderId}` | **404** | **2 assertion FAIL**: HTTP và dữ liệu hóa đơn |
| Inventory sau | 200 | Trừ đúng 1 gạo |
| Logout | 204 | Đã thu hồi refresh phiên test |

Bằng chứng đã lọc: [summary JSON](results/issue-149-2026-11-07-summary.json), [JUnit XML](results/issue-149-2026-11-07-newman.xml). Thời gian UTC nằm trong JSON; ngày báo cáo theo Việt Nam UTC+7.

Kiểm tra runner: cả 3 scenario success/forced-failure/missing-receipt đạt đúng exit code, đủ 8 request và logout. Python/Node syntax check đạt. Workflow đã chuẩn bị, chưa có bằng chứng CI GitHub chạy; không gọi đó là CI xanh. Không thể hoàn tất AC end-to-end hiện tại vì thiếu API hóa đơn.

Kiểm tra lại trước commit/PR ngày 07/11/2026: cả 3 scenario runner tiếp tục đạt; chạy lại API thật trên d018575 vẫn 8 request/15 assertion, 13 PASS và 2 FAIL ở GET hóa đơn 404, Newman exit 1. Bằng chứng lần chạy lại: [issue-149-2026-11-07-recheck.json](results/issue-149-2026-11-07-recheck.json). Đề xuất Draft PR vào develop để review phần QA; chưa đủ điều kiện merge hoặc đóng #149.

## Nội dung bàn giao cho Lead/SV02 (chưa đăng GitHub)

**Tiêu đề:** [#149][ORD-07] GET /api/v1/orders/{orderId} trả 404 sau khi tạo đơn POS thành công.

**Môi trường:** QA riêng greenmart-qa149, backend develop d018575, seed SV06 + fixture QA149, CASHIER GM-001.

**Tái hiện:** đăng nhập `thungan.gm001` → quote fixture → POST /orders/pos (UUID Idempotency-Key mới) nhận 201 và orderId → dùng cùng Bearer gọi GET /orders/{orderId}.

**Mong đợi:** 200, schema Order có id/code/branch/items/payments/tổng/thu ngân theo OpenAPI và ORD-07, dữ liệu khớp đơn vừa tạo.

**Thực tế:** 404; OrderController tại snapshot chỉ có POST /quote và POST /pos, chưa có GET chi tiết. Newman exit 1; không lỗi đăng nhập, thanh toán hay tồn kho trong lần chạy này.

**Ảnh hưởng:** chặn luồng hóa đơn/AC #149; đề nghị SV02/Lead xác nhận phân công API chi tiết đơn. Không quy lỗi hồi quy cho #32 vì endpoint đọc là phần chưa triển khai.

**Đề nghị:** bổ sung endpoint đúng contract và test quyền/chi nhánh, mapping/persistence dữ liệu hóa đơn; sau đó QA chạy lại trên commit được merge. QA không tự sửa backend theo lựa chọn của người dùng. Không tự bỏ request hóa đơn, sửa expected HTTP thành 404 hoặc đặt workflow continue-on-error để làm xanh CI.
