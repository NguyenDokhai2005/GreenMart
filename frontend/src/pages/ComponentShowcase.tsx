import { useState } from 'react';

import {
  Button,
  Input,
  Select,
  Checkbox,
  Radio,
  Tabs,
  Table,
  Dialog,
  Toast,
  OrderStatusBadge,
  Skeleton,
  PageLoader,
  EmptyState,
  ErrorState,
  AsyncBlock,
} from '../components/ui';
import { RevenueAreaChart, Sparkline } from '../components/charts';
import { ApiError, SessionExpiredError } from '../api/client';
import { mapErrorToState, type ErrorScope } from '../lib/errors';
import FullScreenLoading, { type FullScreenReason } from './FullScreenLoading';
import NotFoundPage from './NotFoundPage';
import ForbiddenPage from './ForbiddenPage';
import ServerErrorPage from './ServerErrorPage';
import OfflinePage from './OfflinePage';
import SessionExpiredPage from './SessionExpiredPage';

import './ComponentShowcase.css';

interface Product {
  id: string;
  name: string;
  category: string;
  quantity: number;
  price: string;
}

const products: Product[] = [
  {
    id: 'SP001',
    name: 'Sữa tươi Vinamilk',
    category: 'Sữa',
    quantity: 20,
    price: '32.000đ',
  },
  {
    id: 'SP002',
    name: 'Gạo ST25',
    category: 'Thực phẩm',
    quantity: 15,
    price: '28.000đ',
  },
  {
    id: 'SP003',
    name: 'Nước suối Aquafina',
    category: 'Đồ uống',
    quantity: 50,
    price: '6.000đ',
  },
];

const revenuePoints = [
  { date: '2026-10-12', revenue: 12_500_000 },
  { date: '2026-10-13', revenue: 16_200_000 },
  { date: '2026-10-14', revenue: 14_800_000 },
  { date: '2026-10-15', revenue: 21_300_000 },
  { date: '2026-10-16', revenue: 18_700_000 },
  { date: '2026-10-17', revenue: 25_100_000 },
  { date: '2026-10-18', revenue: 22_400_000 },
];

const revenueTrend = revenuePoints.map(({ date, revenue }) => ({ date, value: revenue }));
const columns = [
  { key: 'id', title: 'Mã sản phẩm' },
  { key: 'name', title: 'Tên sản phẩm' },
  { key: 'category', title: 'Danh mục' },
  { key: 'quantity', title: 'Số lượng', align: 'center' as const },
  { key: 'price', title: 'Đơn giá', align: 'right' as const },
];

const statuses = [
  'NEW',
  'RESERVED',
  'CONFIRMED',
  'COMPLETED',
  'CANCELLED',
  'EXPIRED',
  'REJECTED',
  'RETURNED',
] as const;

// ---- Trạng thái giao diện (#228) ----
type AsyncMode = 'loading' | 'data' | 'empty' | 'filtered' | 'error';

const asyncModes: { mode: AsyncMode; label: string }[] = [
  { mode: 'loading', label: 'Đang tải' },
  { mode: 'data', label: 'Có dữ liệu' },
  { mode: 'empty', label: 'Rỗng' },
  { mode: 'filtered', label: 'Rỗng do lọc' },
  { mode: 'error', label: 'Lỗi' },
];

const serverError = new ApiError(
  'Máy chủ gặp lỗi khi tải danh sách phiếu nhập.',
  500,
  'INTERNAL_ERROR',
  'trace-demo-001',
);

const errorSamples: { label: string; scope: ErrorScope; error: unknown }[] = [
  {
    label: '400 VALIDATION_ERROR',
    scope: 'block',
    error: new ApiError('Dữ liệu không hợp lệ', 400, 'VALIDATION_ERROR', undefined, [
      { field: 'quantity', message: 'Số lượng phải lớn hơn 0' },
      { field: 'sku', message: 'Mã sản phẩm là bắt buộc' },
    ]),
  },
  {
    label: '409 hết hàng',
    scope: 'block',
    error: new ApiError('Sản phẩm đã hết hàng.', 409, 'OUT_OF_STOCK'),
  },
  {
    label: '422 báo giá đổi',
    scope: 'block',
    error: new ApiError('Báo giá đã thay đổi.', 422, 'PRICE_CHANGED'),
  },
  { label: '500 trong khối', scope: 'block', error: serverError },
  { label: '500 cả trang', scope: 'page', error: serverError },
  {
    label: '403 cả trang',
    scope: 'page',
    error: new ApiError('Không có quyền ở chi nhánh này.', 403, 'BRANCH_FORBIDDEN'),
  },
  {
    label: '404 cả trang',
    scope: 'page',
    error: new ApiError('Không tìm thấy.', 404, 'NOT_FOUND'),
  },
  { label: 'Hết phiên (SessionExpiredError)', scope: 'block', error: new SessionExpiredError() },
  {
    label: '401 UNAUTHORIZED',
    scope: 'block',
    error: new ApiError('Phiên hết hạn.', 401, 'UNAUTHORIZED'),
  },
  {
    label: '401 sai mật khẩu',
    scope: 'page',
    error: new ApiError('Sai mật khẩu. Còn 2 lần thử.', 401, 'INVALID_CREDENTIALS', undefined, {
      remainingAttempts: 2,
    }),
  },
  { label: 'Mất kết nối (fetch)', scope: 'page', error: new TypeError('Failed to fetch') },
  { label: 'Quá 15 giây', scope: 'block', error: { isTimeout: true } },
];

