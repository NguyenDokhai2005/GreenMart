import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { Button, Checkbox, Input, Toast } from '../../components/ui';
import { useAuth } from './useAuth';
import {
  getLoginFieldErrors,
  getLoginNotice,
  getLockedUntil,
  parseLockedUntil,
  validateLoginRequest,
  type LoginFieldErrors,
  type LoginNotice,
} from './loginForm';
import './LoginPage.css';
import { APP_PATHS } from './routePolicy';

const LOCKED_UNTIL_STORAGE_KEY = 'greenmart.auth.locked-until';

function readStoredLockDeadline(): number | null {
  try {
    const stored = globalThis.sessionStorage?.getItem(LOCKED_UNTIL_STORAGE_KEY);
    if (!stored) return null;

    const timestamp = parseLockedUntil(stored);
    if (timestamp && timestamp > Date.now()) return timestamp;

    globalThis.sessionStorage?.removeItem(LOCKED_UNTIL_STORAGE_KEY);
  } catch {
    // Storage can be unavailable; the backend remains authoritative for lockout.
  }
  return null;
}

function storeLockDeadline(timestamp: number | null): void {
  try {
    if (timestamp === null) {
      globalThis.sessionStorage?.removeItem(LOCKED_UNTIL_STORAGE_KEY);
      return;
    }
    globalThis.sessionStorage?.setItem(LOCKED_UNTIL_STORAGE_KEY, new Date(timestamp).toISOString());
  } catch {
    // The server-provided deadline is still shown for the current page session.
  }
}

