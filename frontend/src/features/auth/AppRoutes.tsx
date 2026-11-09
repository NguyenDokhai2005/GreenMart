import { useEffect, useState } from 'react';
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import type { ReactNode } from 'react';
import type { Role } from '../../api/client';
import { Button, Toast } from '../../components/ui';
import { evaluateRouteAccess } from './routeAccess';
import { useActiveBranch } from './useActiveBranch';
import { useAuth } from './useAuth';
import BranchSelectionPage from './BranchSelectionPage';
import LoginPage from './LoginPage';
import { OperationsHome, OperationsLayout } from '../../layouts/operations';
import {
  ADMIN_ROLES,
  APP_PATHS,
  getRequestedPath,
  OPERATIONS_ROLES,
  POS_ROLES,
  resolveAuthHomeRedirect,
  STOCK_DOCUMENT_ROLES,
} from './routePolicy';
import './AppRoutes.css';

interface AuthBoundaryProps {
  children: ReactNode;
  allowedRoles?: readonly Role[];
}

function AuthBoundary({ children, allowedRoles }: AuthBoundaryProps) {
  const { state, retrySessionCheck } = useAuth();
  const location = useLocation();
  const decision = evaluateRouteAccess(state, allowedRoles);

  if (decision === 'checking') return <AuthCheckingPage onRetry={retrySessionCheck} />;
  if (decision === 'error') return <ConnectionPage onRetry={retrySessionCheck} />;
  if (decision === 'unauthenticated') {
    return (
      <Navigate
        to={APP_PATHS.login}
        replace
        state={{
          from: location,
          ...(state.status === 'expired' ? { reason: 'session-expired' } : {}),
        }}
      />
    );
  }
  if (decision === 'forbidden') {
    return <Navigate to={APP_PATHS.forbidden} replace state={{ from: location }} />;
  }
  return children;
}

function PublicOnly({ children }: { children: ReactNode }) {
  const { state, retrySessionCheck } = useAuth();
  const location = useLocation();
  if (state.status === 'checking') return <AuthCheckingPage onRetry={retrySessionCheck} />;
  if (state.status === 'error') return <ConnectionPage onRetry={retrySessionCheck} />;
  if (state.status === 'authenticated') {
    return <Navigate to={APP_PATHS.home} replace state={location.state} />;
  }
  return children;
}

function AuthHomeRedirect() {
  const { state, retrySessionCheck } = useAuth();
  const branch = useActiveBranch();
  const location = useLocation();

  if (state.status === 'checking') return <AuthCheckingPage onRetry={retrySessionCheck} />;
  if (state.status === 'error') return <ConnectionPage onRetry={retrySessionCheck} />;
  if (state.status === 'unauthenticated' || state.status === 'expired') {
    return (
      <Navigate
        to={APP_PATHS.login}
        replace
        state={state.status === 'expired' ? { reason: 'session-expired' } : undefined}
      />
    );
  }
  const redirect = resolveAuthHomeRedirect(
    state.user.role,
    branch.state.status,
    getRequestedPath(location.state),
  );

  if (redirect.status === 'checking') return <AuthCheckingPage onRetry={retrySessionCheck} />;
  if (redirect.status === 'unauthenticated') return <Navigate to={APP_PATHS.login} replace />;
  if (redirect.status === 'select-branch') {
    return <Navigate to={APP_PATHS.selectBranch} replace state={location.state} />;
  }

  return <Navigate to={redirect.to} replace state={location.state} />;
}

function BranchBoundary({ children }: { children: ReactNode }) {
  const branch = useActiveBranch();
  const { retrySessionCheck } = useAuth();
  const location = useLocation();

  if (branch.state.status === 'checking') return <AuthCheckingPage onRetry={retrySessionCheck} />;
  if (branch.state.status === 'unauthenticated') return <Navigate to={APP_PATHS.login} replace />;
  if (branch.state.status !== 'active') {
    return <Navigate to={APP_PATHS.selectBranch} replace state={{ from: location }} />;
  }
  return children;
}

function WorkspaceMountPoint({ title }: { title: string }) {
  return (
    <main className="auth-route__workspace-pending" aria-labelledby="workspace-pending-title">
      <h1 id="workspace-pending-title">{title}</h1>
      <Toast
        variant="warning"
        title="Khu làm việc chưa sẵn sàng"
        description="Màn hình chức năng này chưa được tích hợp."
      />
    </main>
  );
}

function ForbiddenPage() {
  const navigate = useNavigate();
  const branch = useActiveBranch();
  return (
    <AuthMessagePage
      title="Bạn không có quyền truy cập"
      message="Tài khoản hiện tại không được phép mở chức năng này."
      actionLabel="Về trang chủ"
      onAction={() => navigate(APP_PATHS.home, { replace: true })}
      secondary={
        <Link to={APP_PATHS.selectBranch} onClick={branch.clearBranch}>
          Chọn chi nhánh khác
        </Link>
      }
    />
  );
}

