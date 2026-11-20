import type { CurrentUser } from '../../api/client';
import { useAuth } from '../auth/useAuth';

/** Người dùng đang đăng nhập; null khi chưa có (các trang này nằm sau AuthBoundary nên hiếm khi xảy ra). */
export function useCurrentUser(): CurrentUser | null {
  const { state } = useAuth();
  return state.status === 'authenticated' ? state.user : null;
}
