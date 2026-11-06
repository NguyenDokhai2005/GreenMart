# Kiểm chứng Issue #70 — 07/11/2026

Phạm vi SV04, PR [#246](https://github.com/NguyenDokhai2005/GreenMart/pull/246),
nhánh `feature/70-auth-login-rbac-ready`. Audit bắt đầu tại head `316cb04`.
PR tiếp tục Draft: còn kiểm tra trực tiếp Chrome/ảnh Figma và Lead review.

## A. Tổng quan

Đã đọc Issue #70 và comment, toàn bộ discussion/review PR #246, các Issue #171,
#228, #241, PR #243, CONTRIBUTING, README, AUTH-01/02/03 trong SRS/user stories
và phần auth/RBAC của OpenAPI. #171 đã đóng; user xác nhận thiết kế được duyệt,
review Lead trên #246 cũng xác nhận có thể tiếp tục. #228 vẫn mở, thuộc SV05.

Baseline thực chạy: 75 tests PASS cùng typecheck, lint, format, build. Test render mới
tái hiện ba lỗi trước sửa: form bị unmount khi Login đang chờ; mất thông báo khóa;
đăng nhập lại sau hết phiên về `/pos` thay vì `/pos/receipts?range=today#receipt-42`.

## B. Đối chiếu acceptance criteria

| AC | Kết quả code | Unit/regression | Backend thật | Kết luận |
| --- | --- | --- | --- | --- |
| AC1 | Giữ form/lỗi khi login chậm, thông báo chung và remainingAttempts, chống gửi lặp | PASS | 401 INVALID_CREDENTIALS; React DOM hiển thị còn 4 lần thử, retry thành công | Đạt kiểm chứng tự động; chưa ảnh Chrome |
| AC2 | Đọc lockedUntil từ backend, hiển thị giờ Việt Nam, khóa theo username | PASS; hết hạn mở lại form và đăng nhập được với response giả lập | Lần 1–4 còn 4/3/2/1; lần 5 ACCOUNT_LOCKED; mật khẩu đúng khi đang khóa vẫn 401; tài khoản khác 200 | Đạt khóa thật; chưa kiểm tra mở khóa tự nhiên trên API/Chrome |
| AC3 | Route guard theo allowlist, ADMIN không có quyền mặc định mọi route | PASS | React + HTTP thật cho cả 5 vai trò; CASHIER và ADMIN mở phiếu kho → /403 | Đạt kiểm chứng tự động; chưa thao tác Chrome |
| AC4 | Một chi nhánh vào thẳng; nhiều chi nhánh chọn khi cần; bỏ lựa chọn sai/người khác | PASS | CASHIER/BRANCH_MANAGER/STOREKEEPER nhận 1 chi nhánh; OWNER chọn từ 3; ADMIN 3 chi nhánh vào /admin | Đạt kiểm chứng tự động |

## C. Review của Lead

- Session expired: listener đã có ở head cũ; bổ sung test render và backend thật,
  sửa PublicOnly giữ `location.state`, để router là nơi duy nhất điều hướng khi login thành công.
- ADMIN 0/1/3 chi nhánh: logic hiện có đúng; unit/render tests PASS, backend thật trả 3 chi nhánh PASS.
- Logout: access token bị từ chối → refresh một lần → thu hồi token mới;
  không lưu phiên xoay phục vụ logout. Nếu refresh cũ đang chạy, chờ và thu hồi kết quả
  thay vì cạnh tranh dùng lại refresh token. Phiên mới không bị logout cũ ghi đè.
- Backend thật: đã build image từ source hiện tại, Flyway V1–V8, seed chính thức thành công.
- 404/layout và thay trang trạng thái tạm bằng component chung: phụ thuộc SV05 #228;
  route không tồn tại hiện vẫn redirect `/`. POS/Operations hiện là điểm tích hợp layout
  #71/#88, không được coi là màn nghiệp vụ hoàn thiện trong #70.
- Screenshot 1440 px: chưa thực hiện. Figma link frame `157:74` trả node không tồn tại;
  truy vấn cấu trúc file sau đó bị giới hạn Starter plan. Chrome computer-use báo
  “The browser access host is unavailable”. Không suy ra khớp Figma từ test DOM.

## D. Thay đổi

- `AuthProvider.tsx`: giữ trạng thái đã ổn định khi gửi Login để form không bị unmount,
  vẫn dùng transition id chặn kết quả cũ.
- `AppRoutes.tsx`, `LoginPage.tsx`: giữ URL quay lại; bỏ điều hướng cạnh tranh từ form;
  abort Login sau 15 giây và cho phép retry.
- `AuthContext.ts`, `api/client.ts`, `loginForm.ts`: truyền AbortSignal, ánh xạ timeout;
  logout có refresh/revoke một lần, thu hồi token xoay trả về muộn.
- `authFlow.test.tsx`: test React thực cho Login, lockout, timeout, 300 ms/15 giây,
  RBAC, chi nhánh và quay lại URL.
- `client.test.ts`: regression logout/refresh/race; giữ các test 401 đồng thời,
  lỗi mạng/500, hủy listener và chặn refresh loop.
- Thêm jsdom (dev dependency), cấu hình integration riêng và hướng dẫn chạy.
  Không sửa CSS, backend, migration, OpenAPI, generated types hoặc component SV05.

## E. Kết quả thực chạy

Môi trường local: Node 24.22.1, npm 11.19.1; backend Docker Java 21,
PostgreSQL 16.10, Redis 7.4.6. Không dùng Prism cho các kết quả integration.

| Kiểm tra | Kết quả |
| --- | --- |
| `npm run typecheck` | PASS |
| `npm run lint` | PASS |
| `npm run format:check` | PASS |
| `npm test -- --run` | 97/97 PASS, 10 test files |
| `npm run build` | PASS |
| `npm run test:auth-integration` | 7/7 PASS với backend thật, React/jsdom |
| `git diff --check` | PASS |

HTTP thật cho mỗi vai trò: login 200, me 200, refresh 200, dùng lại refresh cũ 401,
logout 204, dùng refresh đã logout 401. Integration frontend còn kiểm tra
401 access token → refresh → retry, logout với access không hợp lệ và
refresh đã thu hồi → Login → đúng pathname/search/hash.
Các ca 401 được kích hoạt bằng access token không hợp lệ trong sessionStorage test;
không giả mạo JWT hợp lệ, không đổi TTL/secret hoặc dữ liệu quyền trong database.

Lockout thử trên tài khoản demo mới seed `thungan.gm003` trong database local riêng;
không sửa locked_until để tua thời gian. Thời điểm backend trả về tương ứng
05:03:52 ngày 07/11/2026 (Asia/Ho_Chi_Minh). Không dùng kết quả unit test để nhận
đã xác minh mở khóa tự nhiên trên backend.

Backup trước seed lưu ngoài Git, quyền 0600, kiểm tra không rỗng (79.088 byte).
Đã kiểm đếm chính xác: trước seed mọi bảng nghiệp vụ trống, ngoài lịch sử Flyway
và ba kênh bán tham chiếu. Role forecast_ro đã có LOGIN, không chạy lại init script.
Seed wrapper và kiểm đếm AC-01 PASS: 12 tài khoản, 3 chi nhánh, 1.000 sản phẩm.

`npm audit` báo 18 cảnh báo trong dependency tooling có sẵn (11 moderate, 7 high),
không có jsdom trong danh sách package bị báo. Không tự chạy audit fix hoặc nâng cấp
dependency ngoài phạm vi. Đây là ghi nhận riêng, không phải kết quả quality check PASS.

## F. Git và PR

Giữ PR #246 và nhánh hiện có, đích develop. Không tạo PR mới, không force push,
không merge/đóng issue/đưa PR sang Ready. Commit cuối và CI của commit mới
được ghi trong PR sau push; CI thành công của `316cb04` chỉ là baseline.

## G. Phần cần hoàn tất để nghiệm thu

SV04/Lead cần kiểm tra Chrome desktop 1440 px và gắn ảnh so với frame Figma #171
còn tồn tại. Chạy `npm run dev` (proxy mặc định backend thật), mở các trạng thái
Login mặc định/đang chờ/sai mật khẩu/khóa; OWNER chọn chi nhánh;
ADMIN vào thẳng `/admin`; CASHIER mở `/operations/stock-documents` → `/403`;
reload và logout. Kiểm tra request Network tới backend 8080, không phải Prism 4010.

Đối với hết phiên: mở URL POS có query/hash, thu hồi refresh của chính phiên demo,
kích hoạt request bị 401 rồi đăng nhập lại; xác nhận URL đầy đủ và route guard.
Ảnh/Network export phải loại bỏ mật khẩu, Authorization và token trong response/storage.

SV05 #228 cần tích hợp trang 404 và component trạng thái dùng chung; SV04 áp dụng
vào auth/layout khi component sẵn sàng. Lead cần review lại commit mới và xác nhận
điều kiện bỏ Draft. Chưa đủ bằng chứng để tuyên bố Issue #70 đã nghiệm thu.
