import { useMemo, useState } from 'react';
import { Outlet, useLocation } from 'react-router';
import { useAuth } from '../../features/auth/useAuth';
import { OperationsScopeProvider } from './OperationsScopeProvider';
import { breadcrumbFor, menuForRole } from './menuConfig';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';
import './operations.css';

/** Layout chung cho /operations/* và /admin/*. Đặt SAU AuthBoundary (và BranchBoundary với Operations). */
export default function OperationsLayout() {
  const { state } = useAuth();
  const { pathname } = useLocation();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const user = state.status === 'authenticated' ? state.user : null;
  const items = useMemo(() => (user ? menuForRole(user.role) : []), [user]);
  if (!user) return null; // AuthBoundary đã xử lý các trạng thái khác

  return (
    <OperationsScopeProvider>
      <div className="ops">
        <Sidebar
          items={items}
          mobileOpen={mobileMenuOpen}
          onMobileClose={() => setMobileMenuOpen(false)}
        />
        {mobileMenuOpen && (
          <button
            type="button"
            className="ops-mobile-backdrop"
            aria-label="Đóng menu"
            onClick={() => setMobileMenuOpen(false)}
          />
        )}
        <div className="ops-main">
          <Topbar
            user={user}
            section={breadcrumbFor(pathname) ?? 'Tổng quan'}
            onMenu={() => setMobileMenuOpen(true)}
          />
          <main className="ops-content">
            <Outlet />
          </main>
        </div>
      </div>
    </OperationsScopeProvider>
  );
}
