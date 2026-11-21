import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { formatDayFull, formatDayLabel } from './format';
import { prefersReducedMotion } from './prefersReducedMotion';
import './charts.css';

export interface ForecastChartPoint {
  date: string;
  predictedQty: number;
  actualQty: number | null;
}

function formatQuantity(value: number): string {
  return new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 }).format(value);
}

function ForecastTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean;
  payload?: readonly { dataKey?: string; value?: number | null }[];
  label?: string | number;
}) {
  if (!active || !payload?.length || typeof label !== 'string') return null;
  return (
    <div className="gm-chart-tooltip">
      <strong>{formatDayFull(label)}</strong>
      {payload.map((point) => (
        <p key={point.dataKey}>
          <span>{point.dataKey === 'actualQty' ? 'Thực tế' : 'Dự báo'}</span>
          <b>{point.value == null ? '—' : formatQuantity(point.value)}</b>
        </p>
      ))}
    </div>
  );
}

export default function ForecastAreaChart({
  data,
  horizon,
}: {
  data: readonly ForecastChartPoint[];
  horizon: 7 | 30;
}) {
  return (
    <div className="gm-chart" role="group" aria-label={`Biểu đồ dự báo nhu cầu ${horizon} ngày`}>
      <ResponsiveContainer width="100%" height={280} initialDimension={{ width: 640, height: 280 }}>
        <AreaChart data={[...data]} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
          <defs>
            <linearGradient id="gm-forecast-area" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="var(--gm-color-primary)" stopOpacity={0.2} />
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
            tick={{ fontSize: 12, fill: 'var(--gm-color-text-secondary)' }}
          />
          <YAxis
            tickFormatter={(value: number) => formatQuantity(value)}
            tickLine={false}
            axisLine={false}
            width={48}
            tick={{ fontSize: 12, fill: 'var(--gm-color-text-secondary)' }}
          />
          <Tooltip content={<ForecastTooltip />} />
          <Legend formatter={(value: string) => (value === 'actualQty' ? 'Thực tế' : 'Dự báo')} />
          <Area
            type="monotone"
            dataKey="predictedQty"
            name="Dự báo"
            stroke="var(--gm-color-primary)"
            strokeWidth={2}
            fill="url(#gm-forecast-area)"
            dot={false}
            isAnimationActive={!prefersReducedMotion()}
          />
          <Line
            type="monotone"
            dataKey="actualQty"
            name="Thực tế"
            stroke="#4e7df2"
            strokeWidth={2}
            dot={false}
            connectNulls={false}
            isAnimationActive={!prefersReducedMotion()}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
