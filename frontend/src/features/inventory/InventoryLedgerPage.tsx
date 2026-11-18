import { useCallback, useMemo } from 'react';
import { Link, Outlet, useSearchParams } from 'react-router';
import { ApiError } from '../../api/client';
import type { components } from '../../api/schema';
import { AsyncBlock, Input } from '../../components/ui';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import { APP_PATHS } from '../auth/routePolicy';
import Pagination from './PaginationControl';
import { useRemote } from '../transfers/useRemote';
import { catalogApi } from '../catalog/catalogApi';
import { listInventoryTransactions } from './inventoryTransactionsApi';
import './InventoryLedger.css';

type InventoryTransaction = components['schemas']['InventoryTransaction'];
type Direction = '' | 'INCREASE' | 'DECREASE';

const PAGE_SIZE = 20;

const DIRECTION_OPTIONS: { value: Direction; label: string }[] = [
  { value: '', label: 'Tất cả' },
  { value: 'INCREASE', label: '+ Tăng' },
  { value: 'DECREASE', label: '- Giảm' },
];

const TRANSACTION_LABELS: Record<InventoryTransaction['type'], string> = {
  IMPORT: 'Nhập hàng',
  EXPORT: 'Xuất hủy',
  SALE: 'Bán hàng',
  RESERVE: 'Giữ chỗ',
  RELEASE: 'Giải phóng giữ chỗ',
  RETURN: 'Trả hàng',
  TRANSFER_OUT: 'Chuyển kho',
  TRANSFER_IN: 'Chuyển kho',
  ADJUST: 'Kiểm kê',
};

function parsePage(value: string | null): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 1;
}

function transactionDelta(transaction: InventoryTransaction): number {
  return transaction.deltaOnHand - transaction.deltaReserved;
}

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

async function loadTransactions(branchId: number | undefined, page: number, signal: AbortSignal) {
  const result = await listInventoryTransactions(
    { branchId, page: page - 1, size: PAGE_SIZE },
    signal,
  );
  const productIds = [...new Set(result.content.map((transaction) => transaction.productId))];
  const products = await Promise.all(
    productIds.map(async (productId) => {
      try {
        const product = await catalogApi.product(productId, signal);
        return [productId, product.name] as const;
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          return [productId, `Sản phẩm #${productId}`] as const;
        }
        throw error;
      }
    }),
  );
  const productNames = new Map(products);
  return {
    ...result,
    content: result.content.map((transaction) => ({
      ...transaction,
      productName: productNames.get(transaction.productId) ?? `Sản phẩm #${transaction.productId}`,
    })),
  };
}

