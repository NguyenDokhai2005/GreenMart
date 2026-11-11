# Issue #71 — POS Layout & Navigation

## Baseline và phạm vi

- Baseline: `e41d35a94c95abc42640bcce165368505eaa0cb4` (`origin/develop` sau fetch).
- PR #246 (#70) và #252 (#228) đã merge; #171 đã đóng bởi Lead trong audit trước.
- Nhánh mới: `feature/71-pos-layout-navigation`, tạo từ `origin/develop`.
- Nhánh #71 cũ được giữ nguyên commit `3f09f0d` dưới tên `backup/71-auth-history-before-layout`.
- Trạng thái bàn giao: chưa commit, push, mở PR hoặc merge. Issue chưa được đóng.

Header, sidebar và cách chia vùng nội dung tham chiếu ba PNG #171/#169/#227 người dùng cung cấp. Không tuyên bố khớp Figma từng pixel hoặc xác minh được component binding từ PNG.

## Những giả định tạm thời cần Lead review

Người dùng đã cho phép triển khai mapping dưới đây; chưa coi là Lead phê duyệt:

| Menu               | Route            | Phím           |
| ------------------ | ---------------- | -------------- |
| Bán hàng           | `/pos`           | Ctrl + Alt + 1 |
| Hóa đơn            | `/pos/receipts`  | Ctrl + Alt + 2 |
| Trả hàng           | `/pos/returns`   | Ctrl + Alt + 3 |
| Tra cứu khách      | `/pos/customers` | Ctrl + Alt + 4 |
| Sản phẩm (chỉ xem) | `/pos/products`  | Ctrl + Alt + 5 |

- Mapping, nhãn và modifier tập trung trong `frontend/src/layouts/pos/posNavigation.ts`.
- CASHIER và BRANCH_MANAGER cùng dùng năm mục, giới hạn bởi `POS_ROLES` đã có. Không cấp thêm quyền hành động. Sản phẩm chỉ có placeholder, không có thao tác sửa giá/sản phẩm.
- Các màn nghiệp vụ, kể cả Bán hàng, đều là placeholder đơn giản. Không có giỏ hàng, thanh toán, tìm khách, tạo trả hàng hoặc API giả.
- Header dùng wordmark GreenMart và danh tính/chi nhánh từ context thật. Không thêm trạng thái "ca đang mở" hoặc đổi chi nhánh vào header. Nút Đăng xuất được bổ sung theo yêu cầu người dùng; PNG #171 chưa thể hiện rõ vị trí nút, nên vị trí cạnh tài khoản vẫn cần Lead review.
- Trang đặc biệt của lỗi nội dung nằm trong POS shell theo #228/README; giữ header và sidebar. Guard #70 vẫn quyết định lỗi quyền cấp khu và hết phiên: `/403` và Login có hành vi gốc, không thay UI ở Operations/Admin.
- Giữ tokens/shared UI đã merge thay vì tự sửa khác biệt font/màu giữa PNG và Design System code.
- Route không hợp lệ bên trong `/pos/*` hiện 404. Fallback toàn app ngoài POS giữ nguyên hành vi của #70.

## Kiến trúc tích hợp

Chỉ thay `WorkspaceMountPoint` của `/pos/*` trong `AppRoutes.tsx` bằng `PosLayout`. AuthBoundary, BranchBoundary, route policy và mọi route ngoài POS không thay đổi.

`PosLayout` chứa shell bền vững; `PosRouteContent` tải module trang trong content. Loader nhận navigation item, nên màn nghiệp vụ sau này có thể được gắn theo item mà không viết lại shell. Component trang nhận item; layout không quản lý dữ liệu nghiệp vụ của màn.

Trì hoãn loading và timeout dùng `useDelayedLoading` của #228. Trước 300 ms không render loading hay placeholder rỗng; sau 300 ms dùng PageLoader; quá 15 giây dùng ErrorState có Thử lại. Retry remount riêng content, reset timer; kết quả tải cũ bị bỏ qua khi rời trang. Không tạo thời gian chờ giả để demo.

