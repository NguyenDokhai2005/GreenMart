import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { FormEvent } from 'react';
import type { components } from '../../api/schema';
import {
  AsyncBlock,
  Button,
  Input,
  OrderStatusBadge,
  Select,
  Skeleton,
  Table,
} from '../../components/ui';
import type { TableColumn } from '../../components/ui';
import { mapErrorToState } from '../../lib/errors';
import { getPageItems } from '../inventory/pagination';
import { useAuth } from '../auth/useAuth';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import {
  ORDER_STATUSES,
  STATUS_LABELS,
  hasOrderFilters,
  orderFiltersToApi,
  orderFiltersToParams,
  readOrderFilters,
} from './ordersQuery';
import type { OrderFilters, OrderStatus } from './ordersQuery';
import { useOrdersList } from './useOrdersList';
import './orders.css';

type OrderSummary = components['schemas']['OrderSummary'];
const money = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
});
const dateTime = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

function OrderTableSkeleton() {
  return (
    <div
      className="orders-table-skeleton"
      role="status"
      aria-busy="true"
      aria-label="Đang tải danh sách đơn"
    >
      <div className="orders-table-skeleton__head">
        Mã đơn · Mã đơn kênh · Kênh · Chi nhánh · Khách hàng · SL · Tổng tiền · Trạng thái · Tạo lúc
      </div>
      {Array.from({ length: 8 }, (_, index) => (
        <Skeleton key={index} height={18} />
      ))}
    </div>
  );
}

