import type { ReactNode } from 'react';
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
    <div className="ui-empty-state">
      {icon}
      <p className="ui-state-title">{filtered ? 'Không có kết quả phù hợp' : message}</p>
      {!filtered && description && <p className="ui-state-desc">{description}</p>}
      {filtered && onClearFilters && (
        <button
          type="button"
          className="ui-state-btn ui-state-btn--secondary"
          onClick={onClearFilters}
        >
          Xóa bộ lọc
        </button>
      )}
      {!filtered && actionLabel && onAction && (
        <button type="button" className="ui-state-btn" onClick={onAction}>
          {actionLabel}
        </button>
      )}
    </div>
  );
}

export default EmptyState;
