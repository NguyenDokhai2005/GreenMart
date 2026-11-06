import '../css/states.css';

export interface ErrorStateProps {
  /** `message` của ErrorResponse. */
  message: string;
  /** Gọi lại ĐÚNG API của khối này. */
  onRetry?: () => void;
  traceId?: string;
}

/** Lỗi trong một khối (một API lỗi, trang vẫn dùng được). */
function ErrorState({ message, onRetry, traceId }: ErrorStateProps) {
  return (
    <div className="ui-error-state" role="alert">
      <p className="ui-state-title">{message}</p>
      {traceId && <span className="ui-error-state__trace">Mã lỗi: {traceId}</span>}
      {onRetry && (
        <button type="button" className="ui-state-btn" onClick={onRetry}>
          Thử lại
        </button>
      )}
    </div>
  );
}

export default ErrorState;