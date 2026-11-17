import { formatDayFull, formatVnd } from './format';

interface RevenueTooltipProps {
  active?: boolean;
  payload?: ReadonlyArray<{ value?: unknown }>;
  label?: unknown;
  /** Tên chuỗi hiển thị cạnh chấm màu. */
  seriesName?: string;
}

/** Nội dung tooltip của biểu đồ doanh thu: ngày, số tiền VND, tên chuỗi. Recharts tự truyền active, payload, label. */
export function RevenueTooltipContent({
  active,
  payload,
  label,
  seriesName = 'Doanh thu',
}: RevenueTooltipProps) {
  const value = payload?.[0]?.value;
  if (!active || typeof value !== 'number') return null;

  return (
    <div className="gm-chart-tooltip" role="status">
      <span className="gm-chart-tooltip__label">
        {typeof label === 'string' ? formatDayFull(label) : ''}
      </span>
      <strong className="gm-chart-tooltip__value">{formatVnd(value)}</strong>
      <span className="gm-chart-tooltip__series">
        <i className="gm-chart-tooltip__dot" aria-hidden="true" />
        {seriesName}
      </span>
    </div>
  );
}
