import { Suspense, lazy } from 'react';
import { Navigate } from 'react-router';
import { Skeleton } from '../../components/ui';
import { useAuth } from '../../features/auth/useAuth';
import { operationsHomePath } from './menuConfig';

const DashboardPage = lazy(() => import('../../features/dashboard/DashboardPage'));

/** Route index của /operations: THỦ KHO không có Tổng quan nên chuyển sang Tồn kho. */
export default function OperationsHome() {
  const { state } = useAuth();
  if (state.status === 'authenticated') {
    const home = operationsHomePath(state.user.role);
    if (home !== '/operations') return <Navigate to={home} replace />;
  }
  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <h1>Tổng quan vận hành</h1>
      <Suspense fallback={<Skeleton height={360} />}>
        <DashboardPage />
      </Suspense>
    </div>
  );
}
