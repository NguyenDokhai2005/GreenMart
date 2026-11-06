import { SpecialPage } from './SpecialPage';

export interface ForbiddenPageProps {
  /** Mở bộ chọn chi nhánh hiện có của layout. */
  onSwitchBranch?: () => void;
  message?: string;
  homeHref?: string;
  onHome?: () => void;
}

/** 403: FORBIDDEN / BRANCH_FORBIDDEN khi mở cả trang. Hành động: Về trang chủ, Đổi chi nhánh. */
export default function ForbiddenPage({
  onSwitchBranch,
  message,
  homeHref = '/',
  onHome,
}: ForbiddenPageProps) {
  return (
    <SpecialPage
      code="403"
      title="Bạn không có quyền truy cập"
      description={message ?? 'Tài khoản hoặc chi nhánh hiện tại không được phép xem trang này.'}
      actions={
        <>
          <a
            className="ui-state-btn"
            href={homeHref}
            onClick={
              onHome
                ? (e) => {
                    e.preventDefault();
                    onHome();
                  }
                : undefined
            }
          >
            Về trang chủ
          </a>
          {onSwitchBranch && (
            <button
              type="button"
              className="ui-state-btn ui-state-btn--secondary"
              onClick={onSwitchBranch}
            >
              Đổi chi nhánh
            </button>
          )}
        </>
      }
    />
  );
}
