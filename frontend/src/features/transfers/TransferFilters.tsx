import { Input, Select } from '../../components/ui';
import { TRANSFER_FILTER_STATUSES, TRANSFER_STATUS_LABEL } from './transferStatus';
import type { TransferFilterStatus } from './transferStatus';

interface TransferFiltersProps {
  searchText: string;
  onSearchChange: (text: string) => void;
  status: TransferFilterStatus | undefined;
  onStatusChange: (status: TransferFilterStatus | undefined) => void;
  from: string | undefined;
  to: string | undefined;
  onDatesChange: (from: string | undefined, to: string | undefined) => void;
}

export default function TransferFilters({
  searchText,
  onSearchChange,
  status,
  onStatusChange,
  from,
  to,
  onDatesChange,
}: TransferFiltersProps) {
  const statusOptions = [
    { value: '', label: 'Tất cả trạng thái' },
    ...TRANSFER_FILTER_STATUSES.map((value) => ({
      value,
      label: TRANSFER_STATUS_LABEL[value],
    })),
  ];

  return (
    <section className="ck-filters" aria-label="Bộ lọc phiếu chuyển kho">
      <div className="ck-filter-search">
        <Input
          label="Tìm phiếu"
          type="search"
          aria-label="Tìm phiếu chuyển kho"
          value={searchText}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Mã phiếu"
        />
      </div>
      <Select
        label="Trạng thái"
        aria-label="Trạng thái"
        value={status ?? ''}
        placeholder="Tất cả trạng thái"
        options={statusOptions}
        onChange={(e) =>
          onStatusChange(TRANSFER_FILTER_STATUSES.find((candidate) => candidate === e.target.value))
        }
      />
      <label className="ck-date-field">
        <span>Từ ngày</span>
        <input
          type="date"
          aria-label="Từ ngày"
          value={from ?? ''}
          max={to}
          onChange={(e) => onDatesChange(e.target.value || undefined, to)}
        />
      </label>
      <label className="ck-date-field">
        <span>Đến ngày</span>
        <input
          type="date"
          aria-label="Đến ngày"
          value={to ?? ''}
          min={from}
          onChange={(e) => onDatesChange(from, e.target.value || undefined)}
        />
      </label>
    </section>
  );
}
