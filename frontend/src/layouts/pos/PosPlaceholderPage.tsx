import { EmptyState } from '../../components/ui';
import type { PosNavigationItem } from './posNavigation';

export interface PosPageProps {
  item: PosNavigationItem;
}

export default function PosPlaceholderPage({ item }: PosPageProps) {
  return (
    <section className="pos-layout__page" aria-labelledby="pos-page-title">
      <p className="pos-layout__eyebrow">Khu vực POS</p>
      <h1 id="pos-page-title" tabIndex={-1} data-pos-heading>
        {item.title}
      </h1>
      <div className="pos-layout__placeholder">
        <EmptyState
          message="Màn hình đang được chuẩn bị"
          description="Bạn có thể sử dụng menu bên trái để chuyển sang mục khác."
        />
      </div>
    </section>
  );
}
