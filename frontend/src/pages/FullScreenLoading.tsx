import '../components/ui/css/states.css';

/** Chỉ dùng cho 5 trường hợp được phép (xem docs/ui-states.md). */
export type FullScreenReason = 'session-check' | 'after-login' | 'switch-area' | 'switch-branch' | 'logout';

const LABELS: Record<FullScreenReason, string> = {
  'session-check': 'Đang kiểm tra phiên đăng nhập…',
  'after-login': 'Đang tải thông tin tài khoản và chi nhánh…',
  'switch-area': 'Đang chuyển khu làm việc…',
  'switch-branch': 'Đang đổi chi nhánh…',
  logout: 'Đang đăng xuất…',
};

export interface FullScreenLoadingProps {
  reason: FullScreenReason;
}

export default function FullScreenLoading({ reason }: FullScreenLoadingProps) {
  return (
    <div className="gm-fullscreen" role="status" aria-live="polite" aria-busy="true">
      <div className="gm-page-loader">
        <span className="gm-spinner" aria-hidden="true" />
        <p className="gm-state__desc">{LABELS[reason]}</p>
      </div>
    </div>
  );
}