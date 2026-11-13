import type { ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { AsyncBlock, ErrorState, Skeleton } from '../../components/ui';
import { mapErrorToState } from '../../lib/errors';
import SessionExpiredPage from '../../pages/SessionExpiredPage';

export function CatalogFailure({ error, retry }: { error: unknown; retry: () => void }) {
  const navigate = useNavigate();
  const location = useLocation();
  // Danh sách lỗi chỉ chặn vùng dữ liệu; bộ lọc và vùng khác vẫn hoạt động.
  const state = mapErrorToState(error, 'block');
  if (state.kind === 'page') {
    switch (state.page) {
      case 'session-expired':
        return (
          <SessionExpiredPage
            onLogin={() =>
              navigate('/login', {
                replace: true,
                state: { from: location, reason: 'session-expired' },
              })
            }
          />
        );
    }
  }
  return (
    <ErrorState
      message={state.message ?? 'Không thể tải dữ liệu.'}
      traceId={'traceId' in state ? state.traceId : undefined}
      onRetry={retry}
    />
  );
}

export default function CatalogStates({
  pending,
  error,
  empty,
  filtered,
  retry,
  clear,
  children,
}: {
  pending: boolean;
  error?: unknown;
  empty: boolean;
  filtered: boolean;
  retry: () => void;
  clear: () => void;
  children: ReactNode;
}) {
  // #228 AsyncBlock hiển thị children trong 300 ms đầu. Tránh render bảng rỗng
  // trước khi API trả về để không báo nhầm trạng thái không có dữ liệu.
  return (
    <AsyncBlock
      isLoading={pending}
      error={null}
      isEmpty={!pending && !error && empty}
      onRetry={retry}
      skeleton={<Skeleton lines={3} height={48} />}
      empty={{ message: 'Chưa có dữ liệu', filtered, onClearFilters: clear }}
    >
      {pending ? (
        <div aria-busy="true" className="catalog__pending" />
      ) : error ? (
        <CatalogFailure error={error} retry={retry} />
      ) : (
        children
      )}
    </AsyncBlock>
  );
}
