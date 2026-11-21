# Kiểm thử hệ thống ngày 18/11/2026

Runtime/source: **`9daa47de2e8ad5ebd755650d5023af637b3d82f9`**. Docker28.4.0, RAM khoảng8GB; QA mới, dữ liệu synthetic, chạy tuần tự; Backend768MiB/JVM512MiB/2CPU. Đây là báo cáo kết quả, chưa kết luận toàn hệ thống PASS hoặc issue Done.

Refs #158, #152, #153, #154, #155, #156, #157, #159, #242; retest #384 và #386–#389. Không dùng Closes vì còn lỗi và phần chưa nghiệm thu.

## Kết quả 10 nhóm

| Nhóm | Đã kiểm chứng | Giới hạn/chưa đạt |
|---|---|---|
| Build/unit/contract | Maven538PASS; coverage Sales85,75%/Inventory96,04%; Frontend294PASS và typecheck/lint/format/build; Connector8PASS; schema sinh lại khớp | Forecast214PASS/1SKIP/4subtestPASS; ca SKIP đã chạy riêng1PASS trên DB mới; không đổi JSON gốc thành không SKIP |
| Auth/security/audit | RBAC591+173=764PASS; JWT/refresh/lockout/BCrypt và quyền DB Forecast; quyền promotion/ROP mới | Auth242raw62PASS/2FAIL/1BLOCKED: hai expected cũ trái Lead314/259, đã HTTP-check expected hiện hành riêng; Q31/CORS/IP/log còn chờ |
| Sales | Edge39PASS/1BLOCKED; payment/customer/Redis33PASS; NewmanPOS15/Auth26assertionsPASS; promotionCRUD/status/last-use và CODcomplete | LatePAID đã bổ sung hai fixture PASS; return#43 chưa triển khai |
| Inventory | Nhập/xuất/chuyển/nhận thiếu, self-approval; ADJUST/audit/ledger; snapshot20,count22,sale19,approve+2→21; manualROP/pack/roles PASS | Audit154raw36PASS/2BLOCKED được bổ sung fixture chuẩn; loss report/UI transfer chưa nghiệm thu |
| Online/đa kênh | WEB/MARKET HMAC/duplicate/version/retry/status callback; mapped broker outage≥300s21/21PASS | #384 vẫn FAIL đồng bộ sau tải, chi tiết dưới |
| Reports | Revenue/refund/net đối chiếu SQL; top-products/range/role; inactive history/discount allocation PASS | Không dùng seed refund để nhận workflow return API đã chạy; dashboard UI chưa đủ |
| Data/Forecast | Seed3branch/1000product/10000historicalorders/2000mapping/12account,180alerts;1095000daily_sales;111000future rows; persistent forecast.completed; AC05PASS |4public BackendForecast routes chưa có; actual02:00/staging/UI chưa chạy; bán chậm gần toàn0 vẫn là hạn chế |
| UI/E2E |28browserPASS(account/cart/catalog)+6CASH/CARD/QR+2create/approve import, desktop1440/mobile360 |5fixme legacy phải đọc cùng test bổ sung; invoice/online/return/transfer/Forecast UI còn thiếu; chưa đo manual3món/30s/Figma |
| Concurrency/performance | POS100:5success/95reject; mixed100:2POS+3online/95reject; reverse2SKU100:5/95; không âm tồn;50VU300s đạt threshold |13984flows,readp95=10,3831ms/create55,5495ms,error0,0128719%,logical0; chưa có chi tiết status của số ít request lỗi |
| Operations/regression | Gitleaks0finding snapshot tracked; scan đủ3image; backup/restore6tablecounts; V15→V16 giữ1000product/10200totalorders/3000inventory;386–389retest9/9PASS | Scan còn finding cần triage; không dispatch CI/deploy hoặc nghiệm thu production |

## Finding và phần thiếu triển khai

- **#384 còn tái hiện:** sau tải20325outboxPENDING,oldest284,686329s. Trace `89acf171-4e9b-4958-acbd-73d01ed62246` có2PENDING/0SENT,sau61,168159s chưa tớiWEB/MARKET. Hai mapping đã tạo trước POS. Đầu mối đề xuất SV07 theo issue hiện có; chưa kết luận mất event, không tự gán assignee hoặc quy lỗi SV02/SV03.
- **6Users routes chưa triển khai:** GET/POST `/users`,GET/PUT `/users/{userId}`,POSTunlock/reset-password trả404 vớiADMIN hợp lệ; source không có controller nhóm này. CollectionGET và validcreate cũng404; không suy luận từ404củaID không tồn tại. #144 là nhiệm vụ QA viết test case, #241 giao Auth01; chưa xác minh người code UsersCRUD, cần Lead phân công.
- **4Forecast Backend routes chưa triển khai:** GET`/forecasts`,GET/POST`/forecasts/runs`,GET`/forecasts/runs/{runId}` trả404; direct service đã PASS riêng. Refs#119/#344, không tạo issue trùng.
- Đã quan sát đủ61/61operation:51hiện có,10NOT_IMPLEMENTED. Observation không phải phủ hết mọi role/branch/input/state/schema. ReturnAPI#43 chưa có trong contract hiện tại, ghi riêng với UI dependencies.
- **386–389 đã sửa:**6race category mỗi lần1HTTP200/1CATEGORY_CYCLE422; overflow bị400; inactive history vẫn10000revenue trước/sau; đơn18000 có tổng product revenue18000.

