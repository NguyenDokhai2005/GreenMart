import { SpecialPage } from './SpecialPage';

export interface ServerErrorPageProps {
  traceId?: string;
  onRetry?: () => void;
}

/** 500: lỗi máy chủ hoặc lỗi giao diện không lường trước. Hành động: Thử lại; hiện traceId. */
export default function ServerErrorPage({
  traceId,
  onRetry = () => window.location.reload(),
}: ServerErrorPageProps) {
  return (
    <SpecialPage
      code="500"
      title="Lỗi hệ thống"
      description="Hệ thống đang gặp sự cố. Vui lòng thử lại; nếu vẫn lỗi, gửi mã lỗi cho bộ phận hỗ trợ."
      traceId={traceId}
      actions={
        <button type="button" className="gm-state__btn" onClick={onRetry}>
          Thử lại
        </button>
      }
    />
  );
}
