import { Suspense, lazy } from 'react';
import { Navigate } from 'react-router';
import { Skeleton } from '../../components/ui';
import { useAuth } from '../../features/auth/useAuth';
import { operationsHomePath } from './menuConfig';

// Tải biểu đồ khi cần: Recharts nằm ở chunk riêng, không làm nặng trang đăng nhập và các trang khác.
const RevenueChartTrial = lazy(() => import('../../features/dashboard/RevenueChartTrial'));

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
      {/* TẠM THỜI (#89): biểu đồ thử với dữ liệu mock. Thay bằng trang Tổng quan ở issue dashboard. */}
      <Suspense fallback={<Skeleton height={360} />}>
        <RevenueChartTrial />
      </Suspense>
    </div>
  );
}
