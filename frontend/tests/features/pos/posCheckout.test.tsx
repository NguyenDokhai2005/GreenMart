import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Product, Quote } from '../../../src/api/client';
import PosSalePage from '../../../src/features/pos/PosSalePage';
import { POS_NAVIGATION } from '../../../src/layouts/pos/posNavigation';

const { searchProducts, lookupProduct, quoteOrder, completePosOrder, customers } = vi.hoisted(
  () => ({
    searchProducts: vi.fn(),
    lookupProduct: vi.fn(),
    quoteOrder: vi.fn(),
    completePosOrder: vi.fn(),
    customers: vi.fn(),
  }),
);

vi.mock('../../../src/api/client', () => ({
  searchProducts,
  lookupProduct,
  quoteOrder,
  completePosOrder,
}));
vi.mock('../../../src/features/catalog/catalogApi', () => ({ catalogApi: { customers } }));
vi.mock('../../../src/features/auth/useActiveBranch', () => ({
  useActiveBranch: () => ({
    state: { status: 'active', branch: { id: 1, code: 'GM-001', name: 'Quận 1' } },
  }),
}));

const product: Product = {
  id: 205,
  sku: 'SUA-TH-1L',
  barcode: '8935217400013',
  name: 'Sữa tươi TH',
  unit: 'hộp',
  categoryId: 3,
  categoryName: 'Sữa',
  salePrice: 32000,
  active: true,
};
const quote: Quote = {
  branchId: 1,
  lines: [
    {
      productId: 205,
      sku: product.sku,
      name: product.name,
      unit: product.unit,
      quantity: 1,
      unitPrice: 32000,
      discount: 0,
      lineTotal: 32000,
      available: 5,
      sufficient: true,
    },
  ],
  subtotal: 32000,
  itemDiscount: 0,
  orderDiscount: 0,
  manualDiscount: 0,
  discount: 0,
  total: 32000,
  canComplete: true,
};
const paidOrder = {
  id: 42,
  code: 'POS-GM001-20261116-0042',
  branch: { id: 1, code: 'GM-001', name: 'Quận 1' },
  createdBy: { id: 7, fullName: 'Nguyễn An' },
  customer: { id: 9, name: 'Lê Hoàng Mai', phone: '0900000000' },
  items: [
    { id: 4, name: product.name, quantity: 1, unitPrice: 32000, discount: 0, lineTotal: 32000 },
  ],
  subtotal: 32000,
  orderDiscount: 0,
  discount: 0,
  total: 32000,
  payments: [{ method: 'CASH', status: 'PAID', cashReceived: 40000, change: 8000 }],
};

