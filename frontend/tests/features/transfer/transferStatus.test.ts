import { describe, expect, it } from 'vitest';
import { formatDateTime } from '../../../src/features/transfers/formatDate';
import { stepStates, stepSubtitle } from '../../../src/features/transfers/transferStatus';

describe('stepStates (luồng 3 bước UC-07)', () => {
  it('Nháp: đang ở bước 2 (duyệt xuất)', () =>
    expect(stepStates('DRAFT')).toEqual(['done', 'current', 'todo']));
  it('Đang vận chuyển: đang ở bước 3 (nhận hàng)', () =>
    expect(stepStates('IN_TRANSIT')).toEqual(['done', 'done', 'current']));
  it('Đã nhận: xong cả 3 bước', () =>
    expect(stepStates('RECEIVED')).toEqual(['done', 'done', 'done']));
  it('Đã hủy: dừng ở bước 2', () =>
    expect(stepStates('CANCELLED')).toEqual(['done', 'cancelled', 'todo']));
});

describe('stepSubtitle', () => {
  it('theo Figma', () => {
    expect(stepSubtitle('DRAFT', false)).toBe('Bước 2 · Duyệt xuất tại kho gửi');
    expect(stepSubtitle('IN_TRANSIT', false)).toBe('Bước 3 · Nhận hàng tại kho đích');
    expect(stepSubtitle('RECEIVED', true)).toContain('có chênh lệch');
  });
});

describe('formatDateTime', () => {
  it('giờ Việt Nam dạng dd/MM/yyyy HH:mm', () => {
    expect(formatDateTime('2026-11-07T14:32:00+07:00')).toBe('07/11/2026 14:32');
    expect(formatDateTime('2026-11-07T00:05:00+07:00')).toBe('07/11/2026 00:05');
    expect(formatDateTime('2026-11-07T07:32:00Z')).toBe('07/11/2026 14:32');
  });
});
