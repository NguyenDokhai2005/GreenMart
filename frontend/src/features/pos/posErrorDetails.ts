import type { Quote } from '../../api/client';
import type { CartLine } from './posCart';

export interface StockShortage {
  productId: number;
  requested: number;
  available: number;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonnegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isPositiveInteger(value: unknown): value is number {
  return isNonnegativeInteger(value) && value > 0;
}

export function readStockShortages(details: unknown, lines: CartLine[]): StockShortage[] {
  if (!isRecord(details) || !Array.isArray(details.lines)) return [];
  const cartIds = new Set(lines.map((line) => line.product.id));
  const shortages = new Map<number, StockShortage>();
  for (const item of details.lines) {
    if (
      isRecord(item) &&
      isPositiveInteger(item.productId) &&
      isPositiveInteger(item.requested) &&
      isNonnegativeInteger(item.available) &&
      cartIds.has(item.productId)
    ) {
      shortages.set(item.productId, {
        productId: item.productId,
        requested: item.requested,
        available: item.available,
      });
    }
  }
  return [...shortages.values()];
}

function isPromotion(value: unknown): boolean {
  return (
    value == null ||
    (isRecord(value) &&
      isPositiveInteger(value.id) &&
      typeof value.code === 'string' &&
      (value.name == null || typeof value.name === 'string') &&
      isNonnegativeInteger(value.discount))
  );
}

/** ErrorResponse.details is unknown; accept only a complete quote for the current cart. */
export function readChangedQuote(
  details: unknown,
  branchId: number,
  lines: CartLine[],
): Quote | null {
  if (!isRecord(details) || !isRecord(details.quote)) return null;
  const quote = details.quote;
  if (
    quote.branchId !== branchId ||
    !Array.isArray(quote.lines) ||
    quote.lines.length !== lines.length ||
    !isNonnegativeInteger(quote.subtotal) ||
    !isNonnegativeInteger(quote.itemDiscount) ||
    !isNonnegativeInteger(quote.orderDiscount) ||
    !isNonnegativeInteger(quote.manualDiscount) ||
    !isNonnegativeInteger(quote.discount) ||
    !isNonnegativeInteger(quote.total) ||
    typeof quote.canComplete !== 'boolean' ||
    !isPromotion(quote.orderPromotion)
  ) {
    return null;
  }
  const cartByProductId = new Map(lines.map((line) => [line.product.id, line.quantity]));
  const quotedIds = new Set<number>();
  for (const item of quote.lines) {
    if (
      !isRecord(item) ||
      !isPositiveInteger(item.productId) ||
      quotedIds.has(item.productId) ||
      cartByProductId.get(item.productId) !== item.quantity ||
      typeof item.sku !== 'string' ||
      typeof item.name !== 'string' ||
      typeof item.unit !== 'string' ||
      !isNonnegativeInteger(item.unitPrice) ||
      !isNonnegativeInteger(item.discount) ||
      !isNonnegativeInteger(item.lineTotal) ||
      !isNonnegativeInteger(item.available) ||
      typeof item.sufficient !== 'boolean' ||
      !isPromotion(item.promotion)
    ) {
      return null;
    }
    quotedIds.add(item.productId);
  }
  return quote as Quote;
}
