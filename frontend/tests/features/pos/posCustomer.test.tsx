import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Customer } from '../../../src/features/catalog/catalogApi';
import PosCustomerPanel from '../../../src/features/pos/PosCustomerPanel';

const { customers, saveCustomer } = vi.hoisted(() => ({
  customers: vi.fn(),
  saveCustomer: vi.fn(),
}));

vi.mock('../../../src/features/catalog/catalogApi', () => ({
  catalogApi: { customers, saveCustomer },
}));

const customer: Customer = {
  id: 125,
  name: 'Nguyễn Minh Anh',
  phone: '0912345678',
  email: null,
  active: true,
  createdAt: '2026-11-15T00:00:00Z',
};

function search(phone: string) {
  fireEvent.change(screen.getByLabelText('Số điện thoại'), { target: { value: phone } });
  fireEvent.click(screen.getByRole('button', { name: 'Tìm khách' }));
}

beforeEach(() => {
  customers.mockReset().mockResolvedValue({ content: [customer] });
  saveCustomer.mockReset().mockResolvedValue(customer);
});

afterEach(() => {
  cleanup();
});

describe('POS customer', () => {
  it('shows inactive-customer guidance without offering create or attach', async () => {
    const message =
      'Khách hàng đã ngừng hoạt động. Liên hệ Quản lý hoặc Chủ cửa hàng để kích hoạt lại.';
    customers.mockRejectedValue({ status: 422, code: 'CUSTOMER_INACTIVE', message });
    const onChange = vi.fn();
    render(<PosCustomerPanel customer={null} onChange={onChange} />);
    search(customer.phone);
    expect(await screen.findByText(message)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Tạo nhanh khách' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Gắn khách' })).toBeNull();
    expect((screen.getByLabelText('Số điện thoại') as HTMLInputElement).value).toBe(customer.phone);
    expect(saveCustomer).not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();
  });

  it.each(['0', '-1', 'abc', '1234567890'])('rejects invalid phone %s before requests', (phone) => {
    render(<PosCustomerPanel customer={null} onChange={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Số điện thoại'), { target: { value: phone } });
    expect(screen.getByRole('alert').textContent).toContain('10–11');
    fireEvent.click(screen.getByRole('button', { name: 'Tìm khách' }));
    expect(customers).not.toHaveBeenCalled();
    expect(saveCustomer).not.toHaveBeenCalled();
  });

  it('finds an exact phone, attaches the returned customer, then allows changing it', async () => {
    const onChange = vi.fn();
    const view = render(<PosCustomerPanel customer={null} onChange={onChange} />);
    search(customer.phone);
    await waitFor(() =>
      expect(customers).toHaveBeenCalledWith(
        { phone: customer.phone, page: 0, size: 1 },
        expect.any(AbortSignal),
      ),
    );
    expect(await screen.findByText(`${customer.name} · ${customer.phone}`)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Gắn khách' }));
    expect(onChange).toHaveBeenCalledWith(customer);

    view.rerender(<PosCustomerPanel customer={customer} onChange={onChange} />);
    expect(screen.getByText('Khách hàng đã được gắn vào đơn hiện tại.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Đổi khách' }));
    expect(onChange).toHaveBeenCalledWith(null);
  });

  it('creates a missing customer using only contract fields and attaches the response', async () => {
    customers.mockResolvedValue({ content: [] });
    const onChange = vi.fn();
    render(<PosCustomerPanel customer={null} onChange={onChange} />);
    search(customer.phone);
    expect(await screen.findByText('Không tìm thấy khách hàng với SĐT này.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Tạo nhanh khách' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Họ và tên' }), {
      target: { value: customer.name },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo và gắn khách' }));
    await waitFor(() =>
      expect(saveCustomer).toHaveBeenCalledWith(
        undefined,
        { name: customer.name, phone: customer.phone },
        expect.any(AbortSignal),
      ),
    );
    expect(onChange).toHaveBeenCalledWith(customer);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('keeps entered values on DUPLICATE_PHONE and offers lookup of the existing customer', async () => {
    customers.mockResolvedValueOnce({ content: [] }).mockResolvedValue({ content: [customer] });
    saveCustomer.mockRejectedValue({
      status: 409,
      code: 'DUPLICATE_PHONE',
      message: 'Số điện thoại đã thuộc khách hàng khác',
      details: { phone: customer.phone, customerId: customer.id },
    });
    const onChange = vi.fn();
    render(<PosCustomerPanel customer={null} onChange={onChange} />);
    search(customer.phone);
    fireEvent.click(await screen.findByRole('button', { name: 'Tạo nhanh khách' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Họ và tên' }), {
      target: { value: customer.name },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo và gắn khách' }));
    expect(await screen.findByText(/409 DUPLICATE_PHONE/)).toBeTruthy();
    expect((screen.getByRole('textbox', { name: 'Họ và tên' }) as HTMLInputElement).value).toBe(
      customer.name,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Tìm khách đã có' }));
    await waitFor(() => expect(customers).toHaveBeenCalledTimes(2));
    fireEvent.click(await screen.findByRole('button', { name: 'Gắn khách' }));
    expect(onChange).toHaveBeenCalledWith(customer);
  });

  it('shows backend validation under its field and preserves the form', async () => {
    customers.mockResolvedValue({ content: [] });
    saveCustomer.mockRejectedValue({
      status: 400,
      code: 'VALIDATION_ERROR',
      message: 'Dữ liệu không hợp lệ',
      details: [{ field: 'name', message: 'Tên không hợp lệ' }],
    });
    render(<PosCustomerPanel customer={null} onChange={vi.fn()} />);
    search(customer.phone);
    fireEvent.click(await screen.findByRole('button', { name: 'Tạo nhanh khách' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Họ và tên' }), {
      target: { value: customer.name },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo và gắn khách' }));
    expect(await screen.findByText('Tên không hợp lệ')).toBeTruthy();
    expect((screen.getByRole('textbox', { name: 'Họ và tên' }) as HTMLInputElement).value).toBe(
      customer.name,
    );
  });

  it('ignores a lookup result after the phone changes', async () => {
    let resolveFirst!: (value: { content: Customer[] }) => void;
    customers
      .mockImplementationOnce(
        () => new Promise<{ content: Customer[] }>((resolve) => (resolveFirst = resolve)),
      )
      .mockResolvedValueOnce({ content: [] });
    render(<PosCustomerPanel customer={null} onChange={vi.fn()} />);
    search(customer.phone);
    await waitFor(() => expect(customers).toHaveBeenCalledTimes(1));
    search('0901234567');
    expect(await screen.findByText('Không tìm thấy khách hàng với SĐT này.')).toBeTruthy();
    resolveFirst({ content: [customer] });
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Gắn khách' })).toBeNull());
  });
});
