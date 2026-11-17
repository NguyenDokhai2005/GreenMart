# Kiểm thử hệ thống ngày 17/11/2026

QA: @Huynh-Phuc-Dat (SV08). Refs #152, #153, #154, #155, #156, #157, #159, #242.
Đã chạy qua 10 nhóm; còn lỗi và phần chưa kiểm chứng, chưa nghiệm thu Done toàn hệ thống.

## Commit và môi trường

- Lượt toàn hệ thống: `27acddd4d1452b83ed160e3609347020ee8e9fbb`, sau khi pull develop fast-forward, trên nhánh `feature/system-qa-20261117`.
- Kiểm tra lại các bản sửa: snapshot `b867c671e6ab4e0df5d99b5ebe22f216bf176b3f`. Đây là lượt kiểm tra tập trung, không thay thế full regression.
- Khi xuất bản ban đầu, origin/develop đã tới `b236417`; số liệu lịch sử dưới đây không phải full regression trên commit đó.
- Dùng Docker QA riêng, PostgreSQL, Redis và RabbitMQ thật. Kênh bán và thanh toán được giả lập theo phạm vi dự án. Backend giới hạn 768 MiB, JVM 512 MiB và 2 CPU.
- Đã dừng các stack QA của lượt chạy, giữ dữ liệu và volume. Không reset hoặc thử nghiệm phá dữ liệu trên môi trường chung.

## Kết quả và giới hạn của lượt chạy ban đầu

| Nhóm | Kết quả đã chạy | Lỗi / giới hạn |
|---|---|---|
| Build, unit và contract | Maven verify: 488 PASS. Frontend: 260 PASS; typecheck, lint, format và build đạt. Connector: 8 PASS. API types khớp baseline. | LINE coverage: Sales 85,50 %, Inventory 96,26 %. Không phải coverage mọi hành vi. |
| Auth, security và audit | RBAC #152: 764 PASS. #242: 60 PASS và 2 ca bổ sung PASS. #155 chạy lại: 28 PASS. | Ca ADMIN-read có expected 200 xung đột với quyết định HTTP 403 của Lead ở #259: BLOCKED_SPEC. OWNER login trả 500 ban đầu nhưng không tái hiện sau retry; chưa rõ nguyên nhân. CORS, giới hạn IP và Q31 chưa chốt. |
| Sales | Newman POS: 8 request, 15 assertion đạt. Newman Auth: 14 request, 26 assertion đạt. Bộ bổ sung: 33 PASS, gồm payment rollback, 4 tổ hợp customer filter, null quote trả 400 và idempotency khi Redis ngừng. | #153 ban đầu: 31 PASS, 6 FAIL, 2 BLOCKED. Khuyến mại không giảm tiền (#38/#39); race lượt dùng cuối bị chặn. Late PAID được kiểm tra bổ sung sau đó. |
| Inventory | #154 ban đầu: 36 PASS, 2 BLOCKED. Các ca nhập, xuất, chuyển kho, duyệt lặp, sai role, nhận thừa, hủy draft và ledger bổ sung đạt. | ADJUST chưa có ở baseline; bản sửa #63 đã được kiểm tra riêng. Hủy đơn và audit cũng được bổ sung đạt. |
| Online và integration | WEB/MARKET: HMAC, duplicate, callback và version đạt trước tải. Expiry nhả giữ tồn đúng một lần. Late PAID khi còn/hết hàng đúng CONFIRMED hoặc CANCELLED/REFUNDED. | Sau tải còn 18.325 event outbox PENDING; event cũ nhất khoảng 478 giây. Không nhầm với lỗi HMAC hoặc receiver. |
| Reports và dashboard | Revenue đối soát SQL: gross 585.853.600 đ, refund 5.872.000 đ, net 579.981.600 đ. Low-stock metadata đạt. | Dashboard còn dữ liệu mẫu. Chưa đủ mọi filter, khoảng ngày và role của mọi report. |
| Data và Forecast | Pytest: 190 PASS, 1 SKIP, 4 subtest PASS. Pipeline và model thật DONE; có kiểm tra horizon metrics. | Lỗi dự báo ngày quá khứ #298 tại baseline đã được sửa và kiểm tra riêng bên dưới. Chưa nghiệm thu toàn AC-05. Cần làm rõ metric daily của API so với aggregate theo #295. |
| UI và E2E | Lượt UI chạy lại: 29 PASS, gồm 12 tài khoản × 2 viewport (1.440 px và 360 px), cart, catalog và customer. Hai test API lifecycle PASS ở lượt đầu. | Acceptance vẫn BLOCKED: payment, invoice, online, chứng từ, chuyển kho và Forecast chưa có luồng UI hoàn chỉnh tại baseline. Một trong 29 ca cũ chỉ quan sát placeholder; không phải nghiệp vụ E2E đạt. |
| Concurrency và load | POS 100 request: 5 tạo đơn, 95 từ chối; 5 ledger, tồn 0. Mixed 100 request: 4 POS và 1 online thành công, 95 từ chối; không oversell. Tải 50 VU trong 300 giây: p95 đọc 9,92 ms, tạo đơn 40,09 ms. | Đo 14.170 flow; error rate 0,0084686 %, không phải 0 %. Chưa có breakdown endpoint/status của lỗi hiếm; không đo mọi API dưới tải. |
| Operations và regression | Broker ngừng ít nhất 300 giây: POS vẫn commit; sau khôi phục, replay và callback đạt trước tải. Backup và restore sang DB mới đối soát 6 bảng đạt. Gitleaks trên tracked snapshot không có finding. | Trivy có CVE cần triage, chưa chứng minh exploit. Chưa quét full Git history. Outbox throughput là lỗi riêng sau load. |

