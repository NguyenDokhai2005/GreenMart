import type { ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { AsyncBlock, ErrorState, Skeleton } from '../../components/ui';
import { mapErrorToState } from '../../lib/errors';
import SessionExpiredPage from '../../pages/SessionExpiredPage';

export function CatalogFailure({ error, retry }: { error: unknown; retry: () => void }) {
  const navigate = useNavigate();
  const location = useLocation();
  // A failed list is a block failure: filters and the other blocks remain usable.
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
  // #228 AsyncBlock renders children during the first 300ms. Never pass an empty
  // Table then: it would announce an empty response before any API has completed.
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
