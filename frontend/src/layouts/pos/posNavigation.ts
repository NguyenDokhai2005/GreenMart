import type { Role } from '../../api/client';
import { APP_PATHS, POS_ROLES } from '../../features/auth/routePolicy';

// Temporary #71 mapping authorized by the user; still subject to Lead review.
export const POS_SHORTCUTS = {
  modifiers: { ctrl: true, alt: true, shift: false, meta: false },
  label: 'Ctrl + Alt',
  macLabel: 'Control + Option',
  ariaPrefix: 'Control+Alt',
  rangeLabel: '1–5',
} as const;

export const POS_NAVIGATION = [
  { segment: '', label: 'Bán hàng', title: 'Bán hàng tại quầy', digit: '1', code: 'Digit1' },
  { segment: 'receipts', label: 'Hóa đơn', title: 'Hóa đơn', digit: '2', code: 'Digit2' },
  { segment: 'returns', label: 'Trả hàng', title: 'Trả hàng', digit: '3', code: 'Digit3' },
  {
    segment: 'customers',
    label: 'Tra cứu khách',
    title: 'Tra cứu khách',
    digit: '4',
    code: 'Digit4',
  },
  {
    segment: 'products',
    label: 'Sản phẩm (chỉ xem)',
    title: 'Tra cứu sản phẩm',
    digit: '5',
    code: 'Digit5',
  },
].map((item) => ({
  ...item,
  path: item.segment ? `${APP_PATHS.pos}/${item.segment}` : APP_PATHS.pos,
  roles: POS_ROLES,
}));

export type PosNavigationItem = (typeof POS_NAVIGATION)[number];

export function getPosNavigation(role: Role): PosNavigationItem[] {
  return POS_NAVIGATION.filter((item) => item.roles.includes(role));
}