## Kiểm tra lại các bản sửa trên b867c67

Có 48 ca/assertion PASS, 1 BLOCKED_DEPENDENCY và 27 unit test Forecast PASS. Không dùng Docker socket trong lượt kiểm tra tập trung này.

- **#335 — SV03:** không có token trả 401; ADMIN/OWNER thấy chi nhánh inactive, các role khác chỉ thấy active. Bộ lọc `active=false` đúng.
- **#63 — SV03:** ADJUST snapshot 20, count 17 làm giảm 3 và ghi đúng 1 ledger. Thiếu count trả 422; duyệt lặp trả 409. Snapshot 17, bán 1 rồi count 19 áp dụng delta +2, giữ đúng tồn 18. `allProducts` chứa fixture.
- **#298 — SV06:** pipeline official có 3.000 series và 1.095.000 dòng daily_sales trước fixture. Model DONE, 111.000 kết quả đều thuộc tương lai từ 01–30/11/2026; H7 có 21.000 dòng, H30 có 90.000 dòng, không âm. Xác nhận lỗi ngày quá khứ đã sửa.
- Backend `GET /api/v1/forecasts?branchId=1&productId=1` vẫn trả 404 dù service trực tiếp có dữ liệu: NOT_IMPLEMENTED/BLOCKED theo #119/#344. Ba operation Forecast backend khác chưa được gọi.

## Lỗi và phân công theo bằng chứng

- **SV02 @t-boai:** giỏ 223.000 đ phải giảm 22.300 đ nhưng vẫn tính 223.000 đ; giỏ 350.000 đ không được giảm mức trần 30.000 đ. Đã có #38/#39 nên không tạo trùng. PR #381 đang mở tại lần xuất bản ban đầu; chưa kiểm tra PR head đó trong lượt này.
- **SV07 @thanhcode118:** backlog outbox sau tải 50 VU có 18.325 PENDING, event cũ nhất 478,075 giây. Trace `c4296d74-b77a-4c6a-b823-a2aa2474e8cc`: event 28693/28694 vẫn PENDING, attempts 0 sau 140,019 giây. Chưa có delivery cho trace; chưa kết luận mất event. Batch 25 và fixed delay 1.000 ms là nguyên nhân nghi vấn qua code, chưa profiling đầy đủ. Phối hợp SV02/SV03 nếu thay đổi semantics sự kiện. Theo dõi #384.
- **SV08:** sửa oracle QA về route STOREKEEPER, self-approval trả 422 SELF_APPROVAL, online hết hàng trả 409 và revenue chỉ tính COMPLETED. Giữ raw attempt local, không quy lỗi expected hoặc fixture cho backend. Lỗi QA đọc sai `Item.productId` đã được sửa và chạy lại trên QA mới.
- **Security:** dependency scanner báo 3 CRITICAL, 27 HIGH; image backend có 11 CRITICAL, 42 HIGH; frontend có 3 CRITICAL, 56 HIGH; Forecast có 51 HIGH. Có CVE trùng giữa artifact nên không cộng thành tổng độc lập. Chưa chứng minh exploit. Dependency QA/Newman thuộc SV08; base image và build cần SV07 phối hợp chủ module. Chưa mở bug CVE trước khi triage và kiểm tra trùng theo từng package.

