import { Component, startTransition } from 'react';
import type { ReactNode } from 'react';
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { Button } from '../../components/ui';
import { useAuth } from '../auth/useAuth';
import { useActiveBranch } from '../auth/useActiveBranch';
import { getWorkspacePath } from '../auth/routePolicy';
import NotFoundPage from '../../pages/NotFoundPage';
import ServerErrorPage from '../../pages/ServerErrorPage';
import { CATALOG_PATHS } from './catalogPolicy';
import ProductsPage from './ProductsPage';
import CustomersPage from './CustomersPage';
import './catalog.css';

class CatalogBoundary extends Component<
  { children: ReactNode; navigation: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? (
      <>
        {this.props.navigation}
        <ServerErrorPage onRetry={() => this.setState({ failed: false })} />
      </>
    ) : (
      this.props.children
    );
  }
}

export default function CatalogWorkspace() {
  const auth = useAuth();
  const branch = useActiveBranch();
  const navigate = useNavigate();
  const location = useLocation();
  if (auth.state.status !== 'authenticated') return null;
  const user = auth.state.user;
  const branchId = branch.state.status === 'active' ? branch.state.branch.id : '';
  const workspaceKey = `${user.id}:${branchId}:${user.role}`;
  const navigation = (
    <nav className="catalog__nav" aria-label="Danh mục">
      <Link to={getWorkspacePath(user.role)}>Về khu làm việc</Link>
      {Object.entries(CATALOG_PATHS).map(([key, path]) => (
        <Link
          key={path}
          to={path}
          aria-current={location.pathname.replace(/\/$/, '') === path ? 'page' : undefined}
        >
          {key === 'products' ? 'Sản phẩm' : 'Khách hàng'}
        </Link>
      ))}
    </nav>
  );
  return (
    <div className="catalog">
      <a href="#catalog-content" className="catalog__skip">
        Chuyển đến nội dung
      </a>
      <header className="catalog__header">
        <strong>GreenMart</strong>
        <div>
          {branch.state.status === 'active' && (
            <span>
              {branch.state.branch.code} · {branch.state.branch.name}
            </span>
          )}
          <span>
            {
              {
                ADMIN: 'Quản trị viên',
                OWNER: 'Chủ cửa hàng',
                BRANCH_MANAGER: 'Quản lý chi nhánh',
                CASHIER: 'Thu ngân',
                STOREKEEPER: 'Thủ kho',
              }[user.role]
            }{' '}
            · {user.fullName}
          </span>
          <Button
            variant="secondary"
            onClick={() =>
              startTransition(() => {
                void auth.signOut().catch(() => undefined);
                navigate('/login', { replace: true });
              })
            }
          >
            Đăng xuất
          </Button>
        </div>
      </header>
      <main className="catalog__content" id="catalog-content" tabIndex={-1}>
        <CatalogBoundary key={`${workspaceKey}:${location.pathname}`} navigation={navigation}>
          <div key={workspaceKey}>
            <Routes>
              <Route index element={<Navigate to={CATALOG_PATHS.products} replace />} />
              <Route path="products" element={<ProductsPage navigation={navigation} />} />
              <Route path="customers" element={<CustomersPage navigation={navigation} />} />
              <Route
                path="*"
                element={<NotFoundPage onHome={() => navigate(CATALOG_PATHS.products)} />}
              />
            </Routes>
          </div>
        </CatalogBoundary>
      </main>
    </div>
  );
}
