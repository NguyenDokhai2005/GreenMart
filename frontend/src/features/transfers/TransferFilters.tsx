import Input from '../../components/ui/tsx/Input';
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
  return (
    <section className="ck-filters" aria-label="Bộ lọc phiếu chuyển kho">
      <div className="ck-search">
        <Input
          type="search"
          aria-label="Tìm phiếu chuyển kho"
          value={searchText}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Mã phiếu"
        />
        <svg
          className="ck-search__icon"
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

      <select
        className="ck-select"
        aria-label="Trạng thái"
        value={status ?? ''}
        onChange={(e) => onStatusChange(TRANSFER_FILTER_STATUSES.find((s) => s === e.target.value))}
      >
        <option value="">Trạng thái</option>
        {TRANSFER_FILTER_STATUSES.map((s) => (
          <option key={s} value={s}>
            {TRANSFER_STATUS_LABEL[s]}
          </option>
        ))}
      </select>

      <div className="ck-dates" role="group" aria-label="Khoảng ngày tạo">
        <input
          type="date"
          aria-label="Từ ngày"
          value={from ?? ''}
          max={to}
          onChange={(e) => onDatesChange(e.target.value || undefined, to)}
        />
        <span aria-hidden="true">–</span>
        <input
          type="date"
          aria-label="Đến ngày"
          value={to ?? ''}
          min={from}
          onChange={(e) => onDatesChange(from, e.target.value || undefined)}
        />
      </div>
    </section>
  );
}
