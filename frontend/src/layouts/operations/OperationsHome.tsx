import { Navigate } from 'react-router';
import { useAuth } from '../../features/auth/useAuth';
import { operationsHomePath } from './menuConfig';

/** Route index của /operations: THỦ KHO không có Tổng quan nên chuyển sang Tồn kho. */
export default function OperationsHome() {
  const { state } = useAuth();
  if (state.status === 'authenticated') {
    const home = operationsHomePath(state.user.role);
    if (home !== '/operations') return <Navigate to={home} replace />;
  }
  return <h1>Tổng quan vận hành</h1>; // TODO: thay bằng trang Tổng quan (issue khác)
}
