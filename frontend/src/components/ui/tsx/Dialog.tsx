import { useEffect, type ReactNode } from 'react';
import '../css/Dialog.css';

export interface DialogProps {
  open: boolean;
  title: string;
  children: ReactNode;
  onClose: () => void;
  onConfirm?: () => void;
  confirmText?: string;
  cancelText?: string;
  destructive?: boolean;
  busy?: boolean;
  confirmDisabled?: boolean;
}

export default function Dialog({
  open,
  title,
  children,
  onClose,
  onConfirm,
  confirmText = 'Xác nhận',
  cancelText = 'Quay lại',
  destructive = false,
  busy = false,
  confirmDisabled = false,
}: DialogProps) {
  useEffect(() => {
    if (!open) return;

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) {
        onClose();
      }
    };

    document.addEventListener('keydown', handleKeyDown);

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [open, busy, onClose]);

  if (!open) return null;

  return (
    <div
      className="gm-dialog-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !busy) {
          onClose();
        }
      }}
    >
      <section
        className="gm-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="gm-dialog-title"
      >
        <header className="gm-dialog__header">
          <h2 id="gm-dialog-title" className="gm-dialog__title">
            {title}
          </h2>

          <button
            type="button"
            className="gm-dialog__close"
            aria-label="Đóng hộp thoại"
            onClick={onClose}
            disabled={busy}
          >
            ×
          </button>
        </header>

        <div className="gm-dialog__body">{children}</div>

        <footer className="gm-dialog__footer">
          <button
            type="button"
            className="gm-dialog__button gm-dialog__button--secondary"
            onClick={onClose}
            disabled={busy}
          >
            {cancelText}
          </button>

          {onConfirm && (
            <button
              type="button"
              className={`gm-dialog__button ${
                destructive ? 'gm-dialog__button--danger' : 'gm-dialog__button--primary'
              }`}
              onClick={onConfirm}
              disabled={busy || confirmDisabled}
            >
              {busy ? 'Đang xử lý…' : confirmText}
            </button>
          )}
        </footer>
      </section>
    </div>
  );
}
