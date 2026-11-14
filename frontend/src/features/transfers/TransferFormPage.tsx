import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import type { BranchRef } from '../../api/client';
import AsyncBlock from '../../components/ui/tsx/AsyncBlock';
import Button from '../../components/ui/tsx/Button';
import EmptyState from '../../components/ui/tsx/EmptyState';
import Input from '../../components/ui/tsx/Input';
import Skeleton from '../../components/ui/tsx/Skeleton';
import { Toast } from '../../components/ui';
import { useBreadcrumbTail } from '../../layouts/operations/useBreadcrumbTail';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import { formatDateTime } from './formatDate';
import { TRANSFERS_PATH, transferDetailPath } from './paths';
import {
  createTransfer,
  getStockItem,
  getTransfer,
  listActiveBranches,
  updateTransfer,
} from './transferApi';
import { canCreateTransfer, canEditOrCancel } from './transferPermissions';
import { CREATE_STEP_STATES } from './transferStatus';
import { mapBackendFields, toPayloadItems, validateTransferForm } from './transferForm';
import type { DraftItem, TransferFormErrors } from './transferForm';
import TransferItemsEditor from './TransferItemsEditor';
import TransferStepper from './TransferStepper';
import { useAction } from './useAction';
import { useCurrentUser } from './useCurrentUser';
import { useRemote } from './useRemote';
import './Transfers.css';

interface SubmitPayload {
  toBranchId: number;
  note?: string;
  items: { productId: number; quantity: number }[];
}

interface TransferFormProps {
  title: string;
  subtitle: string;
  source: BranchRef;
  creatorName: string;
  createdAtIso: string;
  submitLabel: string;
  initial?: { toBranchId?: number; note: string; items: DraftItem[] };
  onSubmit: (payload: SubmitPayload) => Promise<{ id: number }>;
  flash: 'created' | 'updated';
  backTo: string;
}

