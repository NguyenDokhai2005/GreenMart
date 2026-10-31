import { afterEach, expect, it, vi } from 'vitest';
import { completePosOrder, getOrder, quoteOrder, request } from '../../src/api/client';
import type { components } from '../../src/api/schema';

afterEach(() => vi.unstubAllGlobals());
it('quotes, completes and reads an invoice; an explicit retry keeps key and body', async () => {
  vi.stubGlobal('sessionStorage', { getItem: () => null });
  const quote = { total: 200700 };
  const order = { id: 42, status: 'COMPLETED' };
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(
    async (url) =>
      new Response(JSON.stringify(String(url).endsWith('/quote') ? quote : order), {
        headers: { 'content-type': 'application/json' },
      }),
  );
  vi.stubGlobal('fetch', fetchMock);
  const items = [{ productId: 101, quantity: 1 }];
  const result = await quoteOrder(1, items, new AbortController().signal);
  const body: components['schemas']['PosOrderRequest'] = {
    branchId: 1,
    items,
    payment: { method: 'CASH', cashReceived: 250000 },
    expectedTotal: result.total,
  };
  const key = crypto.randomUUID();
  await completePosOrder(body, key);
  await completePosOrder(body, key);
  await getOrder(42);
  const first = fetchMock.mock.calls[1][1];
  const retry = fetchMock.mock.calls[2][1];
  expect(first?.body).toBe(retry?.body);
  expect(new Headers(first?.headers).get('Idempotency-Key')).toBe(key);
  expect(new Headers(retry?.headers).get('Idempotency-Key')).toBe(key);
  expect(fetchMock.mock.calls[3][0]).toBe('/api/v1/orders/42');
});

// Compiled by typecheck, never executed. These must remain compile-time errors.
function contractTypeChecks() {
  // @ts-expect-error endpoint absent from the contract
  request('/promotions', 'get', {});
  // @ts-expect-error method absent from the contract
  request('/orders/pos', 'get', {});
  // @ts-expect-error required lookup branchId
  request('/products/lookup', 'get', { query: { code: 'SKU' } });
  // @ts-expect-error required POS body and idempotency header
  request('/orders/pos', 'post', {});
  // @ts-expect-error path parameter must be a number
  request('/orders/{orderId}', 'get', { path: { orderId: 'abc' } });
}
void contractTypeChecks;
