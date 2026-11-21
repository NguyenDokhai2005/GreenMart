import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Product, Quote } from '../../../src/api/client';
import PosSalePage from '../../../src/features/pos/PosSalePage';
import { cartReducer, stockForLine } from '../../../src/features/pos/posCart';
import { POS_NAVIGATION } from '../../../src/layouts/pos/posNavigation';

const { searchProducts, lookupProduct, quoteOrder, customers } = vi.hoisted(() => ({
  searchProducts: vi.fn(),
  lookupProduct: vi.fn(),
  quoteOrder: vi.fn(),
  customers: vi.fn(),
}));

vi.mock('../../../src/api/client', () => ({ searchProducts, lookupProduct, quoteOrder }));
vi.mock('../../../src/features/catalog/catalogApi', () => ({
  catalogApi: { customers },
}));
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

function quote(quantity: number): Quote {
  return {
    branchId: 1,
    lines: [
      {
        productId: product.id,
        sku: product.sku,
        name: product.name,
        unit: product.unit,
        quantity,
        unitPrice: 32000,
        discount: 0,
        lineTotal: quantity * 32000,
        available: 1,
        sufficient: quantity <= 1,
      },
    ],
    subtotal: quantity * 32000,
    itemDiscount: 0,
    orderDiscount: 0,
    manualDiscount: 0,
    discount: 0,
    total: quantity * 32000,
    canComplete: quantity <= 1,
  };
}

