import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CurrentUser } from '../../../src/api/client';
import type { components } from '../../../src/api/schema';
import StocktakeDetailDrawer from '../../../src/features/stocktake/StocktakeDetailDrawer';
import StocktakePage from '../../../src/features/stocktake/StocktakePage';
import type { StockDocument } from '../../../src/features/stock-documents/stockDocumentApi';

const mocks = vi.hoisted(() => ({
  listStockDocuments: vi.fn(),
  createStockDocument: vi.fn(),
  getStockDocument: vi.fn(),
  reconcileStockDocument: vi.fn(),
  updateStockDocument: vi.fn(),
  approveStockDocument: vi.fn(),
  user: { current: null as unknown },
  document: { current: null as unknown },
}));

vi.mock('../../../src/features/stock-documents/stockDocumentApi', () => ({
  listStockDocuments: mocks.listStockDocuments,
  createStockDocument: mocks.createStockDocument,
  getStockDocument: mocks.getStockDocument,
  reconcileStockDocument: mocks.reconcileStockDocument,
  updateStockDocument: mocks.updateStockDocument,
  approveStockDocument: mocks.approveStockDocument,
}));

vi.mock('../../../src/features/auth/useAuth', () => ({
  useAuth: () => ({ state: { status: 'authenticated', user: mocks.user.current } }),
}));

vi.mock('../../../src/layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => ({
    branchId: 1,
    scope: { kind: 'branch', branch: { id: 1, code: 'GM-001', name: 'Chi nhánh 1' } },
  }),
}));

const branch = { id: 1, code: 'GM-001', name: 'Chi nhánh 1' };
const storekeeper: CurrentUser = {
  id: 4,
  username: 'keeper',
  fullName: 'Thủ kho',
  role: 'STOREKEEPER',
  branches: [branch],
};
const manager: CurrentUser = {
  ...storekeeper,
  id: 8,
  username: 'manager',
  fullName: 'Quản lý',
  role: 'BRANCH_MANAGER',
};

const draft = (): StockDocument => ({
  id: 11,
  code: 'KK-GM001-001',
  type: 'ADJUST',
  branch,
  status: 'DRAFT',
  itemCount: 1,
  createdBy: { id: 4, fullName: 'Thủ kho' },
  createdAt: '2026-11-27T09:00:00+07:00',
  items: [
    {
      id: 55,
      productId: 21,
      sku: 'RICE-01',
      productName: 'Gạo thơm',
      unit: 'kg',
      quantity: 8,
      countedQuantity: null,
      difference: null,
    },
  ],
});

const ready: components['schemas']['StocktakeReconciliation'] = {
  documentId: 11,
  branchId: 1,
  inventoryReady: true,
  lines: [
    {
      productId: 21,
      sku: 'RICE-01',
      snapshotQuantity: 8,
      countedQuantity: 5,
      difference: -3,
      onHand: 8,
      reserved: 2,
      projectedOnHand: 5,
      shortfall: 0,
      reservationGap: 0,
      reservationOrderCount: 0,
      reservationOrders: [],
    },
  ],
};

function renderList() {
  return render(
    <MemoryRouter initialEntries={['/operations/stocktake']}>
      <Routes>
        <Route path="/operations/stocktake" element={<StocktakePage />}>
          <Route path=":id" element={<StocktakeDetailDrawer />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/operations/stocktake/11']}>
      <Routes>
        <Route
          path="/operations/stocktake"
          element={<Outlet context={{ user: mocks.user.current, onChanged: vi.fn() }} />}
        >
          <Route path=":id" element={<StocktakeDetailDrawer />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mocks.user.current = storekeeper;
  mocks.document.current = draft();
  mocks.listStockDocuments.mockReset().mockResolvedValue({
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    content: [],
  });
  mocks.createStockDocument.mockReset().mockImplementation(async () => mocks.document.current);
  mocks.getStockDocument.mockReset().mockImplementation(async () => mocks.document.current);
  mocks.reconcileStockDocument.mockReset().mockResolvedValue(ready);
  mocks.updateStockDocument.mockReset().mockImplementation(async (_id, body) => {
    const current = mocks.document.current as StockDocument;
    const next = {
      ...current,
      items: current.items.map((item) => ({
        ...item,
        countedQuantity:
          body.items.find(
            (line: components['schemas']['StockDocumentItemRequest']) =>
              line.productId === item.productId,
          )?.countedQuantity ?? null,
        difference:
          (body.items.find(
            (line: components['schemas']['StockDocumentItemRequest']) =>
              line.productId === item.productId,
          )?.countedQuantity ?? item.quantity) - item.quantity,
      })),
    };
    mocks.document.current = next;
    return next;
  });
  mocks.approveStockDocument.mockReset().mockResolvedValue(draft());
});

afterEach(cleanup);

describe('StocktakePage', () => {
  it('creates an ADJUST snapshot and saves a count with its variance', async () => {
    renderList();
    fireEvent.click(await screen.findByRole('button', { name: '+ Tạo phiếu kiểm kê' }));

    await waitFor(() => {
      expect(mocks.createStockDocument).toHaveBeenCalledWith({
        type: 'ADJUST',
        branchId: 1,
        allProducts: true,
      });
    });
    expect(await screen.findByText('KK-GM001-001')).toBeTruthy();
    expect(screen.getByText('8 kg')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Số đếm Gạo thơm'), { target: { value: '5' } });
    expect(screen.getByText('-3')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Lưu số đếm' }));

    await waitFor(() => {
      expect(mocks.updateStockDocument).toHaveBeenCalledWith(
        11,
        expect.objectContaining({
          items: [{ productId: 21, countedQuantity: 5 }],
        }),
      );
    });
    expect(await screen.findByText('Đã lưu số đếm kiểm kê.')).toBeTruthy();
  });

  it('shows reservation reconciliation and prevents approval when projected stock is short', async () => {
    mocks.user.current = manager;
    const document = { ...draft(), items: [{ ...draft().items[0], countedQuantity: 3 }] };
    mocks.document.current = document;
    mocks.reconcileStockDocument.mockResolvedValue({
      ...ready,
      inventoryReady: false,
      lines: [
        {
          ...ready.lines[0],
          countedQuantity: 3,
          difference: -5,
          projectedOnHand: 3,
          reserved: 5,
          shortfall: 2,
          reservationOrderCount: 1,
          reservationOrders: [{ orderId: 90, code: 'DH-090', status: 'RESERVED', quantity: 2 }],
        },
      ],
    } satisfies components['schemas']['StocktakeReconciliation']);
    renderDetail();

    expect(await screen.findByText(/DH-090/)).toBeTruthy();
    expect(screen.getByText('2', { selector: 'dd' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Duyệt kiểm kê' })).toBeNull();
    expect(screen.getByText(/Không thể duyệt phiếu kiểm kê/)).toBeTruthy();
  });

  it('does not allow the manager who created the stocktake to approve it', async () => {
    mocks.user.current = { ...manager, id: 4 };
    mocks.document.current = { ...draft(), items: [{ ...draft().items[0], countedQuantity: 8 }] };
    renderDetail();

    expect(await screen.findByText('Bạn là người lập phiếu nên không thể tự duyệt.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Duyệt kiểm kê' })).toBeNull();
    expect(mocks.approveStockDocument).not.toHaveBeenCalled();
  });
});
