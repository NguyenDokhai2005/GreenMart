import { describe, expect, it, vi } from 'vitest';
import type { components } from '../../../src/api/schema';
import type { StockDocument } from '../../../src/features/stock-documents/stockDocumentApi';
import {
  getPendingStocktakeCounts,
  saveStocktakeCountBatches,
} from '../../../src/features/stocktake/stocktakeCounts';

const item = {
  id: 1,
  productId: 1,
  sku: 'SKU-1',
  productName: 'Product 1',
  unit: 'unit',
  quantity: 5,
  countedQuantity: null,
  difference: null,
} satisfies StockDocument['items'][number];

describe('stocktake count updates', () => {
  it('selects only changed counts and preserves zero', () => {
    const items = [
      { ...item, productId: 1 },
      { ...item, id: 2, productId: 2 },
    ];

    expect(getPendingStocktakeCounts(items, { 1: '0', 2: '7' }, { 1: '', 2: '7' })).toEqual([
      { productId: 1, countedQuantity: 0 },
    ]);
  });

  it('limits each request to 500 counts and retries only unsaved batches after a failure', async () => {
    const items = Array.from({ length: 1001 }, (_, index) => ({
      ...item,
      id: index + 1,
      productId: index + 1,
      sku: `SKU-${index + 1}`,
      productName: `Product ${index + 1}`,
    }));
    const counts = Object.fromEntries(items.map((entry) => [entry.productId, '0']));
    const savedCounts: Record<number, string> = {};
    let attempts = 0;
    const saveBatch = vi.fn(
      async (documentId: number, body: components['schemas']['StockDocumentUpdateRequest']) => {
        expect(documentId).toBe(10);
        expect(body.items.length).toBeLessThanOrEqual(500);
        attempts += 1;
        if (attempts === 2) throw new Error('Second batch failed');
      },
    );
    const markSaved = (batch: { productId: number; countedQuantity: number }[]) => {
      for (const entry of batch) savedCounts[entry.productId] = String(entry.countedQuantity);
    };

    await expect(
      saveStocktakeCountBatches(
        10,
        getPendingStocktakeCounts(items, counts, savedCounts),
        saveBatch,
        markSaved,
      ),
    ).rejects.toThrow('Second batch failed');

    expect(saveBatch.mock.calls.map(([, body]) => body.items.length)).toEqual([500, 500]);
    expect(Object.keys(savedCounts)).toHaveLength(500);
    const remaining = getPendingStocktakeCounts(items, counts, savedCounts);
    expect(remaining).toHaveLength(501);

    await saveStocktakeCountBatches(10, remaining, saveBatch, markSaved);

    expect(saveBatch.mock.calls.map(([, body]) => body.items.length)).toEqual([500, 500, 500, 1]);
    expect(Object.keys(savedCounts)).toHaveLength(1001);
  });
});
