import { useCallback, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import AsyncBlock from '../../components/ui/tsx/AsyncBlock';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import Pagination from '../inventory/PaginationControl';
import { useDebouncedCallback } from '../inventory/useDebouncedCallback';
import { transferNewPath } from './paths';
import { listTransfers } from './transferApi';
import { canCreateTransfer } from './transferPermissions';
import {
  parseDateParam,
  parsePageParam,
  parseTransferStatus,
  toTransferQuery,
} from './transferQuery';
import type { TransferFilterStatus } from './transferStatus';
import TransferFilters from './TransferFilters';
import TransferTable, { TransferTableSkeleton } from './TransferTable';
import { useCurrentUser } from './useCurrentUser';
import { useRemote } from './useRemote';
import './Transfers.css';

const SEARCH_DEBOUNCE_MS = 300;

/** Danh sách phiếu chuyển kho (INV-04). */
export default function TransferListPage() {
  const user = useCurrentUser();
  const navigate = useNavigate();
  const { branchId } = useOperationsScope();
  const [params, setParams] = useSearchParams();

  const q = params.get('q') ?? '';
  const status = parseTransferStatus(params.get('status'));
  const from = parseDateParam(params.get('from'));
  const to = parseDateParam(params.get('to'));
  const page = parsePageParam(params.get('page'));
  const hasFilters = q !== '' || status !== undefined || from !== undefined || to !== undefined;
  const [dateRangeErrorState, setDateRangeErrorState] = useState({
    locationKey: params.toString(),
    message: '',
  });
  const urlDateRangeError = from && to && from > to ? 'Từ ngày phải trước hoặc bằng Đến ngày.' : '';
  const dateRangeError =
    urlDateRangeError ||
    (dateRangeErrorState.locationKey === params.toString() ? dateRangeErrorState.message : '');

  const [searchText, setSearchText] = useState(q);
  const { data, error, isLoading, reload } = useRemote(
    async (signal) => {
      if (from && to && from > to) {
        return {
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          content: [],
        };
      }
      return listTransfers(toTransferQuery({ branchId, q, status, from, to, page }), signal);
    },
    `${branchId ?? 'all'}|${q}|${status ?? ''}|${from ?? ''}|${to ?? ''}|${page}|${dateRangeError ? 'invalid-date' : ''}`,
  );
  const isInitialLoading = isLoading && data === undefined;
  const { showLoading } = useDelayedLoading(isInitialLoading);

  const update = useCallback(
    (
      patch: {
        q?: string;
        status?: TransferFilterStatus | undefined;
        from?: string | undefined;
        to?: string | undefined;
        page?: number;
      },
      replace = false,
    ) => {
      const nextFrom = 'from' in patch ? patch.from : from;
      const nextTo = 'to' in patch ? patch.to : to;
      if (nextFrom && nextTo && nextFrom > nextTo) {
        setDateRangeErrorState({
          locationKey: params.toString(),
          message: 'Từ ngày phải trước hoặc bằng Đến ngày.',
        });
        return;
      }
      setDateRangeErrorState({ locationKey: params.toString(), message: '' });
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          const set = (key: string, value: string | undefined) =>
            value ? next.set(key, value) : next.delete(key);
          if ('q' in patch) set('q', patch.q?.trim());
          if ('status' in patch) set('status', patch.status);
          if ('from' in patch) set('from', patch.from);
          if ('to' in patch) set('to', patch.to);
          // đổi bộ lọc thì về trang 1; chỉ đổi trang thì giữ nguyên bộ lọc
          set('page', patch.page && patch.page > 1 ? String(patch.page) : undefined);
          return next;
        },
        { replace },
      );
    },
    [from, params, setParams, to],
  );

  const search = useDebouncedCallback(
    (text: string) => update({ q: text }, true),
    SEARCH_DEBOUNCE_MS,
  );

  if (!user) return null;
  const canCreate = canCreateTransfer(user, branchId);
  const totalPages = data?.totalPages ?? 0;

  const clearFilters = () => {
    search.cancel();
    setSearchText('');
    setParams(new URLSearchParams());
  };

  return (
    <div className="ck-page">
      <header className="ck-header">
        <div>
          <h1>Chuyển kho</h1>
          <p>Điều chuyển hàng hóa giữa các chi nhánh</p>
        </div>
        {canCreate && (
          <Link className="gm-button gm-button--primary" to={transferNewPath}>
            + Tạo phiếu chuyển
          </Link>
        )}
      </header>

      <TransferFilters
        searchText={searchText}
        onSearchChange={(text) => {
          setSearchText(text);
          search.call(text);
        }}
        status={status}
        onStatusChange={(next) => update({ status: next })}
        from={from}
        to={to}
        onDatesChange={(f, t) => update({ from: f, to: t })}
        errors={{
          ...(dateRangeError ? { from: dateRangeError, to: dateRangeError } : {}),
        }}
      />

      <section className="ck-card" aria-labelledby="ck-list-title" aria-busy={isLoading}>
        <div className="ck-card__head">
          <h2 id="ck-list-title">Danh sách phiếu chuyển kho</h2>
          {data && <span className="ck-result-count">{data.totalElements} phiếu</span>}
          {isInitialLoading && showLoading && (
            <span className="ck-loading" role="status">
              Đang tải dữ liệu…
            </span>
          )}
        </div>
        <AsyncBlock
          isLoading={isInitialLoading}
          error={error}
          isEmpty={!!data && data.content.length === 0}
          onRetry={reload}
          empty={{
            message: 'Chưa có phiếu chuyển kho',
            description:
              'Phiếu chuyển kho sẽ hiển thị tại đây khi có phát sinh. Bắt đầu bằng cách tạo phiếu chuyển đầu tiên.',
            filtered: hasFilters,
            onClearFilters: clearFilters,
            ...(canCreate
              ? { actionLabel: '+ Tạo phiếu chuyển', onAction: () => navigate(transferNewPath) }
              : {}),
          }}
          skeleton={<TransferTableSkeleton />}
        >
          <TransferTable items={data?.content ?? []} user={user} />
        </AsyncBlock>

        {!error && totalPages > 1 && (
          <div className="ck-card__foot">
            <Pagination
              page={page}
              totalPages={totalPages}
              onChange={(p) => update({ q, status, from, to, page: p })}
            />
          </div>
        )}
      </section>
    </div>
  );
}
