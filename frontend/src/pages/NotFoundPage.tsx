import { SpecialPage } from './SpecialPage';

export interface NotFoundPageProps {
  /** Đích của nút Về trang chủ. Khi dự án có router, truyền thêm onHome để điều hướng không tải lại trang. */
  homeHref?: string;
  onHome?: () => void;
}

/** 404: sai đường dẫn / bản ghi không tồn tại. Hành động: Về trang chủ. */
export default function NotFoundPage({ homeHref = '/', onHome }: NotFoundPageProps) {
  return (
    <SpecialPage
      code="404"
      title="Không tìm thấy trang"
      description="Đường dẫn không đúng hoặc dữ liệu này không còn tồn tại."
      actions={
        <a
          className="gm-state__btn"
          href={homeHref}
          onClick={onHome ? (e) => { e.preventDefault(); onHome(); } : undefined}
        >
          Về trang chủ
        </a>
      }
    />
  );
}