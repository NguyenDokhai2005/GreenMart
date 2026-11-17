const VND = new Intl.NumberFormat('vi-VN');

/** 15000000 → "15.000.000 đ" */
export function formatVnd(amount: number): string {
  return `${VND.format(Math.round(amount))} đ`;
}

/** Nhãn trục tung: 60000000 → "60M", 1500000 → "1,5M", 2500 → "2,5K". */
export function formatVndCompact(amount: number): string {
  const abs = Math.abs(amount);
  const trim = (n: number) => String(Math.round(n * 10) / 10).replace('.', ',');
  if (abs >= 1_000_000_000) return `${trim(amount / 1_000_000_000)}B`;
  if (abs >= 1_000_000) return `${trim(amount / 1_000_000)}M`;
  if (abs >= 1_000) return `${trim(amount / 1_000)}K`;
  return String(amount);
}

/** "2026-10-18" → "18/10" (cắt chuỗi, không qua Date nên không lệch múi giờ). */
export function formatDayLabel(isoDate: string): string {
  const [, month, day] = isoDate.split('-');
  return month && day ? `${day}/${month}` : isoDate;
}

/** "2026-10-18" → "18/10/2026" */
export function formatDayFull(isoDate: string): string {
  const [year, month, day] = isoDate.split('-');
  return year && month && day ? `${day}/${month}/${year}` : isoDate;
}
