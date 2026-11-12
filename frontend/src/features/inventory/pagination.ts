export type PageItem = number | 'ellipsis';

/**
 * Các mục hiển thị ở thanh phân trang, số trang từ 1.
 * Ví dụ tổng 8 trang: trang 1 → 1 2 3 … 8; trang 4 → 1 2 3 4 5 … 8; trang 8 → 1 … 6 7 8.
 */
export function getPageItems(current: number, total: number): PageItem[] {
  if (total <= 1) return [];
  if (total <= 5) return Array.from({ length: total }, (_, i) => i + 1);

  let start = current - 1;
  let end = current + 1;
  if (current <= 2) [start, end] = [1, 3];
  if (current >= total - 1) [start, end] = [total - 2, total];

  const pages = new Set<number>([1, total]);
  for (let p = Math.max(1, start); p <= Math.min(total, end); p++) pages.add(p);

  const sorted = [...pages].sort((a, b) => a - b);
  const items: PageItem[] = [];
  sorted.forEach((p, i) => {
    const prev = sorted[i - 1];
    if (prev !== undefined && p - prev === 2)
      items.push(prev + 1); // khoảng hở 1 trang: hiện luôn số
    else if (prev !== undefined && p - prev > 2) items.push('ellipsis');
    items.push(p);
  });
  return items;
}
