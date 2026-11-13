# Hướng dẫn component dùng chung GreenMart (SV04)

Tài liệu này hướng dẫn tái sử dụng các component đã có trong `src/components`; ưu tiên dùng component chung thay vì tự tạo lại giao diện tương tự.

## Xem ví dụ trực tiếp

Mở trang **`/component-showcase`** trong ứng dụng để xem ví dụ tương tác. Mã nguồn ví dụ ở [`src/pages/ComponentShowcase.tsx`](../pages/ComponentShowcase.tsx). Trang preview có ví dụ cho các component UI và biểu đồ, kèm các props đang được dùng.

## Import component

Import từ barrel export để đường dẫn không phụ thuộc vào vị trí file triển khai:

```tsx
import {
  AsyncBlock,
  Button,
  Checkbox,
  Dialog,
  EmptyState,
  ErrorState,
  Input,
  OrderStatusBadge,
  PageLoader,
  Radio,
  Select,
  Skeleton,
  Table,
  Tabs,
  Toast,
} from '../components/ui';
import { RevenueAreaChart, Sparkline } from '../components/charts';
```

Đường dẫn `../components` cần điều chỉnh theo vị trí file đang dùng. Kiểu props được export cùng tên từ `components/ui`; kiểu dữ liệu biểu đồ được export từ `components/charts`.

## Component biểu mẫu và tương tác

### Button

Nút hỗ trợ props chuẩn của HTML `<button>` và `variant`.

```tsx
<Button variant="primary" type="submit" disabled={saving} onClick={save}>
  Lưu thay đổi
</Button>
<Button variant="secondary" onClick={cancel}>Hủy</Button>
<Button variant="destructive" onClick={remove}>Xóa</Button>
```

| Prop                                            | Kiểu                                        | Mặc định                          | Ghi chú                            |
| ----------------------------------------------- | ------------------------------------------- | --------------------------------- | ---------------------------------- |
| `variant`                                       | `'primary' \| 'secondary' \| 'destructive'` | `'primary'`                       | Kiểu hiển thị                      |
| `children`                                      | `ReactNode`                                 | —                                 | Nội dung nút                       |
| `type`, `disabled`, `onClick`, `className`, ... | Theo `ButtonHTMLAttributes`                 | `type="button"`, `disabled=false` | Có thể dùng props chuẩn của button |

### Input

`Input` nhận props chuẩn của HTML `<input>`. Dùng `value` + `onChange` cho input được quản lý bởi React; `defaultValue` dùng khi chỉ cần giá trị ban đầu.

```tsx
<Input
  label="Tên sản phẩm"
  value={name}
  onChange={(event) => setName(event.target.value)}
  helperText="Nhập tên hiển thị."
  error={name ? undefined : 'Tên sản phẩm là bắt buộc.'}
  required
/>
```

| Prop riêng                                                 | Kiểu                                         | Ghi chú                                    |
| ---------------------------------------------------------- | -------------------------------------------- | ------------------------------------------ |
| `label`                                                    | `string`                                     | Nhãn được liên kết với ô nhập              |
| `helperText`                                               | `string`                                     | Hướng dẫn; không hiển thị khi có `error`   |
| `error`                                                    | `string`                                     | Thông báo lỗi và trạng thái `aria-invalid` |
| `id`, `required`, `disabled`, `value`, `defaultValue`, ... | Theo `InputHTMLAttributes<HTMLInputElement>` | `id` được tự tạo nếu bỏ trống              |

### Select

Mỗi lựa chọn có `label`, `value` và tùy chọn `disabled`. `Select` nhận các props chuẩn còn lại của HTML `<select>`.

```tsx
<Select
  label="Danh mục"
  value={category}
  onChange={(event) => setCategory(event.target.value)}
  placeholder="Chọn danh mục"
  options={[
    { label: 'Sữa', value: 'milk' },
    { label: 'Đồ uống', value: 'drink', disabled: true },
  ]}
  helperText="Chọn một danh mục."
/>
```