Lỗi tải/render được ánh xạ qua `mapErrorToState` và các trang shared 403/404/500/offline/session-expired. Retry nội dung không reload cả browser. Offline thử lại khi có sự kiện `online`. Adapter hết phiên bảo toàn `location.state.from`; khi client phát hết phiên thực tế, guard #70 vẫn đưa về Login như trước.

## Kiểm chứng ngày 07/11/2026

Môi trường local: Node 24.22.1; Chrome trên macOS; backend demo local đã chạy từ trước. Không sửa backend, DB schema, seed, contract, API client, provider hoặc dependency.

| Kiểm tra                              | Kết quả                                                                  |
| ------------------------------------- | ------------------------------------------------------------------------ |
| Baseline trước sửa: unit/render tests | 97/97 PASS, 10 file                                                      |
| `npm run typecheck`                   | PASS                                                                     |
| `npm run lint`                        | PASS                                                                     |
| `npm run format:check`                | PASS                                                                     |
| `npm test -- --run`                   | 130/130 PASS, 12 file; gồm toàn bộ tests #70 hiện có và 33 tests POS mới |
| `npm run build`                       | PASS; trang placeholder xuất thành chunk riêng                           |
| `npm run test:auth-integration`       | 7/7 PASS với HTTP/backend thật, không dùng Prism                         |
| `git diff --check`                    | PASS                                                                     |

Suite auth integration hiện có được chạy bằng mật khẩu demo qua biến môi trường; báo cáo không lưu credential/token. Suite thực hiện login/refresh/logout và một lần sai mật khẩu rồi đăng nhập đúng để reset counter. Không ghi dữ liệu bán hàng.

### Ma trận Acceptance Criteria và regression

| AC / tình huống                                                                 | Bằng chứng                                                                                                  | Đánh giá                                                                       |
| ------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| AC1: CASHIER thấy Bán hàng/Hóa đơn/Tra cứu khách; không có Đơn online/Phiếu kho | PosLayout tests kiểm tra năm menu, href, danh tính, không có nút nghiệp vụ ghi; Chrome CASHIER/backend thật | Đã triển khai và kiểm thử                                                      |
| AC2: chuyển mục không dùng chuột                                                | Test Ctrl+Alt+1–5; thử native Control+Option+1–5 trên Chrome macOS                                          | Đã triển khai; mapping còn cần Lead review                                     |
| Menu active, click và focus                                                     | Test từng route chỉ có một `aria-current=page`; đổi màn giữ cùng DOM header/sidebar và focus heading        | PASS                                                                           |
| Direct URL, refresh và query/hash                                               | Test remount từng route; auth integration #70 chạy BrowserRouter/HTTP thật và reload                        | PASS; không tuyên bố đã thử thủ công Chrome mọi deep link                      |
| URL POS không tồn tại                                                           | Shared 404 trong shell và nút về POS có test                                                                | PASS                                                                           |
| RBAC không suy giảm                                                             | ADMIN/OWNER/STOREKEEPER bị chặn ở POS; CASHIER bị chặn Phiếu kho; tests #70 và auth integration             | PASS                                                                           |
| Một/nhiều chi nhánh và hết phiên                                                | Provider thật trong tests; chọn branch giữ URL; refresh bị từ chối, đăng nhập lại giữ path/search/hash      | PASS                                                                           |
| Loading 200/400 ms, timeout 15 giây, retry                                      | Fake timers, loader trì hoãn trong PosContent tests; không thêm API giả vào app                             | PASS                                                                           |
| Lỗi cục bộ không làm mất dữ liệu khác                                           | Fixture trang có input và ErrorState riêng khối; lỗi/retry giữ cùng input và giá trị                        | PASS trong integration fixture; không phải chứng minh giỏ hàng chưa triển khai |
| 403/404/500/offline và lỗi render                                               | Inject loader/lỗi trong tests; shared pages, traceId, retry và online recovery                              | PASS trong tests; chưa có ảnh browser cho từng lỗi                             |
| Khớp thiết kế                                                                   | PNG tham chiếu + ảnh Chrome desktop                                                                         | Chờ Lead review; không khẳng định pixel-perfect                                |
| DoD merge/review/CI/đóng issue                                                  | Chưa commit hoặc PR                                                                                         | Chưa thực hiện, theo yêu cầu người dùng                                        |

