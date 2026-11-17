import type { SVGProps } from 'react';

const P: Record<string, string> = {
  dashboard: 'M3 3h7v7H3zM14 3h7v4h-7zM14 11h7v10h-7zM3 14h7v7H3z',
  orders: 'M5 4h14l1 4v12H4V8zM4 8h16M9 12a3 3 0 006 0',
  products: 'M12 3l8 4.5v9L12 21l-8-4.5v-9zM4 7.5L12 12l8-4.5M12 12v9',
  customers: 'M9 11a4 4 0 100-8 4 4 0 000 8zM2 21a7 7 0 0114 0M16 4a4 4 0 010 7M18 14a7 7 0 013 7',
  promo: 'M3 12V4h8l10 10-8 8zM7.5 8.5h.01',
  warehouse: 'M3 9l9-5 9 5v12H3zM7 21v-8h10v8M7 17h10',
  forecast: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
  report: 'M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z',
  admin: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z',
  chevron: 'M6 9l6 6 6-6',
  breadcrumb: 'M9 6l6 6-6 6',
  help: 'M12 21a9 9 0 100-18 9 9 0 000 18zM9.5 9.5a2.5 2.5 0 114 2c-.9.6-1.5 1-1.5 2M12 17h.01',
  bell: 'M6 16V11a6 6 0 1112 0v5l2 2H4zM10 21h4',
  lock: 'M6 11h12v10H6zM8 11V7a4 4 0 118 0v4',
  search: 'M11 18a7 7 0 100-14 7 7 0 000 14zM21 21l-5-5',
  check: 'M5 12l5 5 9-10',
  store: 'M4 9l1.5-5h13L20 9M4 9v11h16V9M4 9a3 3 0 006 0 3 3 0 006 0 3 3 0 004 0M10 20v-5h4v5',
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3z',
  logout: 'M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9',
};

export function Icon({
  name,
  size = 18,
  ...rest
}: { name: keyof typeof P | string; size?: number } & SVGProps<SVGSVGElement>) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...rest}
    >
      <path d={P[name] ?? ''} />
    </svg>
  );
}
