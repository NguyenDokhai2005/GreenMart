# Kiểm tra hệ thống và tìm bug — 19/11/2026 (#158)

## Phạm vi và commit

- Baseline `636ed67d4058ba6825468ef4a08466450fb36a1d`: thực thi đủ 10 nhóm QA trên môi trường riêng.
- Follow-up `3308869a2048a8644bf76db1e2ad31b3485d50dc`: Customer #306/V17, Sync Log #139, Auth/Branch do SecurityConfig đổi, frontend/schema và recheck Promotion.
- Nhánh công bố dựa trên `46b5b3f`; phần mới so với follow-up chỉ là tài liệu SV07, không coi đó là một lượt chạy test mới.
- Windows/Docker28.4.0, PostgreSQL thật/Flyway/official seed; backend768MiB/JVM512MiB/2CPU. QA mới từng nhóm, chạy tuần tự; đã dừng container riêng, giữ volume. Không reset dữ liệu hoặc restart stack chung.
- Kết quả có FAIL và giới hạn; không nghiệm thu toàn hệ thống hoặc đóng #158/#153 từ báo cáo này.

## Lỗi đã tái hiện

| Lỗi | Thực tế / kỳ vọng | Phần liên quan |
|---|---|---|
| Promotion: phần tử null trong scope | POST/PUT với `channels`, `productIds`, `branchIds` chứa `[null]` đều500 `INTERNAL_SERVER_ERROR`, cần400 `VALIDATION_ERROR`;6/6 ở cả hai commit | SV02 @t-boai theo#38; báo lỗi riêng, Refs#153/#158 |
| #384: đồng bộ tồn trễ sau tải |20.802 PENDING;2event giao dịch mới chưa SENT sau61,123s, WEB/MARKET không nhận trong60s | Đề xuất SV07 @thanhcode118 theo#127/#133/#138; issue chưa có assignee |

Hai bug, không phải7bug:6 request sai cùng lỗi validation và1ca SLA outbox. Không có bằng chứng mất event hoặc vượt quyền.
Promotion: source nghi vấn `PromotionCrudServiceImpl.java:248/275/281` xử lý phần tử null bằng trim/Map.of; DTO chưa ràng buộc phần tử list. Chưa sửa code ứng dụng.
Outbox: cadence/batch relay là giả thuyết cần profiling. Trace `4c0f01e1-947a-42c8-a645-aeb581043dff`, oldest backlog283,701s. Đo ở baseline; không đổi nhãn thành bài tải trên3308869.

### Tái hiện Promotion (QA riêng)

1. Đăng nhập OWNER; chọn product1/branch1 tồn tại; code khuyến mại duy nhất.
2. Tạo DRAFT hợp lệ bằng POST `/api/v1/promotions`, giữ ID trả về. Ví dụ dưới đây dùng thời gian hợp lệ; khi chạy lại đổi code và thời gian.

```json
{"code":"QA-NULL-CONTROL","type":"PERCENT","level":"ITEM","value":10,"startAt":"2026-11-19T00:00:00Z","endAt":"2026-11-20T00:00:00Z","productIds":[1],"branchIds":[1],"channels":["POS"]}
```

3. Mỗi lần dùng code mới, thay duy nhất một mảng thành `[null]`; gửi POST và PUT `/api/v1/promotions/{draftId}`. Cả6request trả500; các phần tử không nullable trong OpenAPI cần400.
4. GET lại DRAFT: code/phạm vi giữ nguyên, chỉ có control được lưu. HTTP/code/trace đã lọc trong JSON kết quả.

### Tái hiện #384 và kiểm tra khác

- Dùng QA mới, official seed; tạo fixture stock99999, không KM; workload `tests/load/system20261117-load50.js`:5VU warmup30s,50VU steady300s, pacing1s, lookup→quote→POS→detail→inventory. Cấp URL/fixture/credential QA qua process env của runner, không commit giá trị.
- Sau tải tạo fixture/mapping WEB+MARKET trước POS, dùng correlation ID riêng; xác nhận broker/receiver khỏe rồi quan sát stock và metadata outbox trong60s.
- Stock race: `tests/api/stock150` và `tests/load/concurrency-stock.js`. Newman: `tests/api/pos149/run-newman.cjs`, `tests/api/auth242/run-newman.cjs`; chỉ chạy với cấu hình QA mới đã đọc README, không chạy trên DB chung.
- Full backend baseline: `mvn -B -ntp verify -Dspring.test.context.cache.maxSize=2` (snapshot backend+infra, PostgreSQL Testcontainers).
- Follow-up: `mvn -B -ntp test -Dtest=CustomerDbIntegrationTest,CustomerIntegrationTest,CustomerModelTest,CustomerServiceTest,ForecastRoleInitTest,SyncLogApiTest,AuthApiTest,BranchApiTest -Dspring.test.context.cache.maxSize=2`.
- Frontend: typecheck, lint, format:check, build và `npm test -- --run`. Forecast: pytest với requirements-dev/Testcontainers, DB seed1000products riêng cho ca daily-sales idempotency.
- Case HTTP/UI bổ sung chạy bằng công cụ QA cục bộ, không đưa công cụ cá nhân vào repository. Bảng sau là kết quả thực thi đã lọc, không tuyên bố chỉ các lệnh trên tái tạo mọi fixture/scenario.

## Kết quả 10 nhóm

