import { act } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { login, logout, readStoredSession } from '../../src/api/client';
import { catalogApi } from '../../src/features/catalog/catalogApi';
import type { ProductInput } from '../../src/features/catalog/catalogApi';
import { AuthProvider } from '../../src/features/auth/AuthProvider';
import { ActiveBranchProvider } from '../../src/features/auth/ActiveBranchProvider';
import { AppRoutes } from '../../src/features/auth/AppRoutes';

const password = process.env.GREENMART_DEMO_PASSWORD;
if (!password) throw new Error('Set GREENMART_DEMO_PASSWORD for the local demo database.');
const backendFetch = globalThis.fetch;
beforeEach(() => {
  sessionStorage.clear();
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  vi.stubGlobal('fetch', (path: string, init?: RequestInit) =>
    backendFetch(new URL(path, 'http://127.0.0.1:8080'), init),
  );
});
afterEach(async () => {
  cleanup();
  if (readStoredSession()) await logout();
  sessionStorage.clear();
  vi.unstubAllGlobals();
});
async function mount(path: string) {
  await act(async () =>
    render(
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <ActiveBranchProvider>
            <AppRoutes />
          </ActiveBranchProvider>
        </AuthProvider>
      </MemoryRouter>,
    ),
  );
}

it('CASHIER searches real product SKU/barcode/name and pages without cost or product writes', async () => {
  await login('thungan.gm001', password);
  const signal = new AbortController().signal;
  const first = await catalogApi.products({ page: 0, size: 3 }, signal);
  expect(first.content).toHaveLength(3);
  expect(first.totalElements).toBeGreaterThan(3);
  const p = first.content[0];
  expect(p.costPrice == null).toBe(true);
  for (const q of [p.sku, p.name, p.barcode].filter((s): s is string => !!s)) {
    const matches = await catalogApi.products({ q, page: 0, size: 3 }, signal);
    expect(matches.content.some((item) => item.id === p.id)).toBe(true);
  }
  const second = await catalogApi.products({ page: 1, size: 3 }, signal);
  expect(second.page).toBe(1);
  expect(second.content[0].id).not.toBe(p.id);
  await mount('/catalog/products');
  expect(await screen.findByText(p.name, {}, { timeout: 10000 })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Thêm sản phẩm' })).toBeNull();
});

it('customer unfiltered list returns a real page (known backend failure must remain visible)', async () => {
  await login('thungan.gm001', password);
  const customers = await catalogApi.customers({ page: 0, size: 3 }, new AbortController().signal);
  expect(Array.isArray(customers.content)).toBe(true);
});

it('customer search with an explicit keyword loads real rows in the UI', async () => {
  await login('thungan.gm001', password);
  const matches = await catalogApi.customers(
    { q: '0', page: 0, size: 3 },
    new AbortController().signal,
  );
  expect(matches.content.length).toBeGreaterThan(0);
  await mount(`/catalog/customers?q=${encodeURIComponent(matches.content[0].phone)}`);
  await waitFor(() => expect(document.querySelector('tbody tr td')?.textContent).toBeTruthy(), {
    timeout: 10000,
  });
  expect(screen.getByRole('button', { name: 'Thêm khách hàng' })).toBeTruthy();
});

it('OWNER changes demo product price in the real UI, verifies persisted value and restores it in finally', async () => {
  await login('owner', password);
  const signal = new AbortController().signal;
  const result = await catalogApi.products({ page: 0, size: 3 }, signal);
  const original = result.content[0];
  expect(original.costPrice).toBeTypeOf('number');
  const restore: ProductInput = {
    sku: original.sku,
    barcode: original.barcode,
    name: original.name,
    unit: original.unit,
    categoryId: original.categoryId,
    salePrice: original.salePrice,
    costPrice: original.costPrice!,
    active: original.active,
  };
  try {
    await mount('/catalog/products');
    await screen.findByRole('button', { name: `Sửa ${original.name}` }, { timeout: 10000 });
    fireEvent.click(screen.getByRole('button', { name: `Sửa ${original.name}` }));
    fireEvent.change(screen.getByLabelText(/^Giá bán/), {
      target: { value: String(original.salePrice + 1) },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
    await screen.findByText('Đã lưu sản phẩm thành công', {}, { timeout: 10000 });
    const updated = await catalogApi.product(original.id, signal);
    expect(updated.salePrice).toBe(original.salePrice + 1);
    expect(updated.sku).toBe(original.sku);
  } finally {
    await catalogApi.saveProduct(original.id, restore, new AbortController().signal);
    const restored = await catalogApi.product(original.id, new AbortController().signal);
    expect(restored.salePrice).toBe(original.salePrice);
  }
});
