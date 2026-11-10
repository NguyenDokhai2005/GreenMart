import { startTransition, useMemo } from 'react';
import { Link, matchPath, Route, Routes, useLocation, useNavigate } from 'react-router';
import { Button } from '../../components/ui';
import { useAuth } from '../../features/auth/useAuth';
import { useActiveBranch } from '../../features/auth/useActiveBranch';
import { APP_PATHS } from '../../features/auth/routePolicy';
import NotFoundPage from '../../pages/NotFoundPage';
import PosRouteContent, { type PosPageLoader } from './PosRouteContent';
import { getPosNavigation, POS_SHORTCUTS } from './posNavigation';
import { usePosShortcuts } from './usePosShortcuts';
import './PosLayout.css';

const ROLE_LABELS = { CASHIER: 'Thu ngân', BRANCH_MANAGER: 'Quản lý chi nhánh' } as const;

export default function PosLayout({ loadPage }: { loadPage?: PosPageLoader }) {
  const auth = useAuth();
  const branch = useActiveBranch();
  const navigate = useNavigate();
  const location = useLocation();
  const role = auth.state.status === 'authenticated' ? auth.state.user.role : undefined;
  const items = useMemo(() => (role ? getPosNavigation(role) : []), [role]);
  usePosShortcuts(items);

  // The existing AuthBoundary and BranchBoundary own redirects and permission decisions.
  if (auth.state.status !== 'authenticated' || branch.state.status !== 'active') return null;
  if (role !== 'CASHIER' && role !== 'BRANCH_MANAGER') return null;

  const workspaceKey = `${auth.state.user.id}:${branch.state.branch.id}`;
  const activePath = items.find((item) =>
    matchPath({ path: item.path, end: true }, location.pathname),
  )?.path;

  function handleSignOut() {
    // #70 clears local session/branch before awaiting server revocation. Navigate once,
    // immediately; a late success/failure must not redirect a subsequently signed-in user.
    // Declarative router navigation uses a transition. Keep the auth update in
    // the same transition so the outgoing guard cannot save the old return URL.
    startTransition(() => {
      void auth.signOut().catch(() => undefined);
      navigate(APP_PATHS.login, { replace: true });
    });
  }

  return (
    <div className="pos-layout">
      <a className="pos-layout__skip" href="#pos-content">
        Chuyển đến nội dung
      </a>
      <header className="pos-layout__header">
        <span className="pos-layout__brand">GreenMart</span>
        <div className="pos-layout__identity">
          <span>
            {branch.state.branch.code} · {branch.state.branch.name}
          </span>
          <span>
            {ROLE_LABELS[role]} · {auth.state.user.fullName}
          </span>
          <Button variant="secondary" onClick={handleSignOut}>
            Đăng xuất
          </Button>
        </div>
      </header>
      <aside className="pos-layout__sidebar">
        <nav aria-label="Điều hướng POS">
          <p className="pos-layout__nav-title">Điều hướng POS</p>
          <ul className="pos-layout__menu">
            {items.map((item) => (
              <li key={item.path}>
                <Link
                  to={item.path}
                  aria-current={activePath === item.path ? 'page' : undefined}
                  aria-keyshortcuts={`${POS_SHORTCUTS.ariaPrefix}+${item.digit}`}
                  title={`${item.label} (${POS_SHORTCUTS.label} + ${item.digit})`}
                  className={`pos-layout__link${activePath === item.path ? ' pos-layout__link--active' : ''}`}
                >
                  <span>{item.label}</span>
                  <kbd aria-hidden="true">{item.digit}</kbd>
                </Link>
              </li>
            ))}
          </ul>
        </nav>
        <div className="pos-layout__shortcuts">
          <p>
            {POS_SHORTCUTS.label} + {POS_SHORTCUTS.rangeLabel}: chuyển mục
          </p>
          <p>
            macOS: {POS_SHORTCUTS.macLabel} + {POS_SHORTCUTS.rangeLabel}
          </p>
          <p>F2 Tìm sản phẩm · F4 Thanh toán · Esc Xóa tìm kiếm</p>
        </div>
      </aside>
      <main id="pos-content" className="pos-layout__content" tabIndex={-1}>
        <Routes>
          {items.map((item) =>
            item.segment ? (
              <Route
                key={item.path}
                path={item.segment}
                element={
                  <PosRouteContent
                    key={`${workspaceKey}:${item.path}`}
                    item={item}
                    loadPage={loadPage}
                  />
                }
              />
            ) : (
              <Route
                key={item.path}
                index
                element={
                  <PosRouteContent
                    key={`${workspaceKey}:${item.path}`}
                    item={item}
                    loadPage={loadPage}
                  />
                }
              />
            ),
          )}
          <Route path="*" element={<NotFoundPage onHome={() => navigate(APP_PATHS.pos)} />} />
        </Routes>
      </main>
    </div>
  );
}
