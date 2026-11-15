# #156 — Playwright POS và đơn online, 14/11/2026

## Yêu cầu và baseline

Issue #156 giao Huynh-Phuc-Dat (SV08 QA/Security): hệ thống chạy đủ, Playwright chạy luồng POS và đơn online từ kênh tới hoàn tất. Nghiệm thu cần luồng nghiệp vụ thật, không thay thế UI bằng mock hoặc coi API PASS là checkout UI PASS.

Đã kiểm tra nhánh/status/commit local, bảo toàn các untracked cũ, chuyển develop và `git pull --ff-only origin develop` thành công. Baseline mới **`4c3a4fa597345fc4e3aeaf6aa538161d829beb12`**, nhánh riêng **`feature/156-playwright-e2e`**. Không merge/rebase nhánh QA cũ.

Đã đọc #156/comments và bản GitHub tại SHA này của README, CONTRIBUTING, CODEOWNERS, PR template, SRS, BA, user stories, API README/OpenAPI, test plan, security checklist, backend README. Đọc phân công/phụ thuộc #73/#75/#80/#81 và thiết kế #174/#176 cùng source router/frontend và lifecycle backend.

**Khác baseline cũ:** develop vừa bổ sung online lifecycle, complete/cancel, event PAID và scheduler expiry; #40/#320 đã CLOSED. Không dùng kết luận thiếu endpoint ở báo cáo cũ để nhận định bản mới chưa triển khai. Lượt này chưa kiểm thử runtime đủ để kết luận các luồng mới đã hoạt động.

## Đã thực hiện

Tạo bộ QA riêng trong `tests/e2e/issue156/`, không sửa frontend/backend/config dùng chung:

- `package.json`, `package-lock.json`: cố định **@playwright/test 1.65.1**, cài ba package trong thư mục QA bằng npm `--ignore-scripts --no-audit --no-fund`; không cài dependency vào frontend.
- `playwright.config.js`: chỉ nhận project QA156 và cổng local riêng; worker=1/retries=0; không trace/video/screenshot/storageState tự động.
- `flows.spec.js`: guard backend/frontend OCI revision và DB riêng, setup sản phẩm/nhập kho qua API thật, lifecycle API và browser smoke; ghi riêng các ca UI còn bị chặn.
- `safe-reporter.cjs`: xuất kết quả/annotation QA cho phép, không xuất error raw/body/header/token. BLOCKED không làm acceptance hoặc exit status xanh.
- `run_qa.py`: build backend/frontend từ source sạch, project/volume mới, Compose `config --quiet`, credential ngẫu nhiên trong process, official seed một lần, không đọc `.env`, không reset/reseed môi trường đã tồn tại.
- `guards.test.cjs`: bảo vệ target/evidence/reporter; `README.md`: hướng dẫn tái lập và giới hạn.

## Kết quả có bằng chứng

**E2E thực tế: NOT RUN/BLOCKED do Docker Engine. Không có POS/online runtime PASS trong lượt này.**

| Kiểm tra | Kết quả thực tế |
|---|---|
| Guard bằng Node test runner | **4/4 PASS** |
| Playwright discovery `--list --reporter=list` | Nhận đúng **5 test**; không chạy hook/API/browser |
| `node --check` spec/config; `py_compile` helper | PASS |
| `git diff --check` | PASS |
| Docker build backend tại SHA mới | PASS, bước build tiếp theo đã chạy; build skip Maven tests, không nhận backend suite PASS |
| Docker build frontend tại SHA mới | PASS, đã chuyển tới bước Compose up |
| Compose up/wait QA156 | Thất bại exit 1 trước bước sinh/nạp seed và chạy Playwright |
| Chẩn đoán trạng thái Docker | Lệnh container list, inspect backend, info/stats trả **HTTP 500**, có lệnh chờ lâu; chưa xác định nguyên nhân |

Bằng chứng: [issue-156-bootstrap-2026-11-14.json](results/issue-156-bootstrap-2026-11-14.json). Artifact này là **bootstrap/discovery**, không phải báo cáo đã chạy Playwright. Không tạo JSON test PASS giả khi dịch vụ chưa sẵn sàng.

Môi trường dự kiến `greenmart-qa156-20261114`, backend **18166**, frontend **13166**; image tag `greenmart-backend:qa156-4c3a4fa` và `greenmart-frontend:qa156-4c3a4fa`. Chưa xác minh stack hoàn chỉnh healthy hoặc URL có thể sử dụng, không bàn giao như một môi trường đang hoạt động.

