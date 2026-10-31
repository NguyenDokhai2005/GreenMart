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
} from '../components/ui';

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

      {/* FOOTER */}
      <footer className="gm-preview__footer">GREENMART · SHARED COMPONENTS · PREVIEW</footer>
    </main>
  );
}
