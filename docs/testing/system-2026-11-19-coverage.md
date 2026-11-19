# Ma trận phạm vi QA19/11

Baseline636ed67 toàn10nhóm; follow-up3308869 phần Customer/SyncLog/Auth/Branch/frontend/schema. PASS chỉ cho scenario/layer đã chạy, không phải issueDone. [Báo cáo](system-bughunt-2026-11-19.md).

| ID | Kết quả / giới hạn |
|---|---|
|AC01|PASS seed3branch/1000product/10000history/2000mapping/180alerts; tổng mọi status10200orders|
|AC02|PASS3race100,5units/95reject; không chứng minh mọi interleaving|
|AC03|PASS chức năng HMAC/retry/outage; FAIL đồng bộ≤60s sau tải#384|
|AC04|PASS764checks API đã có; Users/BackendForecast thiếu implementation|
|AC05|PASS postprocessed horizon aggregate H7/H30; slowSKU gần toàn0 là giới hạn|
|AC06|PASS alert/ROP API/DB subset; chưa toàn dashboardUI|
|NFR01|PASS50VU300s, readp95 15,8517ms/create80,3545ms; error0 trong bài đo|
|NFR02|FAIL#384:20802PENDING,2event mới chờ61,123s; expiry kiểm tra riêng|
|NFR03|PASS stock/reserve/idempotency scenarios; chưa mọi lịch đồng thời|
|NFR04|PASS ngắt broker300s/replay/callback; chưa mọi SLA recovery|
|NFR05|PASS Auth/RBAC triển khai; UsersCRUD chưa có|
|NFR06|PASS28securitychecks; BLOCKED_SPEC CORS/IP; CVE cần triage, chưa fullOWASP/TLS|
|NFR07|PASSgitleaks0 tracked snapshot; NOT_RUN toànGithistory/private.env|
|NFR08|PASS36audit+ADJUST/cancel/branchdenial; Q31chưa chốt/full logchain NOT_RUN|
|NFR09|PASSQA Compose/health tuần tự; NOT_RUNdeploy một lệnh trên máy khác|
|NFR10|NOT_RUNstaging/deploy; không tự dispatchCI|
|NFR11|PASS539backend/294frontend/Forecast214+1riêng/Connector8; followup81targeted; thiếu phầnE2E tương lai|
|NFR12|PASS36browser desktop1440/mobile360; NOT_RUN3món/30s,thiết bị thật,Safari/Firefox|
|NFR13|NOT_RUNaudit kiến trúc/callgraph/lịch sửPR toàn diện|
|NFR14|PASSfresh/upgrade15→16→17/backuprestoreDBmới6counts; không ghi đè dữ liệu|
|BR01|PASS3race/SQLno-negative trong fixture|
|BR02|PASSPOSCASH/CARD/QR,rollback,idempotency Redis-down|
|BR03|PASSdeadline900s; dời deadline fixture cho scheduler thật, NOT_RUNchờ15phút theo đồng hồ|
|BR04|PASSrelease/refund/cancel/expiry; chưa matrix hoàn lượtKM mọi nhánh|
|BR05|PASSWEB/MARKETduplicate/idempotency/unique|
|BR06|PASSledger chống SQLupdate/delete trong integration, actor/ref/balance|
|BR07|PASSpricing subset/tie-break/gating; chưa hết biên; Promotionnull là lỗi validationCRUD|
|BR08|PASScampaign usage atomic last-use và đếm theo campaign; chưa mọi status-edit race|
|BR09|PASSseed180alerts/manualROP/pack; chưa toàn dashboardUI|
|BR10|NOT_IMPLEMENTEDpublic returnAPI#43; không nhận đã test returnE2E|
|BR11|PASSimplemented RBAC; branch bypass không bypass role; branches/me theo quyết địnhLead|
|BR12|PARTIALreceipt giá fixture; NOT_RUNHTTP đổi giá sau bán rồi so lại đơn cũ|
|BR13|PASScreator không tựapprove;Manager self422/wrongrole403;stock đổi ởapprove/receive|
|BR14|PASSonline allocation/OOS/mixed; chưa mọi tie-break đaSKU|
|BR15|PASSstock aggregation/safety/version; FAILSLA sau tải#384|
|BR16|PASStransfer/actualpartial/duplicate receive; chưa mọi UIhao hụt|
|BR17|PASSsnapshot20,count22,sale19,approve+2=21|
|BR18|PASSCOMPLETED theo completedAt/refund approvedAt;daily_salesnet;regression388/389|
|BR19|PASScashier khôngmanualdiscount,manager cần lý do;role/branch/staleTotal|

5 Playwrightfixme giữ nguyên: payment+invoice, online, stockdocs, transfer, Forecast. Payment/docs đã có8case bổ sung; không coi5skip là5bug hoặc xóa skip để làm CI xanh.
Các gap/spec chưa chốt/NOT_RUN không được tính PASS. Chỉ JSON summary công khai đã lọc, không đưa rawlog/secret/DBdump/công cụ cá nhân vào PR.
