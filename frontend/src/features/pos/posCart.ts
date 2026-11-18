import type { Product, Quote } from '../../api/client';

export interface CartLine {
  product: Product;
  quantity: number;
  available: number;
}

export type CartAction =
  | { type: 'add'; product: Product; available: number }
  | { type: 'quantity'; productId: number; quantity: number }
  | { type: 'remove'; productId: number }
  | { type: 'clear' };

export function cartReducer(lines: CartLine[], action: CartAction): CartLine[] {
  if (action.type === 'clear') return [];
  if (action.type === 'remove') return lines.filter((line) => line.product.id !== action.productId);
  if (action.type === 'quantity') {
    if (!Number.isSafeInteger(action.quantity) || action.quantity < 1 || action.quantity > 9999)
      return lines;
    return lines.map((line) =>
      line.product.id === action.productId ? { ...line, quantity: action.quantity } : line,
    );
  }
  const existing = lines.find((line) => line.product.id === action.product.id);
  if (existing) {
    return lines.map((line) =>
      line.product.id === action.product.id
        ? {
            ...line,
            quantity: Math.min(line.quantity + 1, 9999),
            available: action.available,
          }
        : line,
    );
  }
  if (lines.length >= 100) return lines;
  return [...lines, { product: action.product, quantity: 1, available: action.available }];
}

export function cartKey(branchId: number, lines: CartLine[]): string {
  return JSON.stringify([branchId, lines.map(({ product, quantity }) => [product.id, quantity])]);
}

export function stockForLine(
  line: CartLine,
  quote: Quote | null,
): { available: number; sufficient: boolean } {
  const quoted = quote?.lines.find((item) => item.productId === line.product.id);
  const available = quoted?.available ?? line.available;
  return { available, sufficient: quoted?.sufficient ?? line.quantity <= available };
}
