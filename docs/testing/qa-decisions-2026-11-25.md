# Tổng hợp phương án nghiệp vụ từ QA trình Lead duyệt

Ngày tổng hợp: **25/11/2026**. Người tổng hợp: **Huynh-Phuc-Dat, SV08 QA/Security**.

Tài liệu gom các phương án SV08 đã đưa ra, xác nhận hoặc công bố trong quá trình QA, gồm cả các quyết định trước đợt issue #515–518. Mục đích là để Lead duyệt một bản thống nhất trước khi các module triển khai hoặc cập nhật hợp đồng. **Quyết định nghiệp vụ, việc sửa code và kết quả nghiệm thu là ba trạng thái riêng.** Merge tài liệu này không xác nhận các lỗi đã FIXED, không đóng các issue triển khai.

Mốc đối chiếu nguồn: `develop 05fe1145c12d8e37427a190482287dc27cf4b850`. Các PR chưa merge không được coi là chức năng đã có trên develop. Quyền cập nhật và merge `docs/api/openapi.yaml` vẫn thuộc Lead theo [CONTRIBUTING](../../CONTRIBUTING.md).

## Cách đọc và nguồn quyết định

- **Đã vào hợp đồng:** nội dung có trong BA/OpenAPI tại mốc đối chiếu; vẫn cần đọc contract hiện hành khi triển khai.
- **SV08 chốt, trình Lead:** có xác nhận/công bố của SV08; phần khác contract cần Lead duyệt qua PR.
- **Đề xuất còn mở:** có phương án nhưng chưa đủ bằng chứng đã chốt chi tiết; không tự dùng làm expected result đã ban hành.
- **Đã rút hoặc bị thay thế:** chỉ lưu lịch sử, không phục hồi thành yêu cầu mới.

[Bảng tra cứu 61 issue của SV08](qa-decisions-2026-11-25-index.md) bao gồm cả lỗi hồi quy và đề xuất giao diện. Một issue được tạo hoặc CLOSED không tự chứng minh quyết định đã triển khai và test PASS. Nguồn công khai được dẫn ở từng mục; không đưa nhật ký cá nhân, quy tắc local hoặc dữ liệu xác thực vào PR này.

## Thanh toán và đồng bộ đa kênh

### 1. Ràng buộc sự kiện thanh toán với đúng đơn

