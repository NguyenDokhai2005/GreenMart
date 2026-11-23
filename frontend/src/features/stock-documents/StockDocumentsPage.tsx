import { useCallback, useEffect, useState } from 'react';
import { Outlet, useLocation, useSearchParams } from 'react-router';
import type { CurrentUser } from '../../api/client';
import { AsyncBlock, Button, Toast } from '../../components/ui';
import { mapErrorToState } from '../../lib/errors';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import Pagination from '../inventory/PaginationControl';
import { useDebouncedCallback } from '../inventory/useDebouncedCallback';
import { useAuth } from '../auth/useAuth';
import type { StockDocument } from './stockDocumentApi';
import { listStockDocuments } from './stockDocumentApi';
import type { StockDocumentFiltersValue } from './StockDocumentFilters';
import StockDocumentFilters from './StockDocumentFilters';
import { StockDocumentTableSkeleton } from './StockDocumentTable';
import StockDocumentTable from './StockDocumentTable';
import StockDocumentFormDialog from './StockDocumentFormDialog';
import StockDocumentPageError from './StockDocumentPageError';
import { canCreateStockDocument } from './stockDocumentPermissions';
import { parseStockDocumentStatus } from './stockDocumentPresentation';
import { useRemote } from './useRemote';
import './StockDocuments.css';

const PAGE_SIZE = 20;

export interface StockDocumentOutletContext {
  user: CurrentUser;
  onChanged: () => void;
  onEdit: (document: StockDocument) => void;
}

function parsePage(value: string | null): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 1;
}

function parseDate(value: string | null): string | undefined {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
    ? value
    : undefined;
}

