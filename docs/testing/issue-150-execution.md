# #150 — k6: 100 yêu cầu đồng thời, tồn ban đầu 5

**Phụ trách:** Huynh-Phuc-Dat (SV08 — QA/Security). **Ngày thực hiện:** 08/11/2026 (UTC+7). **Issue:** https://github.com/NguyenDokhai2005/GreenMart/issues/150 (hạn 08/11/2026).

## Yêu cầu và baseline

Đã đọc issue #150/comments, CONTRIBUTING, README, CODEOWNERS, SRS AC-02/US-02/BR-01/BR-02/UC-09, BA EX-01, user story #150, OpenAPI POS/inventory/auth, test-plan TC-CONC-01, checklist security, câu hỏi QA và hướng dẫn secrets trên GitHub.

Trước nhiệm vụ, đã kiểm tra trạng thái và pull fast-forward develop đến `9de7444bb1040f45839c9682c938f063cd9e76a2`, rồi tạo nhánh `feature/150-k6-concurrent-stock`. Backend dùng đúng source baseline này; không sửa backend, migration, API contract hoặc generator của thành viên khác. Giữ nguyên ba file nháp có sẵn của người dùng.

AC #150: sản phẩm còn **5**; gửi **100** yêu cầu song song, mỗi yêu cầu mua **1**; đúng **5** thành công. Test-plan cụ thể hóa: **5 HTTP 201**, **95 HTTP 409 INSUFFICIENT_STOCK**, tồn khả dụng cuối **0**, không tồn âm.

Bộ này kiểm tra POS thuần theo issue #150/TC-CONC-01. Không nhận đã hoàn thành kịch bản trộn POS + webhook (TC-CONC-02/#51), deadlock nhiều dòng (#57), replay idempotency (#34), load 50 VU/p95 (#159) hoặc hóa đơn (#259).

## Công cụ và cách hoạt động

- `tests/load/concurrency-stock.js`: k6 executor `per-vu-iterations`, 100 VU × 1 iteration, tối đa 60 giây. Mỗi VU đợi cùng mốc bắt đầu và gửi đúng một POST, không retry; UUID v4 riêng cho Idempotency-Key.
- `tests/load/stock150/run.py`: dựng môi trường riêng và kiểm tra DB trước/sau; chạy k6 Docker image cố định `grafana/k6:1.3.0`; host chỉ cần Python standard library.
- `compose.qa.yml`: project `greenmart-qa150`, volume/network riêng, backend cổng 18082, credentials ngẫu nhiên qua môi trường process. Không dùng override dev, không xuất secrets.
- Seed SV06 nguyên bản chạy trong container riêng (Linux dùng UID/GID host để có quyền ghi bind mount); fixture riêng thêm productId 900150, SKU QA150-STOCK5, giá 10.000, onHand=5/reserved=0 tại branch 1. Không sửa dữ liệu dev/QA148/QA149.
- `test_run.py`: regression test bộ đánh giá, từ chối kết quả bán vượt tồn, thiếu request, HTTP lỗi bị đếm nhầm, DB sai, threshold fail hoặc chạy tuần tự. Đây không phải bằng chứng backend thật.

Setup k6 chỉ login một phiên CASHIER GM-001 và xác nhận tồn đúng 5. Mỗi POST CASH gửi expectedTotal/cashReceived=10000. Chỉ response 201 + nội dung sale/payment đúng mới tính thành công; chỉ 409 + code INSUFFICIENT_STOCK mới tính hết hàng. 200/400/401/403/422/500/timeout hoặc body sai đều không được tính vào 95 request hết hàng. Các bộ đếm, iterations, checks và tồn cuối đều là threshold bắt buộc; k6 trả mã lỗi khi không đạt.

Đồng bộ start có thêm ngưỡng kỹ thuật launch_offset_ms.max < 1000ms để tránh nhận test gửi rải rác là đồng thời. Đây là điều kiện chất lượng của bài test, không phải SLO hiệu năng trong SRS. Thời gian HTTP ghi trong summary gồm cả setup/teardown; không dùng để chứng nhận p95 POS của #159.

Sau k6, đối chiếu DB thật: tồn 0/giữ chỗ 0; đúng 5 đơn COMPLETED, tổng quantity=5, 5 payment PAID/amount=10000, 5 ledger SALE với tổng delta_on_hand=-5, không số dư ledger âm/giữ chỗ vượt tồn. Kết luận PASS chỉ khi k6 exit0, mọi threshold/count/API check và toàn bộ DB invariant cùng đạt.

Token/refresh chỉ tồn tại trong bộ nhớ k6, logout ở teardown. Báo cáo chỉ chứa metric, threshold và tổng đếm DB, không xuất setup data, environment, request/response body, token, hash hoặc secrets.

## Chạy và tái hiện