function OrdersPagination({
  page,
  totalPages,
  totalElements,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  onPage: (page: number) => void;
}) {
  if (totalElements === 0) return null;
  const items = getPageItems(page, totalPages);
  return (
    <nav className="orders-pagination" aria-label="Phân trang đơn hàng">
      <span>
        Hiển thị {(page - 1) * 8 + 1}–{Math.min(page * 8, totalElements)} / {totalElements} đơn
      </span>
      <div className="orders-pagination__actions">
        <Button
          variant="secondary"
          aria-label="Trang trước"
          disabled={page <= 1}
          onClick={() => onPage(page - 1)}
        >
          ‹
        </Button>
        <span className="orders-pagination__size">8 / trang</span>
        {items.map((item, index) =>
          item === 'ellipsis' ? (
            <span key={`gap-${index}`} aria-hidden="true">
              …
            </span>
          ) : (
            <Button
              key={item}
              variant="secondary"
              aria-label={`Trang ${item}`}
              aria-current={item === page ? 'page' : undefined}
              onClick={() => onPage(item)}
            >
              {item}
            </Button>
          ),
        )}
        <Button
          variant="secondary"
          aria-label="Trang sau"
          disabled={page >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          ›
        </Button>
      </div>
    </nav>
  );
}

/** ORD-06: danh sách đơn, bộ lọc và URL có thể tải lại/chia sẻ. */
function OrdersPageView() {
  const [params, setParams] = useSearchParams();
  const paramsKey = params.toString();
  const applied = useMemo(() => readOrderFilters(new URLSearchParams(paramsKey)), [paramsKey]);
  const [draft, setDraft] = useState<OrderFilters>(applied);
  const [dateError, setDateError] = useState('');
  const { state } = useAuth();
  const { scope, branches, canPickAll } = useOperationsScope();

  const allowedBranchId = branches.some((branch) => branch.id === applied.branchId)
    ? applied.branchId
    : undefined;
  const branchId = canPickAll
    ? (allowedBranchId ?? (scope.kind === 'branch' ? scope.branch.id : undefined))
    : scope.kind === 'branch'
      ? scope.branch.id
      : undefined;
  const query = useMemo(() => orderFiltersToApi(applied, branchId), [applied, branchId]);
  const { data, error, isLoading, retry } = useOrdersList(query);
  const errorState = error ? mapErrorToState(error, 'block') : null;
  const fieldErrors = errorState?.kind === 'form' ? errorState.fieldErrors : {};
  const hasFormError = errorState?.kind === 'form';
  const totalPages = data?.totalPages ?? 0;

  useEffect(() => {
    if (!isLoading && data && totalPages > 0 && applied.page > totalPages) {
      setParams(orderFiltersToParams({ ...applied, page: 1 }), { replace: true });
    }
  }, [applied, data, isLoading, setParams, totalPages]);

  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (draft.from && draft.to && draft.from > draft.to) {
      setDateError('Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.');
      return;
    }
    setDateError('');
    setParams(orderFiltersToParams({ ...draft, page: 1 }));
  }

  function clear() {
    setDraft({
      q: '',
      channel: '',
      branchId: undefined,
      status: [],
      from: '',
      to: '',
      customerId: undefined,
      page: 1,
    });
    setDateError('');
    setParams(new URLSearchParams());
  }

  function changePage(page: number) {
    setParams(orderFiltersToParams({ ...applied, page }));
  }

  const columns: TableColumn<OrderSummary>[] = [
    {
      key: 'code',
      title: 'Mã đơn',
      render: (row) => <strong className="orders-code">{row.code}</strong>,
    },
    { key: 'externalOrderId', title: 'Mã đơn kênh', render: (row) => row.externalOrderId || '—' },
    { key: 'channel', title: 'Kênh' },
    { key: 'branch', title: 'Chi nhánh', render: (row) => row.branch.code },
    {
      key: 'customer',
      title: 'Khách hàng',
      render: (row) =>
        row.customer ? (
          <span>
            {row.customer.name}
            <small>{row.customer.phone}</small>
          </span>
        ) : (
          'Khách lẻ'
        ),
    },
    { key: 'itemCount', title: 'SL', align: 'right', render: (row) => row.itemCount ?? '—' },
    {
      key: 'total',
      title: 'Tổng tiền',
      align: 'right',
      render: (row) => <strong>{money.format(row.total)}</strong>,
    },
    {
      key: 'status',
      title: 'Trạng thái',
      render: (row) => <OrderStatusBadge status={row.status} />,
    },
    {
      key: 'createdAt',
      title: 'Tạo lúc',
      render: (row) => dateTime.format(new Date(row.createdAt)),
    },
    {
      key: 'view',
      title: '',
      render: (row) => (
        <Link className="orders-view" to={`/operations/orders/${row.id}`}>
          Xem
        </Link>
      ),
    },
  ];

  const role = state.status === 'authenticated' ? state.user.role : undefined;
  const currentBranch = branches.find((branch) => branch.id === branchId);
  const branchName = currentBranch
    ? `${currentBranch.code} - ${currentBranch.name}`
    : 'Tất cả chi nhánh';

  return (
    <section className="orders-page" aria-labelledby="orders-title">
      <header className="orders-header">
        <h1 id="orders-title">Danh sách đơn hàng</h1>
        <p>
          {branchName} ·{' '}
          {role === 'BRANCH_MANAGER'
            ? 'Quản lý chi nhánh'
            : role === 'OWNER'
              ? 'Chủ sở hữu'
              : 'Thủ kho'}{' '}
          · Mới nhất trước
        </p>
      </header>

      <form className="orders-filters" onSubmit={apply}>
        <h2>Tìm kiếm và bộ lọc</h2>
        <div className="orders-filters__search">
          <Input
            label="Tìm đơn"
            placeholder="Mã đơn, mã đơn kênh hoặc số điện thoại khách"
            value={draft.q}
            error={fieldErrors.q}
            onChange={(event) => setDraft({ ...draft, q: event.target.value })}
          />
        </div>
        <div className="orders-filters__grid">
          <Select
            label="Kênh"
            placeholder="Tất cả kênh"
            value={draft.channel}
            error={fieldErrors.channel}
            options={[
              { value: 'POS', label: 'POS' },
              { value: 'WEB', label: 'WEB' },
              { value: 'MARKET', label: 'MARKET' },
            ]}
            onChange={(event) =>
              setDraft({ ...draft, channel: event.target.value as OrderFilters['channel'] })
            }
          />
          {canPickAll ? (
            <Select
              label="Chi nhánh"
              placeholder="Tất cả chi nhánh"
              value={draft.branchId ? String(draft.branchId) : ''}
              error={fieldErrors.branchId}
              options={branches.map((branch) => ({
                value: String(branch.id),
                label: `${branch.code} - ${branch.name}`,
              }))}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  branchId: event.target.value ? Number(event.target.value) : undefined,
                })
              }
            />
          ) : (
            <div className="orders-filters__fixed">
              <span>Chi nhánh</span>
              <strong>{branchName}</strong>
            </div>
          )}
          <div className="orders-status-filter">
            <span className="gm-field__label">Trạng thái</span>
            <details>
              <summary>
                {draft.status.length
                  ? draft.status.map((status) => STATUS_LABELS[status]).join(', ')
                  : 'Tất cả trạng thái'}
              </summary>
              <div className="orders-status-filter__options">
                {ORDER_STATUSES.map((status) => (
                  <label key={status}>
                    <input
                      type="checkbox"
                      checked={draft.status.includes(status)}
                      onChange={(event) => {
                        const next: OrderStatus[] = event.target.checked
                          ? [...draft.status, status]
                          : draft.status.filter((item) => item !== status);
                        setDraft({ ...draft, status: next });
                      }}
                    />
                    {STATUS_LABELS[status]}
                  </label>
                ))}
              </div>
            </details>
            {fieldErrors.status && (
              <p role="alert" className="orders-field-error">
                {fieldErrors.status}
              </p>
            )}
          </div>
          <Input
            label="Từ ngày"
            type="date"
            value={draft.from}
            error={dateError || fieldErrors.from}
            onChange={(event) => {
              setDateError('');
              setDraft({ ...draft, from: event.target.value });
            }}
          />
          <Input
            label="Đến ngày"
            type="date"
            value={draft.to}
            error={fieldErrors.to}
            onChange={(event) => {
              setDateError('');
              setDraft({ ...draft, to: event.target.value });
            }}
          />
        </div>
        <div className="orders-filters__actions">
          <Button type="submit">Áp dụng bộ lọc</Button>
          <Button type="button" variant="secondary" onClick={clear}>
            Xóa bộ lọc
          </Button>
          <span>Bộ lọc được giữ trên đường dẫn khi tải lại trang.</span>
        </div>
        {errorState?.kind === 'form' && (
          <p role="alert" className="orders-field-error">
            {errorState.message}
          </p>
        )}
      </form>

      {!hasFormError && (
        <div className="orders-results">
          <AsyncBlock
            isLoading={isLoading}
            error={error}
            isEmpty={!!data && data.content.length === 0}
            onRetry={retry}
            empty={{
              message: 'Chưa có đơn hàng',
              filtered: hasOrderFilters(applied),
              onClearFilters: clear,
            }}
            skeleton={<OrderTableSkeleton />}
          >
            {data && (
              <Table className="orders-table" columns={columns} data={data.content} rowKey="id" />
            )}
          </AsyncBlock>
          {!isLoading && !error && data && (
            <OrdersPagination
              page={applied.page}
              totalPages={data.totalPages}
              totalElements={data.totalElements}
              onPage={changePage}
            />
          )}
        </div>
      )}
    </section>
  );
}

export default function OrdersPage() {
  const [params] = useSearchParams();
  return <OrdersPageView key={params.toString()} />;
}