export default function StockDocumentsPage() {
  const auth = useAuth();
  const user = auth.state.status === 'authenticated' ? auth.state.user : null;
  const { branchId } = useOperationsScope();
  const location = useLocation();
  const [params, setParams] = useSearchParams();
  const type = params.get('type') === 'EXPORT' ? 'EXPORT' : 'IMPORT';
  const status = parseStockDocumentStatus(params.get('status'));
  const q = params.get('q') ?? '';
  const from = parseDate(params.get('from'));
  const to = parseDate(params.get('to'));
  const page = parsePage(params.get('page'));
  const [searchDraft, setSearchDraft] = useState({ locationKey: location.key, value: q });
  const searchText = searchDraft.locationKey === location.key ? searchDraft.value : q;
  const [formType, setFormType] = useState<'IMPORT' | 'EXPORT' | null>(null);
  const [editingDocument, setEditingDocument] = useState<StockDocument | null>(null);
  const [success, setSuccess] = useState('');
  const [dateRangeError, setDateRangeError] = useState('');
  const queryKey = `${branchId ?? 'all'}|${type}|${status ?? ''}|${q}|${from ?? ''}|${to ?? ''}|${page}`;
  const { data, error, isLoading, reload } = useRemote(
    (signal) =>
      listStockDocuments(
        {
          branchId,
          type,
          status: status ? [status] : undefined,
          q: q.trim() || undefined,
          from,
          to,
          page: page - 1,
          size: PAGE_SIZE,
          sort: 'createdAt,desc',
        },
        signal,
      ),
    queryKey,
  );
  const hasFilters = !!(status || q || from || to);
  const canCreate = user ? canCreateStockDocument(user, branchId) : false;
  const formTypeToRender = editingDocument
    ? editingDocument.type === 'IMPORT' || editingDocument.type === 'EXPORT'
      ? editingDocument.type
      : null
    : formType;

  useEffect(() => {
    if (!success) return;
    const timer = globalThis.setTimeout(() => setSuccess(''), 6_000);
    return () => globalThis.clearTimeout(timer);
  }, [success]);

  const update = useCallback(
    (
      patch: {
        type?: 'IMPORT' | 'EXPORT';
        status?: typeof status;
        q?: string;
        from?: string;
        to?: string;
        page?: number;
      },
      replace = false,
    ) => {
      const nextFrom = 'from' in patch ? patch.from : from;
      const nextTo = 'to' in patch ? patch.to : to;
      if (nextFrom && nextTo && nextFrom > nextTo) {
        setDateRangeError('Từ ngày phải trước hoặc bằng Đến ngày.');
        return;
      }
      setDateRangeError('');
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          const set = (key: string, value: string | undefined) =>
            value ? next.set(key, value) : next.delete(key);
          if ('type' in patch) set('type', patch.type);
          if ('status' in patch) set('status', patch.status);
          if ('q' in patch) set('q', patch.q?.trim());
          if ('from' in patch) set('from', patch.from);
          if ('to' in patch) set('to', patch.to);
          set('page', patch.page && patch.page > 1 ? String(patch.page) : undefined);
          return next;
        },
        { replace },
      );
    },
    [from, setParams, to],
  );
  const debouncedSearch = useDebouncedCallback((value: string) => update({ q: value }, true), 300);

  if (!user) return null;

  const clearFilters = () => {
    debouncedSearch.cancel();
    setSearchDraft({ locationKey: location.key, value: '' });
    setParams(new URLSearchParams({ type }));
  };
  const currentFilter: StockDocumentFiltersValue = { status, from, to };
  const pageError = error ? mapErrorToState(error, 'page') : undefined;
  const filterError = error ? mapErrorToState(error, 'block') : undefined;

  if (pageError?.kind === 'page') {
    return <StockDocumentPageError state={pageError} onRetry={reload} />;
  }

  return (
    <div className="sd-page">
      <header className="sd-header">
        <div>
          <p className="sd-breadcrumb">Kho / Phiếu nhập - xuất</p>
          <h1>{type === 'IMPORT' ? 'Phiếu nhập kho' : 'Phiếu xuất kho'}</h1>
          <p>
            {type === 'IMPORT'
              ? 'Quản lý các phiếu nhập hàng.'
              : 'Quản lý các phiếu xuất hủy hàng.'}
          </p>
        </div>
        {canCreate && (
          <div className="sd-header-actions">
            <Button onClick={() => setFormType(type)}>
              {type === 'IMPORT' ? '+ Tạo phiếu nhập' : '+ Tạo phiếu xuất'}
            </Button>
          </div>
        )}
      </header>

      <div className="sd-document-switch" role="group" aria-label="Chọn loại phiếu kho">
        <button
          type="button"
          className={
            type === 'IMPORT' ? 'sd-document-tab sd-document-tab--active' : 'sd-document-tab'
          }
          aria-pressed={type === 'IMPORT'}
          onClick={() => update({ type: 'IMPORT' })}
        >
          Phiếu nhập
        </button>
        <button
          type="button"
          className={
            type === 'EXPORT' ? 'sd-document-tab sd-document-tab--active' : 'sd-document-tab'
          }
          aria-pressed={type === 'EXPORT'}
          onClick={() => update({ type: 'EXPORT' })}
        >
          Phiếu xuất
        </button>
      </div>

      {success && <Toast variant="success" title={success} onClose={() => setSuccess('')} />}

      <StockDocumentFilters
        searchText={searchText}
        value={currentFilter}
        errors={{
          ...(filterError?.kind === 'form' ? filterError.fieldErrors : {}),
          ...(dateRangeError ? { from: dateRangeError, to: dateRangeError } : {}),
        }}
        onSearchChange={(value) => {
          setSearchDraft({ locationKey: location.key, value });
          debouncedSearch.call(value);
        }}
        onChange={(value) => update({ ...value })}
      />

      <section className="sd-card" aria-label="Danh sách phiếu kho" aria-busy={isLoading}>
        <div className="sd-card-heading">
          <h2>{type === 'IMPORT' ? 'Danh sách phiếu nhập' : 'Danh sách phiếu xuất'}</h2>
          {data && <span className="sd-result-count">{data.totalElements} phiếu</span>}
        </div>
        <AsyncBlock
          isLoading={isLoading}
          error={error}
          isEmpty={!isLoading && !!data && data.content.length === 0}
          onRetry={reload}
          empty={{
            message: hasFilters
              ? 'Không tìm thấy phiếu phù hợp'
              : type === 'IMPORT'
                ? 'Chưa có phiếu nhập'
                : 'Chưa có phiếu xuất',
            description: hasFilters
              ? 'Thử thay đổi hoặc xóa bộ lọc để xem thêm phiếu.'
              : type === 'IMPORT'
                ? 'Các phiếu nhập hàng sẽ hiển thị tại đây.'
                : 'Các phiếu xuất hủy sẽ hiển thị tại đây.',
            filtered: hasFilters,
            onClearFilters: clearFilters,
            ...(canCreate
              ? {
                  actionLabel: type === 'IMPORT' ? '+ Tạo phiếu nhập' : '+ Tạo phiếu xuất',
                  onAction: () => setFormType(type),
                }
              : {}),
          }}
          skeleton={<StockDocumentTableSkeleton showBranch={branchId === undefined} />}
        >
          {data && data.content.length > 0 ? (
            <StockDocumentTable
              items={data.content}
              showBranch={branchId === undefined}
              search={location.search}
            />
          ) : null}
        </AsyncBlock>
        {!isLoading && !error && data && data.totalPages > 1 && (
          <div className="sd-card-footer">
            <Pagination
              page={page}
              totalPages={data.totalPages}
              onChange={(nextPage) => update({ page: nextPage })}
            />
          </div>
        )}
      </section>

      <Outlet
        context={
          {
            user,
            onChanged: reload,
            onEdit: setEditingDocument,
          } satisfies StockDocumentOutletContext
        }
      />

      {formTypeToRender && (
        <StockDocumentFormDialog
          type={formTypeToRender}
          branchId={editingDocument?.branch.id ?? branchId}
          document={editingDocument ?? undefined}
          onClose={() => {
            setFormType(null);
            setEditingDocument(null);
          }}
          onSaved={(document: StockDocument) => {
            setFormType(null);
            setEditingDocument(null);
            setSuccess(`Đã lưu phiếu nháp ${document.code}.`);
            reload();
          }}
        />
      )}

      {!canCreate && user.role === 'OWNER' && (
        <p className="sd-readonly-note">
          Bạn có thể xem phiếu kho; việc lập phiếu được thực hiện tại chi nhánh bởi thủ kho hoặc QL
          chi nhánh.
        </p>
      )}
    </div>
  );
}