function TransferForm({
  title,
  subtitle,
  source,
  creatorName,
  createdAtIso,
  submitLabel,
  initial,
  onSubmit,
  flash,
  backTo,
}: TransferFormProps) {
  const navigate = useNavigate();
  const branches = useRemote(listActiveBranches, 'branches');
  const [toBranchId, setToBranchId] = useState(
    initial?.toBranchId ? String(initial.toBranchId) : '',
  );
  const [note, setNote] = useState(initial?.note ?? '');
  const [items, setItems] = useState<DraftItem[]>(initial?.items ?? []);
  const [errors, setErrors] = useState<TransferFormErrors>({ rows: {} });
  const submit = useAction();

  // Phiếu đang sửa: tồn khả dụng của các dòng có sẵn chưa biết, tải từ kho gửi.
  const initialIds = (initial?.items ?? []).map((i) => i.productId);
  const stock = useRemote(
    async (signal) =>
      new Map(
        await Promise.all(
          initialIds.map((id) =>
            getStockItem(source.id, id, signal).then(
              (r) => [id, r.available] as const,
              () => [id, undefined] as const,
            ),
          ),
        ),
      ),
    `stock|${source.id}|${initialIds.join(',')}`,
  );
  const availableOf = (item: DraftItem) => item.available ?? stock.data?.get(item.productId);

  const destinations = (branches.data ?? []).filter((b) => b.id !== source.id);

  const clearRowError = (productId: number) =>
    setErrors((prev) => {
      const rows = { ...prev.rows };
      delete rows[productId];
      return { ...prev, rows };
    });

  async function handleSubmit() {
    const result = validateTransferForm({ toBranchId, items }, availableOf);
    setErrors({ toBranch: result.toBranch, items: result.items, rows: result.rows });
    if (!result.valid) return;

    const created = await submit.run(() =>
      onSubmit({
        toBranchId: Number(toBranchId),
        note: note.trim() || undefined,
        items: toPayloadItems(items),
      }),
    );
    if (created) {
      navigate(transferDetailPath(created.id), { state: { flash }, replace: true });
    }
  }

  // 400 từ backend: báo dưới từng ô theo `details`, dữ liệu đang nhập vẫn giữ nguyên.
  const backend = submit.error ? mapBackendFields(submit.error.fields, items) : { rows: {} };
  const rowErrors = { ...backend.rows, ...errors.rows };
  const toBranchError = errors.toBranch ?? backend.toBranch;

  return (
    <div className="ck-page">
      <header className="ck-header">
        <div>
          <h1>{title}</h1>
          <p>{subtitle}</p>
        </div>
        <Link className="gm-button gm-button--secondary" to={backTo}>
          Quay lại danh sách
        </Link>
      </header>

      <TransferStepper states={CREATE_STEP_STATES} />

      {submit.error && (
        <Toast
          variant="error"
          title="Không thể lưu phiếu chuyển kho"
          description={submit.error.message}
        />
      )}

      <AsyncBlock
        isLoading={branches.isLoading}
        error={branches.error}
        onRetry={branches.reload}
        skeleton={
          <div className="ck-columns">
            <Skeleton lines={5} height={28} />
            <Skeleton lines={6} height={28} />
          </div>
        }
      >
        <div className="ck-columns">
          <TransferItemsEditor
            sourceId={source.id}
            items={items}
            availableOf={availableOf}
            rowErrors={rowErrors}
            listError={errors.items}
            onAdd={(item) => {
              setItems((prev) => [
                ...prev,
                {
                  productId: item.productId,
                  sku: item.sku,
                  productName: item.productName,
                  unit: item.unit,
                  quantity: '1',
                  available: item.available,
                },
              ]);
              setErrors((prev) => ({ ...prev, items: undefined }));
            }}
            onQuantityChange={(productId, quantity) => {
              setItems((prev) =>
                prev.map((i) => (i.productId === productId ? { ...i, quantity } : i)),
              );
              clearRowError(productId);
            }}
            onRemove={(productId) => {
              setItems((prev) => prev.filter((i) => i.productId !== productId));
              clearRowError(productId);
            }}
          />

          <section className="ck-card ck-general" aria-labelledby="ck-general-title">
            <h2 id="ck-general-title">Thông tin chung</h2>
            <Input
              label="Kho gửi"
              value={`${source.code} (tự động)`}
              readOnly
              className="ck-readonly"
            />

            <div className="gm-field">
              <label className="gm-field__label" htmlFor="ck-to-branch">
                Kho nhận{' '}
                <span className="gm-field__required" aria-hidden="true">
                  *
                </span>
              </label>
              <select
                id="ck-to-branch"
                className="ck-select ck-select--full"
                value={toBranchId}
                aria-invalid={toBranchError ? true : undefined}
                aria-describedby={toBranchError ? 'ck-to-branch-error' : undefined}
                onChange={(e) => {
                  setToBranchId(e.target.value);
                  setErrors((prev) => ({ ...prev, toBranch: undefined }));
                }}
              >
                <option value="">Chọn chi nhánh đích</option>
                {destinations.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.code} {b.name}
                  </option>
                ))}
              </select>
              {toBranchError && (
                <p id="ck-to-branch-error" className="gm-field__error" role="alert">
                  {toBranchError}
                </p>
              )}
              {destinations.length === 0 && (
                <p className="gm-field__helper">
                  Chưa có chi nhánh đích khả dụng cho tài khoản này.
                </p>
              )}
            </div>

            <Input
              label="Ngày tạo"
              value={formatDateTime(createdAtIso)}
              readOnly
              className="ck-readonly"
            />
            <Input label="Người tạo phiếu" value={creatorName} readOnly className="ck-readonly" />

            <div className="gm-field">
              <label className="gm-field__label" htmlFor="ck-note">
                Lý do chuyển kho
              </label>
              <textarea
                id="ck-note"
                className="ck-textarea"
                rows={4}
                placeholder="Nhập lý do chuyển kho"
                value={note}
                onChange={(e) => setNote(e.target.value)}
              />
            </div>
          </section>
        </div>
      </AsyncBlock>

      <div className="ck-footer">
        <Link className="gm-button gm-button--secondary" to={backTo}>
          Hủy
        </Link>
        <Button onClick={() => void handleSubmit()} disabled={submit.busy}>
          {submit.busy ? 'Đang lưu…' : submitLabel}
        </Button>
      </div>
    </div>
  );
}

