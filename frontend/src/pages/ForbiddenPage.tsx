import { Button } from '../components/ui';
import { SpecialPage } from './SpecialPage';

export interface ForbiddenPageProps {
  onHome: () => void;
  /** Đổi chi nhánh, ví dụ gọi `clearBranch()` của `useActiveBranch()` rồi điều hướng tới trang chọn chi nhánh. */
  onSwitchBranch?: () => void;
  message?: string;
}

/** 403: FORBIDDEN / BRANCH_FORBIDDEN khi mở cả trang. Hành động: Về trang chủ, Đổi chi nhánh. */
export default function ForbiddenPage({ onHome, onSwitchBranch, message }: ForbiddenPageProps) {
  return (
    <SpecialPage
      code="403"
      title="Bạn không có quyền truy cập"
      description={message ?? 'Tài khoản hoặc chi nhánh hiện tại không được phép xem trang này.'}
      actions={
        <>
          <Button onClick={onHome}>Về trang chủ</Button>
          {onSwitchBranch && (
            <Button variant="secondary" onClick={onSwitchBranch}>
              Đổi chi nhánh
            </Button>
          )}
        </>
      }
    />
  );
}
