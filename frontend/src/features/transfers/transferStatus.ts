import type { TransferStatus } from './transferApi';

export const TRANSFER_STATUS_LABEL: Record<TransferStatus, string> = {
  DRAFT: 'Nháp',
  APPROVED: 'Đã duyệt',
  IN_TRANSIT: 'Đang vận chuyển',
  RECEIVED: 'Đã nhận',
  CANCELLED: 'Đã hủy',
};

export const TRANSFER_STATUS_TONE: Record<
  TransferStatus,
  'draft' | 'transit' | 'received' | 'cancelled'
> = {
  DRAFT: 'draft',
  APPROVED: 'transit',
  IN_TRANSIT: 'transit',
  RECEIVED: 'received',
  CANCELLED: 'cancelled',
};

/** Trạng thái chuyển kho dùng ở bộ lọc (APPROVED không xuất hiện với phiếu chuyển). */
export const TRANSFER_FILTER_STATUSES = ['DRAFT', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED'] as const;
export type TransferFilterStatus = (typeof TRANSFER_FILTER_STATUSES)[number];

export type StepState = 'done' | 'current' | 'todo' | 'cancelled';

/** Khi lập phiếu mới: đang ở bước 1. */
export const CREATE_STEP_STATES: readonly [StepState, StepState, StepState] = [
  'current',
  'todo',
  'todo',
];

export const TRANSFER_STEPS = [
  { title: 'Lập phiếu', who: 'Thủ kho / QL CN nguồn' },
  { title: 'Duyệt xuất', who: 'QL chi nhánh nguồn' },
  { title: 'Nhận hàng', who: 'Kho nhận' },
] as const;

/** Trạng thái từng bước của luồng 3 bước (UC-07). */
export function stepStates(status: TransferStatus): [StepState, StepState, StepState] {
  switch (status) {
    case 'DRAFT':
      return ['done', 'current', 'todo'];
    case 'APPROVED':
    case 'IN_TRANSIT':
      return ['done', 'done', 'current'];
    case 'RECEIVED':
      return ['done', 'done', 'done'];
    case 'CANCELLED':
      return ['done', 'cancelled', 'todo'];
  }
}

/** Dòng phụ dưới tiêu đề trang chi tiết, theo Figma ("Bước 2 · Duyệt xuất tại kho gửi"...). */
export function stepSubtitle(status: TransferStatus, hasDiscrepancy: boolean): string {
  switch (status) {
    case 'DRAFT':
      return 'Bước 2 · Duyệt xuất tại kho gửi';
    case 'APPROVED':
    case 'IN_TRANSIT':
      return 'Bước 3 · Nhận hàng tại kho đích';
    case 'RECEIVED':
      return hasDiscrepancy
        ? 'Hoàn thành · Đã nhận hàng, có chênh lệch'
        : 'Hoàn thành · Đã nhận hàng';
    case 'CANCELLED':
      return 'Phiếu đã hủy';
  }
}
