import { useMemo, useState } from 'react';
import { Outlet, useLocation } from 'react-router';
import { useAuth } from '../../features/auth/useAuth';
import { BreadcrumbContext } from './BreadcrumbContext';
import { OperationsScopeProvider } from './OperationsScopeProvider';
import { breadcrumbFor, menuForRole } from './menuConfig';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';
import './operations.css';

/** Layout chung cho /operations/* và /admin/*. Đặt SAU AuthBoundary (và BranchBoundary với Operations). */
export default function OperationsLayout() {
  const { state } = useAuth();
  const { pathname } = useLocation();
  const user = state.status === 'authenticated' ? state.user : null;
  const items = useMemo(() => (user ? menuForRole(user.role) : []), [user]);
  const [tail, setTail] = useState<string | undefined>();
  const breadcrumb = useMemo(() => ({ setTail }), []);
  if (!user) return null; // AuthBoundary đã xử lý các trạng thái khác

  return (
    <OperationsScopeProvider>
      <BreadcrumbContext.Provider value={breadcrumb}>
        <div className="ops">
          <Sidebar items={items} />
          <div className="ops-main">
            <Topbar user={user} section={breadcrumbFor(pathname) ?? 'Tổng quan'} tail={tail} />
            <main className="ops-content">
              <Outlet />
            </main>
          </div>
        </div>
      </BreadcrumbContext.Provider>
    </OperationsScopeProvider>
  );
}