interface AuthMessagePageProps {
  title: string;
  message: string;
  actionLabel: string;
  onAction: () => void;
  secondary?: ReactNode;
}

function AuthMessagePage({
  title,
  message,
  actionLabel,
  onAction,
  secondary,
}: AuthMessagePageProps) {
  return (
    <main className="auth-route__message-page">
      <header className="auth-route__topbar">GreenMart</header>
      <section className="auth-route__message-card" aria-labelledby="auth-route-title">
        <span className="auth-route__code" aria-hidden="true">
          403
        </span>
        <h1 id="auth-route-title">{title}</h1>
        <p>{message}</p>
        <Button onClick={onAction}>{actionLabel}</Button>
        {secondary && <div className="auth-route__secondary">{secondary}</div>}
      </section>
    </main>
  );
}

function AuthCheckingPage({ onRetry }: { onRetry: () => Promise<void> }) {
  const [showLoader, setShowLoader] = useState(false);
  const [timedOut, setTimedOut] = useState(false);
  const [attempt, setAttempt] = useState(0);

  function handleRetry(): Promise<void> {
    setShowLoader(false);
    setTimedOut(false);
    setAttempt((current) => current + 1);
    return onRetry();
  }

  useEffect(() => {
    const loaderTimer = globalThis.setTimeout(() => setShowLoader(true), 300);
    const timeoutTimer = globalThis.setTimeout(() => setTimedOut(true), 15_000);
    return () => {
      globalThis.clearTimeout(loaderTimer);
      globalThis.clearTimeout(timeoutTimer);
    };
  }, [attempt]);

  if (!showLoader) return <div className="auth-route__silent-loading" aria-busy="true" />;
  if (timedOut) return <ConnectionPage onRetry={handleRetry} />;

  return (
    <main className="auth-route__loading" role="status" aria-live="polite" aria-busy="true">
      <span className="auth-route__spinner" aria-hidden="true" />
      <strong>Đang kiểm tra phiên đăng nhập…</strong>
      <span>Vui lòng chờ trong giây lát.</span>
    </main>
  );
}

function ConnectionPage({ onRetry }: { onRetry: () => Promise<void> }) {
  useEffect(() => {
    const retryWhenOnline = () => void onRetry();
    globalThis.addEventListener('online', retryWhenOnline);
    return () => globalThis.removeEventListener('online', retryWhenOnline);
  }, [onRetry]);

  return (
    <main className="auth-route__connection">
      <Toast
        variant="error"
        title="Không thể xác minh phiên đăng nhập"
        description="Kiểm tra kết nối mạng rồi thử lại. Phiên đã lưu không bị xóa."
      />
      <Button onClick={() => void onRetry()}>Thử lại</Button>
    </main>
  );
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path={APP_PATHS.home} element={<AuthHomeRedirect />} />
      <Route
        path={APP_PATHS.login}
        element={
          <PublicOnly>
            <LoginPage />
          </PublicOnly>
        }
      />
      <Route
        path={APP_PATHS.selectBranch}
        element={
          <AuthBoundary>
            <BranchSelectionPage />
          </AuthBoundary>
        }
      />
      <Route
        path={`${APP_PATHS.stockDocuments}/*`}
        element={
          <AuthBoundary allowedRoles={STOCK_DOCUMENT_ROLES}>
            <BranchBoundary>
              <WorkspaceMountPoint title="Phiếu kho" />
            </BranchBoundary>
          </AuthBoundary>
        }
      />
      <Route
        path={`${APP_PATHS.pos}/*`}
        element={
          <AuthBoundary allowedRoles={POS_ROLES}>
            <BranchBoundary>
              <WorkspaceMountPoint title="Khu POS" />
            </BranchBoundary>
          </AuthBoundary>
        }
      />
      <Route
        path={APP_PATHS.operations}
        element={
          <AuthBoundary allowedRoles={OPERATIONS_ROLES}>
            <BranchBoundary>
              <OperationsLayout />
            </BranchBoundary>
          </AuthBoundary>
        }
      >
        <Route index element={<OperationsHome />} />
        <Route path="*" element={<WorkspaceMountPoint title="Chức năng đang phát triển" />} />
      </Route>
      <Route
        path={APP_PATHS.admin}
        element={
          <AuthBoundary allowedRoles={ADMIN_ROLES}>
            <OperationsLayout />
          </AuthBoundary>
        }
      >
        <Route index element={<h1>Dashboard</h1>} />
        <Route path="*" element={<WorkspaceMountPoint title="Chức năng đang phát triển" />} />
      </Route>
      <Route path={APP_PATHS.forbidden} element={<ForbiddenPage />} />
      <Route path="*" element={<Navigate to={APP_PATHS.home} replace />} />
    </Routes>
  );
}