export default function InventoryLedgerPage() {
  const { branchId } = useOperationsScope();
  const [params, setParams] = useSearchParams();
  const query = params.get('q') ?? '';
  const directionValue = params.get('direction');
  const direction: Direction =
    directionValue === 'INCREASE' || directionValue === 'DECREASE' ? directionValue : '';
  const page = parsePage(params.get('page'));
  const queryKey = `${branchId ?? 'all'}|${page}`;
  const { data, error, isLoading, reload } = useRemote(
    (signal) => loadTransactions(branchId, page, signal),
    queryKey,
  );
  const hasFilters = !!query || !!direction;

  const update = useCallback(
    (patch: { q?: string; direction?: Direction; page?: number }, replace = false) => {
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          const set = (key: string, value: string | undefined) =>
            value ? next.set(key, value) : next.delete(key);
          if ('q' in patch) set('q', patch.q?.trim());
          if ('direction' in patch) set('direction', patch.direction);
          set('page', patch.page && patch.page > 1 ? String(patch.page) : undefined);
          return next;
        },
        { replace },
      );
    },
    [setParams],
  );

  const transactions = useMemo(() => {
    const term = query.trim().toLocaleLowerCase('vi');
    return (data?.content ?? []).filter((transaction) => {
      const delta = transactionDelta(transaction);
      if (direction === 'INCREASE' && delta <= 0) return false;
      if (direction === 'DECREASE' && delta >= 0) return false;
      if (!term) return true;
      return [
        `txn-${transaction.id}`,
        String(transaction.id),
        transaction.productName,
        transaction.sku,
        transaction.refCode ?? '',
      ].some((value) => value.toLocaleLowerCase('vi').includes(term));
    });
  }, [data, direction, query]);

  const clearFilters = () => {
    setParams(new URLSearchParams());
  };

  return (
    <div className="ledger-page">
      <header className="ledger-header">
        <div>
          <h1>Sổ giao dịch</h1>
          <p>Lịch sử giao dịch.</p>
        </div>
      </header>

      <section className="ledger-filters" aria-label="Lọc giao dịch kho">
        <Input
          label="Tìm kiếm giao dịch"
          type="search"
          value={query}
          placeholder="Mã GD, SKU, tham chiếu..."
          onChange={(event) => update({ q: event.target.value, page: 1 }, true)}
        />
        <div>
          <span className="ledger-filter-label" id="ledger-direction-label">
            Phân loại biến động
          </span>
          <div className="ledger-direction" role="group" aria-labelledby="ledger-direction-label">
            {DIRECTION_OPTIONS.map((option) => (
              <button
                key={option.value || 'ALL'}
                type="button"
                aria-pressed={direction === option.value}
                onClick={() => update({ direction: option.value, page: 1 })}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>
      </section>

      <section className="ledger-card" aria-labelledby="ledger-list-title" aria-busy={isLoading}>
        <div className="ledger-card-heading">
          <h2 id="ledger-list-title">Danh sách giao dịch</h2>
        </div>
        <AsyncBlock
          isLoading={isLoading}
          error={error}
          isEmpty={!isLoading && !!data && transactions.length === 0}
          onRetry={reload}
          empty={{
            message: hasFilters ? 'Không tìm thấy giao dịch phù hợp' : 'Chưa có giao dịch kho',
            description: 'Giao dịch kho sẽ hiển thị tại đây khi có phát sinh.',
            filtered: hasFilters,
            onClearFilters: clearFilters,
          }}
          skeleton={<LedgerTableSkeleton />}
        >
          <div className="ledger-table-wrap">
            <table className="ledger-table">
              <thead>
                <tr>
                  <th scope="col">Mã giao dịch</th>
                  <th scope="col">Sản phẩm</th>
                  <th scope="col">SKU</th>
                  <th scope="col">Loại giao dịch</th>
                  <th scope="col" className="ledger-number">
                    Biến động
                  </th>
                  <th scope="col">Thời gian</th>
                  <th scope="col">Tham chiếu</th>
                </tr>
              </thead>
              <tbody>
                {transactions.map((transaction) => (
                  <tr key={transaction.id}>
                    <td>TXN-{transaction.id}</td>
                    <td>{transaction.productName}</td>
                    <td>{transaction.sku}</td>
                    <td>{TRANSACTION_LABELS[transaction.type]}</td>
                    <td className="ledger-number">
                      {transactionDelta(transaction) > 0 ? '+' : ''}
                      {transactionDelta(transaction)}
                    </td>
                    <td>{formatDate(transaction.createdAt)}</td>
                    <td className="ledger-reference">
                      {transaction.refCode ? (
                        transaction.refType === 'STOCK_DOCUMENT' ? (
                          <Link to={`${APP_PATHS.stockDocuments}/${transaction.refId}`}>
                            {transaction.refCode}
                          </Link>
                        ) : transaction.refType === 'ORDER' ? (
                          <Link to={`orders/${transaction.refId}`}>{transaction.refCode}</Link>
                        ) : (
                          <span>{transaction.refCode}</span>
                        )
                      ) : (
                        '—'
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </AsyncBlock>
        {!error && data && data.totalPages > 1 && (
          <div className="ledger-card-footer">
            <Pagination
              page={page}
              totalPages={data.totalPages}
              onChange={(nextPage) => update({ page: nextPage })}
            />
          </div>
        )}
      </section>

      <Outlet />
    </div>
  );
}

function LedgerTableSkeleton() {
  return (
    <div className="ledger-table-wrap" aria-label="Đang tải giao dịch" role="status">
      <table className="ledger-table">
        <thead>
          <tr>
            {[
              'Mã giao dịch',
              'Sản phẩm',
              'SKU',
              'Loại giao dịch',
              'Biến động',
              'Thời gian',
              'Tham chiếu',
            ].map((label) => (
              <th scope="col" key={label}>
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: 5 }, (_, index) => (
            <tr key={index}>
              {Array.from({ length: 7 }, (_, cell) => (
                <td key={cell}>
                  <span className="ledger-skeleton" />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
