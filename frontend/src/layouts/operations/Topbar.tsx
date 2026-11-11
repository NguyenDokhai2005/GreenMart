import { Icon } from './Icon';
import { BranchSwitcher } from './BranchSwitcher';
import type { CurrentUser } from '../../api/client';
import { ROLE_LABEL, userDisplayName } from './adapters';

const initials = (n: string) =>
  n
    .split(' ')
    .map((w) => w[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

export function Topbar({ user, section }: { user: CurrentUser; section: string }) {
  return (
    <header className="ops-topbar">
      <nav aria-label="Breadcrumb" className="ops-crumb">
        <Icon name="grid" size={16} />
        <span>Vận hành</span>
        <Icon name="breadcrumb" size={14} />
        <b aria-current="page">{section}</b>
      </nav>
      <div className="ops-topbar__right">
        <BranchSwitcher />
        <span className="ops-sep" />
        <button type="button" className="ops-bell" aria-label="Thông báo">
          <Icon name="bell" size={20} />
        </button>
        <button type="button" className="ops-user" aria-label="Tài khoản">
          <span className="ops-avatar">{initials(userDisplayName(user))}</span>
          <span className="ops-user__text">
            <b>{userDisplayName(user)}</b>
            <small>{ROLE_LABEL[user.role]}</small>
          </span>
          <Icon name="chevron" size={14} />
        </button>
      </div>
    </header>
  );
}
