import { SpecialPage } from './SpecialPage';

export interface SessionExpiredPageProps {
  /** Trang đăng nhập; mặc định /login. */
  loginHref?: string;
  /** Đường dẫn cần quay lại sau khi đăng nhập; mặc định là URL hiện tại. */
  returnTo?: string;
  /** Khi dự án có router, truyền để điều hướng không tải lại trang. Nhận URL đăng nhập đã kèm returnTo. */
  onLogin?: (loginUrl: string) => void;
}

/**
 * Phiên hết hạn: 401 sau khi làm mới token thất bại.
 * Về đăng nhập kèm `returnTo` để đăng nhập xong quay lại đúng trang cũ.
 * Trang đăng nhập cần đọc `?returnTo=` và điều hướng sau khi thành công.
 */
export default function SessionExpiredPage({
  loginHref = '/login',
  returnTo,
  onLogin,
}: SessionExpiredPageProps) {
  const target =
    returnTo ?? `${window.location.pathname}${window.location.search}${window.location.hash}`;
  const loginUrl = `${loginHref}?returnTo=${encodeURIComponent(target)}`;
  return (
    <SpecialPage
      title="Phiên đăng nhập đã hết hạn"
      description="Vui lòng đăng nhập lại. Bạn sẽ quay về đúng trang đang làm."
      actions={
        <a
          className="ui-state-btn"
          href={loginUrl}
          onClick={
            onLogin
              ? (e) => {
                  e.preventDefault();
                  onLogin(loginUrl);
                }
              : undefined
          }
        >
          Đăng nhập lại
        </a>
      }
    />
  );
}
