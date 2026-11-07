import Button from './Button';
import '../css/states.css';

export interface ErrorStateProps {
  /** `message` của lỗi API. */
  message: string;
  /** Gọi lại ĐÚNG API của khối này. */
  onRetry?: () => void;
  traceId?: string;
}

/** Lỗi trong một khối (một API lỗi, trang vẫn dùng được). */
function ErrorState({ message, onRetry, traceId }: ErrorStateProps) {
  return (
    <div className="gm-error-state" role="alert">
      <p className="gm-state__title">{message}</p>
      {traceId && <span className="gm-error-state__trace">Mã lỗi: {traceId}</span>}
      {onRetry && <Button onClick={onRetry}>Thử lại</Button>}
    </div>
  );
}

export default ErrorState;
