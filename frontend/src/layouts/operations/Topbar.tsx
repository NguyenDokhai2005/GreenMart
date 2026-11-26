import type { CurrentUser } from '../../api/client';
import { BranchSwitcher } from './BranchSwitcher';
import { Icon } from './Icon';
import { UserMenu } from './UserMenu';

interface TopbarProps {
  user: CurrentUser;
  section: string;
  onMenu: () => void;
}

export function Topbar({ user, section, onMenu }: TopbarProps) {
  return (
    <header className="ops-topbar">
      <button type="button" className="ops-mobile-menu" aria-label="Mở menu" onClick={onMenu}>
        <Icon name="grid" size={20} />
      </button>
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
        <UserMenu user={user} />
      </div>
    </header>
  );
}
