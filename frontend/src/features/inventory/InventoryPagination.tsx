import { getPageItems } from './pagination';

interface PaginationProps {
  /** Trang hiện tại, từ 1. */
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}

const Chevron = ({ dir }: { dir: 'left' | 'right' }) => (
  <svg
    width="16"
    height="16"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d={dir === 'left' ? 'M15 6l-6 6 6 6' : 'M9 6l6 6-6 6'} />
  </svg>
);

export default function Pagination({ page, totalPages, onChange }: PaginationProps) {
  const items = getPageItems(page, totalPages);
  if (items.length === 0) return null;

  return (
    <nav className="inv-pager" aria-label="Phân trang">
      <button
        type="button"
        aria-label="Trang trước"
        disabled={page <= 1}
        onClick={() => onChange(page - 1)}
      >
        <Chevron dir="left" />
      </button>
      {items.map((item, i) =>
        item === 'ellipsis' ? (
          <span key={`gap-${i}`} className="inv-pager__gap" aria-hidden="true">
            …
          </span>
        ) : (
          <button
            key={item}
            type="button"
            aria-label={`Trang ${item}`}
            aria-current={item === page ? 'page' : undefined}
            onClick={() => onChange(item)}
          >
            {item}
          </button>
        ),
      )}
      <button
        type="button"
        aria-label="Trang sau"
        disabled={page >= totalPages}
        onClick={() => onChange(page + 1)}
      >
        <Chevron dir="right" />
      </button>
    </nav>
  );
}
