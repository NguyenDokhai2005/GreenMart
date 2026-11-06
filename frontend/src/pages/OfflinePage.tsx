import { useEffect, useState } from 'react';
import { SpecialPage } from './SpecialPage';

export interface OfflinePageProps {
  /** Gọi khi có mạng trở lại để tải lại dữ liệu của trang. */
  onReconnect?: () => void;
}

/** Mất kết nối: tự thử lại khi có mạng (sự kiện `online`). */
export default function OfflinePage({
  onReconnect = () => window.location.reload(),
}: OfflinePageProps) {
  const [online, setOnline] = useState(() => navigator.onLine);

  useEffect(() => {
    const handleOnline = () => {
      setOnline(true);
      onReconnect();
    };
    const handleOffline = () => setOnline(false);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, [onReconnect]);

  return (
    <SpecialPage
      title="Mất kết nối"
      description={
        online
          ? 'Đã có mạng, đang tải lại…'
          : 'Không gọi được máy chủ. Trang sẽ tự thử lại khi có mạng.'
      }
      actions={
        <button
          type="button"
          className="ui-state-btn ui-state-btn--secondary"
          onClick={onReconnect}
        >
          Thử lại ngay
        </button>
      }
    />
  );
}
