# Ma trận AC / NFR / BR — 18/11/2026

Baseline **9daa47de2e8ad5ebd755650d5023af637b3d82f9**. Nguồn: SRS, BA, OpenAPI và quyết định Lead295/314/259. PASS giới hạn theo scenario/layer ghi dưới, không đồng nghĩa issue Done. Các phạm vi NOT_RUN/BLOCKED được giữ nguyên.

## AC01–06

| ID | Trạng thái | Bằng chứng và giới hạn |
|---|---|---|
| AC01 | PASS | Fresh seed:3 branch/1.000 product/10.000 đơn lịch sử/2.000 mapping;180 alert khớp; generator reproducibility trong pytest. Tổng mọi trạng thái là10.200 đơn. |
| AC02 | PASS | POS100/stock5, mixed100 và đảo2SKU/100: đúng5 đơn/đơn vị thành công,95 từ chối; SQL không âm tồn. |
| AC03 | PASS chức năng; FAIL độ trễ sau tải | WEB/MARKET, duplicate/version/retry và mapped outage300s PASS. #384 vượt60s sau50VU. |
| AC04 | PASS API đã có | RBAC764 PASS, deny403 đúng; quyền mới promotion/ROP, ngoại lệ Lead314/259 đã xác nhận. Users/Forecast public chưa triển khai. |
| AC05 | PASS backtest | Horizon aggregate:7d79,28%<97,02%;30d50,48%<72,38%; validation threshold0,4. Bán chậm gần toàn0, giữ caveat. |
| AC06 | PASS API/DB subset; NOT_RUN toàn UI | Seed180 alert, manual ROP và PostgreSQL alert tests PASS. Chưa đo trọn dashboard/widget bán xuống ROP→nhập lên ROP/đóng cảnh báo trên UI. |

## NFR01–14

| ID | Trạng thái | Bằng chứng và giới hạn |
|---|---|---|
| NFR01 | PASS | 50VU/300s,13.984 luồng; p95 đọc10,38ms/tạo55,55ms; lỗi0,01287%<1%; logical0. Chưa xác minh từng lỗi HTTP nhỏ lẻ. |
| NFR02 | FAIL | #384:20.325 PENDING,2 event trace mới vẫn chờ61,168s. Expiry/release được kiểm tra riêng. |
| NFR03 | PASS scenario đã chạy | Ba nhóm race, SQL stock/reserve/ledger và idempotency; không chứng minh mọi lịch xen kẽ có thể. |
| NFR04 | PASS mapped outage | QA163 có mapping và callback baseline; outage≥300s, commit/outbox/replay/callback21/21 PASS. Không suy ra hồi phục≤60s khi chưa đo mốc đó. |
| NFR05 | PASS implemented Auth/RBAC | BCrypt cost≥10/distinct hash; JWT900s, refresh/reuse/logout/change-password/lockout;764 RBAC. API chưa triển khai không được tính là đủ chức năng. |
| NFR06 | PASS subset; BLOCKED policy/triage | Security28 PASS; CORS positive/IP policy chưa chốt; log đầy đủ chưa kiểm tra; CVE chưa triage tính áp dụng. |
| NFR07 | PASS tracked snapshot; NOT_RUN history-wide | Gitleaks0 finding và SecretsCI9daa xanh. Không đọc.env/ignored private files hoặc quét toàn lịch sử Git. |
| NFR08 | PASS audit DB subset; BLOCKED log chain | Audit36 PASS, ADJUST/cancel audit bổ sung PASS. Q31 sai-role còn chờ; không đọc raw JSON logs xuyên HTTP/RabbitMQ. |
| NFR09 | PASS isolated services | Compose config/health, fresh QA và recovery đã chạy tuần tự theo gói. Chưa nghiệm thu một lệnh full deployment trên máy/staging khác. |
| NFR10 | NOT_RUN staging | Đọc CI và trạng thái Secrets; không dispatch CI/deploy. #284 còn mở lúc đối chiếu. |
| NFR11 | PASS unit coverage; thiếu phần E2E | Backend538,Frontend294,Forecast214+ca SKIP rerun riêng1 PASS,Connector8. Coverage85,75%/96,04%. POS UI có; online UI chưa có. |
| NFR12 | PASS360px subset; NOT_RUN3món/30s | 12 account, cart/catalog/payment/documents ở360px PASS. Chưa đo chuẩn thao tác thủ công POS3 món dưới30 giây. |
| NFR13 | NOT_RUN audit kiến trúc/quy trình toàn diện | Đọc rules/CODEOWNERS và source liên quan; chưa audit toàn callgraph/interface/events và toàn bộ lịch sử PR. Lượt này không xuất bản Git. |
| NFR14 | PASS QA | FreshV16; upgradeV15→V16 giữ số bản ghi; backup/restore mới khớp6 bảng. Không phục hồi ghi đè DB có sẵn. |