type SpecialPageKey = 'not-found' | 'forbidden' | 'server-error' | 'offline' | 'session-expired';

const specialPages: { key: SpecialPageKey; label: string }[] = [
  { key: 'not-found', label: '404' },
  { key: 'forbidden', label: '403' },
  { key: 'server-error', label: '500' },
  { key: 'offline', label: 'Mất kết nối' },
  { key: 'session-expired', label: 'Phiên hết hạn' },
];

const fullScreenReasons: { reason: FullScreenReason; label: string }[] = [
  { reason: 'session-check', label: 'Kiểm tra phiên' },
  { reason: 'after-login', label: 'Sau đăng nhập' },
  { reason: 'switch-area', label: 'Đổi khu' },
  { reason: 'switch-branch', label: 'Đổi chi nhánh' },
  { reason: 'logout', label: 'Đăng xuất' },
];

const noop = () => {};

export default function ComponentShowcase() {
  // Dialog
  const [dialogOpen, setDialogOpen] = useState(false);

  // Tabs
  const [activeTab, setActiveTab] = useState('overview');

  // Table
  const [tableState, setTableState] = useState<'normal' | 'loading' | 'empty' | 'error'>('normal');

  // Toast
  const [toastType, setToastType] = useState<'info' | 'success' | 'warning' | 'error' | null>(null);

  // Input và Select
  const [productName, setProductName] = useState('');
  const [category, setCategory] = useState('');

  // Trạng thái giao diện (#228)
  const [asyncMode, setAsyncMode] = useState<AsyncMode>('loading');
  const [sampleIndex, setSampleIndex] = useState(0);
  const [specialPage, setSpecialPage] = useState<SpecialPageKey>('not-found');
  const [fullScreenReason, setFullScreenReason] = useState<FullScreenReason | null>(null);

  const showFullScreen = (reason: FullScreenReason) => {
    setFullScreenReason(reason);
    window.setTimeout(() => setFullScreenReason(null), 2500);
  };

  const sample = errorSamples[sampleIndex];
  const mappedState = mapErrorToState(sample.error, sample.scope);

  const tabs = [
    {
      id: 'overview',
      label: 'Tổng quan',
      content: <p>Nội dung tab Tổng quan.</p>,
    },
    {
      id: 'products',
      label: 'Sản phẩm',
      content: <p>Nội dung tab Sản phẩm.</p>,
    },
    {
      id: 'settings',
      label: 'Cài đặt',
      content: <p>Nội dung tab Cài đặt.</p>,
    },
  ];

  const tableData = tableState === 'empty' ? [] : products;

  return (
    <main className="gm-preview">
      {/* HEADER */}
      <header className="gm-preview__header">
        <div>
          <span className="gm-preview__eyebrow">GREENMART DESIGN SYSTEM</span>

          <h1>Component Preview</h1>

          <p>Trang kiểm thử các component dùng chung của GreenMart.</p>
        </div>

        <span className="gm-preview__tag">UI COMPONENTS</span>
      </header>

      {/* 01. BUTTON */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>01</span>
          <div>
            <h2>Button</h2>
            <p>Các kiểu nút và trạng thái tương tác.</p>
          </div>
        </div>

        <div className="gm-preview__content gm-preview__row">
          <Button variant="primary">Primary</Button>

          <Button variant="secondary">Secondary</Button>

          <Button variant="destructive">Destructive</Button>

          <Button variant="primary" disabled>
            Disabled
          </Button>
        </div>
      </section>

      {/* 02. INPUT */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>02</span>
          <div>
            <h2>Input</h2>
            <p>Kiểm tra nhập liệu và trạng thái lỗi.</p>
          </div>
        </div>

        <div className="gm-preview__form">
          <Input
            label="Tên sản phẩm"
            placeholder="Nhập tên sản phẩm"
            value={productName}
            onChange={(event) => setProductName(event.target.value)}
            helperText="Nhập tên sản phẩm cần quản lý."
          />

          <Input label="Mã sản phẩm" placeholder="Ví dụ: SP001" defaultValue="SP001" />

          <Input label="Trường bị vô hiệu hóa" placeholder="Không thể nhập" disabled />

          <Input label="Trường có lỗi" placeholder="Nhập dữ liệu" error="Thông tin không hợp lệ." />
        </div>
      </section>

      {/* 03. SELECT */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>03</span>
          <div>
            <h2>Select</h2>
            <p>Kiểm tra danh sách lựa chọn.</p>
          </div>
        </div>

        <div className="gm-preview__form">
          <Select
            label="Danh mục sản phẩm"
            placeholder="Chọn danh mục"
            value={category}
            onChange={(event) => setCategory(event.target.value)}
            options={[
              { label: 'Sữa', value: 'milk' },
              { label: 'Thực phẩm', value: 'food' },
              { label: 'Đồ uống', value: 'drink' },
            ]}
            helperText="Chọn danh mục sản phẩm."
          />

          <Select
            label="Danh mục bị vô hiệu hóa"
            options={[
              { label: 'Sữa', value: 'milk' },
              { label: 'Thực phẩm', value: 'food' },
            ]}
            disabled
          />
        </div>

        <div className="gm-preview__result">
          <strong>Kết quả nhập liệu</strong>
          <p>Tên sản phẩm: {productName || 'Chưa nhập'}</p>
          <p>Danh mục: {category || 'Chưa chọn'}</p>
        </div>
      </section>

      {/* 04. CHECKBOX */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>04</span>
          <div>
            <h2>Checkbox</h2>
            <p>Kiểm tra lựa chọn và trạng thái.</p>
          </div>
        </div>

        <div className="gm-preview__content gm-preview__stack">
          <Checkbox label="Cho phép bán sản phẩm" />

          <Checkbox label="Sản phẩm đang hoạt động" defaultChecked />

          <Checkbox label="Không thể chọn" disabled />
        </div>
      </section>

      {/* 05. RADIO */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>05</span>
          <div>
            <h2>Radio</h2>
            <p>Kiểm tra lựa chọn một trong nhiều phương án.</p>
          </div>
        </div>

        <div className="gm-preview__content gm-preview__stack">
          <Radio name="delivery" value="standard" label="Giao hàng tiêu chuẩn" defaultChecked />

          <Radio name="delivery" value="express" label="Giao hàng nhanh" />

          <Radio name="delivery" value="disabled" label="Không thể chọn" disabled />
        </div>
      </section>

      {/* 06. TABS */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>06</span>
          <div>
            <h2>Tabs</h2>
            <p>Kiểm tra chuyển đổi nội dung giữa các tab.</p>
          </div>
        </div>

        <div className="gm-preview__content">
          <Tabs
            tabs={tabs}
            activeId={activeTab}
            onChange={setActiveTab}
            ariaLabel="GreenMart Preview Tabs"
          />
        </div>
      </section>

      {/* 07. TABLE */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>07</span>
          <div>
            <h2>Table</h2>
            <p>Kiểm tra các trạng thái hiển thị dữ liệu.</p>
          </div>
        </div>

        <div className="gm-preview__content">
          <div className="gm-preview__row gm-preview__actions">
            <Button variant="secondary" onClick={() => setTableState('normal')}>
              Có dữ liệu
            </Button>

            <Button variant="secondary" onClick={() => setTableState('loading')}>
              Loading
            </Button>

            <Button variant="secondary" onClick={() => setTableState('empty')}>
              Empty
            </Button>

            <Button variant="secondary" onClick={() => setTableState('error')}>
              Error
            </Button>
          </div>

          <Table<Product>
            columns={columns}
            data={tableData}
            rowKey="id"
            loading={tableState === 'loading'}
            error={tableState === 'error' ? 'Không thể tải dữ liệu sản phẩm.' : null}
            onRetry={() => setTableState('normal')}
            emptyText="Chưa có sản phẩm nào."
          />
        </div>
      </section>

      {/* 08. DIALOG */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>08</span>
          <div>
            <h2>Dialog</h2>
            <p>Kiểm tra mở, đóng và xác nhận hộp thoại.</p>
          </div>
        </div>

        <div className="gm-preview__content">
          <Button variant="primary" onClick={() => setDialogOpen(true)}>
            Mở Dialog
          </Button>

          <Dialog
            open={dialogOpen}
            title="Xác nhận thao tác"
            onClose={() => setDialogOpen(false)}
            onConfirm={() => setDialogOpen(false)}
            confirmText="Đồng ý"
            cancelText="Hủy"
          >
            <p>Bạn có chắc chắn muốn thực hiện thao tác này không?</p>

            <p>Đây là nội dung mẫu để kiểm tra Dialog của GreenMart.</p>
          </Dialog>
        </div>
      </section>

      {/* 09. TOAST */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>09</span>
          <div>
            <h2>Toast</h2>
            <p>Kiểm tra bốn loại thông báo.</p>
          </div>
        </div>

        <div className="gm-preview__content">
          <div className="gm-preview__row">
            <Button variant="secondary" onClick={() => setToastType('info')}>
              Info
            </Button>

            <Button variant="secondary" onClick={() => setToastType('success')}>
              Success
            </Button>

            <Button variant="secondary" onClick={() => setToastType('warning')}>
              Warning
            </Button>

            <Button variant="destructive" onClick={() => setToastType('error')}>
              Error
            </Button>
          </div>

          {toastType && (
            <div className="gm-preview__toast">
              <Toast
                variant={toastType}
                title={
                  {
                    info: 'Thông tin',
                    success: 'Thành công',
                    warning: 'Cảnh báo',
                    error: 'Đã xảy ra lỗi',
                  }[toastType]
                }
                description="Đây là thông báo mẫu để kiểm tra Toast."
                onClose={() => setToastType(null)}
              />
            </div>
          )}
        </div>
      </section>

      {/* 10. ORDER STATUS BADGE */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>10</span>
          <div>
            <h2>Order Status Badge</h2>
            <p>Kiểm tra toàn bộ trạng thái đơn hàng.</p>
          </div>
        </div>

        <div className="gm-preview__status-grid">
          {statuses.map((status) => (
            <div className="gm-preview__status-item" key={status}>
              <OrderStatusBadge status={status} />
              <span>{status}</span>
            </div>
          ))}
        </div>
      </section>

      {/* 11. SKELETON & PAGE LOADER */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>11</span>
          <div>
            <h2>Skeleton &amp; PageLoader</h2>
            <p>
              Skeleton cho một khối đang tải; PageLoader cho vùng nội dung khi chuyển trang (giữ
              header và menu).
            </p>
          </div>
        </div>

        <div className="gm-preview__content gm-preview__stack">
          <Skeleton height={24} width="40%" />
          <Skeleton lines={3} />
          <PageLoader />
        </div>
      </section>

      {/* 12. EMPTY STATE */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>12</span>
          <div>
            <h2>EmptyState</h2>
            <p>
              Chỉ hiện sau khi API trả danh sách rỗng. Nút hành động chỉ có khi người dùng có quyền.
            </p>
          </div>
        </div>

        <div className="gm-preview__content gm-preview__stack">
          <EmptyState
            message="Chưa có phiếu nhập nào"
            description="Tạo phiếu nhập để bắt đầu theo dõi hàng vào kho."
            actionLabel="Tạo phiếu nhập"
            onAction={noop}
          />
          <EmptyState message="Chưa có phiếu nhập nào (không có quyền tạo)" />
          <EmptyState message="" filtered onClearFilters={noop} />
        </div>
      </section>

      {/* 13. ERROR STATE */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>13</span>
          <div>
            <h2>ErrorState</h2>
            <p>Lỗi trong một khối: hiện message của ErrorResponse và nút Thử lại.</p>
          </div>
        </div>

        <div className="gm-preview__content gm-preview__stack">
          <ErrorState message="Không tải được danh sách phiếu nhập." onRetry={noop} />
          <ErrorState message="Máy chủ gặp lỗi." traceId="trace-demo-001" onRetry={noop} />
        </div>
      </section>

      {/* 14. ASYNC BLOCK */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>14</span>
          <div>
            <h2>AsyncBlock</h2>
            <p>
              Ghép các trạng thái theo đúng thứ tự. Chọn &quot;Đang tải&quot;: sau 300 ms hiện
              Skeleton, sau 15 giây chuyển sang lỗi có Thử lại.
            </p>
          </div>
        </div>

        <div className="gm-preview__content">
          <div className="gm-preview__row gm-preview__actions">
            {asyncModes.map(({ mode, label }) => (
              <Button
                key={mode}
                variant={asyncMode === mode ? 'primary' : 'secondary'}
                onClick={() => setAsyncMode(mode)}
              >
                {label}
              </Button>
            ))}
          </div>

          <AsyncBlock
            key={asyncMode}
            isLoading={asyncMode === 'loading'}
            error={asyncMode === 'error' ? serverError : null}
            isEmpty={asyncMode === 'empty' || asyncMode === 'filtered'}
            onRetry={() => setAsyncMode('data')}
            empty={{
              message: 'Chưa có phiếu nhập nào',
              actionLabel: 'Tạo phiếu nhập',
              onAction: noop,
              filtered: asyncMode === 'filtered',
              onClearFilters: () => setAsyncMode('data'),
            }}
          >
            <Table<Product> columns={columns} data={products} rowKey="id" />
          </AsyncBlock>
        </div>
      </section>

      {/* 15. ÁNH XẠ LỖI */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>15</span>
          <div>
            <h2>Ánh xạ lỗi API</h2>
            <p>mapErrorToState: lỗi → trạng thái giao diện (khối, form, hộp thoại, trang).</p>
          </div>
        </div>

        <div className="gm-preview__content">
          <div className="gm-preview__row gm-preview__actions">
            {errorSamples.map((item, index) => (
              <Button
                key={item.label}
                variant={sampleIndex === index ? 'primary' : 'secondary'}
                onClick={() => setSampleIndex(index)}
              >
                {item.label}
              </Button>
            ))}
          </div>

          <div className="gm-preview__result">
            <strong>Kết quả (phạm vi: {sample.scope === 'page' ? 'cả trang' : 'một khối'})</strong>
            <pre style={{ overflowX: 'auto' }}>{JSON.stringify(mappedState, null, 2)}</pre>
          </div>
        </div>
      </section>

      {/* 16. TRANG ĐẶC BIỆT */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>16</span>
          <div>
            <h2>Trang đặc biệt &amp; loading toàn màn hình</h2>
            <p>
              5 trang lỗi cả trang (hiển thị trong layout) và trang loading toàn màn hình (tự đóng
              sau 2,5 giây).
            </p>
          </div>
        </div>

        <div className="gm-preview__content">
          <div className="gm-preview__row gm-preview__actions">
            {specialPages.map(({ key, label }) => (
              <Button
                key={key}
                variant={specialPage === key ? 'primary' : 'secondary'}
                onClick={() => setSpecialPage(key)}
              >
                {label}
              </Button>
            ))}
          </div>

          <div style={{ border: '1px solid #dfe8e1', borderRadius: 8 }}>
            {specialPage === 'not-found' && <NotFoundPage onHome={noop} />}
            {specialPage === 'forbidden' && <ForbiddenPage onHome={noop} onSwitchBranch={noop} />}
            {specialPage === 'server-error' && (
              <ServerErrorPage traceId="trace-demo-001" onRetry={noop} />
            )}
            {specialPage === 'offline' && <OfflinePage onReconnect={noop} />}
            {specialPage === 'session-expired' && <SessionExpiredPage onLogin={noop} />}
          </div>

          <div className="gm-preview__row gm-preview__actions">
            {fullScreenReasons.map(({ reason, label }) => (
              <Button key={reason} variant="secondary" onClick={() => showFullScreen(reason)}>
                Toàn màn hình: {label}
              </Button>
            ))}
          </div>

          {fullScreenReason && <FullScreenLoading reason={fullScreenReason} />}
        </div>
      </section>

      {/* 17. BIỂU ĐỒ */}
      <section className="gm-preview__section">
        <div className="gm-preview__heading">
          <span>17</span>
          <div>
            <h2>RevenueAreaChart &amp; Sparkline</h2>
            <p>
              Biểu đồ doanh thu theo ngày và biểu đồ xu hướng nhỏ cho thẻ số. Cả hai nhận dữ liệu
              theo kiểu dữ liệu dùng chung của thư mục charts.
            </p>
          </div>
        </div>

        <div className="gm-preview__content gm-preview__stack">
          <RevenueAreaChart
            data={revenuePoints}
            height={240}
            ariaLabel="Doanh thu mẫu từ ngày 12 đến ngày 18 tháng 10"
          />
          <Sparkline
            data={revenueTrend}
            tone="primary"
            height={56}
            ariaLabel="Xu hướng doanh thu mẫu"
          />
        </div>
      </section>
      {/* FOOTER */}
      <footer className="gm-preview__footer">GREENMART · SHARED COMPONENTS · PREVIEW</footer>
    </main>
  );
}
