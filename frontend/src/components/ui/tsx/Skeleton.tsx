import type { CSSProperties } from 'react';
import '../css/states.css';

export interface SkeletonProps {
  width?: number | string;
  height?: number | string;
  /** Số dòng; >1 thì dòng cuối ngắn hơn. */
  lines?: number;
  className?: string;
  style?: CSSProperties;
}

/** Placeholder cho MỘT khối đang tải. Phần còn lại của trang vẫn dùng được. */
function Skeleton({ width = '100%', height = 16, lines = 1, className = '', style }: SkeletonProps) {
  const bars = Array.from({ length: Math.max(1, lines) });
  return (
    <div role="status" aria-busy="true" aria-label="Đang tải" className={className} style={{ display: 'grid', gap: 8, ...style }}>
      {bars.map((_, i) => (
        <span key={i} className="ui-skeleton" style={{ width: lines > 1 && i === bars.length - 1 ? '60%' : width, height }} />
      ))}
    </div>
  );
}

export default Skeleton;