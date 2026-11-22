import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useLocation } from 'react-router';
import { Button, Checkbox, Input, Toast } from '../../components/ui';
import { hasLogoutWarning, onLogoutWarning } from '../../api/client';
import { useAuth } from './useAuth';
import {
  getLoginFieldErrors,
  getLoginNotice,
  getAccountLockout,
  clearLegacyLoginLockDeadline,
  getLoginControlState,
  getVisibleLoginNotice,
  validateLoginRequest,
  type LoginFieldErrors,
  type LoginLockout,
  type UserLoginNotice,
} from './loginForm';
import './LoginPage.css';

function formatCountdown(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.ceil(milliseconds / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return [
    hours > 0 ? `${hours} giờ` : '',
    minutes > 0 ? `${minutes} phút` : '',
    seconds > 0 || (hours === 0 && minutes === 0) ? `${seconds} giây` : '',
  ]
    .filter(Boolean)
    .join(' ');
}

export default function LoginPage() {
  const { signIn } = useAuth();
  const location = useLocation();
  const logoutWarning = useSyncExternalStore(onLogoutWarning, hasLogoutWarning, () => false);
  const [username, setUsername] = useState('');
  const normalizedUsername = username.trim();
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<LoginFieldErrors>({});
  const [notice, setNotice] = useState<UserLoginNotice | null>(null);
  const [lockouts, setLockouts] = useState<readonly LoginLockout[]>([]);
  const [now, setNow] = useState(Date.now);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const submitLockRef = useRef(false);
  const currentLockout = lockouts.find((item) => item.username === normalizedUsername);
  const loginControls = getLoginControlState(normalizedUsername, isSubmitting, lockouts, now);
  const isLocked = loginControls.isLocked;
  const visibleNotice = getVisibleLoginNotice(normalizedUsername, notice, lockouts, now);

  useEffect(() => {
    clearLegacyLoginLockDeadline();
  }, []);

  useEffect(() => {
    if (lockouts.length === 0) return undefined;

    const timer = globalThis.setInterval(() => {
      const currentTime = Date.now();
      setNow(currentTime);
      const expiredUsernames = lockouts
        .filter((lockout) => lockout.lockedUntil <= currentTime)
        .map((lockout) => lockout.username);
      if (expiredUsernames.length > 0) {
        setLockouts((current) => current.filter((lockout) => lockout.lockedUntil > currentTime));
        setNotice((current) =>
          current && expiredUsernames.includes(current.username) ? null : current,
        );
      }
    }, 1000);

    return () => globalThis.clearInterval(timer);
  }, [lockouts]);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitLockRef.current || loginControls.submitDisabled) return;

    const validationErrors = validateLoginRequest(normalizedUsername, password);
    setFieldErrors(validationErrors);
    setNotice(null);

    if (validationErrors.username || validationErrors.password) {
      const firstInvalidField = validationErrors.username ? 'login-username' : 'login-password';
      document.getElementById(firstInvalidField)?.focus();
      return;
    }

    submitLockRef.current = true;
    setIsSubmitting(true);
    const controller = new AbortController();
    const timeout = globalThis.setTimeout(
      () => controller.abort(new DOMException('Login timed out', 'TimeoutError')),
      15_000,
    );

    try {
      await signIn(normalizedUsername, password, controller.signal);
      setFieldErrors({});
      setNotice(null);
      setLockouts([]);
    } catch (error) {
      const nextNotice = getLoginNotice(error);
      const serverFieldErrors = getLoginFieldErrors(error);
      setFieldErrors(serverFieldErrors);
      setNotice({ username: normalizedUsername, value: nextNotice });

      const nextLockout = getAccountLockout(error, normalizedUsername);
      if (nextLockout !== undefined) {
        setLockouts((current) => [
          ...current.filter(
            (lockout) =>
              lockout.username !== normalizedUsername && lockout.lockedUntil > Date.now(),
          ),
          ...(nextLockout === null ? [] : [nextLockout]),
        ]);
      } else {
        setLockouts((current) => current.filter((lockout) => lockout.lockedUntil > Date.now()));
      }

      if (serverFieldErrors.username || serverFieldErrors.password) {
        const firstInvalidField = serverFieldErrors.username ? 'login-username' : 'login-password';
        document.getElementById(firstInvalidField)?.focus();
      }
    } finally {
      globalThis.clearTimeout(timeout);
      submitLockRef.current = false;
      setIsSubmitting(false);
    }
  }

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

        {(logoutWarning ||
          (typeof location.state === 'object' &&
            location.state !== null &&
            'logoutWarning' in location.state &&
            location.state.logoutWarning === true)) && (
          <Toast
            variant="warning"
            title="Đã đăng xuất khỏi thiết bị này"
            description="Máy chủ chưa xác nhận thu hồi phiên."
            className="login-page__notice"
          />
        )}

        {visibleNotice && (
          <Toast
            variant="error"
            title={visibleNotice.title}
            description={visibleNotice.description}
            className="login-page__notice"
          />
        )}
        {isLocked && currentLockout && (
          <p className="login-page__lockout-countdown" aria-live="off">
            Thử lại sau {formatCountdown(currentLockout.lockedUntil - now)}.
          </p>
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
                  const nextUsername = event.target.value;
                  setUsername(nextUsername);
                  setFieldErrors({});
                  setNotice((current) =>
                    current?.username === nextUsername.trim() ? current : null,
                  );
                }}
                error={fieldErrors.username}
                disabled={loginControls.usernameDisabled}
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
                  setNotice((current) =>
                    current?.username === normalizedUsername ? null : current,
                  );
                }}
                error={fieldErrors.password}
                disabled={loginControls.passwordDisabled}
              />

              <div className="login-page__options">
                <Checkbox
                  label="Hiện mật khẩu"
                  checked={showPassword}
                  onChange={(event) => setShowPassword(event.target.checked)}
                  disabled={loginControls.passwordDisabled}
                />
                <span className="login-page__account-note">Chỉ dùng tài khoản được cấp</span>
              </div>

              <Button
                type="submit"
                className="login-page__submit"
                disabled={loginControls.submitDisabled}
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
