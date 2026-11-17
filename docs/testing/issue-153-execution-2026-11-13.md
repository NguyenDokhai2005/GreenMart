# #153 — Kiểm thử khuyến mại và đơn hết hạn

QA: Huynh-Phuc-Dat (SV08). Ngày 13/11/2026. **38 checks: 28 PASS, 6 FAIL, 4 BLOCKED. Lượt chạy hoàn tất, không lỗi môi trường; #153 chưa đủ điều kiện nghiệm thu.**

## Yêu cầu và baseline

- [#153](https://github.com/NguyenDokhai2005/GreenMart/issues/153) yêu cầu kết quả EX-05/EX-06/EX-10 đúng BA mục 11, tham chiếu SRS ORD-03/PRM-02/BR-07/BR-08. Issue giao SV08, hạn 13/11, chưa có bình luận quyết định bổ sung.
- Đọc bản GitHub của CONTRIBUTING, README, CODEOWNERS, template PR, SRS, business-analysis, user-stories, API README/OpenAPI, test-plan/security-checklist/QA questions và các issue phụ thuộc.
- Đã chuyển `develop`, pull `--ff-only origin develop`: vẫn mới nhất tại **`83589787bd78aec61940177f9863a80853ca6226`**. Nhánh mới **`feature/153-promotions-expiry-qa`**. Nhánh/commit/bằng chứng #152 được giữ nguyên, không merge/rebase hoặc stage file khác.
- Image `greenmart-backend:qa153-8358978` là alias của image đã build từ cùng source, không dùng bản backend cũ. Image ID `sha256:7e2fed7000456bdf1fdce60607a6d2411a4ba4effcbeffa8e18fbac0b79a609b`; OCI revision khớp commit trên.

## Phạm vi nghiệp vụ cần chứng minh

| Ca | Given / When | Expected theo BA/SRS | Kết quả hiện tại |
| --- | --- | --- | --- |
| EX-05 | Đơn RESERVED giữ 3 đơn vị; quá reservedUntil = thời điểm nhận + 15 phút | Trong 1 phút chuyển EXPIRED, giảm reserved 3/ghi RELEASE, kênh nhận tồn mới | BLOCKED #40/#41 |
| EX-06A | Đã EXPIRED, PAID đến muộn và còn đủ hàng | Giữ chỗ lại, CONFIRMED; không tồn âm/giữ chỗ trùng | BLOCKED #40/#41 |
| EX-06B | Đã EXPIRED, PAID đến muộn và không đủ hàng | CANCELLED, payment REFUNDED giả lập; không giữ tồn thiếu | BLOCKED #40/#41 |
| EX-10 | KM ACTIVE còn 1 lượt, hai báo giá có giảm hợp lệ; hai đơn đồng thời | Một đơn dùng giảm, đơn còn lại 422 TOTAL_CHANGED kèm quote mới; used_count không vượt limit | BLOCKED #39 vì quote chưa áp KM |

Không đổi kỳ vọng, không lấy mock hoặc SQL ép trạng thái EXPIRED làm bằng chứng chức năng đã hoạt động.

## Môi trường và fixture

- Stack mới riêng **`greenmart-qa153-20261113`**, backend **`http://127.0.0.1:18155`**, PostgreSQL/Redis/RabbitMQ thật, network/volume riêng. Cả bốn service healthy sau test. Không dừng hoặc thay dữ liệu QA152/dev.
- Secrets hạ tầng ngẫu nhiên chỉ truyền qua môi trường process; Compose loại env_file và kiểm tra bằng `config --quiet`. Không đọc `.env` hoặc raw logs có thể chứa secrets.
- Generator SV06 nguyên bản nạp một lần vào DB mới. Không sửa generator/migration, không reset/reseed DB cũ.
- Sản phẩm fixture riêng giá 100.000/180.000/223.000/250.000/260.000/350.000/240.000 đ được tạo qua API OWNER. Thủ kho lập IMPORT 20 đơn vị mỗi sản phẩm; manager khác người lập duyệt qua API. Có quote 100.000 không KM và POS 201/COMPLETED làm positive control trước khi tạo KM.
- CRUD promotion #38 chưa được triển khai nên dùng **DML fixture chỉ trong QA153**: INSERT chương trình có prefix QA153 ngẫu nhiên và phạm vi; UPDATE trạng thái/lượt/thời gian/phạm vi chỉ các ID vừa tạo. Không sửa các chiến dịch seed. Nếu có campaign đang ACTIVE, còn hiệu lực trước setup, runner dừng để tránh dữ liệu nhiễu.
- Hai chương trình BA: 10% cho đơn từ 200.000, tối đa 30.000; giảm 25.000 cho đơn từ 300.000. Cả hai ACTIVE, GM-001, kênh POS, used_count=0, đúng thời gian DB. Case hòa 25.000 điều chỉnh riêng fixture AMOUNT xuống ngưỡng 200.000, end_at sớm hơn. Case lượt cuối dùng campaign 10%, limit=1, used_count=0.
- Snapshot eligibility của ba campaign được lưu ngay sau khi INSERT: status ACTIVE, effectiveNow=true, branches=[1], channels=[POS]. Giá trị ID/code/time/limit được đọc lại từ DB, không chỉ dựa vào input fixture.
- DML đặt used_count=1 hoặc end_at ở quá khứ chỉ tạo control hết lượt/hết hiệu lực; **không chứng minh hệ thống đã tự trừ lượt hoặc job tự làm đơn hết hạn**.

## Đã chạy thực tế

Runner: [tests/api/issue153_edges.py](../../tests/api/issue153_edges.py). Bằng chứng: [issue-153-2026-11-13.json](results/issue-153-2026-11-13.json).

| Nhóm checks | PASS | FAIL | BLOCKED |
| --- | ---: | ---: | ---: |
| Login, tạo sản phẩm/tồn, xác minh campaign fixture | 16 | 0 | 0 |
| Quote/POS không KM làm positive control | 2 | 0 | 0 |
| BA 180.000 và ngoài phạm vi kênh | 2 | 0 | 0 |
| BA 223.000 / 260.000 / 350.000 | 0 | 3 | 0 |
| Chọn KM hết hạn sớm hơn khi hòa số giảm | 0 | 1 | 0 |
| Hai quote đủ điều kiện campaign còn một lượt | 0 | 2 | 0 |
| Quote không thay lượt dùng hoặc tạo đơn | 1 | 0 | 0 |
| Control PAUSED / hết lượt / hết hiệu lực | 3 | 0 | 0 |
| EX-10 tranh lượt cuối | 0 | 0 | 1 |
| EX-05 và hai nhánh EX-06 | 0 | 0 | 3 |
| Logout cả bốn phiên | 4 | 0 | 0 |
| **Tổng** | **28** | **6** | **4** |

PASS của các control không áp dụng KM chưa chứng minh bộ lọc trạng thái/phạm vi đúng: implementation đang không áp bất kỳ KM nào, nên các ca positive bắt buộc phải đạt trước khi nghiệm thu BR-07. 28 PASS không có nghĩa EX-05/06/10 đã PASS.

### Kết quả quote sai — phụ thuộc #39, SV02 @t-boai

| Tình huống | Mong đợi | Thực tế HTTP 200 |
| --- | --- | --- |
| Subtotal 223.000 | Giảm 22.300; total 200.700 | orderDiscount=0, total=223.000, orderPromotion=null |
| Subtotal 260.000 | Giảm 26.000; total 234.000 | orderDiscount=0, total=260.000 |
| Subtotal 350.000 | Giảm 30.000 theo cap; total 320.000 | orderDiscount=0, total=350.000 |
| Hai KM cùng giảm 25.000 trên subtotal 250.000 | Chọn AMOUNT hết hạn sớm hơn; total 225.000 | Không chọn KM; total=250.000 |
| Hai actor quote subtotal 100.000, campaign 10% còn một lượt | Cả hai thấy total 90.000 trước khi hoàn tất | Cả hai total=100.000, không có KM |

Sáu FAIL thuộc một nhóm chức năng chưa hoàn chỉnh, không phải sáu bug độc lập. `DefaultPromotionCalculator` hiện trả null ở cả ITEM và ORDER; `QuoteServiceImpl` gọi implementation đó. Đây phù hợp trạng thái [#39 đang OPEN](https://github.com/NguyenDokhai2005/GreenMart/issues/39), giao SV02 @t-boai. Không tự mở bug trùng hoặc sửa backend của SV02.

EX-10 không gửi đồng thời checkout với total giảm giả khi quote thật không trả giảm. Vì vậy **chưa kiểm chứng race/atomicity của PromotionService qua luồng thực**, không kết luận counter bị race chỉ từ việc calculator chưa triển khai.

### Phần hết hạn đơn bị chặn — #40/#41, SV02 @t-boai

- [#40](https://github.com/NguyenDokhai2005/GreenMart/issues/40) và [#41](https://github.com/NguyenDokhai2005/GreenMart/issues/41) đều OPEN. Chưa có controller nhận đơn/sự kiện online hoặc scheduled job hết hạn trong module Sales tại baseline. Các scheduler outbox/delivery không phải job expiry.
- Diagnostic có JWT hợp lệ: GET `/promotions`, POST `/integrations/WEB/orders`, POST `/integrations/WEB/orders/QA153-NOT-CREATED/events` đều **404 NOT_FOUND**. Đây chỉ là kiểm tra route, **không phải webhook HMAC đúng contract** và không được tính PASS nghiệp vụ.
- Không thể tạo RESERVED thật và chờ chuyển EXPIRED rồi gửi PAID hợp lệ; do đó không có kết quả RELEASE, reservation lại, REFUNDED hoặc đồng bộ tồn trong 1 phút để chứng nhận.
- #153 hạn 13/11 nhưng dependency #41 hạn 14/11 theo issue hiện tại. Cần Lead xử lý phụ thuộc/lịch nghiệm thu; QA không tự đổi lịch hoặc quyết định nghiệp vụ.

## Cách chạy lại và điều kiện còn chờ

1. Dùng QA153 riêng đúng baseline/image và seed mới; đặt `GREENMART_DEMO_PASSWORD` trong môi trường process, không đưa mật khẩu vào file/artifact.
2. Chạy `python tests/api/issue153_edges.py`. Runner trả exit 1 nếu FAIL/BLOCKED hoặc bị ngắt, ghi evidence sanitized và logout trong finally. Không chạy vào DB dev/staging.
3. Sau khi #38/#39 có bản sửa, verify lại quote dương tính và các ca biên. Chỉ khi hai quote thực sự áp campaign còn một lượt mới chạy hai POST POS đồng thời; đối chiếu [201,422], quote mới của loser và used_count=1. Phải bổ sung đối chiếu order/payment/stock/ledger đầy đủ trước nghiệm thu race.
4. Sau #40/#41 merge, tạo đơn online thực qua HMAC và kiểm tra ba nhánh expiry/late payment, timer 15 phút + tối đa 1 phút, RELEASE/reservation/refund và outbox/kênh. Không nhận đủ bằng chứng chỉ từ issue closed hoặc test mock của tác giả.

Đã kiểm tra syntax Python, parse/count JSON và scan token/password/hash. Lượt đầu 37 checks có cùng sáu lỗi, lưu local; lượt cuối 38 checks bổ sung xác minh fixture và bỏ session không dùng, không reset dữ liệu. Bằng chứng chính chỉ dùng lượt cuối, không cộng dồn hai lượt.

Không sửa code/backend/contract của thành viên khác hoặc đăng bình luận/issue mới; thay đổi này chỉ bàn giao QA, không tự đóng #153. Nhật ký cá nhân vẫn chỉ local. **#153 chưa Done**: phụ thuộc còn mở, các tình huống chính chưa đạt, chưa review/CI/merge.
