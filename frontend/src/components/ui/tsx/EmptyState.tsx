import type { ReactNode } from 'react';
import Button from './Button';
import '../css/states.css';

export interface EmptyStateProps {
  /** Câu giải thích, ví dụ "Chưa có phiếu nhập nào". */
  message: string;
  description?: string;
  /** Nút hành động — CHỈ truyền khi người dùng có quyền (ví dụ "Tạo phiếu nhập"). */
  actionLabel?: string;
  onAction?: () => void;
  /** Rỗng do bộ lọc: "Không có kết quả phù hợp" + nút Xóa bộ lọc. */
  filtered?: boolean;
  onClearFilters?: () => void;
  icon?: ReactNode;
}

/**
 * Chỉ render SAU KHI API đã trả danh sách rỗng.
 * Không dùng trong lúc đang tải — dùng <AsyncBlock> để đảm bảo thứ tự.
 */
function EmptyState({
  message,
  description,
  actionLabel,
  onAction,
  filtered = false,
  onClearFilters,
  icon,
}: EmptyStateProps) {
  return (
    <div className="gm-empty-state">
      {icon}
      <p className="gm-state__title">{filtered ? 'Không có kết quả phù hợp' : message}</p>
      {!filtered && description && <p className="gm-state__desc">{description}</p>}
      {filtered && onClearFilters && (
        <Button variant="secondary" onClick={onClearFilters}>
          Xóa bộ lọc
        </Button>
      )}
      {!filtered && actionLabel && onAction && <Button onClick={onAction}>{actionLabel}</Button>}
    </div>
  );
}

export default EmptyState;