| Prop riêng                                                       | Kiểu                                           | Mặc định          | Ghi chú                                          |
| ---------------------------------------------------------------- | ---------------------------------------------- | ----------------- | ------------------------------------------------ |
| `options`                                                        | `SelectOption[]`                               | Bắt buộc          | `SelectOption` gồm `label`, `value`, `disabled?` |
| `label`                                                          | `string`                                       | —                 | Nhãn trường                                      |
| `placeholder`                                                    | `string`                                       | `'Vui lòng chọn'` | Tùy chọn đầu tiên có giá trị rỗng                |
| `helperText`, `error`                                            | `string`                                       | —                 | Hướng dẫn hoặc thông báo lỗi                     |
| `value`, `defaultValue`, `onChange`, `required`, `disabled`, ... | Theo `SelectHTMLAttributes<HTMLSelectElement>` | `defaultValue=""` | Hỗ trợ controlled/uncontrolled                   |

### Checkbox

`label` là bắt buộc. Có thể dùng các props chuẩn của input checkbox (trừ `type`), gồm `checked`, `defaultChecked` và `onChange`.

```tsx
<Checkbox
  label="Đang kinh doanh"
  description="Cho phép bán sản phẩm này."
  checked={active}
  onChange={(event) => setActive(event.target.checked)}
/>
<Checkbox label="Chọn một phần" indeterminate />
```

Props riêng: `label: string`, `description?: string`, `indeterminate?: boolean`, `id?: string`, `disabled?: boolean`. `indeterminate` được áp dụng trực tiếp lên input DOM.

### Radio

Dùng chung `name` cho các lựa chọn thuộc cùng một nhóm. Hỗ trợ các props chuẩn của input radio (trừ `type`).

```tsx
<Radio name="delivery" value="standard" label="Tiêu chuẩn" checked={delivery === 'standard'} onChange={() => setDelivery('standard')} />
<Radio name="delivery" value="express" label="Nhanh" checked={delivery === 'express'} onChange={() => setDelivery('express')} />
```

Props riêng: `label: string`, `description?: string`, `id?: string`, `disabled?: boolean`.

### Tabs

`TabItem` có `id`, `label`, `content` và `disabled?`. Có thể điều khiển tab bằng cặp `activeId` + `onChange`; nếu không, dùng `defaultActiveId`.

```tsx
<Tabs
  ariaLabel="Thông tin sản phẩm"
  tabs={[
    { id: 'details', label: 'Chi tiết', content: <p>Mô tả sản phẩm</p> },
    { id: 'stock', label: 'Tồn kho', content: <p>Số lượng tồn</p> },
  ]}
  activeId={tab}
  onChange={setTab}
/>
```

Props: `tabs: TabItem[]`, `defaultActiveId?: string`, `activeId?: string`, `onChange?: (id: string) => void`, `ariaLabel?: string` (mặc định `'Tabs'`), `className?: string`. Component hỗ trợ phím mũi tên, Home và End để điều hướng.

## Component hiển thị dữ liệu và trạng thái

### Table

`Table` là component generic: cung cấp kiểu dữ liệu cho hàng bằng `Table<Product>`. `rowKey` phải ổn định và duy nhất trong danh sách.

```tsx
interface Product {
  id: string;
  name: string;
  quantity: number;
}

<Table<Product>
  caption="Sản phẩm"
  columns={[
    { key: 'name', title: 'Tên sản phẩm' },
    { key: 'quantity', title: 'Số lượng', align: 'right' },
  ]}
  data={products}
  rowKey="id"
  emptyText="Chưa có sản phẩm."
/>;
```

