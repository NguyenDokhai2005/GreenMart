# Layout Operations (#88)

Một layout chung cho `/operations/*` và `/admin/*`. Menu và phạm vi chi nhánh đổi theo vai trò; không có layout riêng từng vai trò.
Dùng lại `useAuth`, `useActiveBranch`, `routePolicy` của `features/auth` (SV04), không giữ state auth riêng.

## Cấu trúc

| File                                                                                | Vai trò                                                                         |
| ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `OperationsLayout.tsx`                                                              | Khung: Sidebar + Topbar + `<Outlet />`, bọc `OperationsScopeProvider`           |
| `OperationsHome.tsx`                                                                | Route index `/operations`: THỦ KHO chuyển sang Tồn kho                          |
| `menuConfig.ts`                                                                     | Nguồn duy nhất của menu; lọc theo vai trò **và** `canOpenRoutePath`             |
| `OperationsScopeContext.ts`, `OperationsScopeProvider.tsx`, `useOperationsScope.ts` | Phạm vi dữ liệu: "Tất cả chi nhánh" hoặc một chi nhánh                          |
| `BranchSwitcher.tsx`                                                                | Dropdown chọn chi nhánh (OWNER/ADMIN/nhiều chi nhánh) hoặc ô khóa (1 chi nhánh) |
| `Sidebar.tsx`, `Topbar.tsx`, `UserMenu.tsx`, `Icon.tsx`, `adapters.ts`              | Thành phần giao diện; menu tài khoản có thao tác đăng xuất                      |
| `operations.css`                                                                    | CSS layout, dùng token `--gm-*`                                                 |
| `tests/layouts/operations/UserMenu.test.tsx`                                        | Kiểm tra menu tài khoản và đăng xuất                                            |
| `*.test.ts(x)`                                                                      | Test phân quyền menu và phạm vi chi nhánh                                       |

## Routes đã tích hợp

- `AppRoutes.tsx` gắn `OperationsLayout` vào `/operations` và `/admin`, giữ kiểm tra quyền; khu Operations tiếp tục yêu cầu chi nhánh đang chọn.
- Route index Operations dùng `OperationsHome` (THỦ KHO chuyển tới Tồn kho); route index Admin hiển thị Dashboard.
- Các route con chưa có trang thật hiển thị trạng thái chưa tích hợp bên trong layout.
- `/operations/stock-documents` vẫn do route Phiếu kho riêng xử lý; `/pos` không thay đổi.
- Menu tài khoản gọi `signOut` để xóa phiên và chuyển về `/login`; nếu thu hồi token qua mạng lỗi, phiên local vẫn được đăng xuất.

## Dùng trong trang con

```tsx
const { branchId } = useOperationsScope(); // undefined = tất cả chi nhánh
// truyền branchId vào query API (không truyền thì backend trả mọi chi nhánh được phép xem)
```

## Phụ thuộc

- `react-router`, `components/ui` xuất `Input` và `EmptyState`.
- `features/auth`: `useAuth`, `useActiveBranch`, `routePolicy`.
- `api/client`: kiểu `Role`, `BranchRef`, `CurrentUser`.

## Kiểm tra

```
npm run typecheck && npm run lint && npm test
```

## Còn chờ / chưa làm

- **Quyền theo từng đường dẫn** (SV04): `routePolicy` hiện chỉ chặn theo khu, THỦ KHO gõ URL vẫn vào được `/operations/promotions`. Khi SV04 bổ sung vào `getRolesForPath`, menu tự theo.
- **OWNER bị ép chọn chi nhánh** trước khi vào Operations (luồng của SV04); chờ BA/SV04 quyết định.
- **Responsive (#101):** layout hiện chỉ dành cho desktop (1440 px theo Figma); bản mobile làm ở #101.
- **Chuông thông báo:** chưa có hành động (`openapi.yaml` chưa có API thông báo).
- **Trang con** (Tổng quan, Tồn kho, ...) thuộc các issue khác.

# Issue #89: chọn thư viện biểu đồ, biểu đồ thử doanh thu 30 ngày

Thư viện chọn: **Recharts**. Lý do và số liệu so sánh nằm trong `PR_DESCRIPTION.md` (dán vào mô tả PR).

## Cài đặt và chép file

```
cd frontend
npm i recharts react-is@^19
```

Giải nén `issue-89-chart-library.zip` vào `frontend/` (giữ đúng đường dẫn trong `src/`). File `src/layouts/operations/OperationsHome.tsx` ghi đè bản của #88 (thêm biểu đồ thử, tải lười).

## Dùng biểu đồ

```tsx
import { RevenueAreaChart, Sparkline } from '../../components/charts';

<RevenueAreaChart data={points} />            // points: { date: 'YYYY-MM-DD', revenue: number }[]
<Sparkline data={series} tone="danger" />     // series: { date, value }[]
```

## Xem biểu đồ

Sau khi đã gắn `OperationsLayout` vào `AppRoutes.tsx` (xem README của #88), đăng nhập bằng OWNER hoặc QLCN và mở `/operations`. Trang Tổng quan tạm hiển thị biểu đồ thử.

## Kiểm tra

```
npm run typecheck && npm run lint && npm test
```
