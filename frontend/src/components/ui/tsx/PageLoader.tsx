import '../css/states.css';

export interface PageLoaderProps {
  label?: string;
}

/**
 * Loading cho vùng nội dung khi chuyển trang trong cùng khu (POS / Operations).
 * KHÔNG phủ toàn màn hình: header và menu trái vẫn hiển thị.
 */
function PageLoader({ label = 'Đang tải…' }: PageLoaderProps) {
  return (
    <div className="gm-page-loader" role="status" aria-live="polite" aria-busy="true">
      <span className="gm-spinner" aria-hidden="true" />
      <p className="gm-state__desc">{label}</p>
    </div>
  );
}

export default PageLoader;