## BR01–19

| ID | Trạng thái | Bằng chứng và giới hạn |
|---|---|---|
| BR01 | PASS scenario đã chạy | Ba nhóm concurrency; onHand/reserved/available và noNegative đối soát SQL. |
| BR02 | PASS | CASH/CARD/QR thật; debit1 lần, rollback khi payment lỗi; Redis-down idempotency PASS. |
| BR03 | PASS | Deadline RESERVED khoảng900s; chỉ dời deadline fixture để scheduler thật chạy, không ép status/release. |
| BR04 | PASS release/refund; NOT_RUN toàn promo-credit | Expiry,latePAID và cancel release/refund/audit PASS. Chưa có HTTP matrix riêng hoàn lượt KM trên mọi nhánh cancel/expire/reject. |
| BR05 | PASS | WEB/MARKET duplicate không tạo đơn thứ hai; idempotency và unique checks. |
| BR06 | PASS DB/integration | InventoryServiceTransactionTest.ledgerRejectsSqlUpdateAndDeleteAfterCommit PASS; actor/ref/balances và ADJUST delta đúng. Audit mutation được test riêng. |
| BR07 | PASS pricing subset; NOT_RUN hết biên | BA7,item/order promotion,tie expiry,usage/gating và20% PASS. Chưa HTTP matrix riêng hết biên làm tròn phân số/thời gian. |
| BR08 | PASS | Last-use concurrent:1 HTTP201/1 TOTAL_CHANGED422,usedCount1. |
| BR09 | PASS DB/API subset | Seed180 alert; ROP25/available17 mở1 alert. Integration test tái dùng row và đóng sau replenishment PASS; chưa signoff widget UI. |
| BR10 | NOT_IMPLEMENTED | #43 return API chưa có; #82 UI còn mở. Seed refund không thay workflow7 ngày/fresh-food. |
| BR11 | PASS permission đã chạy |764 RBAC và quyền mới; ngoại lệ branch metadata/ADMIN Sales theo Lead. Không mở quyền để làm xanh expected cũ. |
| BR12 | NOT_RUN HTTP đổi giá sau bán | Đã kiểm tra detail/snapshot/discount/report trên đơn thật; chưa lưu case HTTP riêng đổi giá sản phẩm sau bán rồi đọc lại đơn. |
| BR13 | PASS | Self-approval422; keeper tạo/manager duyệt HTTP/UI; DRAFT20→APPROVED22; approve lặp bị chặn. |
| BR14 | PASS subset; NOT_RUN hết allocation | Default/reject/mixed và no-oversell đã chạy. Chưa HTTP matrix riêng mọi fallback/tie/tổng khả dụng cao nhất/no-split multiSKU online. |
| BR15 | PASS chức năng; FAIL deadline sau tải | Available theo serviced branches−safety stock đúng; old version bị bỏ qua. #384 làm cập nhật chậm sau tải. |
| BR16 | PASS receipt; NOT_RUN loss report | Nhận2/3 có chênh1,source2/destination2; nhận thừa422/lặp409. Chưa đối chiếu báo cáo hao hụt/UI transfer. |
| BR17 | PASS | Snapshot20,count22,sale còn19,duyệt delta+2→21, không ghi đè22. |
| BR18 | PASS reporting/data scenarios | Gross/refund/net cùng ngày/branch,1.095.000 daily_sales,fix388/389 PASS. Return API tương lai vẫn chưa triển khai. |
| BR19 | PASS tested role/reason | CASHIER sửa giá bị403; manager discount/reason và allocation được retest; unit/role suites đã chạy. |

Xem `system-recheck-2026-11-18.md` và JSON tổng hợp đi kèm. Đây là báo cáo coverage; các ghi chú NOT_RUN/BLOCKED không được chuyển thành Done.
