import { useMemo, useState } from 'react';
import { RevenueAreaChart, Sparkline, formatVnd } from '../../components/charts';
import type { SparklineTone, SparkPoint } from '../../components/charts';
import { REVENUE_30_DAYS, lastDays } from './mock/revenue';
import './RevenueChartTrial.css';

const RANGES = [30, 7] as const;

const toSeries = (map: (revenue: number, i: number) => number): SparkPoint[] =>
  REVENUE_30_DAYS.map((p, i) => ({ date: p.date, value: map(p.revenue, i) }));

const CARDS: {
  title: string;
  tone: SparklineTone;
  series: SparkPoint[];
  format: (n: number) => string;
}[] = [
  { title: 'Tổng doanh thu', tone: 'primary', series: toSeries((r) => r), format: formatVnd },
  {
    title: 'Tổng đơn hàng',
    tone: 'info',
    series: toSeries((r) => Math.round(r / 58_000)),
    format: (n) => `${n} đơn`,
  },
  {
    title: 'Đơn hủy',
    tone: 'danger',
    series: toSeries((_, i) => 8 + Math.round(Math.abs(Math.sin(i * 1.3)) * 14)),
    format: (n) => `${n} đơn`,
  },
];

/** Biểu đồ thử với dữ liệu mock (#89). Thay bằng dữ liệu API ở các issue dashboard sau. */
export default function RevenueChartTrial() {
  const [range, setRange] = useState<(typeof RANGES)[number]>(30);
  const data = useMemo(() => lastDays(REVENUE_30_DAYS, range), [range]);

  return (
    <div className="gm-trial">
      <div className="gm-trial__cards">
        {CARDS.map((c) => (
          <article key={c.title} className="gm-trial__card">
            <h3>{c.title}</h3>
            <strong>{c.format(c.series.reduce((s, p) => s + p.value, 0))}</strong>
            <Sparkline
              data={c.series}
              tone={c.tone}
              ariaLabel={`Xu hướng ${c.title.toLowerCase()} 30 ngày`}
            />
          </article>
        ))}
      </div>

      <section className="gm-trial__panel" aria-labelledby="gm-trial-title">
        <header>
          <h2 id="gm-trial-title">Xu hướng doanh thu theo thời gian</h2>
          <div className="gm-trial__seg" role="group" aria-label="Khoảng thời gian">
            {RANGES.map((r) => (
              <button key={r} type="button" aria-pressed={range === r} onClick={() => setRange(r)}>
                {r} ngày
              </button>
            ))}
          </div>
        </header>
        <RevenueAreaChart data={data} />
        <p className="gm-trial__note">Dữ liệu mock cố định, chưa nối API (#97, #115).</p>
      </section>
    </div>
  );
}
