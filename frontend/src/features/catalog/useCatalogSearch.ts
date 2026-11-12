import { useEffect, useState } from 'react';
import { useLocation, useNavigationType, useSearchParams } from 'react-router';

export function useCatalogSearch() {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const navigationType = useNavigationType();
  const q = params.get('q') ?? '';
  const [searchText, setSearchText] = useState(q);
  const [locationKey, setLocationKey] = useState(location.key);
  if (locationKey !== location.key) {
    setLocationKey(location.key);
    if (navigationType === 'POP') setSearchText(q);
  }
  useEffect(() => {
    if (params.get('mode') === 'phone') return;
    if (searchText === q) return;
    const timer = setTimeout(
      () =>
        setParams(
          (previous) => {
            const next = new URLSearchParams(previous);
            if (searchText) next.set('q', searchText);
            else next.delete('q');
            next.set('page', '0');
            return next;
          },
          { replace: true },
        ),
      300,
    );
    return () => clearTimeout(timer);
  }, [searchText, q, params, setParams]);
  const rawPage = Number(params.get('page') ?? 0);
  const rawSize = Number(params.get('size') ?? 10);
  return {
    q,
    searchText,
    onSearch: setSearchText,
    page: Number.isSafeInteger(rawPage) && rawPage >= 0 ? rawPage : 0,
    size: [3, 10, 20, 50].includes(rawSize) ? rawSize : 10,
    change: (changes: Record<string, string | null>) => {
      if ('q' in changes) setSearchText(changes.q ?? '');
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          for (const [key, value] of Object.entries(changes)) {
            if (value === null || value === '') next.delete(key);
            else next.set(key, value);
          }
          return next;
        },
        { replace: true },
      );
    },
  };
}
