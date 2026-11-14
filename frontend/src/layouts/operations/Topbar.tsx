import type { CurrentUser } from '../../api/client';
import { BranchSwitcher } from './BranchSwitcher';
import { Icon } from './Icon';
import { UserMenu } from './UserMenu';

export function Topbar({
  user,
  section,
  tail,
}: {
  user: CurrentUser;
  section: string;
  tail?: string;
}) {
  return (
    <header className="ops-topbar">
      <nav aria-label="Breadcrumb" className="ops-crumb">
        <Icon name="grid" size={16} />
        <span>Vận hành</span>
        <Icon name="breadcrumb" size={14} />
        {tail ? (
          <>
            <span className="ops-crumb__path">{section} /</span>
            <b aria-current="page">{tail}</b>
          </>
        ) : (
          <b aria-current="page">{section}</b>
        )}
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
