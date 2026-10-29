
import type { ButtonHTMLAttributes } from "react";
import "../css/Button.css";

export type ButtonVariant =
  | "primary"
  | "secondary"
  | "destructive";

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
}

export default function Button({
  children,
  variant = "primary",
  type = "button",
  disabled = false,
  className = "",
  ...props
}: ButtonProps) {
  const classes = [
    "gm-button",
    `gm-button--${variant}`,
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <button
      {...props}
      type={type}
      disabled={disabled}
      className={classes}
    >
      {children}
    </button>
  );
}
