import { Button, Select } from '../../components/ui';

export default function CatalogPagination({
  page,
  size,
  total,
  disabled,
  onPage,
  onSize,
}: {
  page: number;
  size: number;
  total: number;
  disabled: boolean;
  onPage: (page: number) => void;
  onSize: (size: number) => void;
}) {
  const hasRows = page * size < total;
  const pages = Math.max(1, Math.ceil(total / size));
  const start = Math.max(0, Math.min(page - 2, pages - 5));
  return (
    <nav className="catalog__pagination" aria-label="Phân trang">
      <span>
        Hiển thị {hasRows ? page * size + 1 : 0}–{hasRows ? Math.min((page + 1) * size, total) : 0}{' '}
        / {total} kết quả
      </span>
      <Button
        variant="secondary"
        disabled={disabled || page === 0}
        onClick={() => onPage(page - 1)}
      >
        ‹ Trước
      </Button>
      <Select
        label="Số dòng mỗi trang"
        value={String(size)}
        disabled={disabled}
        onChange={(e) => onSize(Number(e.target.value))}
        options={[3, 10, 20, 50].map((n) => ({ value: String(n), label: `${n} / trang` }))}
      />
      {Array.from({ length: Math.min(5, pages) }, (_, n) => start + n).map((n) => (
        <Button
          key={n}
          variant={page === n ? 'primary' : 'secondary'}
          aria-current={page === n ? 'page' : undefined}
          disabled={disabled}
          onClick={() => onPage(n)}
        >
          {n + 1}
        </Button>
      ))}
      <Button
        variant="secondary"
        disabled={disabled || page + 1 >= pages}
        onClick={() => onPage(page + 1)}
      >
        Sau ›
      </Button>
    </nav>
  );
}