const Notice = ({ message, description }: { message: string; description?: string }) => (
  <div className="ck-page">
    <header className="ck-header">
      <h1>Phiếu chuyển kho</h1>
      <Link className="gm-button gm-button--secondary" to={TRANSFERS_PATH}>
        Quay lại danh sách
      </Link>
    </header>
    <section className="ck-card">
      <EmptyState message={message} description={description} />
    </section>
  </div>
);

/** Bước 1: lập phiếu chuyển kho (trạng thái Nháp). Kho gửi là chi nhánh đang làm việc. */
export function TransferCreatePage() {
  useBreadcrumbTail('Tạo phiếu');
  const user = useCurrentUser();
  const { scope, branchId } = useOperationsScope();
  const [createdAtIso] = useState(() => new Date().toISOString());

  if (!user) return null;
  if (scope.kind !== 'branch' || !canCreateTransfer(user, branchId)) {
    return scope.kind !== 'branch' ? (
      <Notice
        message="Chọn một chi nhánh để lập phiếu"
        description="Phiếu chuyển kho được lập từ một chi nhánh gửi. Hãy chọn chi nhánh ở góc trên bên phải."
      />
    ) : (
      <Notice
        message="Bạn không có quyền lập phiếu chuyển kho"
        description="Chỉ Thủ kho hoặc Quản lý chi nhánh gửi được lập phiếu."
      />
    );
  }

  return (
    <TransferForm
      title="Tạo phiếu chuyển kho"
      subtitle="Bước 1 · Lập phiếu (Nháp)"
      source={scope.branch}
      creatorName={user.fullName}
      createdAtIso={createdAtIso}
      submitLabel="Tạo phiếu"
      flash="created"
      backTo={TRANSFERS_PATH}
      onSubmit={(payload) => createTransfer({ branchId: scope.branch.id, ...payload })}
    />
  );
}

/** Sửa phiếu nháp ("Sửa phiếu" ở bước duyệt xuất). */
export function TransferEditPage() {
  const user = useCurrentUser();
  const docId = Number(useParams().id);
  const {
    data: doc,
    error,
    isLoading,
    reload,
  } = useRemote((signal) => getTransfer(docId, signal), `doc|${docId}`);
  useBreadcrumbTail(doc ? `${doc.code} · Sửa` : undefined);

  if (!user) return null;
  return (
    <AsyncBlock
      isLoading={isLoading}
      error={error}
      onRetry={reload}
      skeleton={<Skeleton lines={6} height={28} />}
    >
      {doc &&
        (canEditOrCancel(user, doc) ? (
          <TransferForm
            title={`Sửa phiếu chuyển kho ${doc.code}`}
            subtitle="Chỉ sửa được khi phiếu còn ở trạng thái Nháp"
            source={doc.branch}
            creatorName={doc.createdBy.fullName}
            createdAtIso={doc.createdAt}
            submitLabel="Lưu phiếu"
            flash="updated"
            backTo={transferDetailPath(doc.id)}
            initial={{
              toBranchId: doc.toBranch?.id,
              note: doc.note ?? '',
              items: doc.items.map((i) => ({
                productId: i.productId,
                sku: i.sku,
                productName: i.productName,
                unit: i.unit,
                quantity: String(i.quantity),
              })),
            }}
            onSubmit={(payload) => updateTransfer(doc.id, payload)}
          />
        ) : (
          <Notice
            message="Không thể sửa phiếu này"
            description="Chỉ sửa được phiếu Nháp tại kho gửi, bởi Thủ kho hoặc Quản lý chi nhánh gửi."
          />
        ))}
    </AsyncBlock>
  );
}
