import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { ApiError, listSyncLogs, retrySyncLog } from '../../api/client';
import { AsyncBlock, Button, Dialog, Select, Toast } from '../../components/ui';
import { getPageItems } from '../inventory/pagination';
import { useCatalogQuery } from '../catalog/useCatalogQuery';
import './admin.css';

type SyncLog = {
  id?: number;
  channelCode?: string;
  direction?: 'IN' | 'OUT';
  externalId?: string | null;
  status?: 'SUCCESS' | 'FAILED' | 'RETRYING';
  attempts?: number;
  error?: string | null;
  createdAt?: string;
};

type SyncStatus = 'SUCCESS' | 'FAILED' | 'RETRYING';

const STATUS_LABEL: Record<SyncStatus, string> = {
  SUCCESS: 'Thành công',
  FAILED: 'Thất bại',
  RETRYING: 'Đang gửi lại',
};

function readPage(params: URLSearchParams) {
  const raw = Number(params.get('page'));
  return Number.isInteger(raw) && raw > 0 ? raw : 1;
}

export default function AdminChannelsPage() {
  const [params, setParams] = useSearchParams();
  const rawChannel = params.get('channel') ?? params.get('channelCode');
  const channel = rawChannel === 'MARKET' ? 'MARKET' : rawChannel === 'WEB' ? 'WEB' : '';
  const statusParam = params.get('status');
  const status: SyncStatus | '' =
    statusParam === 'SUCCESS' || statusParam === 'FAILED' || statusParam === 'RETRYING'
      ? statusParam
      : '';
  const page = readPage(params);
  const [retryTarget, setRetryTarget] = useState<SyncLog | null>(null);
  const [retryingId, setRetryingId] = useState<number | null>(null);
  const [retryError, setRetryError] = useState('');
  const [blockedIds, setBlockedIds] = useState<Set<number>>(new Set());
  const [localStatuses, setLocalStatuses] = useState<Map<number, SyncStatus>>(new Map());
  const [retryBaselines, setRetryBaselines] = useState<
    Map<number, { status?: SyncStatus; externalId?: string | null; attempts?: number }>
  >(new Map());

  const load = useCallback(
    (signal: AbortSignal) =>
      listSyncLogs(
        {
          channelCode: channel || undefined,
          status: status || undefined,
          page: page - 1,
          size: 20,
        },
        signal,
      ),
    [channel, page, status],
  );
  const query = useCatalogQuery(`admin-sync:${channel}:${status}:${page}`, load);
  const data = query.data;
  const totalPages = data ? Math.ceil(data.totalElements / data.size) : 0;

  useEffect(() => {
    if (!query.pending && totalPages > 0 && page > totalPages) {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.delete('page');
          return next;
        },
        { replace: true },
      );
    }
  }, [page, query.pending, setParams, totalPages]);

  const updateFilter = (key: 'channel' | 'status', value: string) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set(key, value);
      else next.delete(key);
      next.delete('page');
      return next;
    });
  };

  const changePage = (nextPage: number) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      if (nextPage > 1) next.set('page', String(nextPage));
      else next.delete('page');
      return next;
    });
  };

  const confirmRetry = async () => {
    if (!retryTarget?.id || retryingId !== null) return;
    const id = retryTarget.id;
    setRetryingId(id);
    setRetryError('');
    try {
      await retrySyncLog(id);
      setLocalStatuses((prev) => new Map(prev).set(id, 'RETRYING'));
      setRetryBaselines((prev) =>
        new Map(prev).set(id, {
          status: retryTarget.status,
          externalId: retryTarget.externalId,
          attempts: retryTarget.attempts,
        }),
      );
      query.retry();
      setRetryTarget(null);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setBlockedIds((prev) => new Set(prev).add(id));
        setRetryError('Log không còn ở trạng thái FAILED hoặc đã được gửi lại.');
      } else {
        setRetryError(error instanceof ApiError ? error.message : 'Không thể gửi lại callback.');
      }
      setRetryTarget(null);
    } finally {
      setRetryingId(null);
    }
  };

  const pageItems = useMemo(() => getPageItems(page, totalPages), [page, totalPages]);

  return (
    <div className="admin-page">
      <header className="admin-page__heading">
        <div>
          <h1>Trạng thái đồng bộ kênh</h1>
          <p>Theo dõi callback nhận và gửi dữ liệu qua kênh bán hàng.</p>
        </div>
      </header>

      {retryError && (
        <Toast
          variant="error"
          title="Không thể gửi lại"
          description={retryError}
          onClose={() => setRetryError('')}
        />
      )}

      <section className="admin-card admin-filters" aria-label="Bộ lọc đồng bộ kênh">
        <Select
          label="Kênh"
          aria-label="Lọc theo kênh"
          value={channel}
          placeholder="Tất cả kênh"
          options={[
            { label: 'Tất cả kênh', value: '' },
            { label: 'Website', value: 'WEB' },
            { label: 'Sàn TMĐT', value: 'MARKET' },
          ]}
          onChange={(event) => updateFilter('channel', event.target.value)}
        />
        <Select
          label="Trạng thái"
          aria-label="Lọc theo trạng thái"
          value={status}
          placeholder="Tất cả trạng thái"
          options={[
            { label: 'Tất cả trạng thái', value: '' },
            { label: 'Thành công', value: 'SUCCESS' },
            { label: 'Thất bại', value: 'FAILED' },
            { label: 'Đang gửi lại', value: 'RETRYING' },
          ]}
          onChange={(event) => updateFilter('status', event.target.value)}
        />
      </section>

      <section className="admin-card" aria-labelledby="admin-sync-title">
        <div className="admin-card__head">
          <h2 id="admin-sync-title">Nhật ký đồng bộ</h2>
        </div>
        <AsyncBlock
          isLoading={query.pending}
          error={query.error}
          onRetry={query.retry}
          isEmpty={!!data && data.content.length === 0}
          empty={{
            message: 'Không có log đồng bộ phù hợp',
            filtered: !!channel || !!status,
            onClearFilters:
              channel || status
                ? () => {
                    setParams((prev) => {
                      const next = new URLSearchParams(prev);
                      next.delete('channel');
                      next.delete('channelCode');
                      next.delete('status');
                      next.delete('page');
                      return next;
                    });
                  }
                : undefined,
          }}
        >
          <div className="admin-sync-table" role="table" aria-label="Danh sách log đồng bộ">
            <div className="admin-sync-table__head" role="row">
              <span role="columnheader">Thời gian</span>
              <span role="columnheader">Kênh</span>
              <span role="columnheader">Chiều</span>
              <span role="columnheader">Mã ngoài</span>
              <span role="columnheader">Trạng thái</span>
              <span role="columnheader">Thử</span>
              <span role="columnheader">Hành động</span>
            </div>
            {data?.content.map((raw) => {
              const log = raw as SyncLog;
              const id = log.id;
              const baseline = id ? retryBaselines.get(id) : undefined;
              const responseChanged =
                !!baseline &&
                !!id &&
                (log.status !== baseline.status ||
                  log.externalId !== baseline.externalId ||
                  (log.attempts ?? 0) !== (baseline.attempts ?? 0));
              const serverAuthoritative =
                log.status === 'SUCCESS' || (log.status === 'FAILED' && responseChanged);
              const statusValue = id
                ? serverAuthoritative
                  ? log.status
                  : (localStatuses.get(id) ?? log.status)
                : log.status;
              const canRetry =
                statusValue === 'FAILED' &&
                id !== undefined &&
                !blockedIds.has(id) &&
                retryingId !== id;
              return (
                <div
                  className="admin-sync-row"
                  role="row"
                  key={id ?? `${log.externalId}-${log.createdAt}`}
                >
                  <span data-label="Thời gian">
                    {log.createdAt
                      ? new Intl.DateTimeFormat('vi-VN', {
                          dateStyle: 'short',
                          timeStyle: 'short',
                        }).format(new Date(log.createdAt))
                      : '—'}
                  </span>
                  <span data-label="Kênh">{log.channelCode || '—'}</span>
                  <span data-label="Chiều">
                    {log.direction === 'IN' ? 'Nhận' : log.direction === 'OUT' ? 'Gửi' : '—'}
                  </span>
                  <span data-label="Mã ngoài">{log.externalId || '—'}</span>
                  <span data-label="Trạng thái">
                    <span className={`admin-status admin-status--${statusValue?.toLowerCase()}`}>
                      {statusValue ? STATUS_LABEL[statusValue] : '—'}
                    </span>
                  </span>
                  <span data-label="Thử">{log.attempts ?? 0}</span>
                  <span data-label="Hành động">
                    {canRetry && (
                      <Button variant="secondary" onClick={() => setRetryTarget(log)}>
                        Gửi lại
                      </Button>
                    )}
                    {statusValue !== 'FAILED' && <span className="admin-no-action">—</span>}
                  </span>
                </div>
              );
            })}
          </div>

          {totalPages > 1 && (
            <nav className="admin-pagination" aria-label="Phân trang đồng bộ kênh">
              <Button
                variant="secondary"
                aria-label="Trang trước"
                disabled={page <= 1}
                onClick={() => changePage(page - 1)}
              >
                ‹
              </Button>
              {pageItems.map((item, index) =>
                item === 'ellipsis' ? (
                  <span key={`gap-${index}`}>…</span>
                ) : (
                  <Button
                    key={item}
                    variant="secondary"
                    aria-label={`Trang ${item}`}
                    aria-current={item === page ? 'page' : undefined}
                    onClick={() => changePage(item)}
                  >
                    {item}
                  </Button>
                ),
              )}
              <Button
                variant="secondary"
                aria-label="Trang sau"
                disabled={page >= totalPages}
                onClick={() => changePage(page + 1)}
              >
                ›
              </Button>
            </nav>
          )}
        </AsyncBlock>
      </section>

      <Dialog
        open={retryTarget !== null}
        title="Gửi lại callback?"
        confirmText="Gửi lại"
        cancelText="Hủy"
        busy={retryingId !== null}
        onClose={() => setRetryTarget(null)}
        onConfirm={() => void confirmRetry()}
      >
        Log #{retryTarget?.id} sẽ được đưa vào hàng đợi gửi lại.
      </Dialog>
    </div>
  );
}
