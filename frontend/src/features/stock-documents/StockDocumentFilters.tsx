import { Input, Select } from '../../components/ui';
import type { StockDocumentStatus } from './stockDocumentApi';
import {
  parseStockDocumentStatus,
  STOCK_DOCUMENT_STATUS_LABELS,
} from './stockDocumentPresentation';

export interface StockDocumentFiltersValue {
  status?: StockDocumentStatus;
  from?: string;
  to?: string;
}

interface StockDocumentFiltersProps {
  searchText: string;
  value: StockDocumentFiltersValue;
  errors?: Record<string, string>;
  onSearchChange: (value: string) => void;
  onChange: (value: StockDocumentFiltersValue) => void;
}

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: 'Tất cả trạng thái' },
  { value: 'DRAFT', label: STOCK_DOCUMENT_STATUS_LABELS.DRAFT },
  { value: 'APPROVED', label: STOCK_DOCUMENT_STATUS_LABELS.APPROVED },
  { value: 'CANCELLED', label: STOCK_DOCUMENT_STATUS_LABELS.CANCELLED },
];

export default function StockDocumentFilters({
  searchText,
  value,
  errors = {},
  onSearchChange,
  onChange,
}: StockDocumentFiltersProps) {
  return (
    <section className="sd-filters-card" aria-label="Bộ lọc phiếu kho">
      <div className="sd-filter-fields">
        <Input
          label="Tìm phiếu"
          type="search"
          value={searchText}
          error={errors.q}
          placeholder="Mã phiếu hoặc nhà cung cấp..."
          onChange={(event) => onSearchChange(event.target.value)}
        />
        <Select
          label="Trạng thái"
          value={value.status ?? ''}
          error={errors.status}
          options={STATUS_OPTIONS}
          onChange={(event) =>
            onChange({
              ...value,
              status: parseStockDocumentStatus(event.target.value || null),
            })
          }
        />
        <label className="sd-date-field">
          <span>Từ ngày</span>
          <input
            type="date"
            value={value.from ?? ''}
            aria-invalid={errors.from ? true : undefined}
            aria-describedby={errors.from ? 'sd-filter-from-error' : undefined}
            onChange={(event) => onChange({ ...value, from: event.target.value || undefined })}
          />
          {errors.from && (
            <span id="sd-filter-from-error" className="sd-field-error" role="alert">
              {errors.from}
            </span>
          )}
        </label>
        <label className="sd-date-field">
          <span>Đến ngày</span>
          <input
            type="date"
            value={value.to ?? ''}
            aria-invalid={errors.to ? true : undefined}
            aria-describedby={errors.to ? 'sd-filter-to-error' : undefined}
            onChange={(event) => onChange({ ...value, to: event.target.value || undefined })}
          />
          {errors.to && (
            <span id="sd-filter-to-error" className="sd-field-error" role="alert">
              {errors.to}
            </span>
          )}
        </label>
      </div>
    </section>
  );
}
