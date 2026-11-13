import { Button } from '../components/ui';
import { SpecialPage } from './SpecialPage';

export interface SessionExpiredPageProps {
  /**
   * Điều hướng tới trang đăng nhập theo quy ước của AuthBoundary (#70):
   * `navigate(APP_PATHS.login, { replace: true, state: { from: location, reason: 'session-expired' } })`.
   * LoginPage đọc `state.from` (qua `getRequestedPath`, đã chặn open redirect) để về đúng trang cũ.
   */
  onLogin: () => void;
}

/** Phiên hết hạn: SessionExpiredError, hoặc 401 UNAUTHORIZED / INVALID_REFRESH_TOKEN. */
export default function SessionExpiredPage({ onLogin }: SessionExpiredPageProps) {
  return (
    <SpecialPage
      title="Phiên đăng nhập đã hết hạn"
      description="Vui lòng đăng nhập lại. Bạn sẽ quay về đúng trang đang làm."
      actions={<Button onClick={onLogin}>Đăng nhập lại</Button>}
    />
  );
}