## Forecast và security

AC05 dùng quyết định Lead295: horizon aggregate, clean holdout02–31/10/2026, threshold0,4 chọn validation. H7WAPE79,2812%<baseline97,0159%;H30=50,4820%<72,3841%. Nhóm bán chậm99,8333%zero vẫn thua baseline toàn0; không suy từ backtest ra độ chính xác tương lai. Public run diagnostic WAPE không bị thay bằng chỉ số aggregate trong bằng chứng.

| Trivy0.61.1 target | Records | Critical | High | Medium | Low | Unknown |
|---|---:|---:|---:|---:|---:|---:|
| Dependency tracked |56|3|27|19|7|0|
| Backend image |448|11|42|283|112|0|
| Frontend image |185|3|56|87|39|0|
| Forecast image |238|0|51|105|68|14|

Records có thể trùng CVE giữa package/image. Đây là scanner findings, chưa xác minh exploit/reachability/architecture hoặc chọn bản nâng cấp. Ba Critical dependency nằm trong lockfile NewmanQA149(Handlebars4.7.8), thuộc tooling QA cần review; không quy lỗi Backend từ finding này. Gitleaks chỉ quét snapshot tracked, không phải toàn lịch sử hay file private. Scan exit0 không có nghĩa0finding.

## Đối soát và tái kiểm tra

JSON: [system-2026-11-18-summary.json](results/system-2026-11-18-summary.json). Ma trận: [AC/NFR/BR](system-2026-11-18-coverage.md). JSON là tổng hợp có chọn trường từ bằng chứng thực thi; không phải nguyên bản mọi request/response hoặc toàn bộ harness có thể chạy một lệnh từ PR.

Các suite công khai được gọi trong lượt QA: `mvn -B -ntp verify -Dspring.test.context.cache.maxSize=2`; frontend `npm run typecheck`, `lint`, `format:check`, `build`, `npm test -- --run`; connector `node --test test/connector.test.js`; Forecast `pytest -q -p no:cacheprovider`; API `tests/api/issue152_rbac.py`, `issue152_supplemental.py`, `issue153_edges.py`, `issue154_audit.py`, `issue155_security.py`, `auth242/check_api.py`, `bughunt20261117.py`; Newman `pos149/run-newman.cjs`, `auth242/run-newman.cjs`; k6 `tests/load/concurrency-stock.js`, `system20261117-load50.js`. Các runner cần QA riêng/fixture và biến môi trường đúng guard; không chạy vào database dùng chung.

Các probe bổ sung UI/CRUD/latePAID/đảo dòng và điều phối là harness QA cục bộ, chưa đóng gói trong PR. Quy trình: freshofficialseed→APIfixtures→kiểm status/balance/ledger/actor→đối soát metadata SQL; callback đúng HMAC/mapping/receiver khỏe trước fault; không SQL ép trạng thái EXPIRED, chỉ dời deadline fixture để job thật chạy. Load50 dùng5VU30s warmup,45s bắt đầu50VU300s,pacing1s; không build/model đồng thời. Các phép đo bổ sung chưa phải một bộ replay độc lập từ ba file báo cáo này.

Giữ raw failures riêng: Auth2FAIL do expected cũ; UIplaceholder assertions lỗi thời; selector required-label/title và filter0test được sửa ở helper, cuối cùng28+6+2browserPASS. Brokerfixture ban đầu thiếu mapping không dùng để quy lỗi SV07; QA mới có mapping chạy lại21PASS. ADJUSTlegacy request sai snapshot không được coi là backend chưa hỗ trợ. Wrapperexit0 không ghi đè kết quả individualFAIL/BLOCKED.

Còn chờ: Q31,CORS/IPpolicy,rawlog/correlation,severitytriage,overnight02:00,staging/TLS,manualFigma/UIstates,POS3món30s,completeallocation/price-change-after-sale/promo-credit/loss-reportHTTPcoverage. Các QA của lượt này đã dừng, giữ volume. Không kèm dump,imagearchive,credentials,rawlogs hoặc thay đổi application/contract/migration.