Yêu cầu Docker Desktop Linux containers/Compose 2.24.4+, Python 3.11+, kết nối mạng khi build/tải image. Không cần cài k6/Node/dependency Python trên host.

```powershell
# Repo root, đã cập nhật develop và ở nhánh nhiệm vụ riêng
python -m unittest discover -s tests/load/stock150 -p test_run.py -v
python tests/load/stock150/run.py --prepare
python tests/load/stock150/run.py --run --output docs/testing/results/issue-150-2026-11-08.json
```

`--prepare` từ chối nếu container/volume QA150 đã tồn tại; `--run` từ chối nếu fixture không còn nguyên 5 hoặc đã có giao dịch, và không ghi đè báo cáo cũ. Mỗi dataset QA150 chỉ dùng một lần. Không tự reset/reseed để chạy lại; giữ dữ liệu lỗi để điều tra. Chỉ thao tác với container được lọc đúng label project QA150 và service postgres.

Nếu cần tự chạy k6 sau khi chuẩn bị (kết quả phải được đối chiếu DB để kết luận #150):

```powershell
docker run --rm --network greenmart-qa150_greenmart --mount type=bind,src=D:/GreenMart/GreenMart/tests/load,dst=/scripts,readonly --mount type=bind,src=D:/GreenMart/GreenMart/.local-tools/stock150-manual-results,dst=/results grafana/k6:1.3.0 run /scripts/concurrency-stock.js
```

Tạo thư mục output trước. Trên Linux, thêm `--user <UID-host>:<GID-host>` để k6 ghi summary; ưu tiên dùng runner Python đã xử lý tự động. Không chạy cả lệnh manual và runner lên cùng dataset. Không chạy vào staging/dev dùng chung để tránh tiêu thụ tồn thật. Không dùng down -v.

Không thêm workflow CI mới cho load test trong nhiệm vụ này; issue #150 yêu cầu script/bằng chứng k6, kiểm thử tải chạy local theo test-plan. Các check CI hiện có chỉ xác nhận khi có PR được phép tạo và workflow thực sự chạy, không tự nhận CI xanh.

## Kết quả và DoD

Đã chạy API/DB thật ngày 08/11/2026 lúc 13:03 (UTC+7), backend baseline `9de7444`, Docker QA150 riêng. [Báo cáo k6 và DB đầy đủ](results/issue-150-2026-11-08.json).

| Chỉ tiêu | Thực tế | Kỳ vọng |
|---|---:|---:|
| Request POS / iterations | 100 / 100 | 100 / 100 |
| HTTP 201 + nội dung đơn/thanh toán đúng | 5 | 5 |
| HTTP 409 INSUFFICIENT_STOCK | 95 | 95 |
| Lỗi ngoài dự kiến | 0 | 0 |
| API checks đạt | 100% | 100% |
| Độ lệch launch lớn nhất | 60 ms | <1000 ms (điều kiện kỹ thuật của test) |
| Tồn thực / giữ chỗ / khả dụng cuối | 0 / 0 / 0 | 0 / 0 / 0 |
| Đơn COMPLETED / tổng quantity / payment PAID | 5 / 5 / 5 | 5 / 5 / 5 |
| Dòng SALE / tổng delta_on_hand | 5 / -5 | 5 / -5 |
| Số dư ledger âm hoặc reserved vượt onHand | 0 | 0 |

**PASS**, k6 exit **0**, tất cả 8 nhóm threshold đạt. Trước chạy: onHand=5, reserved=0, available=5, chưa có đơn/payment/ledger cho sản phẩm fixture. Sau chạy: dữ liệu DB đúng như bảng. Dataset đã tiêu thụ; không chạy lại hoặc reset tự động.

**6/6 unit test bộ đánh giá PASS**, Python compile và git diff check đạt. Không cộng unit test vào số request thật. Stack QA150 được giữ lại để review; không xóa volume.

Kiểm tra lại trước bàn giao: 6/6 unit test tiếp tục PASS, Python compile/diff check đạt; nạp lại báo cáo vào bộ đánh giá vẫn PASS. Đối chiếu DB QA150 bằng truy vấn chỉ đọc khớp hoàn toàn dữ liệu sau chạy đã ghi trong báo cáo; không gửi lại load vào dataset đã tiêu thụ hoặc reset tồn để làm lại bằng chứng.

Phần triển khai/kiểm thử local đã sẵn sàng review. Chưa xác nhận CI GitHub cho #150; review/CI/merge và đóng issue vẫn cần quy trình nhóm. Các metric k6 ở báo cáo chứng nhận lần chạy tại baseline 9de7444, không được diễn giải thành lần chạy load mới trong CI.

Nguồn công cụ: [k6 per-VU iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/per-vu-iterations/), [handleSummary](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/), [k6 v1.3.0](https://github.com/grafana/k6/releases/tag/v1.3.0).