### Kiểm tra phím tắt

Trên Chrome macOS đang sử dụng, native Control + Option + 1/2/3/4/5 đã chuyển đúng route, tiêu đề và focus; chưa gặp xung đột trong môi trường kiểm tra này. Không khẳng định tương thích mọi hệ điều hành, browser extension hoặc layout bàn phím.

Handler dùng `event.code` (Digit1–Digit5), nên không phụ thuộc ký tự bị Option thay đổi trên macOS. Test bao phủ ký tự `key` khác số. Không xử lý khi đang nhập trong input/textarea/select/contenteditable/textbox, IME composition, key repeat, có dialog modal, event đã bị chặn, hoặc modifier không khớp. Listener được gỡ khi unmount. F2/F4/Esc/Enter không bị handler #71 chặn và không có logic nghiệp vụ mới cho các phím này.

macOS VoiceOver dùng Control + Option làm modifier. Nếu hệ thống/assistive technology giữ phím trước browser, không thể bảo đảm shortcut tới trang. Menu vẫn truy cập được bằng Tab/Enter và chuột; Lead có thể đổi mapping tập trung trong config. Chưa kiểm thử bật VoiceOver hoặc trên Windows/Linux.

## Ảnh và hướng dẫn demo

[Ảnh CASHIER desktop](images/issue-71/pos-cashier-desktop.png) chụp trực tiếp Chrome sau đăng nhập backend local thật. Viewport rộng 1440 CSS px; ảnh Retina 2880 px, chiều cao tự động 3830 CSS px (7660 px ảnh). Đây là ảnh viewport cao, không phải ảnh 1440 × 900 hoặc bản ghép Figma.

1. Chạy backend demo theo hướng dẫn đã có; không seed lại DB chỉ để xem layout.
2. Tại `frontend`, chạy `npm run dev`. Mở URL Vite thực tế; lần kiểm tra này là `http://127.0.0.1:5174` vì 5173 đã có tiến trình khác.
3. Đăng nhập CASHIER demo bằng credential trong tài liệu dự án. Kiểm tra header lấy chi nhánh/tên thật và năm menu.
4. Dùng chuột hoặc Ctrl+Alt+1–5 (macOS: Control+Option+1–5). Kiểm tra menu active và heading được focus; trang nghiệp vụ hiện thông báo đang chuẩn bị.
5. Mở `/pos/receipts?range=today#detail`, refresh; sau đó thử `/pos/not-a-route` để xem 404 trong shell và quay lại POS.
6. CASHIER mở `/operations/stock-documents`: guard cũ đưa tới `/403`.
7. Chạy `npm test -- --run tests/layouts/pos` để tái lập loading, timeout, lỗi, retry và session regression. Các lỗi được inject trong test, không có nút/route giả để gây lỗi trong sản phẩm.
8. `npm run test:auth-integration` kiểm tra auth với backend thật theo README. Không thu hồi refresh token của người khác hoặc thao tác DB để demo hết phiên.

## Bàn giao SV05 — chưa sửa trong #71

- `vitest.config.ts` hiện chỉ include `tests/**/*.test.{ts,tsx}`; ba test shared trong `src/hooks`, `src/lib`, `src/components/ui/tsx` chưa được discovery. 130 tests PASS không bao gồm ba file này.
- AsyncBlock vẫn render children trước mốc 300 ms. Với shared Table có `data=[]`, có thể hiện "Không có dữ liệu" trong lúc tải, như đã tái hiện trong audit. #71 không sử dụng AsyncBlock quanh placeholder/content loading nên không phụ thuộc cách render này.
- Khác biệt logo loading, icon/wording/typography trong shared UI so với PNG vẫn cần SV05/Lead review. Không override shared CSS để che khác biệt.

## Rà soát từng file thay đổi

