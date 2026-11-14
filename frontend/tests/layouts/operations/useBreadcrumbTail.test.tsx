import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { BreadcrumbContext } from '../../../src/layouts/operations/BreadcrumbContext';
import { useBreadcrumbTail } from '../../../src/layouts/operations/useBreadcrumbTail';

describe('useBreadcrumbTail', () => {
  it('đặt đoạn cuối breadcrumb và bỏ đi khi rời trang', () => {
    const setTail = vi.fn();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <BreadcrumbContext.Provider value={{ setTail }}>{children}</BreadcrumbContext.Provider>
    );
    const { unmount } = renderHook(() => useBreadcrumbTail('CK-GM001-20261107-004'), { wrapper });
    expect(setTail).toHaveBeenLastCalledWith('CK-GM001-20261107-004');
    unmount();
    expect(setTail).toHaveBeenLastCalledWith(undefined);
  });

  it('ngoài OperationsLayout không làm gì và không lỗi', () => {
    expect(() => renderHook(() => useBreadcrumbTail('x'))).not.toThrow();
  });
});
