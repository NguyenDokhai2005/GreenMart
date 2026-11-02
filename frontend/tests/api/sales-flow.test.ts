import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { completePosOrder, getOrder, quoteOrder } from '../../src/api/client';
import type { components } from '../../src/api/schema';

const quote: components['schemas']['Quote'] = {
  branchId: 1,
  customerId: 125,
  lines: [
    {
      productId: 101,
      sku: 'GAO-ST25-5KG',
      name: 'Gạo ST25 túi 5kg',
      unit: 'túi',
      quantity: 1,
      unitPrice: 180000,
      discount: 15000,
      lineTotal: 165000,
      promotion: { id: 11, code: 'GAO-15K', name: null, discount: 15000 },
      available: 40,
      sufficient: true,
    },
    {
      productId: 205,
      sku: 'SUA-TH-1L',
      name: 'Sữa tươi TH True Milk 1L',
      unit: 'hộp',
      quantity: 2,
      unitPrice: 29000,
      discount: 0,
      lineTotal: 58000,
      promotion: null,
      available: 12,
      sufficient: true,
    },
  ],
  orderPromotion: {
    id: 12,
    code: 'DON200K-10',
    name: 'Giảm 10% đơn từ 200.000 đ',
    discount: 22300,
  },
  subtotal: 238000,
  itemDiscount: 15000,
  orderDiscount: 22300,
  manualDiscount: 0,
  discount: 37300,
  total: 200700,
  canComplete: true,
};

const order = { id: 42, code: 'POS-GM001-20261105-0042' };
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });

let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;

beforeEach(() => {
  vi.stubGlobal('sessionStorage', {
    getItem: () => null,
    setItem: () => undefined,
    removeItem: () => undefined,
  });
  fetchMock = vi.fn<typeof fetch>();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => vi.unstubAllGlobals());

it('quotes a cart, retries checkout with the same key and body, then reads the order', async () => {
  fetchMock
    .mockResolvedValueOnce(json(quote))
    .mockResolvedValueOnce(json(order, 201))
    .mockResolvedValueOnce(json(order, 200))
    .mockResolvedValueOnce(json(order));

  const quoteRequest: components['schemas']['QuoteRequest'] = {
    branchId: 1,
    customerId: 125,
    items: [
      { productId: 101, quantity: 1 },
      { productId: 205, quantity: 2 },
    ],
  };
  const receivedQuote = await quoteOrder(quoteRequest);
  const request: components['schemas']['PosOrderRequest'] = {
    branchId: 1,
    customerId: 125,
    items: quoteRequest.items,
    payment: { method: 'CASH', cashReceived: 250000 },
    expectedTotal: receivedQuote.total,
  };
  const idempotencyKey = crypto.randomUUID();

  await expect(completePosOrder(request, idempotencyKey)).resolves.toMatchObject(order);
  await expect(completePosOrder(request, idempotencyKey)).resolves.toMatchObject(order);
  await expect(getOrder(42)).resolves.toMatchObject(order);

  const quoteCall = fetchMock.mock.calls[0];
  const createCall = fetchMock.mock.calls[1];
  const retryCall = fetchMock.mock.calls[2];
  expect(quoteCall?.[0]).toBe('/api/v1/orders/quote');
  expect(JSON.parse(String(quoteCall?.[1]?.body))).toEqual(quoteRequest);
  expect(createCall?.[0]).toBe('/api/v1/orders/pos');
  expect(JSON.parse(String(createCall?.[1]?.body))).toEqual(request);
  expect(JSON.parse(String(createCall?.[1]?.body)).expectedTotal).toBe(quote.total);
  expect(new Headers(createCall?.[1]?.headers).get('Idempotency-Key')).toBe(idempotencyKey);
  expect(retryCall?.[0]).toBe(createCall?.[0]);
  expect(retryCall?.[1]?.body).toBe(createCall?.[1]?.body);
  expect(new Headers(retryCall?.[1]?.headers).get('Idempotency-Key')).toBe(idempotencyKey);
  expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/v1/orders/42');
});