| Prop        | Kiểu                                            | Mặc định              | Ghi chú                                                                    |
| ----------- | ----------------------------------------------- | --------------------- | -------------------------------------------------------------------------- |
| `columns`   | `TableColumn<T>[]`                              | Bắt buộc              | Mỗi cột có `key`, `title`; tùy chọn `render(row, index)`, `width`, `align` |
| `data`      | `T[]`                                           | Bắt buộc              | Các hàng dữ liệu                                                           |
| `rowKey`    | `keyof T \| ((row, index) => string \| number)` | Bắt buộc              | Khóa React cho mỗi hàng                                                    |
| `caption`   | `string`                                        | —                     | Tiêu đề bảng cho trợ năng                                                  |
| `loading`   | `boolean`                                       | `false`               | Hiển thị hàng đang tải                                                     |
| `error`     | `string \| null`                                | `null`                | Hiển thị lỗi thay cho dữ liệu                                              |
| `onRetry`   | `() => void`                                    | —                     | Hiện nút thử lại khi có lỗi                                                |
| `emptyText` | `string`                                        | `'Không có dữ liệu.'` | Nội dung khi danh sách rỗng                                                |
| `className` | `string`                                        | `''`                  | Class bổ sung cho vùng bọc                                                 |

Thứ tự ưu tiên trạng thái trong bảng là `loading` → `error` → danh sách rỗng → dữ liệu.

### Dialog

Dialog được điều khiển bằng `open`; tự đóng khi nhấn Escape hoặc click nền nếu không ở trạng thái `busy`.

```tsx
<Dialog
  open={open}
  title="Xác nhận xóa"
  onClose={() => setOpen(false)}
  onConfirm={deleteProduct}
  confirmText="Xóa"
  cancelText="Hủy"
  destructive
  busy={deleting}
>
  <p>Thao tác này không thể hoàn tác.</p>
</Dialog>
```

Props bắt buộc: `open: boolean`, `title: string`, `children: ReactNode`, `onClose: () => void`. Props tùy chọn: `onConfirm`, `confirmText` (mặc định `'Xác nhận'`), `cancelText` (mặc định `'Quay lại'`), `destructive`, `busy`, `confirmDisabled`. Nút xác nhận chỉ hiện khi có `onConfirm`.

### Toast

Toast chỉ hiển thị thông báo; component không tự đóng theo thời gian. Quản lý vòng đời bằng state của màn hình gọi.

```tsx
<Toast
  variant="success"
  title="Đã lưu"
  description="Thông tin sản phẩm đã được cập nhật."
  onClose={() => setToast(null)}
/>
```

Props: `title: string`, `description?: string`, `variant?: 'info' | 'success' | 'warning' | 'error'` (mặc định `'info'`), `icon?: ReactNode`, `onClose?: () => void`, `className?: string`.

### OrderStatusBadge

Nhận `status` theo union `OrderStatus`: `'NEW' | 'RESERVED' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED' | 'REJECTED' | 'RETURNED'`.

```tsx
<OrderStatusBadge status="CONFIRMED" />
```

Component tự hiển thị nhãn tiếng Việt tương ứng; không truyền chuỗi tùy ý.

### Skeleton và PageLoader

`Skeleton` dùng cho placeholder của một khối; `PageLoader` dùng khi chuyển trang trong vùng nội dung và giữ lại header/menu.

```tsx
<Skeleton width="40%" height={24} />
<Skeleton lines={3} />
<PageLoader label="Đang tải danh sách..." />
```

`Skeleton` props: `width?: number | string` (mặc định `'100%'`), `height?: number | string` (mặc định `16`), `lines?: number` (mặc định `1`), `className?`, `style?: CSSProperties`. `PageLoader` có `label?: string` (mặc định `'Đang tải…'`).

### EmptyState và ErrorState

Chỉ render `EmptyState` sau khi API đã trả danh sách rỗng. Chỉ đưa nút hành động vào khi người dùng có quyền thực hiện hành động đó.

```tsx
<EmptyState
  message="Chưa có phiếu nhập nào"
  description="Tạo phiếu để bắt đầu theo dõi hàng vào kho."
  actionLabel="Tạo phiếu nhập"
  onAction={createReceipt}
/>
<EmptyState message="" filtered onClearFilters={clearFilters} />
<ErrorState message="Không tải được dữ liệu." traceId={traceId} onRetry={reload} />
```

