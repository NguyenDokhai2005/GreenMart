import { Link, useLocation, useParams } from 'react-router';
import AsyncBlock from '../../components/ui/tsx/AsyncBlock';
import Skeleton from '../../components/ui/tsx/Skeleton';
import { useBreadcrumbTail } from '../../layouts/operations/useBreadcrumbTail';
import { TRANSFERS_PATH } from './paths';
import { getTransfer } from './transferApi';
import TransferDetail from './TransferDetail';
import { useCurrentUser } from './useCurrentUser';
import { useRemote } from './useRemote';
import './Transfers.css';

function readFlash(state: unknown): 'created' | 'updated' | undefined {
  const flash =
    typeof state === 'object' && state !== null ? (state as { flash?: unknown }).flash : undefined;
  return flash === 'created' || flash === 'updated' ? flash : undefined;
}

/** Trang chi tiết phiếu chuyển kho: /operations/transfers/:id */
export default function TransferDetailPage() {
  const user = useCurrentUser();
  const docId = Number(useParams().id);
  const location = useLocation();
  const {
    data: doc,
    error,
    isLoading,
    reload,
  } = useRemote((signal) => getTransfer(docId, signal), `doc|${docId}`);
  useBreadcrumbTail(doc?.code);

  if (!user) return null;

  return (
    <div className="ck-page">
      {!doc && (
        <header className="ck-header">
          <h1>Phiếu chuyển kho</h1>
          <Link className="gm-button gm-button--secondary" to={TRANSFERS_PATH}>
            Quay lại danh sách
          </Link>
        </header>
      )}
      <AsyncBlock
        isLoading={isLoading}
        error={error}
        onRetry={reload}
        skeleton={
          <div className="ck-columns">
            <Skeleton lines={5} height={28} />
            <Skeleton lines={6} height={28} />
          </div>
        }
      >
        {doc ? (
          <TransferDetail
            key={doc.id}
            doc={doc}
            user={user}
            onChanged={reload}
            flash={readFlash(location.state)}
          />
        ) : null}
      </AsyncBlock>
    </div>
  );
}
