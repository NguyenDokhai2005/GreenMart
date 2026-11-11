/** Một điểm doanh thu theo ngày. `date` dạng `YYYY-MM-DD` (giờ Việt Nam), `revenue` là VND số nguyên (Money). */
export interface RevenuePoint {
  date: string;
  revenue: number;
}

/** Một điểm cho biểu đồ nhỏ (sparkline) trên thẻ số. */
export interface SparkPoint {
  date: string;
  value: number;
}

export type SparklineTone = 'primary' | 'info' | 'danger';
