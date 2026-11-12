import Input from '../../components/ui/tsx/Input';
import { STATUS_FILTERS } from './inventoryQuery';
import type { StatusFilter } from './inventoryQuery';

interface InventoryFiltersProps {
  searchText: string;
  onSearchChange: (text: string) => void;
  status: StatusFilter;
  onStatusChange: (status: StatusFilter) => void;
}

export default function InventoryFilters({
  searchText,
  onSearchChange,
  status,
  onStatusChange,
}: InventoryFiltersProps) {
  return (
    <section className="inv-card inv-filters" aria-label="Bộ lọc tồn kho">
      <div className="inv-search">
        <Input
          label="Tìm sản phẩm"
          type="search"
          value={searchText}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Nhập tên sản phẩm, SKU hoặc barcode..."
        />
        <svg
          className="inv-search__icon"
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          aria-hidden="true"
        >
          <path d="M11 18a7 7 0 100-14 7 7 0 000 14zM21 21l-5-5" />
        </svg>
      </div>

      <div>
        <span className="inv-filters__label" id="inv-status-label">
          Cảnh báo tồn
        </span>
        <div className="inv-chips" role="group" aria-labelledby="inv-status-label">
          {STATUS_FILTERS.map((f) => (
            <button
              key={f.label}
              type="button"
              className="inv-chip"
              aria-pressed={status === f.value}
              onClick={() => onStatusChange(f.value)}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}
