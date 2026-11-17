# Kiểm thử hệ thống ngày 17/11/2026

QA: @Huynh-Phuc-Dat (SV08). Refs #152, #153, #154, #155, #156, #157, #159, #242.
Đã chạy qua 10 nhóm; còn lỗi và phần chưa kiểm chứng, chưa nghiệm thu Done toàn hệ thống.

## Commit và môi trường

- Full suite: `27acddd4d1452b83ed160e3609347020ee8e9fbb`, sau pull develop fast-forward, nhánh `feature/system-qa-20261117`.
- Retest tập trung sau khi fix merge: snapshot `b867c671e6ab4e0df5d99b5ebe22f216bf176b3f`, không merge vào nhánh QA và không thay thế full regression.
- Trước xuất bản, origin/develop đã tới `b236417`; kết quả dưới đây không phải full regression trên commit đó.
- Docker QA riêng, PostgreSQL/Redis/RabbitMQ thật; kênh/ thanh toán giả lập theo phạm vi dự án. Backend tải giới hạn768MiB, JVM512MiB,2CPU. Không test phá dữ liệu môi trường chung.
- Đã stop các stack QA của lượt chạy, giữ dữ liệu/volume; không reset môi trường phát triển.

## Kết quả và giới hạn

| Nhóm | Kết quả đã chạy | Lỗi / giới hạn |
|---|---|---|
| Build/unit/contract | Maven verify488PASS; frontend260PASS, typecheck/lint/format/build đạt; connector8PASS; API types khớp baseline | LINE coverage Sales85,50%, Inventory96,26%; không phải coverage mọi hành vi |
| Auth/security/audit | RBAC#152:764PASS; #242:60PASS và2bổ sungPASS; #155 retry28PASS | Raw ADMIN-read FAIL do expected200 xung đột quyết định Lead#259403: BLOCKED_SPEC. OWNER login500 ban đầu không tái hiện sau retry; chưa rõ nguyên nhân. CORS/IP/Q31 chưa chốt |
| Sales | Newman POS8request/15assertion, Auth14/26 đạt; supplement33PASS gồm payment rollback, customer4filter, nullquote400, Redis-down idempotency | #153:31PASS/6FAIL/2BLOCKED ban đầu; KM không giảm tiền (#38/#39), last-use race bị chặn. Late PAID được bổ sung sau đó |
| Inventory | #154:36PASS/2BLOCKED ban đầu; nhập/xuất/transfer/doubleapprove/sai role/overreceive/hủy draft và ledger bổ sung đạt | ADJUST thiếu ở baseline nhưng fix#63 đã retest đạt; hủy đơn/audit cũng bổ sung đạt |
| Online/integration | WEB/MARKET HMAC/duplicate/callback/version đạt trước tải; expiry release once; late PAID còn/hết hàng đúng CONFIRMED hoặc CANCELLED/REFUNDED | Sau tải outbox18325PENDING, oldest478s; không nhầm với lỗi HMAC/receiver |
| Reports/dashboard | Revenue đối soát SQL: gross585853600, refund5872000, net579981600; low-stock metadata đạt | Dashboard còn dữ liệu mẫu; chưa đủ mọi filter/ngày/role của mọi report |
| Data/Forecast | Pytest190PASS/1SKIP/4subtestPASS; pipeline/model thật DONE; kiểm tra horizon metrics | Baseline target quá khứ#298 đã fix và retest riêng bên dưới. Chưa nghiệm thu toàn AC05; API metric daily/aggregate cần làm rõ theo#295 |
| UI/E2E | Lượt corrected29PASS gồm12account×2viewport1440/360, cart/catalog/customer;2APIguardPASS ở lượt đầu | Acceptance BLOCKED: payment/invoice/online/docs/transfer/Forecast placeholder tại baseline; không gọi API thay UI rồi ghi E2E đạt |
| Concurrency/load | POS100:5created/95reject,5ledger,tồn0. Mixed100 retest4POS+1online/95reject,khôngoversell. 50VU300s đạt p95 đọc9,92ms/tạo40,09ms |14170flow đo; error0,0084686%, không phải0. Chưa có breakdown endpoint/status cho lỗi hiếm; không đo mọi API dưới tải |
| Operations/regression | Broker ngừng≥300s, POS vẫn commit, khôi phục replay/callback đạt trước tải; backup/restore DB mới đối soát6bảng đạt; Gitleaks tracked snapshot0finding | Trivy có CVE cần triage (không đồng nghĩa exploit đã xác nhận); chưa quét full Git history; outbox throughput là lỗi riêng sau load |

## Retest bản sửa b867c67

48 assertion/case PASS,1BLOCKED_DEPENDENCY;27unit Forecast thay đổi PASS, không dùng Docker socket trong lượt này.

