import { useId } from 'react';
import { Area, AreaChart, ResponsiveContainer, YAxis } from 'recharts';
import './charts.css';
import { prefersReducedMotion } from './prefersReducedMotion';
import type { SparklineTone, SparkPoint } from './types';

const TONE_COLOR: Record<SparklineTone, string> = {
  primary: 'var(--gm-color-primary)',
  info: 'var(--gm-color-info)',
  danger: 'var(--gm-color-danger)',
};

export interface SparklineProps {
  data: readonly SparkPoint[];
  tone?: SparklineTone;
  height?: number;
  ariaLabel?: string;
}

/** Biểu đồ nhỏ không trục cho thẻ số (Tổng doanh thu, Tổng đơn hàng, Đơn hủy...). */
export default function Sparkline({
  data,
  tone = 'primary',
  height = 56,
  ariaLabel,
}: SparklineProps) {
  const gradientId = `gm-spark-${useId().replace(/\W/g, '')}`;
  const color = TONE_COLOR[tone];

  return (
    <div className="gm-chart" role="img" aria-label={ariaLabel ?? 'Xu hướng'}>
      <ResponsiveContainer width="100%" height={height} initialDimension={{ width: 200, height }}>
        <AreaChart data={[...data]} margin={{ top: 4, right: 0, left: 0, bottom: 0 }}>
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={color} stopOpacity={0.25} />
              <stop offset="100%" stopColor={color} stopOpacity={0} />
            </linearGradient>
          </defs>
          <YAxis hide domain={['dataMin', 'dataMax']} />
          <Area
            type="monotone"
            dataKey="value"
            stroke={color}
            strokeWidth={2}
            fill={`url(#${gradientId})`}
            dot={false}
            isAnimationActive={!prefersReducedMotion()}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
