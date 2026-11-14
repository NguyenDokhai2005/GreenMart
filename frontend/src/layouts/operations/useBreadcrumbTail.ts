import { useContext, useEffect } from 'react';
import { BreadcrumbContext } from './BreadcrumbContext';

/**
 * Trang con đặt đoạn cuối của breadcrumb, ví dụ "Kho / Chuyển kho / CK-GM001-20261107-004".
 * Rời trang thì tự bỏ. Ngoài OperationsLayout hook không làm gì.
 */
export function useBreadcrumbTail(tail: string | undefined) {
  const setTail = useContext(BreadcrumbContext)?.setTail;
  useEffect(() => {
    setTail?.(tail);
    return () => setTail?.(undefined);
  }, [setTail, tail]);
}
