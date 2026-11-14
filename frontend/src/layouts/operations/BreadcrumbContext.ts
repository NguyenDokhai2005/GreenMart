import { createContext } from 'react';

export interface BreadcrumbContextValue {
  /** Đặt đoạn cuối của breadcrumb (vd mã phiếu); undefined để bỏ. */
  setTail: (tail: string | undefined) => void;
}

export const BreadcrumbContext = createContext<BreadcrumbContextValue | undefined>(undefined);
