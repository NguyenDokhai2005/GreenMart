import { useLocation, useNavigate } from 'react-router';
import { APP_PATHS } from '../auth/routePolicy';
import { useActiveBranch } from '../auth/useActiveBranch';
import ForbiddenPage from '../../pages/ForbiddenPage';
import NotFoundPage from '../../pages/NotFoundPage';
import OfflinePage from '../../pages/OfflinePage';
import ServerErrorPage from '../../pages/ServerErrorPage';
import SessionExpiredPage from '../../pages/SessionExpiredPage';
import type { UiErrorState } from '../../lib/errors';

type PageError = Extract<UiErrorState, { kind: 'page' }>;

interface StockDocumentPageErrorProps {
  state: PageError;
  onRetry: () => void;
}

export default function StockDocumentPageError({ state, onRetry }: StockDocumentPageErrorProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const branch = useActiveBranch();
  const backToList = () => navigate(APP_PATHS.stockDocuments, { replace: true });

  switch (state.page) {
    case 'not-found':
      return <NotFoundPage onHome={backToList} />;
    case 'forbidden':
      return (
        <ForbiddenPage
          onHome={backToList}
          onSwitchBranch={() => {
            branch.clearBranch();
            navigate(APP_PATHS.selectBranch);
          }}
          message={state.message}
        />
      );
    case 'server-error':
      return <ServerErrorPage traceId={state.traceId} onRetry={onRetry} />;
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
  }
}
