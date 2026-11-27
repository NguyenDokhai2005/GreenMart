import { useState } from 'react';
import { AsyncBlock, Button, Dialog, Input, Select, Toast } from '../../components/ui';
import type { CurrentUser } from '../../api/client';
import { searchProducts } from '../../api/client';
import type { components } from '../../api/schema';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import { branchLabel } from '../../layouts/operations/adapters';
import { useAuth } from '../auth/useAuth';
import Pagination from '../inventory/PaginationControl';
import { mapErrorToState } from '../../lib/errors';
import { useRemote } from '../stock-documents/useRemote';
import type { Promotion, PromotionRequest } from './promotionApi';
import {
  changePromotionStatus,
  createPromotion,
  listPromotions,
  updatePromotion,
} from './promotionApi';
import './promotions.css';

type PromotionStatus = Promotion['status'];
type PromotionLevel = Promotion['level'];
type PromotionType = Promotion['type'];
type PromotionChannel = components['schemas']['ChannelCode'];
type PromotionProductOption = Pick<components['schemas']['Product'], 'id' | 'name' | 'sku'>;

const PAGE_SIZE = 20;
const STATUS_LABELS: Record<PromotionStatus, string> = {
  DRAFT: 'Bản nháp',
  ACTIVE: 'Đang áp dụng',
  PAUSED: 'Tạm dừng',
  ENDED: 'Đã kết thúc',
};
const CHANNELS: { value: PromotionChannel; label: string }[] = [
  { value: 'POS', label: 'Tại quầy' },
  { value: 'WEB', label: 'Website' },
  { value: 'MARKET', label: 'Marketplace' },
];

function formatDate(value: string) {
  return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' }).format(new Date(value));
}

function toLocalDateTime(value: string) {
  const date = new Date(value);
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  return date.toISOString().slice(0, 16);
}

function errorFields(error: unknown) {
  const mapped = mapErrorToState(error, 'block');
  return mapped.kind === 'form' ? mapped.fieldErrors : {};
}