| File                                                   | Lý do thuộc #71                                                               |
| ------------------------------------------------------ | ----------------------------------------------------------------------------- |
| `frontend/src/features/auth/AppRoutes.tsx`             | Thêm import và thay đúng mount point POS; giữ nguyên guards và route khác     |
| `frontend/src/layouts/pos/posNavigation.ts`            | Một cấu hình route/menu/shortcut tạm thời và allowlist kế thừa POS_ROLES      |
| `frontend/src/layouts/pos/PosLayout.tsx`               | Header/sidebar/content, active menu, skip link, gắn route con và 404 POS      |
| `frontend/src/layouts/pos/PosLayout.css`               | Chỉ style có tiền tố POS; dùng tokens hiện có; bố cục desktop và fallback hẹp |
| `frontend/src/layouts/pos/PosPlaceholderPage.tsx`      | Placeholder đơn giản cho các màn chưa làm; không API hoặc nghiệp vụ           |
| `frontend/src/layouts/pos/PosRouteContent.tsx`         | Tải nội dung, loading/timeout/retry, focus, adapter shared special pages      |
| `frontend/src/layouts/pos/PosErrorBoundary.tsx`        | Cô lập lỗi render nội dung khỏi shell; retry bằng remount content             |
| `frontend/src/layouts/pos/usePosShortcuts.ts`          | Phím điều hướng có điều kiện, cleanup; không xử lý phím nghiệp vụ             |
| `frontend/tests/layouts/pos/PosLayout.test.tsx`        | Menu, URL, chuột/phím, focus, RBAC, branch và hết phiên                       |
| `frontend/tests/layouts/pos/PosContent.test.tsx`       | Loading/timeout/retry, stale result, lỗi shared/render và dữ liệu khối khác   |
| `docs/testing/issue-71-validation.md`                  | Bằng chứng, giả định, hướng dẫn demo, ma trận AC và bàn giao                  |
| `docs/testing/images/issue-71/pos-cashier-desktop.png` | Ảnh browser thực tế làm bằng chứng desktop                                    |

## Final review trước commit/PR — 07/11/2026

### Kết luận

Đủ điều kiện tạo PR để Lead review trong phạm vi #71, sau khi người dùng cho phép commit/push. Chưa thực hiện các thao tác đó. Chưa coi issue hoàn thành hoặc đủ điều kiện merge: cần CI của PR và quyết định Lead về mapping tạm thời/giao diện. Không còn blocker #71 đã biết sau các sửa lỗi bên dưới.

GitHub xác nhận remote develop vẫn là `e41d35a`; HEAD và origin/develop local cùng SHA. Đã review diff tracked và toàn bộ 11 file untracked bằng diff với `/dev/null`, gồm ảnh PNG. Không có file bị xóa, staged change hoặc commit mới. Tổng phạm vi: 1 file sửa, 11 file thêm.

### Lỗi tái hiện và đã sửa trong final review

| Mức | Tình huống trước sửa                                                                           | Sửa và regression                                                                                                                                                           |
| --- | ---------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P2  | `/pos/receipts/` render được trang nhưng menu không active                                     | Dùng `matchPath` cùng cơ chế khớp route của router; test URL trailing slash đã fail trước sửa và PASS sau sửa                                                               |
| P2  | Trong fixture màn nghiệp vụ, đổi active branch ngay tại cùng route vẫn giữ input của branch cũ | Key vùng content theo user ID + branch ID + route; test với ActiveBranchProvider thật xác nhận input được reset sang branch mới. Không sửa provider hoặc thêm UI đổi branch |
| P2  | ErrorBoundary dùng `undefined` làm dấu hiệu chưa lỗi nên bỏ sót component ném `undefined`      | Tách cờ `hasError`; regression cho Error, undefined, null; shared 500 và retry hoạt động                                                                                    |

### Test discovery thực tế

`frontend/node_modules/.bin/vitest list --filesOnly`, chạy từ thư mục frontend, liệt kê đúng 12 file: 2 API, 8 auth và 2 POS. Lần chạy sau sửa: 130 tests PASS = 97 tests baseline + 33 tests POS. Suite integration riêng discovery 1 file với 7 tests, chạy backend thật và PASS.

Các file shared dưới đây không được discovery; không ghi nhận PASS cho chúng và không sửa config/SV05:

