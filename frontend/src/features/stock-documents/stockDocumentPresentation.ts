import type { ExportReason, StockDocumentStatus, StockDocumentType } from './stockDocumentApi';

export const STOCK_DOCUMENT_TYPE_LABELS: Record<StockDocumentType, string> = {
  IMPORT: 'Nhập kho',
  EXPORT: 'Xuất hủy',
  TRANSFER: 'Chuyển kho',
  ADJUST: 'Điều chỉnh',
};

export const STOCK_DOCUMENT_STATUS_LABELS: Record<StockDocumentStatus, string> = {
  DRAFT: 'Nháp',
  APPROVED: 'Đã duyệt',
  IN_TRANSIT: 'Đang vận chuyển',
  RECEIVED: 'Đã nhận',
  CANCELLED: 'Đã hủy',
};

export const EXPORT_REASON_OPTIONS: readonly { value: ExportReason; label: string }[] = [
  { value: 'EXPIRED', label: 'Hết hạn' },
  { value: 'DAMAGED', label: 'Hư hỏng' },
  { value: 'LOST', label: 'Thất lạc' },
  { value: 'OTHER', label: 'Lý do khác' },
];

export function parseStockDocumentStatus(value: string | null): StockDocumentStatus | undefined {
  return value === 'DRAFT' ||
    value === 'APPROVED' ||
    value === 'IN_TRANSIT' ||
    value === 'RECEIVED' ||
    value === 'CANCELLED'
    ? value
    : undefined;
}

export function formatStockDocumentDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('vi-VN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

export function formatStockDocumentMoney(value: number): string {
  return new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
    maximumFractionDigits: 0,
  }).format(value);
}

export function exportReasonLabel(value: string | null | undefined): string {
  return EXPORT_REASON_OPTIONS.find((option) => option.value === value)?.label ?? '—';
}