function PromotionFormDialog({
  promotion,
  user,
  onClose,
  onSaved,
}: {
  promotion?: Promotion;
  user: CurrentUser;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { branches } = useOperationsScope();
  const [code, setCode] = useState(promotion?.code ?? '');
  const [type, setType] = useState<PromotionType>(promotion?.type ?? 'PERCENT');
  const [level, setLevel] = useState<PromotionLevel>(promotion?.level ?? 'ORDER');
  const [value, setValue] = useState(promotion ? String(promotion.value) : '');
  const [maxDiscount, setMaxDiscount] = useState(
    promotion?.maxDiscount == null ? '' : String(promotion.maxDiscount),
  );
  const [minOrder, setMinOrder] = useState(
    promotion?.minOrder == null ? '' : String(promotion.minOrder),
  );
  const [minQuantity, setMinQuantity] = useState(
    promotion?.minQuantity == null ? '' : String(promotion.minQuantity),
  );
  const [startAt, setStartAt] = useState(
    promotion?.startAt ? toLocalDateTime(promotion.startAt) : '',
  );
  const [endAt, setEndAt] = useState(promotion?.endAt ? toLocalDateTime(promotion.endAt) : '');
  const [usageLimit, setUsageLimit] = useState(
    promotion?.usageLimit == null ? '' : String(promotion.usageLimit),
  );
  const [branchIds, setBranchIds] = useState<number[]>(promotion?.branchIds ?? []);
  const [channels, setChannels] = useState<PromotionChannel[]>(promotion?.channels ?? []);
  const [productIds, setProductIds] = useState<number[]>(promotion?.productIds ?? []);
  const [productSearch, setProductSearch] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const products = useRemote(
    (signal) =>
      level === 'ITEM' && productSearch.trim()
        ? searchProducts(productSearch.trim(), signal, 0, 20).then((page) =>
            page.content.map((product) => ({
              id: product.id,
              name: product.name,
              sku: product.sku,
            })),
          )
        : Promise.resolve([] as PromotionProductOption[]),
    `promotion-products|${level}|${productSearch}`,
  );

  function toggleBranch(id: number) {
    setBranchIds((current) =>
      current.includes(id) ? current.filter((value) => value !== id) : [...current, id],
    );
  }

  function toggleChannel(channel: PromotionChannel) {
    setChannels((current) =>
      current.includes(channel)
        ? current.filter((value) => value !== channel)
        : [...current, channel],
    );
  }

  async function submit() {
    if (busy) return;
    const start = new Date(startAt);
    const end = new Date(endAt);
    const nextErrors: Record<string, string> = {};
    if (!Number.isFinite(start.getTime())) nextErrors.startAt = 'Vui lòng chọn ngày bắt đầu.';
    if (!Number.isFinite(end.getTime())) nextErrors.endAt = 'Vui lòng chọn ngày kết thúc.';
    if (level === 'ITEM' && productIds.length === 0) {
      nextErrors.productIds = 'Chọn ít nhất một sản phẩm cho khuyến mại cấp sản phẩm.';
    }
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors);
      return;
    }

    const body: PromotionRequest = {
      code: code.trim(),
      type,
      level,
      value: Number(value),
      startAt: start.toISOString(),
      endAt: end.toISOString(),
      branchIds,
      channels,
      ...(type === 'PERCENT' && maxDiscount ? { maxDiscount: Number(maxDiscount) } : {}),
      ...(level === 'ORDER' && minOrder ? { minOrder: Number(minOrder) } : {}),
      ...(level === 'ITEM' && minQuantity ? { minQuantity: Number(minQuantity) } : {}),
      ...(level === 'ITEM' ? { productIds } : {}),
      ...(usageLimit ? { usageLimit: Number(usageLimit) } : {}),
    };

    setBusy(true);
    setErrors({});
    setMessage('');
    try {
      if (promotion) await updatePromotion(promotion.id, body);
      else await createPromotion(body);
      onSaved();
    } catch (error) {
      const mapped = mapErrorToState(error, 'block');
      setErrors(errorFields(error));
      setMessage(mapped.message ?? 'Không thể lưu chương trình khuyến mại.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog
      open
      title={promotion ? `Sửa khuyến mại ${promotion.code}` : 'Tạo chương trình khuyến mại'}
      onClose={onClose}
      onConfirm={() => void submit()}
      confirmText={promotion ? 'Lưu thay đổi' : 'Tạo bản nháp'}
      busy={busy}
    >
      <div className="promo-form">
        {message && <Toast variant="error" title={message} />}
        <Input
          label="Mã khuyến mại"
          required
          value={code}
          maxLength={30}
          error={errors.code}
          onChange={(event) => setCode(event.target.value.toUpperCase())}
        />
        <div className="promo-form__grid">
          <Select
            label="Loại giảm giá"
            value={type}
            options={[
              { value: 'PERCENT', label: 'Phần trăm' },
              { value: 'AMOUNT', label: 'Số tiền' },
            ]}
            onChange={(event) => setType(event.target.value as PromotionType)}
          />
          <Select
            label="Cấp áp dụng"
            value={level}
            options={[
              { value: 'ORDER', label: 'Đơn hàng' },
              { value: 'ITEM', label: 'Sản phẩm' },
            ]}
            onChange={(event) => setLevel(event.target.value as PromotionLevel)}
          />
        </div>
        <Input
          label={type === 'PERCENT' ? 'Mức giảm (%)' : 'Mức giảm (VND)'}
          type="number"
          min={1}
          step={1}
          required
          value={value}
          error={errors.value}
          onChange={(event) => setValue(event.target.value)}
        />
        {type === 'PERCENT' && (
          <Input
            label="Giảm tối đa (VND)"
            type="number"
            min={1}
            value={maxDiscount}
            error={errors.maxDiscount}
            onChange={(event) => setMaxDiscount(event.target.value)}
          />
        )}
        {level === 'ORDER' ? (
          <Input
            label="Giá trị đơn tối thiểu (VND)"
            type="number"
            min={0}
            value={minOrder}
            error={errors.minOrder}
            onChange={(event) => setMinOrder(event.target.value)}
          />
        ) : (
          <>
            <Input
              label="Số lượng tối thiểu trên một dòng"
              type="number"
              min={1}
              step={1}
              value={minQuantity}
              helperText="Để trống để áp dụng từ 1 sản phẩm."
              error={errors.minQuantity}
              onChange={(event) => setMinQuantity(event.target.value)}
            />
            <Input
              label="Tìm sản phẩm"
              type="search"
              value={productSearch}
              placeholder="Tên hoặc SKU"
              onChange={(event) => setProductSearch(event.target.value)}
            />
            <AsyncBlock
              isLoading={!!productSearch.trim() && products.isLoading}
              error={products.error}
              isEmpty={!!productSearch.trim() && !!products.data && products.data.length === 0}
              onRetry={products.reload}
              empty={{ message: 'Không tìm thấy sản phẩm phù hợp.' }}
              skeleton={
                <div
                  className="promo-product-search-skeleton"
                  role="status"
                  aria-label="Đang tìm sản phẩm"
                />
              }
            >
              {products.data
                ?.filter((product) => !productIds.includes(product.id))
                .map((product) => (
                  <button
                    type="button"
                    className="promo-product-option"
                    key={product.id}
                    onClick={() => setProductIds((current) => [...current, product.id])}
                  >
                    {product.name} · {product.sku} <span>Thêm</span>
                  </button>
                ))}
            </AsyncBlock>
            <ul className="promo-product-list" aria-label="Sản phẩm đã chọn">
              {productIds.map((id) => {
                const product = products.data?.find((item) => item.id === id);
                return (
                  <li key={id}>
                    {product ? `${product.name} · ${product.sku}` : `Mã sản phẩm ${id}`}
                    <button
                      type="button"
                      aria-label={`Xóa sản phẩm ${id}`}
                      onClick={() =>
                        setProductIds((current) => current.filter((item) => item !== id))
                      }
                    >
                      Xóa
                    </button>
                  </li>
                );
              })}
            </ul>
            {errors.productIds && <p className="promo-form__error">{errors.productIds}</p>}
          </>
        )}
        <div className="promo-form__grid">
          <Input
            label="Bắt đầu"
            type="datetime-local"
            required
            value={startAt}
            error={errors.startAt}
            onChange={(event) => setStartAt(event.target.value)}
          />
          <Input
            label="Kết thúc"
            type="datetime-local"
            required
            value={endAt}
            error={errors.endAt}
            onChange={(event) => setEndAt(event.target.value)}
          />
        </div>
        <Input
          label="Giới hạn lượt dùng"
          type="number"
          min={1}
          value={usageLimit}
          helperText="Để trống nếu không giới hạn."
          error={errors.usageLimit}
          onChange={(event) => setUsageLimit(event.target.value)}
        />
        <fieldset className="promo-choice-group">
          <legend>Chi nhánh áp dụng</legend>
          <p>Không chọn chi nhánh nào nghĩa là áp dụng toàn hệ thống.</p>
          {branches.map((branch) => (
            <label key={branch.id}>
              <input
                type="checkbox"
                checked={branchIds.includes(branch.id)}
                onChange={() => toggleBranch(branch.id)}
              />
              {branch.name}
            </label>
          ))}
          {branches.length === 0 && <span>Tài khoản chưa có danh sách chi nhánh.</span>}
        </fieldset>
        <fieldset className="promo-choice-group">
          <legend>Kênh áp dụng</legend>
          <p>Không chọn kênh nào nghĩa là áp dụng mọi kênh.</p>
          {CHANNELS.map((channel) => (
            <label key={channel.value}>
              <input
                type="checkbox"
                checked={channels.includes(channel.value)}
                onChange={() => toggleChannel(channel.value)}
              />
              {channel.label}
            </label>
          ))}
        </fieldset>
        <span className="promo-form__owner">Đang thao tác với vai trò {user.role}.</span>
      </div>
    </Dialog>
  );
}

function PromotionStatusActions({
  promotion,
  onChange,
}: {
  promotion: Promotion;
  onChange: (promotion: Promotion, status: 'ACTIVE' | 'PAUSED' | 'ENDED') => void;
}) {
  if (promotion.status === 'ENDED') return <span>—</span>;
  return (
    <div className="promo-actions">
      {promotion.status !== 'ACTIVE' && (
        <Button
          variant="secondary"
          aria-label={`Kích hoạt ${promotion.code}`}
          onClick={() => onChange(promotion, 'ACTIVE')}
        >
          Kích hoạt
        </Button>
      )}
      {promotion.status === 'ACTIVE' && (
        <Button
          variant="secondary"
          aria-label={`Tạm dừng ${promotion.code}`}
          onClick={() => onChange(promotion, 'PAUSED')}
        >
          Tạm dừng
        </Button>
      )}
      {(promotion.status === 'ACTIVE' || promotion.status === 'PAUSED') && (
        <Button
          variant="secondary"
          aria-label={`Kết thúc ${promotion.code}`}
          onClick={() => onChange(promotion, 'ENDED')}
        >
          Kết thúc
        </Button>
      )}
    </div>
  );
}

export default function PromotionsPage() {
  const auth = useAuth();
  const user = auth.state.status === 'authenticated' ? auth.state.user : null;
  const { branchId, scope } = useOperationsScope();
  const [status, setStatus] = useState<PromotionStatus | ''>('');
  const [level, setLevel] = useState<PromotionLevel | ''>('');
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Promotion | null | undefined>();
  const [notice, setNotice] = useState('');
  const [actionError, setActionError] = useState('');
  const queryKey = `${branchId ?? 'all'}|${status}|${level}|${page}`;
  const remote = useRemote(
    (signal) =>
      listPromotions(
        {
          branchId,
          status: status ? [status] : undefined,
          level: level || undefined,
          page: page - 1,
          size: PAGE_SIZE,
          sort: 'startAt,desc',
        },
        signal,
      ),
    queryKey,
  );
  const canEdit = user?.role === 'OWNER';

  if (!user) return null;

  async function updateStatus(promotion: Promotion, next: 'ACTIVE' | 'PAUSED' | 'ENDED') {
    setActionError('');
    try {
      await changePromotionStatus(promotion.id, next);
      setNotice(`${promotion.code}: ${STATUS_LABELS[next]}.`);
      remote.reload();
    } catch (error) {
      const mapped = mapErrorToState(error, 'block');
      setActionError(mapped.message ?? 'Không thể đổi trạng thái khuyến mại.');
    }
  }

  return (
    <div className="promo-page">
      <header className="promo-header">
        <div>
          <p className="promo-breadcrumb">Bán hàng / Khuyến mại</p>
          <h1>Khuyến mại</h1>
          <p>
            {canEdit
              ? scope.kind === 'all'
                ? 'Quản lý chương trình khuyến mại trên toàn hệ thống.'
                : `Quản lý chương trình khuyến mại tại ${branchLabel(scope.branch)}.`
              : 'Xem các chương trình khuyến mại áp dụng tại chi nhánh được giao.'}
          </p>
        </div>
        {canEdit && <Button onClick={() => setEditing(null)}>+ Tạo khuyến mại</Button>}
      </header>

      {notice && <Toast variant="success" title={notice} onClose={() => setNotice('')} />}
      {actionError && <Toast variant="error" title={actionError} />}

      <section className="promo-filters" aria-label="Bộ lọc khuyến mại">
        <Select
          label="Trạng thái"
          value={status}
          options={[
            { value: '', label: 'Tất cả trạng thái' },
            ...Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label })),
          ]}
          onChange={(event) => {
            setStatus(event.target.value as PromotionStatus | '');
            setPage(1);
          }}
        />
        <Select
          label="Cấp áp dụng"
          value={level}
          options={[
            { value: '', label: 'Tất cả cấp áp dụng' },
            { value: 'ORDER', label: 'Đơn hàng' },
            { value: 'ITEM', label: 'Sản phẩm' },
          ]}
          onChange={(event) => {
            setLevel(event.target.value as PromotionLevel | '');
            setPage(1);
          }}
        />
      </section>

      <section className="promo-card" aria-label="Danh sách khuyến mại">
        <div className="promo-card__heading">
          <h2>Danh sách chương trình</h2>
          {remote.data && <span>{remote.data.totalElements} chương trình</span>}
        </div>
        <AsyncBlock
          isLoading={remote.isLoading}
          error={remote.error}
          isEmpty={!!remote.data && remote.data.content.length === 0}
          onRetry={remote.reload}
          empty={{
            message: 'Chưa có chương trình khuyến mại',
            description: 'Các chương trình phù hợp với bộ lọc sẽ hiển thị tại đây.',
          }}
          skeleton={
            <div className="promo-skeleton" role="status" aria-label="Đang tải khuyến mại" />
          }
        >
          {remote.data && remote.data.content.length > 0 && (
            <div className="promo-table-wrap">
              <table className="promo-table">
                <thead>
                  <tr>
                    <th>Mã</th>
                    <th>Ưu đãi</th>
                    <th>Cấp</th>
                    <th>Thời gian</th>
                    <th>Trạng thái</th>
                    {canEdit && <th>Thao tác</th>}
                  </tr>
                </thead>
                <tbody>
                  {remote.data.content.map((promotion) => (
                    <tr key={promotion.id}>
                      <td>
                        <strong>{promotion.code}</strong>
                      </td>
                      <td>
                        {promotion.type === 'PERCENT'
                          ? `${promotion.value}%`
                          : `${new Intl.NumberFormat('vi-VN').format(promotion.value)} đ`}
                        {promotion.level === 'ITEM' && promotion.minQuantity != null && (
                          <small>Tối thiểu {promotion.minQuantity} sản phẩm/dòng</small>
                        )}
                      </td>
                      <td>{promotion.level === 'ORDER' ? 'Đơn hàng' : 'Sản phẩm'}</td>
                      <td>
                        {formatDate(promotion.startAt)} – {formatDate(promotion.endAt)}
                      </td>
                      <td>
                        <span
                          className={`promo-status promo-status--${promotion.status.toLowerCase()}`}
                        >
                          {STATUS_LABELS[promotion.status]}
                        </span>
                      </td>
                      {canEdit && (
                        <td>
                          <div className="promo-actions">
                            {(promotion.status === 'DRAFT' || promotion.status === 'PAUSED') && (
                              <Button
                                variant="secondary"
                                aria-label={`Sửa ${promotion.code}`}
                                onClick={() => setEditing(promotion)}
                              >
                                Sửa
                              </Button>
                            )}
                            <PromotionStatusActions
                              promotion={promotion}
                              onChange={(item, next) => void updateStatus(item, next)}
                            />
                          </div>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </AsyncBlock>
        {!remote.error && remote.data && remote.data.totalPages > 1 && (
          <div className="promo-card__footer">
            <Pagination page={page} totalPages={remote.data.totalPages} onChange={setPage} />
          </div>
        )}
      </section>

      {!canEdit && (
        <p className="promo-readonly">Chỉ Chủ chuỗi được tạo, sửa và đổi trạng thái khuyến mại.</p>
      )}
      {editing !== undefined && (
        <PromotionFormDialog
          promotion={editing ?? undefined}
          user={user}
          onClose={() => setEditing(undefined)}
          onSaved={() => {
            setEditing(undefined);
            setNotice('Đã lưu chương trình khuyến mại.');
            remote.reload();
          }}
        />
      )}
    </div>
  );
}
