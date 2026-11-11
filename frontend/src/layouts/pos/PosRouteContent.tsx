import { useEffect, useState, type ComponentType } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { ErrorState, PageLoader } from '../../components/ui';
import { APP_PATHS } from '../../features/auth/routePolicy';
import { useActiveBranch } from '../../features/auth/useActiveBranch';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { mapErrorToState, TIMEOUT_MESSAGE } from '../../lib/errors';
import ForbiddenPage from '../../pages/ForbiddenPage';
import NotFoundPage from '../../pages/NotFoundPage';
import OfflinePage from '../../pages/OfflinePage';
import ServerErrorPage from '../../pages/ServerErrorPage';
import SessionExpiredPage from '../../pages/SessionExpiredPage';
import PosErrorBoundary from './PosErrorBoundary';
import type { PosPageProps } from './PosPlaceholderPage';
import type { PosNavigationItem } from './posNavigation';

export type PosPageLoader = (
  item: PosNavigationItem,
) => Promise<{ default: ComponentType<PosPageProps> }>;
const loadPlaceholder: PosPageLoader = () => import('./PosPlaceholderPage');

function PosPageError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const navigate = useNavigate();
  const location = useLocation();
  const branch = useActiveBranch();
  const state = mapErrorToState(error, 'page');
  const onHome = () => navigate(APP_PATHS.pos);

  if (state.kind === 'page') {
    switch (state.page) {
      case 'not-found':
        return <NotFoundPage onHome={onHome} />;
      case 'forbidden':
        return (
          <ForbiddenPage
            onHome={onHome}
            onSwitchBranch={() => {
              branch.clearBranch();
              navigate(APP_PATHS.selectBranch, { state: { from: location } });
            }}
            message={state.message}
          />
        );
      case 'offline':
        return <OfflinePage onReconnect={onRetry} />;
      case 'session-expired':
        return (
          <SessionExpiredPage
            onLogin={() =>
              navigate(APP_PATHS.login, {
                replace: true,
                state: { from: location, reason: 'session-expired' },
              })
            }
          />
        );
      case 'server-error':
        return <ServerErrorPage traceId={state.traceId} onRetry={onRetry} />;
    }
  }
  return <ErrorState message={state.message} onRetry={onRetry} />;
}

function LoadedPage({
  item,
  loadPage,
  onRetry,
}: {
  item: PosNavigationItem;
  loadPage: PosPageLoader;
  onRetry: () => void;
}) {
  const [result, setResult] = useState<
    | { status: 'loading' }
    | { status: 'ready'; Page: ComponentType<PosPageProps> }
    | { status: 'error'; error: unknown }
  >({ status: 'loading' });
  const { showLoading, timedOut } = useDelayedLoading(result.status === 'loading');

  useEffect(() => {
    let active = true;
    // Promise.resolve also catches synchronous loader failures without replacing the shell.
    void Promise.resolve()
      .then(() => loadPage(item))
      .then(
        ({ default: Page }) => active && setResult({ status: 'ready', Page }),
        (error: unknown) => active && setResult({ status: 'error', error }),
      );
    return () => {
      active = false;
    };
  }, [item, loadPage]);

  useEffect(() => {
    if (result.status === 'ready') {
      document.querySelector<HTMLElement>('[data-pos-heading]')?.focus();
    }
  }, [result]);

  if (result.status === 'loading') {
    if (timedOut) return <ErrorState message={TIMEOUT_MESSAGE} onRetry={onRetry} />;
    return showLoading ? <PageLoader label="Đang tải nội dung…" /> : null;
  }
  if (result.status === 'error') return <PosPageError error={result.error} onRetry={onRetry} />;
  return <result.Page item={item} />;
}

export default function PosRouteContent({
  item,
  loadPage = loadPlaceholder,
}: {
  item: PosNavigationItem;
  loadPage?: PosPageLoader;
}) {
  const [attempt, setAttempt] = useState(0);
  const onRetry = () => setAttempt((current) => current + 1);
  return (
    <PosErrorBoundary
      key={`${item.path}:${attempt}`}
      fallback={(error) => <PosPageError error={error} onRetry={onRetry} />}
    >
      <LoadedPage item={item} loadPage={loadPage} onRetry={onRetry} />
    </PosErrorBoundary>
  );
}
