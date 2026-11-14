# Playwright QA #156

Bộ QA độc lập, không sửa package/config của frontend. Playwright được cố định bằng package-lock. Dùng API/backend/DB thật; không mock response nghiệp vụ.

## Chuẩn bị

Từ thư mục này chạy `npm ci --ignore-scripts --no-audit --no-fund`. Cần Docker Engine và runtime Python để sinh seed (mặc định image `greenmart-forecast:qa151`, đổi bằng `QA156_SEED_IMAGE`). Cần Chromium của Playwright hoặc cung cấp đường dẫn browser có sẵn bằng `QA156_CHROMIUM_EXE`; ghi rõ version browser thực tế trong báo cáo. Không dùng browser profile cá nhân.

Cung cấp `GREENMART_DEMO_PASSWORD` trong process theo tài liệu demo, không tạo file credential. Từ root repo chạy:

```text
python tests/e2e/issue156/run_qa.py
```

Helper build backend/frontend từ HEAD, gắn OCI revision, khởi tạo project/volume mới, kiểm tra Compose bằng `config --quiet`, seed một lần và chạy Playwright. Mặc định API 18166, UI 13166. Có thể đổi `QA156_PROJECT` (bắt đầu `greenmart-qa156-`), `QA156_PORT` (18000–18999), `QA156_UI_PORT` (13000–13999), `QA156_REPORT` (file chưa tồn tại). Không đọc `.env`, không reset/reseed project hoặc volume đã có, không dừng stack khác. Backend/frontend/seed/Compose phải sạch so với commit build.

Nếu bootstrap thất bại, giữ nguyên dữ liệu và ghi NOT RUN/BLOCKED; không đổi thành PASS hoặc tự reset để làm lại. Muốn chạy từ đầu phải chọn project/port/report mới. Không tự dựng lại project với credential khác trên volume đã có.

## Các ca

1. API POS: fixture sản phẩm/nhập kho qua API; quote, CASH, COMPLETED, chi tiết, tồn và một dòng SALE; gửi lại cùng Idempotency-Key không tạo/trừ tồn lần hai.
2. API online: mapping/signing key mới chỉ trong QA, HMAC WEB thật; RESERVED giữ 3, PAID → CONFIRMED, manager complete → COMPLETED; kiểm tra thanh toán, tồn và outbox.
3. Browser smoke: nhập thông tin ở trang login thật, vào POS, quan sát shell hiện tại, logout thật.
4. Browser POS nghiệp vụ: hiện đánh dấu BLOCKED khi quan sát placeholder, không dùng API để thay thế thao tác thanh toán trên UI.
5. Browser online nghiệp vụ: quan sát trang đơn hàng còn đang phát triển và đánh dấu BLOCKED; hoàn thiện thao tác UI sau khi #80/#81 có implementation và thiết kế được duyệt.

Các ca 4/5 là ghi nhận phụ thuộc hiện tại, chưa phải script checkout/complete UI đã hoàn thiện. Khi màn thật thay placeholder, cập nhật test theo UI được duyệt; không bỏ assert để cho pass. API lifecycle PASS không đủ nghiệm thu toàn #156.

## Báo cáo an toàn

Reporter chỉ xuất tên test, status, duration và annotation QA cho phép; không ghi lỗi raw, request/body/header, token, storageState, trace, video hoặc screenshot tự động. Không đọc Docker logs. Có BLOCKED thì reporter trả process failure, dù Playwright không coi skipped là assertion failure. Xem `acceptance` và từng test, không chỉ `status` gốc của Playwright.

File kết quả đã tồn tại sẽ bị từ chối ghi đè. Giữ nguyên evidence cũ, dùng file mới cho recheck. Chỉ đưa báo cáo/bằng chứng đã kiểm tra vào commit khi người dùng cho phép.

## Kiểm tra guard

```text
node --test tests/e2e/issue156/guards.test.cjs
node --check tests/e2e/issue156/flows.spec.js
python -m py_compile tests/e2e/issue156/run_qa.py
```

Guard không gọi Docker/API: từ chối target chung hoặc overwrite evidence; reporter không xuất canary secret và không biến BLOCKED thành acceptance PASS.

Kết quả thực tế ngày 15/11: xem `docs/testing/issue-156-playwright-recheck-2026-11-15.md` từ root repo. Hai API flow và browser smoke đã PASS; hai browser business flow BLOCKED vì UI placeholder. Dùng label pattern cho trường login bắt buộc có dấu `*`; không dùng exact label text thiếu ký hiệu để quy timeout thành lỗi ứng dụng.