| Nhóm | Kết quả | Giới hạn |
|---|---|---|
|01 Build/unit/contract|Backend539PASS; Sales85,75%/Inventory96,04% line coverage; frontend294PASS+4checks; connector8; Forecast214+4subtest,1skip rerun riêng1PASS|Full verify ở baseline; follow-up81 targeted tests, không phải verify lần2|
|02 Auth/RBAC|764PASS; JWT/refresh/logout/password/lockout;#329 init trước Flyway và future-table ACL denied PASS|#242 raw62PASS/2expected cũFAIL/1Q31BLOCKED|
|03 Catalog/customer/POS/KM|Sales33PASS; Newman15POS+26Auth assertions PASS; PromotionCRUD/roles|Null scope6FAIL; chưa mọi biên pricing/time|
|04 Inventory|Import/export/transfer/partial/self-approval; ADJUST audit/ledger; ROP; snapshot20→sale19+delta2=21|Chưa mọi UI discrepancy/hao hụt|
|05 Integration|WEB/MARKET HMAC/duplicate/version/recovery; broker ngừng300s vẫn commit/replay/callback; latePAID còn/hết hàng PASS|#384FAIL sau tải; không suy ra mọi recovery dưới60s|
|06 Reports|Revenue/refund/net đối soát SQL; top-products/date/role; fixed388/389 PASS|Chưa mọi dashboard/widget|
|07 Data/AI|Seed3branch/1000product/10000đơn lịch sử/12account/2000mapping/180alert; daily_sales1.095.000rows; modelDONE111.000rows/eventpersistent|Target01–30/11 từ last-sale31/10; actual02:00 chưa chạy|
|08 Browser|28browser+2API PASS;8browser checkout/docs bổ sung; tổng36browser,12account×desktop/mobile|5fixme; payment/docs đã bổ sung, invoice/online/transfer/Forecast chưa đủ; exit1 vì acceptance có skip|
|09 Concurrency/load|POS100:5win/95reject; mixed100:3POS+2online/95reject; reverse2SKU100:5win/95reject; không âm/deadlock|Chỉ scenario đã chạy|
|10 Ops/regression|Backup/restore DB mới khớp6bảng; upgrade15→16/16→17; gitleaks0snapshot;3image+dependency scans; fixed386–3899PASS|CVE cần triage; chưa staging/deploy/history/full log chain|

50VU/300s:14.062flows/orders; p95đọc15,8517ms/tạo80,3545ms; businessErrorRate0/logicalFailures0 trong request đo. Không suy ra mọi API không có500; invalid Promotion nằm ngoài bài tải.
AC05 horizon aggregate cùng holdout02–31/10: H7 hậu xử lý0,4 WAPE79,2812%<97,0159%;H30 50,4820%<72,3841%. Ngưỡng chọn validation;800SKU chậm gần toàn0 vẫn là giới hạn.

## Follow-up và điểm cần chú ý

- `3308869`: Backend81/81 targeted tests, frontend294/294+typecheck/lint/format/build, schema sinh lại khớp OpenAPI.
- Customer#306 **59/59PASS**: active flag/role/filter, ẩn POS, FK/history, đơn kênh giữ đúng khách inactive, reopen.
- SyncLog#139 **48/48PASS**: ADMIN/OWNER xem, ADMIN retry; filter/paging/status; callback chữ ký sai thật→FAILED; hai retry202+409→SUCCESS, cùng event/body; receiver tồn6/version3. Hai IN-log fixture chỉ dùng no-payload/paging.
- Helper version ban đầu đọc nhầm cấp ngoài(null); đã đối soát bổ sung `items[0].version=3` khớp receiver và hash toàn body trước/sau không đổi. Không dùng null=null làm bằng chứng version. JSON cũ giữ nguyên.
- Schema check đầu bị chặn bởi network-disabled trong khi npx cần tải công cụ; chạy lại đúng mạng đã PASS. Không quy thành lỗi contract.
- V16→V17 giữ1500customer/10200order/1000product/3000inventory và customer links, khách cũ active=true/FKRESTRICT.
- #242 ID071 phải3 active branches theo Lead#314; ADMIN Sales403 theo Lead#259. `/auth/me` vẫn nhánh được gán. Hai rawFAIL là QA expected cũ; không phải bug Backend.
- #153/#154 fixture latePAID/ADJUST/cancel cũ chưa đủ; đã kiểm tra bổ sung qua API thật, không gắn nhãn ADJUST chưa triển khai.
- HTTP observations: baseline61/61, latest delta19, kết hợp64/64 operations hiện có; observation gồm404/500, không phải PASS/mọi role-state-schema hoặc64 operation chạy lại ở330.
-6Users và4BackendForecast routes chưa triển khai ở baseline/source tương ứng. Users implementation chưa xác minh assignee;#144 là QA,#241 là Auth. Forecast đã có#119/#344, không tạo issue trùng.
- Forecast run API dùng metric rawdaily khác AC05 chính thức: REVIEW_REQUIRED, chờ Lead xác nhận generic-field semantics; không kết luận AC05FAIL hoặc event metric đã đo trực tiếp.
- Trivy: dependency56records/backend448/frontend185/Forecast247; cảnh báo chưa chứng minh khai thác. Handlebars4.7.8 trong QA runner có3criticalrecords cần SV08 phối hợp triage, không tự quy mọiCVE choSV07.
- Q31/CORS/IP policy chưa chốt; actual02:00, full log/history, staging/TLS/WAN, mọi UI return/invoice/online/transfer/Forecast, POS3món/30s và mọi biên/interleaving chưa kiểm tra.

Số liệu đã lọc: [JSON](results/system-2026-11-19-summary.json). Phạm vi [AC/NFR/BR](system-2026-11-19-coverage.md).
Không xuất bản secrets, dump, rawlogs, nhật ký hay công cụ/quy tắc cá nhân. Chỉ báo cáo QA, không sửa runtime/contract/migration; không đóng bug bằng PR này. Refs#158,#153,#152,#154,#155,#156,#242,#384.
