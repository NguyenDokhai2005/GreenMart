import { useId } from 'react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import './charts.css';
import { formatDayLabel, formatVnd, formatVndCompact } from './format';
import { prefersReducedMotion } from './prefersReducedMotion';
import { RevenueTooltipContent } from './RevenueTooltip';
import type { RevenuePoint } from './types';

export interface RevenueAreaChartProps {
  data: readonly RevenuePoint[];
  height?: number;
  /** Mô tả cho trình đọc màn hình; mặc định tự tóm tắt tổng doanh thu. */
  ariaLabel?: string;
}

const AXIS_TICK = { fontSize: 12, fill: 'var(--gm-color-text-secondary)' } as const;

/** Biểu đồ vùng doanh thu theo ngày (DASH-01): đường cong mượt, nền gradient, tooltip ngày và số tiền VND. */
export default function RevenueAreaChart({ data, height = 320, ariaLabel }: RevenueAreaChartProps) {
  const gradientId = `gm-rev-${useId().replace(/\W/g, '')}`;
  const total = data.reduce((sum, p) => sum + p.revenue, 0);
  const label = ariaLabel ?? `Biểu đồ doanh thu ${data.length} ngày, tổng ${formatVnd(total)}`;

  return (
    <div className="gm-chart" role="group" aria-label={label}>
      <ResponsiveContainer width="100%" height={height} initialDimension={{ width: 640, height }}>
        <AreaChart data={[...data]} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="var(--gm-color-primary)" stopOpacity={0.28} />
              <stop offset="100%" stopColor="var(--gm-color-primary)" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid vertical={false} strokeDasharray="3 3" stroke="var(--gm-chart-grid)" />
          <XAxis
            dataKey="date"
            tickFormatter={formatDayLabel}
            interval={data.length > 10 ? 2 : 0}
            tickLine={false}
            axisLine={false}
            tick={AXIS_TICK}
          />
          <YAxis
            tickFormatter={formatVndCompact}
            tickLine={false}
            axisLine={false}
            width={48}
            tick={AXIS_TICK}
          />
          <Tooltip
            content={<RevenueTooltipContent />}
            cursor={{ stroke: 'var(--gm-chart-grid)', strokeDasharray: '3 3' }}
          />
          <Area
            type="monotone"
            dataKey="revenue"
            stroke="var(--gm-color-primary)"
            strokeWidth={2.5}
            fill={`url(#${gradientId})`}
            dot={false}
            activeDot={{ r: 5 }}
            isAnimationActive={!prefersReducedMotion()}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