- `frontend/src/hooks/useDelayedLoading.test.ts`
- `frontend/src/lib/errors.test.ts`
- `frontend/src/components/ui/tsx/AsyncBlock.test.tsx`

Đã chạy lại typecheck, lint, format check, toàn bộ unit/render tests và build sau sửa. Whitespace check bao gồm tracked diff và từng file text untracked. Suite auth/backend thật chạy lại 7/7 PASS. Không phát hiện regression trong các kiểm tra này; đây không phải bảo đảm cho mọi kịch bản nghiệp vụ chưa triển khai.

### Kiểm tra phạm vi và khả năng tích hợp

Đối chiếu byte với baseline xác nhận AppRoutes chỉ khác import PosLayout và mount POS. Các function guard, route khác, API client/schema, auth/branch provider, shared components/pages/hooks/errors, dependency, cấu hình test, backend và OpenAPI không thay đổi. #72/#73 và các issue nghiệp vụ chưa được triển khai trong diff; không có request nghiệp vụ mới.

Shell giữ nguyên khi đổi menu. Loader nhận item để gắn module màn sau này; mỗi màn dùng context/route hooks hiện có. Vùng nội dung được reset khi user/chi nhánh thay đổi, không giữ dữ liệu của workspace cũ. Dữ liệu nghiệp vụ cần giữ khi rời một menu (ví dụ giỏ chưa thanh toán) vẫn phải do issue nghiệp vụ sở hữu; layout không tự lưu giỏ. Route chi tiết mới của các issue sau cần đăng ký tường minh, không bật wildcard để cấp quyền hoặc giả định mọi URL con đã hợp lệ.

### Đối chiếu ảnh

Đã xem lại ảnh browser đã lưu cùng vùng POS của PNG #171 ở độ phân giải đọc được: cùng wordmark/header xanh đậm, thông tin user/branch bên phải, sidebar trái, thứ tự năm menu và nền active xanh nhạt. Không có khác biệt màu/CSS mới từ ba sửa lỗi final review.

Ảnh browser được chụp trong lượt triển khai trước final review, rộng 1440 CSS px và chiều cao tự động; không phải ảnh đối chiếu 1440 × 900. Content hai cột sản phẩm/giỏ và nhãn ca trong PNG không được triển khai vì ngoài phạm vi #71 đã chốt. Sidebar có thêm gợi ý số shortcut tạm thời. Không xác minh pixel-perfect, prototype hay token/component binding; Lead vẫn cần review phần hình ảnh và các giả định liệt kê ở đầu tài liệu.

## Bổ sung Đăng xuất trên Header POS — 07/11/2026

Header trước đó chưa có Đăng xuất. Theo yêu cầu người dùng, thêm shared Button secondary bên phải thông tin tài khoản; chỉ chỉnh padding/căn giữa header để chứa nút cao 44 px trong hàng header hiện có. Không sửa shared component hoặc các màn khác.

Handler gọi signOut() từ useAuth(); session và active branch do cơ chế #70 xóa. Auth update và navigate('/login', replace) nằm trong cùng React transition để guard không lưu URL cũ vào return state khi logout chủ động. Không đợi thu hồi token trên mạng trước khi về Login; lỗi thu hồi không khôi phục phiên local và không tạo điều hướng muộn có thể ảnh hưởng tài khoản đăng nhập tiếp theo. Không tuyên bố thu hồi trên server thành công khi request lỗi.

Ba test mới kiểm tra logout xóa session/branch, request logout dùng refresh token cũ, đăng nhập user ID/chi nhánh khác chỉ còn session/danh tính mới; đồng thời kiểm tra logout cũ resolve hoặc reject sau login mới không xóa phiên hoặc đổi route mới. Tổng suite hiện là 130 tests/12 file; ba test shared SV05 vẫn không được discovery.

Phạm vi bổ sung này: PosLayout.tsx (nút và handler), PosLayout.css (căn header), PosLayout.test.tsx (ba test), tài liệu này (bằng chứng). AuthProvider, API client, guards và shared components không sửa. Ảnh desktop đã lưu được chụp trước khi thêm nút Đăng xuất, nên chưa phản ánh thay đổi header này.