- #335 (SV03): branch list không token401; ADMIN/OWNER thấy inactive, các role khác chỉ active; filter active=false đúng.
- #63 (SV03): ADJUST snapshot20/count17 giảm3, đúng1ledger; thiếu count422, duyệt lặp409. Snapshot17, bán1, count19: delta+2 giữ đúng tồn18. allProducts chứa fixture.
- #298 (SV06): official3000series/1095000dailyrows trước fixture; model DONE,111000kết quả đều tương lai01–30/11/2026, H7=21000/H30=90000, không âm. **Xác nhận lỗi ngày quá khứ đã sửa**.
- Backend `GET /api/v1/forecasts?branchId=1&productId=1` vẫn404 dù service trực tiếp có dữ liệu: NOT_IMPLEMENTED/BLOCKED theo#119/#344; ba operation Forecast backend khác chưa gọi.

## Lỗi và phân công theo bằng chứng

- **SV02 @t-boai:** KM giỏ223000 phải giảm22300 nhưng vẫn tổng223000; giỏ350000 không giảm trần30000. Đã có#38/#39, không tạo trùng. PR#381 đang mở khi kiểm tra xuất bản; chưa retest PR head đó.
- **SV07 @thanhcode118:** backlog outbox sau50VU:18325PENDING, oldest478,075s; trace `c4296d74-b77a-4c6a-b823-a2aa2474e8cc`, event28693/28694 PENDING attempts0 sau140,019s. Chưa có delivery cho trace; chưa kết luận mất event. Batch25/fixedDelay1000ms là nguyên nhân nghi vấn qua code, chưa profiling đầy đủ. Phối hợp SV02/SV03 nếu phương án sửa thay đổi semantics sự kiện.
- **SV08:** sửa oracle QA: STOREKEEPER về Inventory; manager tự duyệt422 SELF_APPROVAL; online OOS409; revenue chỉ COMPLETED. Giữ raw attempt local, không quy các lỗi expected/fixture này cho backend. Một assertion latest đầu đọc sai Item.productId đã sửa và chạy lại bằng QA mới.
- **Security:** dependency scanner CRITICAL3/HIGH27; ảnh backend CRITICAL11/HIGH42, frontend CRITICAL3/HIGH56, Forecast HIGH51. Có trùng CVE giữa artifact; chưa chứng minh exploit. QA/Newman dependencies thuộc SV08, base image/build phối hợp SV07/chủ module. Chưa tạo bug CVE khi chưa triage/kiểm tra trùng từng package.

## Chạy lại test mới và đọc bằng chứng

- UI: dùng môi trường mới theo [QA156 README](../../tests/e2e/issue156/README.md), process environment/guard và safe reporter hiện có. Từ `tests/e2e/issue156`, chọn riêng spec bằng `npx playwright test system20261117.spec.js --config playwright.config.js`. Runner mặc định có thể discovery cả spec cũ; không cộng trùng các attempt. Không dùng project/volume cũ để reseed.
- k6: [system20261117-load50.js](../../tests/load/system20261117-load50.js). Chuẩn bị QA riêng, fixture sản phẩm active có tồn≥99999 và không KM, cung cấp `QA_BASE_URL=http://host.docker.internal:18xxx/api/v1`, `LOAD_PRODUCT_ID`, `LOAD_SKU`, `SOURCE_COMMIT`, `GREENMART_DEMO_PASSWORD` chỉ trong process. Container k6 cần mount thư mục kết quả riêng tới `/results`; chạy `k6 run` script. Không dùng staging/shared. Sau tải đối soát outbox age và receiver, không chỉ p95 API.
- Lệnh tĩnh: `node --check tests/e2e/issue156/system20261117.spec.js`; `node --check tests/load/system20261117-load50.js`; `node --test tests/e2e/issue156/guards.test.cjs`.
- [Bằng chứng đã lọc](results/system-2026-11-17-summary.json): số liệu component, load, outbox, latest model/case và tên/status UI; không token/password/raw logs/DB dump. Không cộng suite lặp thành tổng coverage toàn dự án.

Chưa kiểm chứng: full DLQ/redrive/sync-log, race lượt KM cuối, mọi return/report filter, multi-line deadlock ordering, toàn API dưới tải, mọi browser/accessibility, deploy/staging. Một pytest vẫnSKIP. Cần quyết định Lead về CORS/IP/Q31 và semantics metric; không đổi expected để test pass.
Đây là báo cáo/test bổ sung; không tự đóng #156/#157 hoặc các nhiệm vụ còn thiếu bằng chứng. CI/review/merge của PR theo dõi riêng, chưa coi Done khi mới push.
