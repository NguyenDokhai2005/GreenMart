import { useCallback } from 'react';
import { useCatalogQuery } from '../catalog/useCatalogQuery';
import { dashboardApi } from './dashboardApi';

export function useLowStockAlerts(branchId: number | undefined) {
  const load = useCallback(
    (signal: AbortSignal) => dashboardApi.lowStockAlerts({ branchId }, signal),
    [branchId],
  );
  const query = useCatalogQuery(`low-stock:${branchId ?? 'all'}`, load);

  return {
    ...query,
    data: query.data
      ? [...query.data].sort((a, b) => {
          if (a.severity !== b.severity) return a.severity === 'OUT' ? -1 : 1;
          return b.openedAt.localeCompare(a.openedAt);
        })
      : undefined,
  };
}
