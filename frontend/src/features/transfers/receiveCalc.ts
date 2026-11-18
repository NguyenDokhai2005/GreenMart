export interface ReceiveRowInput {
  itemId: number;
  /** Số lượng đã xuất (quantity trên phiếu). */
  quantity: number;
  /** Chuỗi người dùng nhập ở ô "SL thực nhận". */
  receivedText: string;
}

export interface ReceiveRowResult {
  itemId: number;
  /** null khi ô nhập chưa hợp lệ. */
  received: number | null;
  /** received − quantity; luôn ≤ 0 vì không nhận thừa (BR-16). */
  difference: number | null;
  error?: string;
}

export function evaluateRow(row: ReceiveRowInput): ReceiveRowResult {
  const text = row.receivedText.trim();
  if (text === '')
    return {
      itemId: row.itemId,
      received: null,
      difference: null,
      error: 'Nhập số lượng thực nhận',
    };
  const n = Number(text);
  if (!Number.isInteger(n) || n < 0) {
    return { itemId: row.itemId, received: null, difference: null, error: 'Số lượng không hợp lệ' };
  }
  if (n > row.quantity) {
    return {
      itemId: row.itemId,
      received: null,
      difference: null,
      error: `Không nhận quá số lượng xuất (${row.quantity})`,
    };
  }
  return { itemId: row.itemId, received: n, difference: n - row.quantity };
}

export interface ReceiveSummary {
  rows: ReceiveRowResult[];
  hasInvalid: boolean;
  hasDiscrepancy: boolean;
  /** Tổng số lượng thiếu (số dương). */
  shortfallTotal: number;
}

export function summarizeReceive(inputs: ReceiveRowInput[]): ReceiveSummary {
  const rows = inputs.map(evaluateRow);
  const shortfallTotal = rows.reduce(
    (sum, r) => sum + (r.difference !== null && r.difference < 0 ? -r.difference : 0),
    0,
  );
  return {
    rows,
    hasInvalid: rows.some((r) => r.received === null),
    hasDiscrepancy: shortfallTotal > 0,
    shortfallTotal,
  };
}

/** Body cho POST /receive: đủ mọi dòng của phiếu. */
export function toReceivePayload(
  rows: ReceiveRowResult[],
): { itemId: number; receivedQuantity: number }[] {
  return rows.flatMap((r) =>
    r.received === null ? [] : [{ itemId: r.itemId, receivedQuantity: r.received }],
  );
}

/** "−4" hiển thị cho chênh lệch âm, "0" khi đủ, "+n" không xảy ra nhưng vẫn xử lý. */
export function formatDifference(diff: number): string {
  if (diff < 0) return `-${Math.abs(diff)}`;
  if (diff > 0) return `+${diff}`;
  return '0';
}
