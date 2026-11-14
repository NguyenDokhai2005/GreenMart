import type { TransferStatus } from './transferApi';
import { TRANSFER_STATUS_LABEL, TRANSFER_STATUS_TONE } from './transferStatus';

export default function TransferStatusBadge({ status }: { status: TransferStatus }) {
  return (
    <span className={`ck-badge ck-badge--${TRANSFER_STATUS_TONE[status]}`}>
      {TRANSFER_STATUS_LABEL[status]}
    </span>
  );
}