`EmptyState` props: `message: string`, `description?`, `actionLabel?`, `onAction?`, `filtered?: boolean`, `onClearFilters?`, `icon?: ReactNode`. `ErrorState` props: `message: string`, `onRetry?: () => void`, `traceId?: string`.

### AsyncBlock

Dùng để chuẩn hóa trạng thái của một vùng dữ liệu theo thứ tự tải → lỗi → rỗng → nội dung. Trong lúc tải, không truyền `isEmpty` thay cho kết quả API.

```tsx
<AsyncBlock
  isLoading={query.isLoading}
  error={query.error}
  isEmpty={!query.isLoading && !query.error && query.data.length === 0}
  onRetry={query.refetch}
  empty={{ message: 'Chưa có sản phẩm.' }}
>
  <ProductTable products={query.data} />
</AsyncBlock>
```

Props: `isLoading: boolean`, `error?: unknown`, `isEmpty?: boolean` (mặc định `false`), `onRetry?: () => void`, `empty?: EmptyStateProps`, `skeleton?: ReactNode`, `children: ReactNode`. Mặc định skeleton tải là ba dòng; sau 300 ms mới hiện loading và sau 15 giây loading được chuyển thành lỗi timeout.

## Biểu đồ

Import từ `components/charts`. `RevenuePoint` dùng `date: string` dạng `YYYY-MM-DD` và `revenue: number` nguyên theo VND; `SparkPoint` dùng `date: string` và `value: number`.

```tsx
import { RevenueAreaChart, Sparkline } from '../components/charts';
import type { RevenuePoint, SparkPoint } from '../components/charts';

const revenue: RevenuePoint[] = [
  { date: '2026-10-17', revenue: 25_100_000 },
  { date: '2026-10-18', revenue: 22_400_000 },
];
const trend: SparkPoint[] = revenue.map(({ date, revenue: value }) => ({ date, value }));

<RevenueAreaChart data={revenue} height={240} ariaLabel="Doanh thu hai ngày" />
<Sparkline data={trend} tone="info" height={56} ariaLabel="Xu hướng doanh thu" />
```

| Component          | Props                                                                                                                                                                                      |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `RevenueAreaChart` | `data: readonly RevenuePoint[]` (bắt buộc), `height?: number` (mặc định `320`), `ariaLabel?: string` (nếu bỏ trống tự tóm tắt tổng doanh thu)                                              |
| `Sparkline`        | `data: readonly SparkPoint[]` (bắt buộc), `tone?: 'primary' \| 'info' \| 'danger'` (mặc định `'primary'`), `height?: number` (mặc định `56`), `ariaLabel?: string` (mặc định `'Xu hướng'`) |

`RevenueTooltipContent` được export để tái sử dụng nội dung tooltip tương thích với Recharts; thường không cần gọi trực tiếp. Các hàm định dạng `formatVnd`, `formatVndCompact`, `formatDayLabel` và `formatDayFull` cũng được export từ `components/charts`.

## Quy ước khi sử dụng

- Dùng barrel exports (`components/ui` hoặc `components/charts`) và props TypeScript; không sao chép lại markup/CSS của component chung.
- Giữ state nghiệp vụ trong màn hình gọi; các component nhận dữ liệu và callback qua props.
- Cung cấp nhãn/`ariaLabel`, thông báo lỗi rõ ràng và `rowKey` ổn định khi phù hợp.
- Chọn đúng phạm vi trạng thái: `Skeleton` cho khối, `PageLoader` cho vùng nội dung khi chuyển trang, `AsyncBlock` cho vòng đời tải dữ liệu.
- Xem [`ComponentShowcase`](../pages/ComponentShowcase.tsx) để tham khảo các trạng thái tương tác đang được duy trì trong mã nguồn.
