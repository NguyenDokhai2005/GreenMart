# Kiểm tra luồng đa kênh đầu–cuối — 20/11/2026 (#16)

Refs #16 · AC-03 · UC-02 · SYNC-02, SYNC-03, SYNC-04 · BR-03, BR-05, BR-14, BR-15

## Kết luận

**19/19 kịch bản đạt.** Luồng đơn WEB/MARKET chạy đúng hợp đồng từ webhook nhận đơn tới giữ chỗ, thanh toán, hoàn tất/hủy/hết hạn, đẩy tồn và báo trạng thái về kênh, kể cả khi kênh mất kết nối rồi gửi lại.

Phạm vi là chức năng với từng đơn chạy tuần tự. Đồng bộ dưới tải vẫn là lỗi mở #384: SV08 đo ngày 18–19/11, sau tải 50 VU có event chờ quá 60 giây. Báo cáo này không thay thế phép đo đó.

## Môi trường

| Mục | Giá trị |
|---|---|
| Mã nguồn | `develop` tại `4e45b443fb25424554aae68df9a58102335d8f67` (sau #409) |
| Stack | Docker 29.8.1 trên Windows; project Compose riêng `greenmart-demo-qa16`, cổng 28180–28183; image build từ đúng commit trên |
| Dữ liệu | Seed chính thức (`generate_data.py` + `seed_data.sql`): 3 chi nhánh, 1.000 sản phẩm, WEB/MARKET có ánh xạ SKU và chi nhánh phục vụ |
| Secrets | Sinh ngẫu nhiên cho lần chạy, chỉ nằm trong `.local-tools/` (đã gitignore), không in ra log |
| Stack chung | Không dừng, không reset, không ghi dữ liệu vào stack `greenmart` đang chạy |

Stack dựng bằng `scripts/demo-clean.py` của PR #401 (SV07), hết 51 giây, có sẵn build cache.

## Cách chạy lại

```powershell
python scripts/demo-clean.py --project greenmart-demo-qa16 --port 28180
python scripts/qa-multichannel.py --project greenmart-demo-qa16 --port 28180
```

`qa-multichannel.py` ký HMAC bằng secret của project, gọi API backend, đọc `GET /mock/state` của mock-connector và truy vấn PostgreSQL của chính project đó. Kết quả ghi ra `.local-tools/<project>/multichannel-result.json`. Bản của lần chạy này ở [results/multichannel-2026-11-20.json](results/multichannel-2026-11-20.json).

Fixture tự chọn: sản phẩm có ánh xạ ở cả WEB và MARKET, tổng khả dụng ở các chi nhánh phục vụ của mỗi kênh ≥ 40. Lần này là sản phẩm 1 (`W-COM-SUON-BI-CHA-0001` / `M-COM-SUON-BI-CHA-0001`), tồn ban đầu GM-001: 23, GM-002: 22, GM-003: 22.

## Kết quả

| ID | Kịch bản | Kết quả | Bằng chứng |
|---|---|---|---|
| S01 | WEB, thanh toán ONLINE | PASS | 202 `RESERVED`, phân bổ GM-001, `reserved` +2, `reservedUntil` còn 14,99 phút |
| S02 | Đẩy tồn ra WEB sau giữ chỗ | PASS | Kênh nhận sau 2,02 s: `available` 63 = 67 tồn − 2 giữ chỗ − 2 safety stock |
| S03 | Cùng sản phẩm trên MARKET | PASS | MARKET nhận `available` 63 sau 0,5 s |
| S04 | Gửi trùng `externalOrderId` | PASS | 200, cùng `orderId`, tồn không đổi, chỉ 1 đơn trong DB (BR-05) |
| S05 | Sai chữ ký HMAC | PASS | 401 `INVALID_SIGNATURE`, không tạo đơn |
| S06 | Kênh báo `PAID` | PASS | 200 `CONFIRMED`; kênh nhận trạng thái sau 1,53 s |
| S07 | Gửi lại `PAID` | PASS | 200, vẫn `CONFIRMED`, không làm gì thêm |
| S08 | QL GM-001 hoàn tất đơn | PASS | 200 `COMPLETED`; `on_hand` −2, `reserved` −2; kênh nhận `COMPLETED` sau 1,0 s |
| S09 | QL GM-002 hoàn tất đơn của GM-001 | PASS | 403 `BRANCH_FORBIDDEN` |
| S10 | MARKET, COD | PASS | 202 `CONFIRMED` ngay sau giữ chỗ, phân bổ GM-002 |
| S11 | Kênh báo `CANCELLED` | PASS | 200 `CANCELLED`; `reserved` −1, `on_hand` giữ nguyên; kênh nhận sau 2,52 s |
| S12 | SKU kênh chưa ánh xạ | PASS | 422 `UNMAPPED_SKU`, đơn lưu `REJECTED`, `details.externalSkus` đúng SKU |
| S13 | Đặt 9.999 sản phẩm | PASS | 409 `INSUFFICIENT_STOCK`, đơn `REJECTED`, `details.lines` có `available`, tồn không đổi |
| S14 | Quá hạn giữ chỗ | PASS | Job hết hạn chuyển `EXPIRED` sau 11,66 s, `reserved` −1, kênh nhận `EXPIRED` sau 1,03 s |
| S15 | `PAID` đến sau khi `EXPIRED` (BA E2) | PASS | Còn đủ hàng → giữ chỗ lại, 200 `CONFIRMED` |
| S16 | QLCN hủy đơn đã thanh toán | PASS | 200 `CANCELLED`, nhả giữ chỗ, payment `REFUNDED`, kênh nhận `CANCELLED` |
| S17 | Tắt mock-connector rồi nhận đơn COD | PASS | Webhook vẫn 202; 3 callback (sync log 32 tồn WEB, 33 tồn MARKET, 34 trạng thái đơn WEB) thử 6 lần rồi `FAILED` sau 57,17 s |
| S18 | Bật lại kênh, ADMIN gửi lại | PASS | `GET /integrations/sync-logs?status=FAILED` thấy đủ 3 log; `POST .../retry` 202 cả 3; tất cả `SUCCESS` sau 16,95 s; kênh nhận `CONFIRMED` và tồn mới (`available` 62) |
| S19 | Thu ngân gọi gửi lại sync log | PASS | 403 |

Số đo (một đơn mỗi lúc, máy dev): webhook nhận đơn 15–234 ms; đẩy tồn và báo trạng thái về kênh 0–2,5 s, đều dưới ngưỡng 60 giây của SYNC-03. Mất kết nối: mỗi callback gửi 1 lần + thử lại 5 lần (`attempts` = 6) rồi mới `FAILED`, đúng số lần trong mô tả `pushChannelStock`.

## Giới hạn

- **Không đo tải.** Đồng bộ dưới tải là #384, chưa có người nhận.
- **S14 dời `reserved_until` bằng SQL** thay vì chờ đủ 15 phút; job hết hạn và callback chạy thật. Thời lượng giữ chỗ 15 phút đã kiểm ở S01 qua `reservedUntil`.
- **Không kiểm qua giao diện.** Màn danh sách và chi tiết đơn online (#80, #81) chưa merge, nên các thao tác của QLCN chạy qua API với tài khoản demo.
- **Một sản phẩm, một dòng mỗi đơn.** Phân bổ chi nhánh nhiều dòng (BR-14) và giữ chỗ đồng thời đã có test backend và báo cáo QA của SV08; báo cáo này không lặp lại.
- **`demo-clean.py` thuộc PR #401**, chưa có trên `develop`. Cần merge #401 để lệnh chạy lại ở trên dùng được ngay sau khi clone.

## Phối hợp

- SV02 (@t-boai): đơn online, giữ chỗ, hoàn tất/hủy — S01, S04, S06–S16.
- SV07 (@thanhcode118): webhook, mock-connector, giao callback và gửi lại — S02, S03, S05, S17–S19.

Nhờ hai bạn đối chiếu kết quả với phần mình phụ trách trong review PR này.
