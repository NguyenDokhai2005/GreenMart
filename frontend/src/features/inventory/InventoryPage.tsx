import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import AsyncBlock from '../../components/ui/tsx/AsyncBlock';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import { APP_PATHS } from '../auth/routePolicy';
import InventoryFilters from './InventoryFilters';
import InventoryTable, { InventoryTableSkeleton } from './InventoryTable';
import { parsePage, parseStatus } from './inventoryQuery';
import type { StatusFilter } from './inventoryQuery';
import InventoryPagination from './InventoryPagination';
import { useDebouncedCallback } from './useDebouncedCallback';
import { useInventoryList } from './useInventoryList';
import './InventoryPage.css';

const SEARCH_DEBOUNCE_MS = 300;

/** Màn tồn kho (INV-01): tồn thực, giữ chỗ, khả dụng và cảnh báo theo sản phẩm × chi nhánh. */
export default function InventoryPage() {
  const { branchId, scope, locked } = useOperationsScope();
  const [params, setParams] = useSearchParams();

  const q = params.get('q') ?? '';
  const status = parseStatus(params.get('status'));
  const page = parsePage(params.get('page'));
  const hasFilters = q !== '' || status !== undefined;

  const [searchText, setSearchText] = useState(q);
  const { data, error, isLoading, retry } = useInventoryList({ branchId, q, status, page });
  const { showLoading } = useDelayedLoading(isLoading);

  const update = useCallback(
    (patch: { q?: string; status?: StatusFilter; page?: number }, replace = false) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          const set = (key: string, value: string | undefined) =>
            value ? next.set(key, value) : next.delete(key);
          if ('q' in patch) set('q', patch.q?.trim());
          if ('status' in patch) set('status', patch.status);
          // đổi bộ lọc thì về trang 1; chỉ đổi trang thì giữ nguyên bộ lọc
          set('page', patch.page && patch.page > 1 ? String(patch.page) : undefined);
          return next;
        },
        { replace },
      );
    },
    [setParams],
  );

  const search = useDebouncedCallback(
    (text: string) => update({ q: text }, true),
    SEARCH_DEBOUNCE_MS,
  );

  const clearFilters = () => {
    search.cancel();
    setSearchText('');
    setParams(new URLSearchParams());
  };

  // Đang ở trang vượt quá tổng số trang (vd đổi chi nhánh): quay về trang 1.
  const totalPages = data?.totalPages ?? 0;
  useEffect(() => {
    if (!isLoading && totalPages > 0 && page > totalPages) update({ page: 1 }, true);
  }, [isLoading, totalPages, page, update]);

  const showBranch = branchId === undefined;
  const where = locked
    ? 'tại chi nhánh được giao'
    : scope.kind === 'all'
      ? 'của tất cả chi nhánh'
      : 'tại chi nhánh đang chọn';

  return (
    <div className="inv-page">
      <header className="inv-header">
        <div>
          <h1>Tồn kho theo chi nhánh</h1>
          <p>Tra cứu tồn thực, giữ chỗ và khả dụng {where}.</p>
        </div>
        <Link className="inv-link" to={`${APP_PATHS.operations}/ledger`}>
          <svg
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M12 6c-2-1.5-5-2-8-2v14c3 0 6 .5 8 2 2-1.5 5-2 8-2V4c-3 0-6 .5-8 2zM12 6v14" />
          </svg>
          <span>Xem sổ giao dịch</span>
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M9 6l6 6-6 6" />
          </svg>
        </Link>
      </header>

      <InventoryFilters
        searchText={searchText}
        onSearchChange={(text) => {
          setSearchText(text);
          search.call(text);
        }}
        status={status}
        onStatusChange={(next) => update({ status: next })}
      />

      <section className="inv-card inv-list" aria-labelledby="inv-list-title">
        <div className="inv-list__head">
          <h2 id="inv-list-title">Danh sách tồn kho</h2>
          {isLoading && showLoading && (
            <span className="inv-loading" role="status">
              <span className="inv-spinner" aria-hidden="true" />
              Đang tải dữ liệu…
            </span>
          )}
        </div>

        <AsyncBlock
          isLoading={isLoading}
          error={error}
          isEmpty={!!data && data.content.length === 0}
          onRetry={retry}
          empty={{
            message: 'Chưa có dữ liệu tồn kho',
            description: 'Dữ liệu hiển thị tại đây khi có phát sinh.',
            filtered: hasFilters,
            onClearFilters: clearFilters,
          }}
          skeleton={<InventoryTableSkeleton showBranch={showBranch} />}
        >
          <InventoryTable items={data?.content ?? []} showBranch={showBranch} />
        </AsyncBlock>

        {!error && totalPages > 1 && (
          <div className="inv-list__foot">
            <InventoryPagination
              page={page}
              totalPages={totalPages}
              onChange={(p) => update({ q, status, page: p })}
            />
          </div>
        )}
      </section>

      <p className="inv-note">
        Khả dụng = tồn thực − giữ chỗ. Hết hàng: khả dụng = 0; tồn thấp: 0 &lt; khả dụng ≤ ROP (điểm
        đặt hàng lại).
        <br />
        Không sửa tồn trực tiếp. Xem sổ giao dịch để tra cứu biến động tồn kho.
      </p>
    </div>
  );
}