## Điều chỉnh test theo review PR #385

- Test cart chỉ kiểm tra quét/tìm sản phẩm, giỏ hàng và quote. Không bấm checkout để khẳng định thông báo “chưa khả dụng” là kết quả đúng.
- Bỏ ca yêu cầu các màn phải hiển thị placeholder. Thay bằng 5 `test.fixme` cho payment/invoice (#75/#77), online (#80/#81), chứng từ (#92), chuyển kho (#96) và Forecast (#100).
- Mỗi ca fixme có annotation `blocked`. Safe reporter vẫn đánh dấu acceptance BLOCKED và trả trạng thái chưa đạt; SKIP không biến thành nghiệp vụ PASS.
- Khi tính năng được tích hợp và QA có thiết kế/contract đã duyệt, thay từng fixme bằng luồng UI thật. Việc merge màn hình mới không tự làm test placeholder đỏ và cũng không tự nghiệm thu luồng chưa được test.
- JSON giữ nguyên 29 case UI lịch sử của lượt chạy lại, đồng thời ghi riêng 2 case API lifecycle PASS của lượt đầu cùng SHA. Không cộng hai attempt thành một lần chạy mới hoặc sửa kết quả cũ thành kết quả của spec sau review.
- Helper logout đợi control tài khoản của layout được render trước khi chọn nút trực tiếp hoặc menu. Giữ riêng lượt đầu có timeout tại bước này, không quy thành lỗi logout backend khi chưa có request.
- Chạy lại toàn bộ spec sau sửa trên ứng dụng `b236417`: **30 PASS, 5 SKIP, không có assertion FAIL hoặc timeout**. Playwright status `passed`; acceptance vẫn `BLOCKED`. Safe reporter chủ động trả exit 1 do các luồng fixme chưa được nghiệm thu, không phải vì màn hình mới làm hỏng assertion placeholder. Bốn test guard đạt. Lượt trước đó có 29 PASS, 1 timeout ở logout control và 5 SKIP, được giữ riêng trong JSON.
- Đã chuẩn bị tích hợp develop `b236417` bằng merge không commit, không có xung đột. Các thay đổi ứng dụng từ develop được giữ nguyên; bản sửa QA và merge commit chưa được commit/push.

## Chạy lại và đọc bằng chứng

- Dùng môi trường mới theo [QA156 README](../../tests/e2e/issue156/README.md), process environment, guard và safe reporter hiện có. Từ `tests/e2e/issue156`, chạy `npx playwright test system20261117.spec.js --config playwright.config.js`. Runner mặc định có thể discovery cả spec cũ; không cộng test trùng. Không reseed project/volume đã có.
- [Script k6](../../tests/load/system20261117-load50.js): chuẩn bị QA riêng và sản phẩm active có tồn ít nhất 99.999, không có khuyến mại. Cung cấp `QA_BASE_URL=http://host.docker.internal:18xxx/api/v1`, `LOAD_PRODUCT_ID`, `LOAD_SKU`, `SOURCE_COMMIT`, `GREENMART_DEMO_PASSWORD` chỉ trong process. Mount thư mục kết quả riêng vào `/results`, rồi chạy `k6 run`. Sau tải phải đối soát outbox age và receiver, không chỉ p95 API.
- Kiểm tra tĩnh: `node --check tests/e2e/issue156/system20261117.spec.js`; `node --check tests/load/system20261117-load50.js`; `node --test tests/e2e/issue156/guards.test.cjs`.
- [Bằng chứng đã lọc](results/system-2026-11-17-summary.json) gồm component, load, outbox, model/case tập trung và danh sách UI/API lịch sử. Không có token, password, raw logs hoặc DB dump. Không cộng các suite lặp thành coverage toàn dự án.

Chưa kiểm chứng đầy đủ: DLQ/redrive/sync-log, race lượt khuyến mại cuối, mọi return/report filter, thứ tự khóa nhiều dòng, toàn API dưới tải, mọi browser/accessibility và deploy/staging. Một pytest vẫn SKIP. Cần quyết định Lead về CORS, IP, Q31 và cách trình bày metric. Không đổi expected để test pass.
Đây là test và báo cáo bổ sung; không tự đóng #156/#157 hoặc các nhiệm vụ còn thiếu bằng chứng. CI, review và merge của bản sửa theo dõi riêng; chưa coi Done khi mới chuẩn bị thay đổi local.