Nguồn: [#416](https://github.com/NguyenDokhai2005/GreenMart/issues/416); đã có mô tả trong OpenAPI. Đầu mối theo issue: SV02, SV03, SV07.

- Chữ ký gắn method, raw path có kênh/đơn, event ID, transport timestamp và hash nguyên byte body. Đổi target/nội dung không được dùng lại bằng chứng thanh toán.
- Transport timestamp chấp nhận lệch ±300 giây; khác `occurredAt` nghiệp vụ để không chặn thanh toán đến muộn hợp lệ.
- Retry giữ event ID/path/body, ký timestamp gửi mới; receipt tối thiểu 7 ngày. Retry đúng trả acknowledgement cũ, không thêm payment/refund.
- `providerRef` duy nhất theo gateway/merchant scope do server cấu hình, giữ binding cả sau refund. Không gắn một mã cho hai đơn.
- Payment, trạng thái đơn, audit và outbox phải nhất quán. Regression gồm retry đúng, đổi target/body, sự kiện đồng thời và late PAID.

### 2. Phục hồi callback sau khi hết retry

Nguồn: [#419](https://github.com/NguyenDokhai2005/GreenMart/issues/419), [PR #513](https://github.com/NguyenDokhai2005/GreenMart/pull/513). Phương án SV08 trình duyệt; PR còn OPEN tại mốc tổng hợp. Chính: SV07.

- Giữ retry hữu hạn và FAILED/error queue, không xóa payload sau khi hết lượt. FAILED hữu hạn theo contract cũ không tự đồng nghĩa mất dữ liệu.
- Phương án recovery probe theo kênh: không quá một lần/30 giây; drain backlog có giới hạn khi kênh hồi phục.
- Mục tiêu gửi trạng thái tồn mới nhất trong 60 giây sau phát hiện hồi phục khi tải trong ngưỡng; giữ version, không để snapshot cũ đè mới.
- Sự kiện vòng đời đơn phải giữ hiệu ứng/thứ tự nghiệp vụ, không gộp như snapshot tồn. Manual/auto retry đồng thời không tạo hiệu ứng trùng.
- FAILED kéo dài 24 giờ cần cảnh báo; không tự xóa. Các mốc recovery phải được ghi vào contract/vận hành và kiểm chứng, không nhận đã đạt SLA chỉ vì API nhanh.

### 3. Tính nhất quán và validation nền

Nguồn: [#420](https://github.com/NguyenDokhai2005/GreenMart/issues/420), [#428](https://github.com/NguyenDokhai2005/GreenMart/issues/428), [#384](https://github.com/NguyenDokhai2005/GreenMart/issues/384).

- Body hoặc productId mapping sai phải trả validation theo contract, không 500. Không sửa contract để hợp thức hóa đầu vào lỗi.
- Late PAID không được để giữ chỗ mồ côi khi bước sau lỗi; đối chiếu payment, reservation, trạng thái, audit/outbox trong rollback.
- Backlog sau tải và hồi phục kênh là hai tình huống khác nhau. Kiểm tra hết đường outbox → broker → receiver; không coi p95 HTTP đạt là đồng bộ đã đạt.

## Bán hàng khách hàng và kho

### 4. Khách ngừng hoạt động tại POS

Nguồn: [#418](https://github.com/NguyenDokhai2005/GreenMart/issues/418); đã có quy tắc trong BA/OpenAPI. SV02 chính nghiệp vụ, SV03 và SV04 phối hợp theo issue.

- Quote và hoàn tất đơn mới đều chặn khách inactive, kể cả truyền trực tiếp customerId; trả `422 CUSTOMER_INACTIVE`, không phát sinh order/payment/trừ tồn.
- SĐT inactive không được coi là chưa có khách rồi tạo lại. Hướng dẫn liên hệ OWNER/BRANCH_MANAGER kích hoạt qua API hiện có; CASHIER không tự kích hoạt.
- Lịch sử đơn giữ liên kết khách cũ. Retry đơn đã thành công vẫn trả đơn cũ nếu quyền người dùng còn hợp lệ.
- Giữ ngoại lệ online đã nêu ở #306: nhận theo SĐT, không tự tái kích hoạt khách; không dùng ngoại lệ này để mở quyền POS.

### 5. Kiểm kê khi có hàng giữ chỗ

Nguồn: [#421](https://github.com/NguyenDokhai2005/GreenMart/issues/421); BA/OpenAPI hiện hành đã cụ thể hóa. SV03 chính, SV02 phối hợp reservation/đơn.

- Lưu snapshot, số đếm và audit trên phiếu DRAFT. Chênh lệch bằng số đếm trừ snapshot; tồn dự kiến bằng tồn hiện tại cộng chênh lệch.
- Nếu tồn dự kiến nhỏ hơn reserved, giữ DRAFT và hiển thị đối soát, số thiếu, đơn giữ hàng liên quan; luôn giữ `0 ≤ reserved ≤ on_hand`.
- Quản lý nhập thêm hàng thực hoặc xử lý đơn qua luồng hủy/hoàn được phép; không tự hủy đơn đã trả tiền hoặc sửa reserved bằng SQL.
- Duyệt khác người lập, khóa và kiểm lại trong transaction; không ghi số đếm cũ đè biến động sau snapshot.
- **Phương án cũ khóa giữ/bán mới cho toàn bộ SKU bị ảnh hưởng không được áp dụng mặc định.** BA hiện tại không tạo trạng thái khóa tạm mới; reservation mới có thể khiến lần duyệt sau bị chặn.

### 6. Quyền trên mọi đường trả kết quả POS

Nguồn: [#515](https://github.com/NguyenDokhai2005/GreenMart/issues/515). **SV08 đã chốt ngày 25/11**, trình Lead. SV02 chính, SV03 phối hợp.

- Kiểm role hiện tại và quyền chi nhánh của đơn trước cả cache-hit và DB-fallback. Sau đổi quyền, phiên đăng nhập mới không được nhận kết quả cũ trái quyền.
- Key cũ không miễn BR-11 hoặc `x-roles`. Retry hợp lệ vẫn 200 cùng đơn, không tạo payment/ledger hoặc trừ tồn lần hai.
- Đối chứng đã xác minh: sau đổi branch/role, key mới 403 nhưng key cũ 200. Giới hạn đã chứng minh là đơn chính tài khoản từng tạo và giữ đúng key/body, không phải đọc mọi đơn bất kỳ.

## Khuyến mại danh mục và dữ liệu đầu vào

### 7. Công thức khuyến mại và số lượt dùng

Nguồn: [#425](https://github.com/NguyenDokhai2005/GreenMart/issues/425), [#422](https://github.com/NguyenDokhai2005/GreenMart/issues/422). SV02 chính, SV03/SV04/SV06 phối hợp theo từng phần.

- ITEM-AMOUNT giảm theo từng đơn vị; ORDER-AMOUNT giảm một lần mỗi đơn. Giảm không làm tiền dòng âm.
- `minQuantity` mặc định 1, xét trên chính SKU của dòng; không gộp SKU khác. ORDER minOrder xét sau giảm dòng.
- Một campaign tăng used_count một lần mỗi đơn hoàn tất dù xuất hiện nhiều dòng; seed cùng đơn vị. Giới hạn lượt dùng phải nguyên tử khi đồng thời.
- Giữ chọn một KM/dòng và một KM/đơn có lợi nhất, tie-break theo contract; không tự nhân cap PERCENT theo số lượng.
- Hóa đơn đã bán giữ snapshot, không tính lại theo công thức mới. Quy tắc hoàn lượt khi hủy/hết hạn không tự mở rộng sang trả một phần.
- **Phạm vi danh mục:** phương án cũ productIds hoặc categoryIds là phần mở rộng. OpenAPI tại mốc này chỉ hỗ trợ productIds cho ITEM và ghi categoryIds chưa hỗ trợ v1; không nhận đã triển khai hoặc âm thầm cấp thêm scope.

### 8. Vòng đời thời hạn khuyến mại

Nguồn: [#426](https://github.com/NguyenDokhai2005/GreenMart/issues/426). Phương án QA đã công bố để nhóm duyệt; SV02 chính module, SV03/SV06 phối hợp theo issue.

- Create thông thường yêu cầu start < end và end > now; không activate chương trình hết hạn. Import lịch sử phải có luồng kiểm soát riêng.
- Có thể bật trước một chương trình bắt đầu trong tương lai, nhưng quote/complete chỉ áp khi ACTIVE và `start ≤ now < end`; UI phân biệt chưa đến giờ.
- Job đưa chương trình quá hạn sang ENDED theo mục tiêu ≤60 giây, có audit; quote/complete không chờ job mới chặn giảm giá.
- Seed lịch sử dùng trạng thái phù hợp; không thay hóa đơn quá khứ. Mục tiêu job cần được ghi trong đặc tả và test, không coi issue CLOSED là số đo runtime.

### 9. Chuẩn hóa SKU và tên danh mục

Nguồn: [#430](https://github.com/NguyenDokhai2005/GreenMart/issues/430); đã có phần tương ứng trong BA/API. SV03 điều phối theo issue, SV02 phần category và SV07 phần mapping.

- External SKU demo: trim ngoài, giữ case, dài 1–60, khớp `[A-Za-z0-9][A-Za-z0-9._:-]{0,59}`; mapping và webhook lookup cùng chuẩn hóa.
- Provider khác quy tắc cần adapter/contract riêng. Render chuỗi như text; regex không thay thế kiểm soát XSS.
- Category unique trong cùng parent, kể cả gốc: Unicode NFC, trim/gộp whitespace, so không phân biệt hoa thường nhưng vẫn giữ dấu. Khác parent được trùng.
- Có ràng buộc/khóa DB chống race và chu trình; trùng trả 409 theo mã contract. Không tự gộp category.

### 10. Validation và bảo toàn lịch sử

Nguồn: [#386](https://github.com/NguyenDokhai2005/GreenMart/issues/386), [#387](https://github.com/NguyenDokhai2005/GreenMart/issues/387), [#388](https://github.com/NguyenDokhai2005/GreenMart/issues/388), [#389](https://github.com/NguyenDokhai2005/GreenMart/issues/389), [#406](https://github.com/NguyenDokhai2005/GreenMart/issues/406), [#483](https://github.com/NguyenDokhai2005/GreenMart/issues/483), [#484](https://github.com/NguyenDokhai2005/GreenMart/issues/484).

- Số nguyên không được âm thầm cắt phần thập phân; phần tử null phải được validation, không 500.
- Chặn tràn trong quote và thao tác danh mục đồng thời; không có tổng tiền âm/0 do overflow hoặc chu trình do race.
- Ngừng bán sản phẩm không xóa số bán lịch sử; báo cáo sản phẩm phải tính giảm cấp đơn và đối soát tiền thực thu/hoàn.
- Đây là các yêu cầu hồi quy có nguồn lỗi, không phải thêm chính sách tùy ý. Tham chiếu issue để xác định commit sửa và nghiệm thu riêng.

### 11. Miền offset phân trang

Nguồn: [#516](https://github.com/NguyenDokhai2005/GreenMart/issues/516). **SV08 đã chốt ngày 25/11**, trình Lead. Chính: SV02.

- Giữ page ≥0, size 1..100; kiểm `page × size ≤ 2147483647` bằng long trước JPA cho danh sách dùng Page chung.
- Vượt miền hỗ trợ: `400 VALIDATION_ERROR`, details chỉ trường page và giới hạn theo size; không 500. Trang hợp lệ ngoài dữ liệu trả 200 PageResponse rỗng.
- Ghi điều kiện liên trường vào OpenAPI. Regression size 1/20/100 ngay dưới/trên ngưỡng; không đặt page tối đa cố định không phụ thuộc size.

## Forecast ETL và cách đánh giá AI

### 12. Cutoff vận hành và phục hồi lượt chạy

Nguồn: [#417](https://github.com/NguyenDokhai2005/GreenMart/issues/417), [PR #509](https://github.com/NguyenDokhai2005/GreenMart/pull/509). SV06 chính, SV07 vận hành.

- Múi giờ Asia/Ho_Chi_Minh; job ngày T cập nhật ETL đến hết T−1 trước khi kiểm độ mới và chạy model. Không coi phần ngày T chưa kết thúc là một ngày đầy đủ.
- Production dùng khoảng ngày động; lịch sử cố định chỉ cho demo/backtest có tham số rõ. Startup catch-up khi thiếu lượt hợp lệ, không chỉ khung 02–06h; chống chạy trùng.
- ETL lỗi/stale không báo lượt mới DONE; giữ kết quả tốt gần nhất cùng cutoff/cảnh báo cũ. Không dùng kết quả backtest để chứng minh độ chính xác live.

### 13. Nguồn và ý nghĩa stockout

Nguồn: [#432](https://github.com/NguyenDokhai2005/GreenMart/issues/432). SV06 chính, SV03 phối hợp lịch sử kho. Còn là vấn đề chất lượng dữ liệu cần nghiệm thu.

- Benchmark seed dùng lịch JSON chính thức với provenance; production cần ledger liên tục, số dư đầu ngày và carry-forward ngày không phát sinh giao dịch.
- Stockout theo nghiệp vụ là khả dụng bằng 0 cả ngày Việt Nam, không chỉ một thời điểm zero.
- Thiếu lịch sử phải được nhận diện bằng chất lượng dữ liệu/unknown hoặc chặn đánh giá theo policy; không mặc định false rồi nhận dữ liệu đầy đủ. Metadata/schema mới cần Lead duyệt.
- Dùng cùng mask cho model và baseline, công bố độ phủ và số mẫu bị loại; không sao chép nhãn seed sang production để làm số đẹp.

### 14. Metric horizon provenance và tương thích dữ liệu cũ

Nguồn quyết định SV08: [bình luận #423 ngày 24/11](https://github.com/NguyenDokhai2005/GreenMart/issues/423#issuecomment-7127476270). SV06 chính, SV03 phối hợp DB/API; [PR #514](https://github.com/NguyenDokhai2005/GreenMart/pull/514) còn OPEN tại mốc tổng hợp.

- Scalar `mae`, `wape`, `baselineMae`, `baselineWape` của run mới đại diện **H7, overall, HORIZON_AGGREGATE_POSTPROC**. Breakdown bắt buộc có H7/H30; AC-05 phải hơn baseline tốt nhất ở cả hai tầm.
- Baseline chọn theo WAPE giữa weekly-naive và rolling-mean28 trên cùng dữ liệu, origin, horizon, mask và fallback; cặp baseline MAE/WAPE thuộc cùng baseline thắng. Giữ cả hai để đối chiếu.
- Phân biệt cutoff dữ liệu phục vụ và evaluation cutoff/origin. Metadata gồm horizon, aggregation, postprocess/protocol version, cửa sổ test, stockout mode, nguồn/hash dữ liệu.
- Breakdown có schema cho raw daily/aggregate, overall/top200/slow-moving/zero và non-zero demand; kèm số mẫu và tổng actual. Chi tiết ở run detail; list/event giữ summary gọn và runId.
- Giữ threshold slow-moving **0.4** cho phiên bản này: daily `<0.4` về 0; bằng 0.4 không bị xóa. Áp fallback chuỗi dưới 28 ngày và clip theo đúng thứ tự serving; evaluator cùng policy/version.
- Không tự tune theo từng lượt trong #423. Đổi threshold sau này phải freeze trên validation trước test và tạo version/artifact mới; không nhìn test rồi chọn ngưỡng.
- Khóa cửa sổ test 30 ngày; giữ benchmark H7 hiện có để tái lập và báo rolling H7 riêng. Công bố origin/ngày thực dùng, không trộn protocol hoặc gọi dữ liệu phát triển là blind test mới.
- WAPE là tỷ lệ có thể >1; UI đổi ra %. Tổng actual ≤0: null/N/A, vẫn báo MAE/số mẫu; không coi N/A là PASS, không xuất NaN/Infinity, không tự clip actual âm.
- Legacy giữ ngữ nghĩa/số cũ, đánh dấu legacy; không gắn nhãn postprocessed hoặc backfill không tái lập được. RUNNING/FAILED chưa có metric hợp lệ dùng null thay vì 0 giả.
- Kết quả phục vụ lấy metadata từ đúng run/artifact đã lưu; tổng horizon khớp daily points và dữ liệu ROP. Không trình bày metric toàn run như accuracy của riêng SKU.

### 15. Runtime docs và trạng thái chưa có dự báo

Nguồn: [#431](https://github.com/NguyenDokhai2005/GreenMart/issues/431), [#433](https://github.com/NguyenDokhai2005/GreenMart/issues/433). SV06/SV07 phần runtime; SV05 chính UX, SV03 phối hợp API.

- Production tắt runtime OpenAPI/docs/Redoc mặc định; dev bật bằng cấu hình rõ. Contract tĩnh trong repo vẫn dùng cho nhóm, business API vẫn cần auth, health chỉ trạng thái tối thiểu.
- Giữ 404 khi chưa có DONE/series theo contract; frontend có EmptyState và message đúng ngữ cảnh. Không nuốt 401/403/500 thành trạng thái rỗng hoặc tự thêm nút/quyền chạy model.

## Reporting và quản trị

### 16. Bán trả cùng kỳ và khác kỳ

Nguồn: [#517](https://github.com/NguyenDokhai2005/GreenMart/issues/517). **SV08 đã chốt ngày 25/11**, trình Lead. SV06 chính; SV02/SV03 phối hợp Returns.

- Doanh thu bán giữ đơn từng hoàn tất, gồm COMPLETED/RETURNED, theo completed_at. Chỉ trừ phiếu trả APPROVED theo approved_at.
- Cùng kỳ bán và trả đủ đóng góp net 0. Hoàn kỳ sau chỉ giảm kỳ trả, không xóa lại doanh thu kỳ bán trước. Tổng kỳ vẫn có thể âm hợp lệ do hoàn đơn kỳ trước.
- Số đơn bán đếm giao dịch hoàn tất, kể cả sau đó RETURNED; số phiếu trả tách riêng. Doanh thu bán thuộc chi nhánh bán, tiền hoàn thuộc chi nhánh nhận trả; toàn chuỗi đối soát đúng.
- Đồng nhất revenue/top-products và định nghĩa liên quan. BA/OpenAPI hiện chỉ ghi COMPLETED nên phải cập nhật cùng PR; không nói coder trái contract cũ.
- Bằng chứng cần phân biệt: tổng tiền hoàn vẫn bằng tiền bán, không có bằng chứng refund thực tế hai lần; bất thường đã tái hiện ở báo cáo.

### 17. Tổng tiền vượt miền int64

Nguồn: [#518](https://github.com/NguyenDokhai2005/GreenMart/issues/518). **SV08 đã chốt ngày 25/11**, trình Lead. SV06 chính; Lead quản lý Money, SV02 phối hợp nếu đổi cận Sales.

- Giữ Money/int64; tính chính xác để kiểm tổng từng nhóm và toàn báo cáo trước chuyển long. Không dùng float, cắt số hoặc wrap âm.
- Vượt miền: 400 VALIDATION_ERROR, thông báo tổng vượt miền hỗ trợ và details bộ lọc liên quan; không 409 xung đột chung. Thu hẹp bộ lọc khi có thể; không tự sửa/xóa đơn đã nhận hoặc đặt cận giá mới trong issue này.
- Hỗ trợ tổng lớn hơn int64 là thay đổi schema riêng cần đánh giá client/DB. Không đòi API trả 1e19 trong schema int64 hiện tại; oracle cũ kỳ vọng đó không hợp lệ.
- Cập nhật mô tả lỗi ở BA/OpenAPI. Ca thử giá cực lớn là boundary P3, không suy rộng sang doanh thu thông thường.

### 18. Quản trị Users và thu hồi quyền

Nguồn: [#424](https://github.com/NguyenDokhai2005/GreenMart/issues/424), [#434](https://github.com/NguyenDokhai2005/GreenMart/pull/434). SV03 chính; UI theo các task Admin riêng.

- Chỉ ADMIN quản trị Users theo OpenAPI; bảo vệ thao tác tự khóa/tự bỏ role và ADMIN cuối cùng. Không trả password/hash.
- Thay đổi role/branch, vô hiệu hóa hoặc đổi mật khẩu phải thu hồi phiên tương ứng; audit thao tác quản trị. Kiểm tra bằng phiên mới và cả API nghiệp vụ, không chỉ nhìn menu.
- Phân biệt task QA #144 với task triển khai Users; không giao thành viên mới chỉ từ assignee của test case.

## Giao diện tìm kiếm và cách nhập liệu

### 19. POS và giao diện bán hàng

Nguồn: [#442–447](qa-decisions-2026-11-25-index.md), [#453–457](qa-decisions-2026-11-25-index.md), [#461–467](qa-decisions-2026-11-25-index.md). SV04 phần Sales UI; SV02/SV03 phần tìm kiếm Backend; thiết kế vẫn cần Lead duyệt.

- Giỏ giữ được khi chuyển mục/tải lại theo #445; giảm tay chỉ QLCN, có lý do và API kiểm quyền theo #447. Không sửa bằng cách mở quyền thu ngân.
- F4 khi giỏ hợp lệ mở hộp thanh toán như nút bấm; **không tự thu tiền/tạo đơn**. Giữ guard disabled/loading/dialog/modifier và kiểm tra vị trí focus theo #467.
- Tiền khách đưa hỗ trợ định dạng nghìn rõ, ví dụ `38.000` → 38000; hiển thị giá trị đã chuẩn hóa trước xác nhận. API vẫn nhận tiền nguyên, không hiểu `62.000` thành 62.
- Trim username, không trim mật khẩu; SĐT chuẩn hóa trước validation/unique. **Mở rộng +84 chưa được chốt chỉ từ đề xuất #456**, không coi backend chặn +84 là sai contract.
- Tìm không dấu là mở rộng đối chiếu theo từng endpoint, không gộp lỗi Backend và Frontend. Trim query UI; escape ký tự LIKE nếu contract dùng tìm literal.
- Hóa đơn tìm theo mã/SĐT; thống nhất ký hiệu tiền, không hiện âm 0; gợi ý tồn/target nút theo thiết kế đã duyệt. Không tự đặt lại kích thước Figma từ một ảnh QA.

### 20. Operations trạng thái lỗi và phạm vi màn hình

Nguồn: [#448–452](qa-decisions-2026-11-25-index.md), [#454](https://github.com/NguyenDokhai2005/GreenMart/issues/454), [#458–460](qa-decisions-2026-11-25-index.md), [#463](https://github.com/NguyenDokhai2005/GreenMart/issues/463), [#466](https://github.com/NguyenDokhai2005/GreenMart/issues/466), [#468](https://github.com/NguyenDokhai2005/GreenMart/issues/468). SV05 Operations, Admin theo phân công riêng #475/#476.

- Route chuyển kho phải đúng loại chứng từ; picker không chỉ tìm trang đầu; ledger không báo không có kết quả chỉ vì đang lọc trang hiện tại. Bộ lọc “Tất cả” có nhãn rõ.
- Validation khoảng ngày/số lượng bằng tiếng Việt, đúng giới hạn; lỗi 403 có thông báo quyền phù hợp, không dùng nút Thử lại vô ích.
- Phân biệt placeholder/chưa triển khai với lỗi Backend; dùng API thật, đủ loading/empty/error/role/branch và responsive theo task/Figma. Không mở task trùng Đơn/Trả hàng/Dashboard/Dự báo đã có.
- Toast thành công phải có cơ chế kết thúc và không bị hiểu là kết quả thao tác sau; **5–8 giây trong #458 mới là ví dụ**, chưa phải mốc đã chốt. Không tự đưa timeout mới vào contract.

## Phương án cũ và điểm chưa đủ xác nhận

### 21. Q31 về audit sai vai trò

Nguồn: [#242](https://github.com/NguyenDokhai2005/GreenMart/issues/242), [#310](https://github.com/NguyenDokhai2005/GreenMart/issues/310), [câu hỏi QA](qa-questions-week1.md). **Đề xuất còn mở**, không nhận đã được Lead ban hành chỉ vì SV08 từng yêu cầu lập phương án.

- Phương án trình duyệt: request đã xác thực bị chặn sai role trả 403 FORBIDDEN và ghi đúng một audit FORBIDDEN, actor từ principal, entity http_request, entity_id null; method/path/traceId/ROLE_NOT_ALLOWED/role hiệu lực trong detail.
- Không ghi token/password/cookie/body/query/stack trace. Ghi transaction riêng, chống ghi trùng qua filter/service/handler; không truy vấn thêm tài nguyên bị cấm để tạo audit.
- Sai chi nhánh giữ BRANCH_FORBIDDEN theo Q-27; 401 không tạo actor giả. Audit sink lỗi vẫn từ chối 403 và cảnh báo vận hành, không cho phép thao tác.
- SV03 chính đề xuất, SV08 test, SV07 phối hợp vận hành. TC-AUTH02-41 vẫn BLOCKED phần expected audit tới khi có quyết định được duyệt; quan sát 0 dòng audit không phải PASS chính sách.

### 22. IP token và tham số đã bị thay thế

- Đề xuất cũ IP 30 lần sai/60 giây, trần 120 request và khóa 5 phút **không áp dụng**. Contract sau [PR #434](https://github.com/NguyenDokhai2005/GreenMart/pull/434) quy định login 20 request/IP/60 giây, request 21 chặn 60 giây; refresh 60 request/IP/60 giây. Đây là nguồn hiện hành, không nhận các con số đó do SV08 tự chốt.
- [#429](https://github.com/NguyenDokhai2005/GreenMart/issues/429) đã đóng **NOT_PLANNED** theo yêu cầu trước đó. Không phục hồi yêu cầu mọi refresh reuse phải thu hồi toàn family từ đề xuất cũ thành bug mới.
- Access 15 phút, refresh 7 ngày, xoay refresh cùng sid và logout thu hồi phiên ngay theo contract hiện hành. Đổi mật khẩu/role/branch và vô hiệu hóa xử lý thu hồi theo Auth/Users; không gộp với phần đã rút ở #429.
- CORS allowlist, retention audit, timeout toast và phạm vi categoryIds chưa được chốt chi tiết trong những nguồn trên phải theo dõi riêng; không bổ sung giá trị giả định chỉ để biến test BLOCKED thành PASS.

## Điều kiện Lead duyệt và triển khai

- [ ] Lead xác nhận các mục SV08 trình duyệt, các điểm khác hợp đồng và những mục vẫn còn mở; không gộp chúng thành tất cả đã triển khai.
- [ ] PR sửa từng module cập nhật BA/OpenAPI khi đổi ngữ nghĩa, mã lỗi, giới hạn hoặc metadata; giữ tương thích dữ liệu cũ theo quyết định tương ứng.
- [ ] Bổ sung negative, boundary, role/branch, concurrency, rollback và regression đúng trigger; bằng chứng có commit/môi trường, loại secrets.
- [ ] Chỉ ghi FIXED sau test bản sửa; review, CI và merge là các điều kiện riêng. PR tài liệu dùng Refs, không tự đóng issue sửa code.

Phạm vi bản tổng hợp là các phương án và nguồn đã tìm thấy trong 61 issue do SV08 tạo, bình luận #423 và tài liệu QA liên quan. Những quyết định vận hành chỉ local và quy tắc trợ lý cá nhân không thuộc tài liệu công khai này.
