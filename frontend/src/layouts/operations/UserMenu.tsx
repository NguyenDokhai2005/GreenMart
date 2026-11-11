import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import type { CurrentUser } from '../../api/client';
import { APP_PATHS } from '../../features/auth/routePolicy';
import { useAuth } from '../../features/auth/useAuth';
import { ROLE_LABEL, userDisplayName } from './adapters';
import { Icon } from './Icon';

const initials = (name: string) =>
  name
    .split(' ')
    .map((word) => word[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

export function UserMenu({ user }: { user: CurrentUser }) {
  const { signOut } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;

    const onDown = (event: MouseEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };

    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  async function logout() {
    if (busy) return;
    setBusy(true);
    try {
      await signOut();
    } catch {
      // signOut clears the local session before its network request.
    } finally {
      navigate(APP_PATHS.login, { replace: true });
    }
  }

  const name = userDisplayName(user);

  return (
    <div className="ops-usermenu" ref={root}>
      <button
        type="button"
        className="ops-user"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Tài khoản ${name}`}
        onClick={() => setOpen((current) => !current)}
      >
        <span className="ops-avatar">{initials(name)}</span>
        <span className="ops-user__text">
          <b>{name}</b>
          <small>{ROLE_LABEL[user.role]}</small>
        </span>
        <Icon name="chevron" size={14} className={open ? 'ops-flip' : undefined} />
      </button>

      {open && (
        <div className="ops-menu" role="menu" aria-label="Menu tài khoản">
          <button
            type="button"
            role="menuitem"
            className="ops-menu__item ops-menu__item--danger"
            disabled={busy}
            onClick={() => void logout()}
          >
            <Icon name="logout" />
            <span>{busy ? 'Đang đăng xuất…' : 'Đăng xuất'}</span>
          </button>
        </div>
      )}
    </div>
  );
}