function formatCountdown(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.ceil(milliseconds / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const twoDigits = (value: number) => String(value).padStart(2, '0');

  return hours > 0
    ? `${hours}:${twoDigits(minutes)}:${twoDigits(seconds)}`
    : `${twoDigits(minutes)}:${twoDigits(seconds)}`;
}

export default function LoginPage() {
  const { signIn } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<LoginFieldErrors>({});
  const [notice, setNotice] = useState<LoginNotice | null>(null);
  const [lockedUntil, setLockedUntil] = useState<number | null>(readStoredLockDeadline);
  const [now, setNow] = useState(Date.now);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const submitLockRef = useRef(false);
  const isLocked = lockedUntil !== null && lockedUntil > now;

  useEffect(() => {
    if (lockedUntil === null) return undefined;

    const timer = globalThis.setInterval(() => {
      const currentTime = Date.now();
      setNow(currentTime);
      if (lockedUntil <= currentTime) {
        setLockedUntil(null);
        setNotice(null);
        storeLockDeadline(null);
      }
    }, 1000);

    return () => globalThis.clearInterval(timer);
  }, [lockedUntil]);

  function clearNoticeOnEdit() {
    if (!isLocked) setNotice(null);
  }

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitLockRef.current || isLocked) return;

    const validationErrors = validateLoginRequest(username, password);
    setFieldErrors(validationErrors);
    setNotice(null);

    if (validationErrors.username || validationErrors.password) {
      const firstInvalidField = validationErrors.username ? 'login-username' : 'login-password';
      document.getElementById(firstInvalidField)?.focus();
      return;
    }

    submitLockRef.current = true;
    setIsSubmitting(true);

    try {
      await signIn(username, password);
      setFieldErrors({});
      setNotice(null);
      storeLockDeadline(null);
      setLockedUntil(null);
      navigate(APP_PATHS.home, { replace: true, state: location.state });
    } catch (error) {
      const nextNotice = getLoginNotice(error);
      const serverFieldErrors = getLoginFieldErrors(error);
      setFieldErrors(serverFieldErrors);
      setNotice(nextNotice);

      const deadline = getLockedUntil(error);
      const activeDeadline = deadline !== undefined && deadline > Date.now() ? deadline : null;
      setLockedUntil(activeDeadline);
      storeLockDeadline(activeDeadline);

      if (serverFieldErrors.username || serverFieldErrors.password) {
        const firstInvalidField = serverFieldErrors.username ? 'login-username' : 'login-password';
        document.getElementById(firstInvalidField)?.focus();
      }
    } finally {
      submitLockRef.current = false;
      setIsSubmitting(false);
    }
  }

  const lockedDescription =
    isLocked && lockedUntil !== null
      ? `${notice?.description ?? 'Tài khoản tạm khóa.'} Thử lại sau ${formatCountdown(lockedUntil - now)}.`
      : notice?.description;

  return (
    <div className="login-page">
      <header className="login-page__topbar">
        <span className="login-page__wordmark">GreenMart</span>
      </header>

      <main className="login-page__main">
        <header className="login-page__intro">
          <h1>Chào mừng trở lại</h1>
          <p>Đăng nhập để bắt đầu ca bán hàng</p>
        </header>

        {typeof location.state === 'object' &&
          location.state !== null &&
          'reason' in location.state &&
          location.state.reason === 'session-expired' && (
            <Toast
              variant="warning"
              title="Phiên đăng nhập đã hết hạn"
              description="Đăng nhập lại để tiếp tục làm việc."
              className="login-page__notice"
            />
          )}

        {typeof location.state === 'object' &&
          location.state !== null &&
          'logoutWarning' in location.state &&
          location.state.logoutWarning === true && (
            <Toast
              variant="warning"
              title="Đã đăng xuất khỏi thiết bị này"
              description="Máy chủ chưa xác nhận thu hồi phiên."
              className="login-page__notice"
            />
          )}

        {notice && (
          <Toast
            variant="error"
            title={notice.title}
            description={lockedDescription}
            className="login-page__notice"
          />
        )}

        <div className="login-page__columns">
          <section className="login-page__brand-panel" aria-label="Giới thiệu GreenMart">
            <h2>
              Một nơi quản lý.
              <br />
              Mọi giao dịch rõ ràng.
            </h2>
            <p>Hệ thống bán hàng và quản lý vận hành của chuỗi GreenMart.</p>
            <span className="login-page__brand-caption">GreenMart / Nội bộ</span>
          </section>

          <section className="login-page__form-card" aria-labelledby="login-form-title">
            <h2 id="login-form-title">Đăng nhập hệ thống</h2>
            <form className="login-page__form" onSubmit={handleSubmit} noValidate>
              <Input
                id="login-username"
                name="username"
                label="Tên đăng nhập"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                required
                maxLength={60}
                placeholder="Nhập tên đăng nhập"
                value={username}
                onChange={(event) => {
                  setUsername(event.target.value);
                  setFieldErrors((current) => ({ ...current, username: undefined }));
                  clearNoticeOnEdit();
                }}
                error={fieldErrors.username}
                disabled={isSubmitting || isLocked}
              />

              <Input
                id="login-password"
                name="password"
                label="Mật khẩu"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                required
                maxLength={100}
                placeholder="Nhập mật khẩu"
                value={password}
                onChange={(event) => {
                  setPassword(event.target.value);
                  setFieldErrors((current) => ({ ...current, password: undefined }));
                  clearNoticeOnEdit();
                }}
                error={fieldErrors.password}
                disabled={isSubmitting || isLocked}
              />

              <div className="login-page__options">
                <Checkbox
                  label="Hiện mật khẩu"
                  checked={showPassword}
                  onChange={(event) => setShowPassword(event.target.checked)}
                  disabled={isSubmitting || isLocked}
                />
                <span className="login-page__account-note">Chỉ dùng tài khoản được cấp</span>
              </div>

              <Button
                type="submit"
                className="login-page__submit"
                disabled={isSubmitting || isLocked}
              >
                {isSubmitting ? 'Đang đăng nhập…' : isLocked ? 'Tạm khóa đăng nhập' : 'Đăng nhập'}
              </Button>
            </form>
            <p className="login-page__help">Cần trợ giúp? Liên hệ quản trị viên.</p>
          </section>
        </div>
      </main>
    </div>
  );
}
