import { Button } from '../components/ui';
import { SpecialPage } from './SpecialPage';

export interface NotFoundPageProps {
  /** Về trang chủ, ví dụ `() => navigate(APP_PATHS.home)`. */
  onHome: () => void;
}

/** 404: sai đường dẫn / bản ghi không tồn tại. Hành động: Về trang chủ. */
export default function NotFoundPage({ onHome }: NotFoundPageProps) {
  return (
    <SpecialPage
      code="404"
      title="Không tìm thấy trang"
      description="Đường dẫn không đúng hoặc dữ liệu này không còn tồn tại."
      actions={<Button onClick={onHome}>Về trang chủ</Button>}
    />
  );
}
