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
| `Sidebar.tsx`, `Topbar.tsx`, `Icon.tsx`, `adapters.ts`                              | Thành phần giao diện, nhãn hiển thị                                             |
| `operations.css`                                                                    | CSS layout, dùng token `--gm-*`                                                 |
| `*.test.ts(x)`                                                                      | Test phân quyền menu và phạm vi chi nhánh                                       |

## Gắn vào `AppRoutes.tsx` (thay hai `WorkspaceMountPoint`)

```tsx
import { OperationsHome, OperationsLayout } from '../../layouts/operations';

// Giữ route `${APP_PATHS.stockDocuments}/*` ở trên như hiện tại (khớp cụ thể hơn nên được ưu tiên).
<Route
  path={APP_PATHS.operations}
  element={
    <AuthBoundary allowedRoles={OPERATIONS_ROLES}>
      <BranchBoundary><OperationsLayout /></BranchBoundary>
    </AuthBoundary>
  }
>
  <Route index element={<OperationsHome />} />
  {/* các trang con: orders, products, inventory... */}
</Route>

<Route
  path={APP_PATHS.admin}
  element={<AuthBoundary allowedRoles={ADMIN_ROLES}><OperationsLayout /></AuthBoundary>}
>
  <Route index element={<h1>Dashboard</h1>} />
</Route>
```

## Dùng trong trang con

```tsx
const { branchId } = useOperationsScope(); // undefined = tất cả chi nhánh
// truyền branchId vào query API (không truyền thì backend trả mọi chi nhánh được phép xem)
```

## Phụ thuộc

- `react-router` (v7), `components/ui` xuất `Input` và `EmptyState`.
- `features/auth`: `useAuth`, `useActiveBranch`, `routePolicy`.
- `api/client`: kiểu `Role`, `BranchRef`, `CurrentUser`.

## Kiểm tra

```
npm run typecheck && npm run lint && npm test
```

## Còn chờ / chưa làm

- **Quyền theo từng đường dẫn** (SV04): `routePolicy` hiện chỉ chặn theo khu, THỦ KHO gõ URL vẫn vào được `/operations/promotions`. Khi SV04 bổ sung vào `getRolesForPath`, menu tự theo.
- **OWNER bị ép chọn chi nhánh** trước khi vào Operations (luồng của SV04); chờ BA/SV04 quyết định.
- **Nút người dùng, chuông thông báo:** chưa có hành động (Figma chưa vẽ menu tài khoản, `openapi.yaml` chưa có API thông báo).
- **Trang con** (Tổng quan, Tồn kho, ...) thuộc các issue khác.