Đã hỏi người dùng kiểm tra Docker Desktop có đang khởi động lại/thiếu tài nguyên hay không. Không tự restart Docker, đọc logs/environment chứa secrets, reset volume hoặc dừng stack khác. `docker info` lỗi không được diễn giải thành máy chỉ có 0 RAM; nguyên nhân Engine chưa được xác định.

## Năm test đã chuẩn bị, chưa thực thi runtime

| Test | Mục tiêu | Trạng thái hiện tại |
|---|---|---|
| API POS | quote 2 × 31.000 → CASH → COMPLETED; GET detail; stock 20→18; replay Idempotency-Key cùng đơn/một SALE | NOT RUN |
| API online | WEB HMAC thật → RESERVED giữ 3 → PAID → CONFIRMED → manager complete; detail/payment/tồn/outbox | NOT RUN |
| Browser smoke | Login CASHIER bằng form thật → POS shell → logout 204 | NOT RUN |
| Browser POS nghiệp vụ | Quan sát placeholder, ghi BLOCKED; chưa có thao tác quét/giỏ hàng/thanh toán UI hoàn chỉnh | NOT RUN; phụ thuộc UI qua đọc source |
| Browser online nghiệp vụ | Manager login → Đơn hàng; quan sát trang đang phát triển, ghi BLOCKED; chưa có thao tác chọn đơn/complete UI | NOT RUN; phụ thuộc UI qua đọc source |

Hai ca UI nghiệp vụ hiện là điểm ghi nhận phụ thuộc, **chưa phải script checkout/complete UI hoàn thiện**. Sau khi UI được triển khai/duyệt thiết kế, QA phải viết tiếp các thao tác và assertion nghiệp vụ thật; không bỏ assert placeholder để cho PASS. Không đánh dấu E2E hoàn tất chỉ vì API lifecycle đã có test.

## Phát hiện bằng đọc source và GitHub

- `/pos` dùng `PosRouteContent` nạp `PosPlaceholderPage`, hiển thị “Màn hình đang được chuẩn bị”. #73 (giỏ hàng) và #75 (thanh toán) **OPEN**, giao **SV04 @voxuanvy611-sys**, phụ thuộc thiết kế #174.
- `/operations/orders` thuộc router fallback “Chức năng đang phát triển”. #80 (danh sách) và #81 (chi tiết/hoàn tất/hủy) **OPEN**, cùng SV04; #81 chờ thiết kế #176.
- Đây là phụ thuộc chưa hoàn tất, **chưa phải lỗi UI đã tái hiện trong browser**. Không tự sửa màn của SV04 hoặc tạo bug mới/tag người khác. Không gọi đây là regression của backend mới.
- Backend mới có controller cho events và complete/cancel; test API online đã được chuẩn bị để kiểm chứng sau khi Docker hoạt động. Chưa nhận các tiêu chí này PASS chỉ dựa vào trạng thái CLOSED hoặc source code.

## Tiếp tục khi môi trường sẵn sàng

Theo [README của bộ QA](../../tests/e2e/issue156/README.md), dùng project/port/report mới nếu cần bootstrap từ đầu; không dựng lại stack cũ với credential mới trên volume cũ. Giữ project/volume đã tạo để xem xét, không xóa tự động.

Chạy discovery/guard không cần Docker. Chạy thực tế cần Docker ổn định, demo password trong process, Chromium riêng (có thể đặt QA156_CHROMIUM_EXE) và image Python seed. Chưa launch browser nên chưa có version browser runtime hoặc screenshot chứng minh UI. Không dùng browser profile của người dùng.

Không thêm workflow CI trong lượt này; không ép CI xanh bằng bỏ qua UI còn thiếu. Khi có kết quả thật, ghi source/image/browser version, từng test, bằng chứng an toàn và giới hạn; chỉ cập nhật kết luận #156 theo bằng chứng đó.

**#156 chưa Done.** Đây là kết quả bootstrap lịch sử ngày 14/11. Lượt thực thi thật ngày 15/11 được báo cáo riêng trong `issue-156-playwright-recheck-2026-11-15.md`; không đổi kết quả NOT RUN của lượt cũ. Phần nghiệm thu UI vẫn cần hoàn thiện theo phụ thuộc và qua review/CI/merge.
