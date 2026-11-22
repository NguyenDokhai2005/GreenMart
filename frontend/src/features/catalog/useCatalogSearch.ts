import { useEffect, useState } from 'react';
import { useLocation, useNavigationType, useSearchParams } from 'react-router';

export function useCatalogSearch() {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const navigationType = useNavigationType();
  const rawQ = params.get('q') ?? '';
  const q = rawQ.trim();
  const [searchText, setSearchText] = useState(rawQ);
  const [locationKey, setLocationKey] = useState(location.key);
  if (locationKey !== location.key) {
    setLocationKey(location.key);
    if (navigationType === 'POP') setSearchText(rawQ);
  }
  useEffect(() => {
    if (params.get('mode') === 'phone') return;
    const normalized = searchText.trim();
    if (normalized === q && rawQ === q) return;
    const timer = setTimeout(
      () =>
        setParams(
          (previous) => {
            const next = new URLSearchParams(previous);
            if (normalized) next.set('q', normalized);
            else next.delete('q');
            next.set('page', '0');
            return next;
          },
          { replace: true },
        ),
      300,
    );
    return () => clearTimeout(timer);
  }, [searchText, q, rawQ, params, setParams]);
  const rawPage = Number(params.get('page') ?? 0);
  const rawSize = Number(params.get('size') ?? 10);
  return {
    q,
    searchText,
    onSearch: setSearchText,
    page: Number.isSafeInteger(rawPage) && rawPage >= 0 ? rawPage : 0,
    size: [3, 10, 20, 50].includes(rawSize) ? rawSize : 10,
    change: (changes: Record<string, string | null>) => {
      if ('q' in changes) setSearchText((changes.q ?? '').trim());
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          for (const [key, value] of Object.entries(changes)) {
            const normalized = value === null ? null : key === 'q' ? value.trim() : value;
            if (normalized === null || normalized === '') next.delete(key);
            else next.set(key, normalized);
          }
          return next;
        },
        { replace: true },
      );
    },
  };
}