async function openCheckout() {
  render(<PosSalePage item={POS_NAVIGATION[0]} />);
  const search = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
  fireEvent.change(search, { target: { value: product.sku } });
  fireEvent.keyDown(search, { key: 'Enter' });
  const checkout = screen.getByRole('button', { name: 'Thanh toán · F4' });
  await waitFor(() => expect((checkout as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(checkout);
  expect(screen.getByRole('dialog', { name: 'Thanh toán đơn hàng' })).toBeTruthy();
}

beforeEach(() => {
  searchProducts.mockReset().mockResolvedValue({ content: [product] });
  lookupProduct.mockReset().mockResolvedValue({ product, branchId: 1, available: 5 });
  quoteOrder.mockReset().mockResolvedValue(quote);
  completePosOrder.mockReset();
  customers.mockReset().mockResolvedValue({ content: [] });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('POS checkout', () => {
  it('shows item and order discounts from the completed API order in the printable receipt', async () => {
    completePosOrder.mockResolvedValue({
      ...paidOrder,
      items: [
        {
          ...paidOrder.items[0],
          discount: 4000,
          lineTotal: 28000,
          promotion: { code: 'KM-SUA' },
        },
      ],
      subtotal: 32000,
      orderDiscount: 3000,
      discount: 7000,
      total: 25000,
      payments: [{ method: 'CARD', status: 'PAID' }],
    });
    await openCheckout();
    fireEvent.click(screen.getByRole('button', { name: 'Thẻ' }));
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
    const receipt = await screen.findByRole('dialog', { name: 'Thanh toán thành công' });
    expect(receipt.textContent).toContain('KM-SUA 4.000 đ');
    expect(receipt.textContent).toContain('Giảm giá đơn hàng−3.000 đ');
    expect(receipt.textContent).toContain('Tổng giảm giá7.000 đ');
    expect(receipt.textContent).toContain('Tổng thanh toán25.000 đ');
  });

  it('shows cash change and blocks completion below the quoted total', async () => {
    completePosOrder.mockResolvedValue(paidOrder);
    await openCheckout();
    const confirm = screen.getByRole('button', { name: 'Hoàn tất' });
    const cash = screen.getByRole('textbox', { name: 'Tiền khách đưa (VND)' });
    expect((confirm as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(cash, { target: { value: '20000' } });
    expect(screen.getByText('Tiền khách đưa nhỏ hơn tổng thanh toán.')).toBeTruthy();
    expect((confirm as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(cash, { target: { value: '40000' } });
    expect(screen.getByText('8.000 đ')).toBeTruthy();
    fireEvent.click(confirm);
    await waitFor(() => expect(completePosOrder).toHaveBeenCalledTimes(1));
    expect(completePosOrder.mock.calls[0][0]).toEqual({
      branchId: 1,
      items: [{ productId: 205, quantity: 1 }],
      payment: { method: 'CASH', cashReceived: 40000 },
      expectedTotal: 32000,
    });
    expect(completePosOrder.mock.calls[0][1]).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    const receipt = await screen.findByRole('dialog', { name: 'Thanh toán thành công' });
    expect(receipt.textContent).toContain(paidOrder.code);
    expect(receipt.textContent).toContain('Nguyễn An');
    expect(receipt.textContent).toContain('Lê Hoàng Mai');
    expect(receipt.textContent).toContain('Tiền thừa 8.000 đ');
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    fireEvent.click(screen.getByRole('button', { name: 'In hóa đơn' }));
    expect(print).toHaveBeenCalledOnce();
    print.mockRestore();
    fireEvent.click(screen.getByRole('button', { name: 'Đơn hàng mới' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByText('Giỏ hàng đang trống')).toBeTruthy();
  });

  it('retries an uncertain network result with the exact same key and request', async () => {
    completePosOrder
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(paidOrder);
    await openCheckout();
    fireEvent.change(screen.getByRole('textbox', { name: 'Tiền khách đưa (VND)' }), {
      target: { value: '40000' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
    expect(await screen.findByText(/Chưa xác định được kết quả/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Quay lại' }) as HTMLButtonElement).disabled).toBe(
      true,
    );
    expect(
      (screen.getByRole('textbox', { name: 'Tiền khách đưa (VND)' }) as HTMLInputElement).disabled,
    ).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(completePosOrder).toHaveBeenCalledTimes(2));
    expect(completePosOrder.mock.calls[1]).toEqual(completePosOrder.mock.calls[0]);
    expect(await screen.findByRole('dialog', { name: 'Thanh toán thành công' })).toBeTruthy();
  });

  it.each(['PAYMENT_FAILED', 'PAYMENT_TIMEOUT'])(
    'keeps the cart after %s and allows a new QR attempt',
    async (code) => {
      completePosOrder
        .mockRejectedValueOnce({ status: 422, code, message: 'Thẻ thất bại' })
        .mockResolvedValueOnce({ ...paidOrder, payments: [{ method: 'QR', status: 'PAID' }] });
      await openCheckout();
      fireEvent.click(screen.getByRole('button', { name: 'Thẻ' }));
      fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
      expect(await screen.findByText('Thẻ thất bại')).toBeTruthy();
      expect(screen.getByText(/Bạn có thể chọn phương thức khác/)).toBeTruthy();
      expect(screen.getByRole('spinbutton', { name: `Số lượng ${product.name}` })).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: 'QR' }));
      fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
      await waitFor(() => expect(completePosOrder).toHaveBeenCalledTimes(2));
      expect(completePosOrder.mock.calls[0][0].payment).toEqual({ method: 'CARD' });
      expect(completePosOrder.mock.calls[1][0].payment).toEqual({ method: 'QR' });
      expect(completePosOrder.mock.calls[1][1]).not.toBe(completePosOrder.mock.calls[0][1]);
      expect(await screen.findByRole('dialog', { name: 'Thanh toán thành công' })).toBeTruthy();
    },
  );

  it('requotes an unchanged cart after a TOTAL_CHANGED response', async () => {
    completePosOrder.mockRejectedValueOnce({
      status: 422,
      code: 'TOTAL_CHANGED',
      message: 'Tổng tiền đã thay đổi',
    });
    await openCheckout();
    fireEvent.click(screen.getByRole('button', { name: 'Thẻ' }));
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
    expect(await screen.findByText('Tổng tiền đã thay đổi')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Quay lại' }));
    await waitFor(() => expect(quoteOrder).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('spinbutton', { name: `Số lượng ${product.name}` })).toBeTruthy();
  });
});
