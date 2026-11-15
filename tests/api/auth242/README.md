# QA đăng nhập và phân quyền — #242

Bộ kiểm thử thuộc SV08, không sửa backend, migration, seed hoặc OpenAPI.

- `check_api.py`: giữ đủ 26 mã AUTH, 23 mã RBAC và 16 mã Identity từ tài liệu gốc. Hai probe bổ sung kiểm tra audit/lookup tách khỏi tổng 65 TC.
- `prepare_qa.py`: build backend commit hiện tại, tạo stack QA mới, sinh seed SV06 và chạy các mã trên API/DB thật.
- `auth.postman_collection.json`: 14 request / 26 assertions cho login, me, refresh/anti-replay, role/branch denial, OWNER scope, logout và refresh sau logout.
- `run-newman.cjs`: dùng Newman 6.2.1 đã pin trong `../pos149`; chỉ xuất tên assertion, HTTP status và kết quả. Không xuất execution/raw request/response/header/token.
- `test-runner.cjs`: HTTP doubles chỉ kiểm tra runner success/failure, lỗi kết nối, cleanup và loại secrets khỏi artifact; không thay cho API thật.

## Chạy 65 mã trên QA mới

Yêu cầu Docker Engine, Python standard library, đủ tài nguyên cho một backend và PostgreSQL/Redis/RabbitMQ. Không chạy trên staging hoặc DB chung.

PowerShell, tại gốc repo:

```powershell
$env:GREENMART_DEMO_PASSWORD = '<mật khẩu demo công khai trong README>'
$env:QA242_PROJECT = 'greenmart-qa242-my-recheck'
$env:QA242_PORT = '18160'
$env:QA242_REPORT_DIR = 'D:/GreenMart/GreenMart/.local-tools/qa242-my-recheck-results'
python tests/api/auth242/prepare_qa.py
```

Helper mặc định dùng seed runtime image local `greenmart-forecast:qa151`; có thể đặt `QA242_SEED_IMAGE` thành image forecast đã build từ dự án. Script generator hiện tại được mount read-only, không lấy seed cũ trong image. Không tự cài dependency Python.

Helper từ chối project/volume đã tồn tại và backend/seed/compose có thay đổi chưa commit để tránh ghi nhầm baseline. Chọn tên project, port và thư mục bằng chứng mới khi chạy lại; không xóa/reset DB cũ. Mặc định project `greenmart-qa242-20261113`, port `18158`.

JWT key ngẫu nhiên chỉ nằm trong process env của helper/backend/checker. Các TC expiry dùng JWT HS256 hết hạn được ký bằng key QA này; không đọc key của stack khác. Refresh expiry mô phỏng bằng cách đưa score của một refresh token của tài khoản QA về quá khứ trong Redis; không sửa clock, không chờ bảy ngày. Báo cáo ghi rõ cách mô phỏng.

Tác động trong QA: login/logout, tài khoản CASHIER tạm với user_branch, lockout/đổi mật khẩu, một sản phẩm mới, một phiếu IMPORT nháp (không duyệt), một chi nhánh inactive. Tài khoản tạm được vô hiệu hóa trong cleanup; tài khoản demo không đổi mật khẩu. Các fixture được giữ làm bằng chứng, không xóa dữ liệu.

Exit code: 0 chỉ khi toàn bộ đạt; 1 nếu có FAIL (kể cả probe bổ sung); 2 nếu runtime gián đoạn hoặc còn BLOCKED mà không có FAIL. BLOCKED giữ nguyên expected và HTTP thực tế, dẫn issue phụ thuộc, không đổi expected thành 404.

## Newman và CI hiện có

```powershell
$env:QA_BASE_URL = 'http://127.0.0.1:18158/api/v1'
$env:QA_SOURCE_COMMIT = '<commit backend thực chạy>'
$env:QA_REPORT_DIR = 'D:/GreenMart/GreenMart/.local-tools/qa242-newman-results'
npm run auth --prefix tests/api/pos149
npm test --prefix tests/api/pos149
```

Không thêm dependency/lockfile riêng: dùng `npm ci --ignore-scripts --no-audit --no-fund` trong `tests/api/pos149` khi chưa có Newman. Với lệnh `npm run pos` của workflow QA POS Newman hiện có, POS #149 phải PASS trước, rồi collection AUTH/RBAC #242 mới chạy. `npm test` chạy regression cả hai runner. Artifact auth nằm ở thư mục con `auth242`, không ghi đè summary POS.

Job CI vẫn tên QA POS Newman nhưng chạy cả POS và AUTH; xem `../pos149/README.md`. 65 TC với fixture DB không được nhận là đã chạy trong CI chỉ vì collection core PASS.

## Quyết định và giới hạn

- Q-29: quyết định Redis hash/TTL 7 ngày tại #241; mô phỏng expiry theo lưu trữ thật của `RefreshTokenStore`.
- Q-30: #241 yêu cầu security ErrorResponse có traceId; kiểm tra HTTP thật, không dựa vào mock.
- Q-27: [Lead chốt trong #310](https://github.com/NguyenDokhai2005/GreenMart/issues/310#issuecomment-6854060178): branch denial ghi actor/action/entity/branchId/method/path/traceId, không ghi query/body.
- **Q-31 chưa chốt.** #310 chỉ nói audit role FORBIDDEN nằm ngoài phạm vi của issue đó. TC-AUTH02-41 là BLOCKED bất kể có 0 hay 1 audit mới, nếu quyền trả đúng 403 FORBIDDEN; lỗi quyền vẫn FAIL. Tổng đúng tại baseline đã chạy là 55 PASS / 10 BLOCKED; assessment sửa phân loại ngày 14/11 không phải lượt chạy API mới.
- Helper sinh FORECAST_INTERNAL_TOKEN ngẫu nhiên trong process để tương thích Compose khi #353 đã merge; không xuất token vào artifact.

- Chưa có GET orders (#333) và GET branches (#335) thì các mã phụ thuộc BLOCKED. Không thay bằng endpoint khác để nhận mã đó PASS; probe inventory riêng chỉ là bằng chứng bổ sung.
- Hai probe còn FAIL đã có #309/#310; không tạo issue trùng. Kết quả lỗi áp dụng cho commit backend ghi trong báo cáo, không đại diện develop mới hơn.

Kết quả từng mã và giới hạn: `docs/testing/issue-242-execution-2026-11-13.md`.

Regression cho bản sửa review Q-31/token Compose (không gọi Docker hoặc API thật):

```text
python -m unittest discover -s tests/api/auth242 -p test_review_fixes.py -v
```