beforeEach(() => {
  searchProducts.mockReset().mockResolvedValue({ content: [product] });
  lookupProduct.mockReset().mockResolvedValue({ product, branchId: 1, available: 1 });
  customers.mockReset().mockResolvedValue({
    content: [
      {
        id: 125,
        name: 'Nguyễn Minh Anh',
        phone: '0912345678',
        email: null,
        createdAt: '2026-11-15T00:00:00Z',
      },
    ],
  });
  quoteOrder
    .mockReset()
    .mockImplementation(async (request: { items: { quantity: number }[] }) =>
      quote(request.items[0].quantity),
    );
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('POS cart', () => {
  it.each(['0', '-1', '1.5', '10000', ''])(
    'shows quantity error and prevents quote for %s',
    async (value) => {
      render(<PosSalePage item={POS_NAVIGATION[0]} />);
      const search = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
      fireEvent.change(search, { target: { value: product.barcode } });
      fireEvent.keyDown(search, { key: 'Enter' });
      const input = await screen.findByRole('spinbutton', { name: `Số lượng ${product.name}` });
      await waitFor(() => expect(quoteOrder).toHaveBeenCalled());
      vi.mocked(quoteOrder).mockClear();
      fireEvent.change(input, { target: { value } });
      expect(screen.getByText('Số lượng phải là số nguyên từ 1 đến 9.999.')).toBeTruthy();
      await new Promise((resolve) => setTimeout(resolve, 180));
      expect(quoteOrder).not.toHaveBeenCalled();
      expect(
        (screen.getByRole('button', { name: /Thanh toán/ }) as HTMLButtonElement).disabled,
      ).toBe(true);
      fireEvent.change(input, { target: { value: '2' } });
      await waitFor(() =>
        expect(quoteOrder).toHaveBeenCalledWith(
          { branchId: 1, items: [{ productId: 205, quantity: 2 }] },
          expect.any(AbortSignal),
        ),
      );
    },
  );

  it('adds repeated scans to one line and blocks checkout when quote reports insufficient stock', async () => {
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    const input = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');

    fireEvent.change(input, { target: { value: product.barcode } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() =>
      expect(
        (screen.getByRole('spinbutton', { name: `Số lượng ${product.name}` }) as HTMLInputElement)
          .value,
      ).toBe('1'),
    );
    await waitFor(() =>
      expect(quoteOrder).toHaveBeenCalledWith(
        { branchId: 1, items: [{ productId: 205, quantity: 1 }] },
        expect.any(AbortSignal),
      ),
    );

    fireEvent.change(input, { target: { value: product.barcode } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() =>
      expect(
        (screen.getByRole('spinbutton', { name: `Số lượng ${product.name}` }) as HTMLInputElement)
          .value,
      ).toBe('2'),
    );
    await waitFor(() =>
      expect(quoteOrder).toHaveBeenCalledWith(
        { branchId: 1, items: [{ productId: 205, quantity: 2 }] },
        expect.any(AbortSignal),
      ),
    );
    await waitFor(() => expect(screen.getByText('Thiếu hàng · chỉ còn 1')).toBeTruthy());
    expect(
      (screen.getByRole('button', { name: 'Thanh toán · F4' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(lookupProduct).toHaveBeenCalledTimes(2);
    expect(screen.getAllByRole('button', { name: `Xóa ${product.name}` })).toHaveLength(1);
  });

  it('accepts a fast keyboard scanner outside the search field and requotes after removing a line', async () => {
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    for (const character of product.barcode!) fireEvent.keyDown(window, { key: character });
    fireEvent.keyDown(window, { key: 'Enter' });
    await waitFor(() => expect(lookupProduct).toHaveBeenCalledWith(product.barcode, 1));
    await waitFor(() =>
      expect(
        (screen.getByRole('button', { name: 'Thanh toán · F4' }) as HTMLButtonElement).disabled,
      ).toBe(false),
    );
    const callsBeforeRemove = quoteOrder.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: `Xóa ${product.name}` }));
    expect(screen.getByText('Giỏ hàng đang trống')).toBeTruthy();
    expect(quoteOrder.mock.calls.length).toBe(callsBeforeRemove);
    expect(
      (screen.getByRole('button', { name: 'Thanh toán · F4' }) as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it('shows a filtered empty state only after search resolves and clears the filter', async () => {
    searchProducts.mockResolvedValue({ content: [] });
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    const input = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
    fireEvent.change(input, { target: { value: 'không có' } });
    expect(screen.queryByText('Không có kết quả phù hợp')).toBeNull();
    await waitFor(() => expect(screen.getByText('Không có kết quả phù hợp')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
    expect((input as HTMLInputElement).value).toBe('');
  });

  it('keeps the cart after a quote conflict', async () => {
    quoteOrder.mockRejectedValue({
      status: 422,
      code: 'TOTAL_CHANGED',
      message: 'Giá đã thay đổi',
    });
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    fireEvent.change(screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU'), {
      target: { value: product.sku },
    });
    fireEvent.keyDown(screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU'), {
      key: 'Enter',
    });
    await waitFor(() => expect(screen.getByText('Giá đã thay đổi')).toBeTruthy());
    expect(
      (screen.getByRole('spinbutton', { name: `Số lượng ${product.name}` }) as HTMLInputElement)
        .value,
    ).toBe('1');
    expect(
      (screen.getByRole('button', { name: 'Thanh toán · F4' }) as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it('renders subtotal, discount and total from the quote response', async () => {
    quoteOrder.mockResolvedValue({ ...quote(1), itemDiscount: 5000, discount: 5000, total: 27000 });
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    const input = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
    fireEvent.change(input, { target: { value: product.sku } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(screen.getByText('27.000 đ')).toBeTruthy());
    expect(screen.getAllByText('−5.000 đ')).toHaveLength(2);
    expect(screen.getAllByText('32.000 đ').length).toBeGreaterThan(0);
  });

  it('shows line and order promotions from the quote without changing the quoted total', async () => {
    quoteOrder.mockResolvedValue({
      ...quote(1),
      lines: [
        {
          ...quote(1).lines[0],
          discount: 12000,
          lineTotal: 20000,
          promotion: { id: 11, code: 'KM-SUA', name: 'KM Sữa tươi', discount: 12000 },
        },
      ],
      itemDiscount: 12000,
      orderPromotion: { id: 12, code: 'DON-8K', name: 'Giảm đơn 8.000 đ', discount: 8000 },
      orderDiscount: 8000,
      discount: 20000,
      total: 12000,
    });
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    const input = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
    fireEvent.change(input, { target: { value: product.sku } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(screen.getByText('KM Sữa tươi · KM-SUA')).toBeTruthy());
    expect(screen.getByText('DON-8K')).toBeTruthy();
    expect(screen.getAllByText('−12.000 đ').length).toBeGreaterThan(0);
    expect(screen.getByText('−8.000 đ')).toBeTruthy();
    expect(screen.getByText('−20.000 đ')).toBeTruthy();
    expect(screen.getByText('12.000 đ')).toBeTruthy();
  });

  it('shows the promotion code alone when its name is missing and hides order code after a requote', async () => {
    quoteOrder.mockResolvedValueOnce({
      ...quote(1),
      lines: [
        {
          ...quote(1).lines[0],
          discount: 2000,
          lineTotal: 30000,
          promotion: { id: 11, code: 'ITEM-2K', discount: 2000 },
        },
      ],
      orderPromotion: { id: 12, code: 'ORDER-1K', discount: 1000 },
      itemDiscount: 2000,
      orderDiscount: 1000,
      discount: 3000,
      total: 29000,
    });
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    const search = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
    fireEvent.change(search, { target: { value: product.sku } });
    fireEvent.keyDown(search, { key: 'Enter' });
    await waitFor(() => expect(screen.getByText('ITEM-2K')).toBeTruthy());
    expect(screen.getByText('ORDER-1K')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: `Tăng số lượng ${product.name}` }));
    await waitFor(() => expect(quoteOrder).toHaveBeenCalledTimes(2));
    await screen.findAllByText('64.000 đ');
    expect(screen.queryByText('ITEM-2K')).toBeNull();
    expect(screen.queryByText('ORDER-1K')).toBeNull();
  });

  it('does not repeat an item promotion code when the name equals the code', async () => {
    quoteOrder.mockResolvedValue({
      ...quote(1),
      lines: [
        {
          ...quote(1).lines[0],
          promotion: { id: 11, code: 'ITEM-2K', name: 'ITEM-2K', discount: 2000 },
        },
      ],
    });
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    const search = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
    fireEvent.change(search, { target: { value: product.sku } });
    fireEvent.keyDown(search, { key: 'Enter' });
    expect(await screen.findByText('ITEM-2K')).toBeTruthy();
    expect(screen.queryByText('ITEM-2K · ITEM-2K')).toBeNull();
  });

  it('adds a product selected from a name search using its SKU', async () => {
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    const input = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
    fireEvent.change(input, { target: { value: 'Sữa tươi' } });
    await waitFor(() =>
      expect(searchProducts).toHaveBeenCalledWith('Sữa tươi', expect.any(AbortSignal)),
    );
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(lookupProduct).toHaveBeenCalledWith(product.sku, 1));
  });

  it('places validation details beneath the quantity without removing the line', async () => {
    quoteOrder.mockRejectedValue({
      status: 400,
      code: 'VALIDATION_ERROR',
      message: 'Dữ liệu không hợp lệ',
      details: [{ field: 'items[0].quantity', message: 'Số lượng không hợp lệ' }],
    });
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    const input = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
    fireEvent.change(input, { target: { value: product.sku } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(screen.getByText('Số lượng không hợp lệ')).toBeTruthy());
    expect(screen.getByRole('spinbutton', { name: `Số lượng ${product.name}` })).toBeTruthy();
  });

  it('requotes with the attached customer and removes that ID after changing customer', async () => {
    render(<PosSalePage item={POS_NAVIGATION[0]} />);
    const productSearch = screen.getByLabelText('Quét mã vạch hoặc tìm theo tên / SKU');
    fireEvent.change(productSearch, { target: { value: product.sku } });
    fireEvent.keyDown(productSearch, { key: 'Enter' });
    await waitFor(() => expect(quoteOrder).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByLabelText('Số điện thoại'), {
      target: { value: '0912345678' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm khách' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Gắn khách' }));
    await waitFor(() =>
      expect(quoteOrder).toHaveBeenCalledWith(
        { branchId: 1, customerId: 125, items: [{ productId: 205, quantity: 1 }] },
        expect.any(AbortSignal),
      ),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Đổi khách' }));
    await waitFor(() =>
      expect(quoteOrder).toHaveBeenLastCalledWith(
        { branchId: 1, items: [{ productId: 205, quantity: 1 }] },
        expect.any(AbortSignal),
      ),
    );
    expect(screen.getByRole('spinbutton', { name: `Số lượng ${product.name}` })).toBeTruthy();
  });
});

it('keeps stock warning from lookup until an updated quote arrives', () => {
  const line = cartReducer([], { type: 'add', product, available: 1 })[0];
  const increased = cartReducer([line], { type: 'add', product, available: 1 });
  expect(stockForLine(increased[0], null)).toEqual({ available: 1, sufficient: false });
});
