import type { ReactNode } from "react";
import "../css/Toast.css";

export type ToastVariant =
  | "info"
  | "success"
  | "warning"
  | "error";

export interface ToastProps {
  title: string;
  description?: string;
  variant?: ToastVariant;
  icon?: ReactNode;
  onClose?: () => void;
  className?: string;
}

const defaultIcons: Record<ToastVariant, string> = {
  info: "ⓘ",
  success: "✓",
  warning: "!",
  error: "×",
};

export default function Toast({
  title,
  description,
  variant = "info",
  icon,
  onClose,
  className = "",
}: ToastProps) {
  return (
    <div
      className={`gm-toast gm-toast--${variant} ${className}`.trim()}
      role={variant === "error" ? "alert" : "status"}
    >
      <span className="gm-toast__icon" aria-hidden="true">
        {icon ?? defaultIcons[variant]}
      </span>

      <div className="gm-toast__content">
        <div className="gm-toast__title">
          {title}
        </div>

        {description && (
          <div className="gm-toast__description">
            {description}
          </div>
        )}
      </div>

      {onClose && (
        <button
          type="button"
          className="gm-toast__close"
          aria-label="Đóng thông báo"
          onClick={onClose}
        >
          ×
        </button>
      )}
    </div>
  );
}