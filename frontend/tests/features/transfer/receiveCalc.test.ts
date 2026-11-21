import { describe, expect, it } from 'vitest';
import {
  evaluateRow,
  formatDifference,
  summarizeReceive,
  toReceivePayload,
} from '../../../src/features/transfers/receiveCalc';

describe('evaluateRow', () => {
  it('nhận đủ: chênh lệch 0', () => {
    expect(evaluateRow({ itemId: 1, quantity: 50, receivedText: '50' })).toEqual({
      itemId: 1,
      received: 50,
      difference: 0,
    });
  });
  it('nhận thiếu: chênh lệch âm', () => {
    expect(evaluateRow({ itemId: 2, quantity: 28, receivedText: '24' }).difference).toBe(-4);
  });
  it('không nhận thừa (BR-16)', () => {
    const r = evaluateRow({ itemId: 1, quantity: 50, receivedText: '51' });
    expect(r.received).toBeNull();
    expect(r.error).toBe('Không nhận quá số lượng xuất (50)');
  });
  it('ô trống, số âm, số lẻ không hợp lệ', () => {
    expect(evaluateRow({ itemId: 1, quantity: 5, receivedText: ' ' }).error).toBe(
      'Nhập số lượng thực nhận',
    );
    expect(evaluateRow({ itemId: 1, quantity: 5, receivedText: '-1' }).error).toBe(
      'Số lượng không hợp lệ',
    );
    expect(evaluateRow({ itemId: 1, quantity: 5, receivedText: '2.5' }).error).toBe(
      'Số lượng không hợp lệ',
    );
  });
  it('nhận 0 là hợp lệ (thiếu toàn bộ)', () => {
    expect(evaluateRow({ itemId: 1, quantity: 5, receivedText: '0' }).difference).toBe(-5);
  });
});

describe('summarizeReceive', () => {
  const rows = [
    { itemId: 11, quantity: 50, receivedText: '50' },
    { itemId: 12, quantity: 28, receivedText: '24' },
    { itemId: 13, quantity: 20, receivedText: '20' },
  ];
  it('tổng thiếu và cờ chênh lệch (ví dụ Figma: thiếu 4)', () => {
    const s = summarizeReceive(rows);
    expect(s.shortfallTotal).toBe(4);
    expect(s.hasDiscrepancy).toBe(true);
    expect(s.hasInvalid).toBe(false);
  });
  it('nhận đủ thì không có chênh lệch', () => {
    const s = summarizeReceive(rows.map((r) => ({ ...r, receivedText: String(r.quantity) })));
    expect(s.hasDiscrepancy).toBe(false);
    expect(s.shortfallTotal).toBe(0);
  });
  it('payload đủ mọi dòng theo itemId; dòng sai thì bị đánh dấu không hợp lệ', () => {
    expect(toReceivePayload(summarizeReceive(rows).rows)).toEqual([
      { itemId: 11, receivedQuantity: 50 },
      { itemId: 12, receivedQuantity: 24 },
      { itemId: 13, receivedQuantity: 20 },
    ]);
    expect(summarizeReceive([{ itemId: 1, quantity: 5, receivedText: '9' }]).hasInvalid).toBe(true);
  });
});

describe('formatDifference', () => {
  it('âm có dấu trừ, 0 là "0"', () => {
    expect(formatDifference(-4)).toBe('-4');
    expect(formatDifference(0)).toBe('0');
    expect(formatDifference(3)).toBe('+3');
  });
});
