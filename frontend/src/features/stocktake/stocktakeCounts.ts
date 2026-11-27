import type { components } from '../../api/schema';
import type { StockDocument } from '../stock-documents/stockDocumentApi';

export const STOCKTAKE_UPDATE_BATCH_SIZE = 500;

export interface PendingStocktakeCount {
  productId: number;
  countedQuantity: number;
}

export function getPendingStocktakeCounts(
  items: StockDocument['items'],
  counts: Record<number, string>,
  savedCounts: Record<number, string>,
): PendingStocktakeCount[] {
  return items.flatMap((item) => {
    const value = counts[item.productId] ?? '';
    if (value === '' || value === savedCounts[item.productId]) return [];
    return [{ productId: item.productId, countedQuantity: Number(value) }];
  });
}

export async function saveStocktakeCountBatches(
  documentId: number,
  counts: PendingStocktakeCount[],
  saveBatch: (
    documentId: number,
    body: components['schemas']['StockDocumentUpdateRequest'],
  ) => Promise<unknown>,
  onBatchSaved: (batch: PendingStocktakeCount[]) => void,
): Promise<void> {
  for (let offset = 0; offset < counts.length; offset += STOCKTAKE_UPDATE_BATCH_SIZE) {
    const batch = counts.slice(offset, offset + STOCKTAKE_UPDATE_BATCH_SIZE);
    await saveBatch(documentId, { items: batch });
    onBatchSaved(batch);
  }
}
