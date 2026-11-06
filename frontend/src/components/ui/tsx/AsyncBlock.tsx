import type { ReactNode } from 'react';
import { useDelayedLoading } from '../../../hooks/useDelayedLoading';
import { GENERIC_ERROR_MESSAGE, TIMEOUT_MESSAGE, mapErrorToState, type ApiErrorLike } from '../../../lib/errors';
import EmptyState, { type EmptyStateProps } from './EmptyState';
import ErrorState from './ErrorState';
import Skeleton from './Skeleton';

export interface AsyncBlockProps {
  isLoading: boolean;
  error?: ApiErrorLike | null;
  /** Chỉ có nghĩa khi đã tải xong và không lỗi. */
  isEmpty?: boolean;
  onRetry?: () => void;
  empty?: EmptyStateProps;
  /** Skeleton tùy biến cho khối; mặc định 3 dòng. */
  skeleton?: ReactNode;
  children: ReactNode;
}

/**
 * Bọc MỘT khối dữ liệu, áp đúng thứ tự:
 *   quá 15 giây -> đang tải (sau 300 ms) -> lỗi -> rỗng -> nội dung.
 * Đảm bảo "Không có dữ liệu" không bao giờ hiện trong lúc đang tải.
 */
function AsyncBlock({ isLoading, error, isEmpty = false, onRetry, empty, skeleton, children }: AsyncBlockProps) {
  const { showLoading, timedOut } = useDelayedLoading(isLoading);

  if (isLoading && timedOut) {
    return <ErrorState message={TIMEOUT_MESSAGE} onRetry={onRetry} />;
  }
  if (isLoading) {
    // 300 ms đầu: chưa hiện loading, giữ nguyên nội dung hiện có (nếu có).
    return showLoading ? <>{skeleton ?? <Skeleton lines={3} />}</> : <>{children}</>;
  }
  if (error) {
    const s = mapErrorToState(error, 'block');
    // form/dialog/page do nơi gọi xử lý (ô nhập, Toast, route lỗi); khối chỉ hiện lỗi chung.
    const message = s.kind === 'block' || s.kind === 'dialog' ? s.message : GENERIC_ERROR_MESSAGE;
    return <ErrorState message={message} traceId={s.kind === 'block' ? s.traceId : undefined} onRetry={onRetry} />;
  }
  if (isEmpty && empty) return <EmptyState {...empty} />;
  return <>{children}</>;
}

export default AsyncBlock;