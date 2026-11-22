import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Product, Quote } from '../../../src/api/client';
import PosSalePage from '../../../src/features/pos/PosSalePage';
import { POS_NAVIGATION } from '../../../src/layouts/pos/posNavigation';

const { searchProducts, lookupProduct, quoteOrder, completePosOrder, customers, roleRef } =
  vi.hoisted(() => ({
    searchProducts: vi.fn(),
    lookupProduct: vi.fn(),
    quoteOrder: vi.fn(),
    completePosOrder: vi.fn(),
    customers: vi.fn(),
    roleRef: { current: 'CASHIER' as string },
  }));

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
vi.mock('../../../src/features/auth/useAuth', () => ({
  useAuth: () => ({ state: { status: 'authenticated', user: { id: 7, role: roleRef.current } } }),
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

it('opens checkout with F4 from the search field without completing an order', async () => {
  render(<PosSalePage item={POS_NAVIGATION[0]} />);
  const search = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
  fireEvent.keyDown(search, { key: 'F4' });
  expect(screen.queryByRole('dialog', { name: 'Thanh toán đơn hàng' })).toBeNull();
  fireEvent.change(search, { target: { value: product.sku } });
  fireEvent.keyDown(search, { key: 'Enter' });
  const checkout = screen.getByRole('button', { name: 'Thanh toán · F4' });
  await waitFor(() => expect((checkout as HTMLButtonElement).disabled).toBe(false));
  fireEvent.keyDown(search, { key: 'F4' });
  expect(screen.getByRole('dialog', { name: 'Thanh toán đơn hàng' })).toBeTruthy();
  expect(completePosOrder).not.toHaveBeenCalled();
});

beforeEach(() => {
  roleRef.current = 'CASHIER';
  sessionStorage.clear();
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
  it('quotes and completes a manager manual discount with its reason', async () => {
    roleRef.current = 'BRANCH_MANAGER';
    completePosOrder.mockResolvedValue(paidOrder);
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    const search = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
    fireEvent.change(search, { target: { value: product.sku } });
    fireEvent.keyDown(search, { key: 'Enter' });
    await screen.findByText(product.name);
    fireEvent.change(screen.getByLabelText('Giảm giá thủ công (VND)'), {
      target: { value: '2000' },
    });
    expect(
      (screen.getByRole('button', { name: 'Thanh toán · F4' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    fireEvent.change(screen.getByLabelText('Lý do giảm giá'), {
      target: { value: 'Khách thân thiết' },
    });
    await waitFor(() =>
      expect(quoteOrder).toHaveBeenLastCalledWith(
        expect.objectContaining({ manualDiscount: { amount: 2000, reason: 'Khách thân thiết' } }),
        expect.any(AbortSignal),
      ),
    );
    const checkout = screen.getByRole('button', { name: 'Thanh toán · F4' });
    await waitFor(() => expect((checkout as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(checkout);
    fireEvent.change(screen.getByRole('textbox', { name: 'Tiền khách đưa (VND)' }), {
      target: { value: '40000' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
    await waitFor(() =>
      expect(completePosOrder).toHaveBeenCalledWith(
        expect.objectContaining({ manualDiscount: { amount: 2000, reason: 'Khách thân thiết' } }),
        expect.any(String),
      ),
    );
  });
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
    expect(receipt.textContent).toContain('KM-SUA 4.000 ₫');
    expect(receipt.textContent).toContain('Giảm giá đơn hàng−3.000 ₫');
    expect(receipt.textContent).toContain('Tổng giảm giá−7.000 ₫');
    expect(receipt.textContent).toContain('Tổng thanh toán25.000 ₫');
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
    fireEvent.change(cash, { target: { value: '40,000' } });
    expect((confirm as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(cash, { target: { value: '40.000' } });
    expect(screen.getByText('Số tiền ghi nhận: 40.000 ₫')).toBeTruthy();
    expect(screen.getByText('8.000 ₫')).toBeTruthy();
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
    expect(receipt.textContent).toContain('Tiền thừa 8.000 ₫');
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
      expect(screen.getByText(/Thử lại hoặc chọn phương thức khác/)).toBeTruthy();
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

  it('retries a known payment failure with the same key and unchanged request', async () => {
    completePosOrder
      .mockRejectedValueOnce({
        status: 422,
        code: 'PAYMENT_FAILED',
        message: 'Thanh toán thất bại',
      })
      .mockResolvedValueOnce(paidOrder);
    await openCheckout();
    fireEvent.click(screen.getByRole('button', { name: 'Thẻ' }));
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
    expect(await screen.findByText('Thanh toán thất bại')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(completePosOrder).toHaveBeenCalledTimes(2));
    expect(completePosOrder.mock.calls[1]).toEqual(completePosOrder.mock.calls[0]);
  });

  it('marks the stock line red with the available quantity after a checkout conflict', async () => {
    completePosOrder.mockRejectedValueOnce({
      status: 409,
      code: 'INSUFFICIENT_STOCK',
      message: 'Không đủ tồn kho khả dụng',
      details: { lines: [{ productId: 205, sku: product.sku, requested: 1, available: 0 }] },
    });
    await openCheckout();
    fireEvent.click(screen.getByRole('button', { name: 'Thẻ' }));
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByText('Thiếu hàng · chỉ còn 0')).toBeTruthy();
    const quantity = screen.getByRole('spinbutton', { name: `Số lượng ${product.name}` });
    expect(quantity.closest('.pos-sale__cart-line--conflict')).toBeTruthy();
    expect((quantity as HTMLInputElement).value).toBe('1');
    expect(
      (screen.getByRole('button', { name: 'Thanh toán · F4' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Kiểm tra giỏ hàng' }));
    expect(document.activeElement).toBe(quantity);
  });

  it('shows details.quote and requires confirmation before retrying TOTAL_CHANGED', async () => {
    const changedQuote: Quote = {
      ...quote,
      lines: [{ ...quote.lines[0], discount: 2000, lineTotal: 30000 }],
      itemDiscount: 2000,
      discount: 2000,
      total: 30000,
    };
    completePosOrder
      .mockRejectedValueOnce({
        status: 422,
        code: 'TOTAL_CHANGED',
        message: 'Tổng tiền đã thay đổi',
        details: { quote: changedQuote },
      })
      .mockResolvedValueOnce({ ...paidOrder, total: 30000 });
    await openCheckout();
    fireEvent.click(screen.getByRole('button', { name: 'Thẻ' }));
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
    expect(await screen.findByText('Tổng tiền đã thay đổi')).toBeTruthy();
    expect(screen.getByText('Tổng mới: 30.000 ₫')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'QR' }) as HTMLButtonElement).disabled).toBe(true);
    expect(completePosOrder).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận tổng mới' }));
    expect(screen.getByRole('dialog', { name: 'Thanh toán đơn hàng' })).toBeTruthy();
    expect(screen.getAllByText('30.000 ₫').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
    await waitFor(() => expect(completePosOrder).toHaveBeenCalledTimes(2));
    expect(completePosOrder.mock.calls[1][0].expectedTotal).toBe(30000);
    expect(completePosOrder.mock.calls[1][1]).not.toBe(completePosOrder.mock.calls[0][1]);
    expect(quoteOrder).toHaveBeenCalledTimes(1);
  });

  it('requires more cash before retrying when the confirmed total increases', async () => {
    const changedQuote: Quote = {
      ...quote,
      lines: [{ ...quote.lines[0], unitPrice: 42000, lineTotal: 42000 }],
      subtotal: 42000,
      total: 42000,
    };
    completePosOrder.mockRejectedValueOnce({
      status: 422,
      code: 'TOTAL_CHANGED',
      message: 'Tổng tiền đã thay đổi',
      details: { quote: changedQuote },
    });
    await openCheckout();
    const cash = screen.getByRole('textbox', { name: 'Tiền khách đưa (VND)' });
    fireEvent.change(cash, { target: { value: '40000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
    expect(await screen.findByText('Tổng mới: 42.000 ₫')).toBeTruthy();
    expect((cash as HTMLInputElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận tổng mới' }));
    expect((cash as HTMLInputElement).disabled).toBe(false);
    expect(screen.getByText('Tiền khách đưa nhỏ hơn tổng thanh toán.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Hoàn tất' }) as HTMLButtonElement).disabled).toBe(
      true,
    );
    expect(completePosOrder).toHaveBeenCalledTimes(1);
  });

  it('requotes without resubmitting when TOTAL_CHANGED details are incomplete', async () => {
    completePosOrder.mockRejectedValueOnce({
      status: 422,
      code: 'TOTAL_CHANGED',
      message: 'Tổng tiền đã thay đổi',
      details: { quote: { total: 30000 } },
    });
    await openCheckout();
    fireEvent.click(screen.getByRole('button', { name: 'Thẻ' }));
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }));
    expect(await screen.findByText('Tổng tiền đã thay đổi')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Xem lại báo giá' }));
    await waitFor(() => expect(quoteOrder).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('spinbutton', { name: `Số lượng ${product.name}` })).toBeTruthy();
    expect(completePosOrder).toHaveBeenCalledTimes(1);
  });
});
