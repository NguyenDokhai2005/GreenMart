import { useMemo } from 'react';
import { Outlet, useLocation } from 'react-router';
import { useAuth } from '../../features/auth/useAuth';
import { OperationsScopeProvider } from './OperationsScopeProvider';
import { MENU, menuForRole, type MenuItem } from './menuConfig';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';
import './operations.css';

function findLabel(items: MenuItem[], path: string): string | undefined {
  for (const i of items) {
    const hit = i.children ? findLabel(i.children, path) : undefined;
    if (hit) return hit;
    if (i.to && (path === i.to || (i.to.split('/').length > 2 && path.startsWith(`${i.to}/`))))
      return i.label;
  }
}

/** Layout chung cho /operations/* và /admin/*. Đặt SAU AuthBoundary (và BranchBoundary với Operations). */
export default function OperationsLayout() {
  const { state } = useAuth();
  const { pathname } = useLocation();
  const user = state.status === 'authenticated' ? state.user : null;
  const items = useMemo(() => (user ? menuForRole(user.role) : []), [user]);
  if (!user) return null; // AuthBoundary đã xử lý các trạng thái khác

  return (
    <OperationsScopeProvider>
      <div className="ops">
        <Sidebar items={items} />
        <div className="ops-main">
          <Topbar user={user} section={findLabel(MENU, pathname) ?? 'Tổng quan'} />
          <main className="ops-content">
            <Outlet />
          </main>
        </div>
      </div>
    </OperationsScopeProvider>
  );
}
