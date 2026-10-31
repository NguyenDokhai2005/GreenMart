import { useEffect, useId, useRef, type ReactNode } from 'react';
import Button from './Button';
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
  const titleId = useId();
  const dialogRef = useRef<HTMLElement>(null);

  useEffect(() => {
    if (!open) return;

    const dialog = dialogRef.current;

    const previousFocusedElement =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;

    dialog?.focus();

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();

        if (!busy) {
          onClose();
        }
        return;
      }

      if (event.key === 'Tab' && dialog) {
        const focusableElements = dialog.querySelectorAll<HTMLElement>(
          [
            'button:not(:disabled)',
            'input:not(:disabled)',
            'select:not(:disabled)',
            'textarea:not(:disabled)',
            'a[href]',
            '[tabindex]:not([tabindex="-1"])',
          ].join(','),
        );

        const elements = Array.from(focusableElements).filter(
          (element) => element.getAttribute('aria-hidden') !== 'true',
        );

        if (elements.length === 0) {
          event.preventDefault();
          dialog.focus();
          return;
        }

        const firstElement = elements[0];
        const lastElement = elements[elements.length - 1];

        if (
          event.shiftKey &&
          (document.activeElement === firstElement || document.activeElement === dialog)
        ) {
          event.preventDefault();
          lastElement.focus();
        } else if (
          !event.shiftKey &&
          (document.activeElement === lastElement || document.activeElement === dialog)
        ) {
          event.preventDefault();
          firstElement.focus();
        }
      }
    };

    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = previousOverflow;

      if (previousFocusedElement?.isConnected) {
        previousFocusedElement.focus();
      }
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
        ref={dialogRef}
        className="gm-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
      >
        <header className="gm-dialog__header">
          <h2 id={titleId} className="gm-dialog__title">
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
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            {cancelText}
          </Button>

          {onConfirm && (
            <Button
              variant={destructive ? 'destructive' : 'primary'}
              onClick={onConfirm}
              disabled={busy || confirmDisabled}
            >
              {busy ? 'Đang xử lý…' : confirmText}
            </Button>
          )}
        </footer>
      </section>
    </div>
  );
}
