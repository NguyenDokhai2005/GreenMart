
import { useId } from "react";
import type { InputHTMLAttributes } from "react";
import "../css/Input.css";

export interface InputProps
  extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  helperText?: string;
  error?: string;
}

export default function Input({
  label,
  helperText,
  error,
  id,
  required = false,
  disabled = false,
  className = "",
  ...props
}: InputProps) {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const helperId = `${inputId}-helper`;
  const errorId = `${inputId}-error`;

  const describedBy = [
    helperText ? helperId : undefined,
    error ? errorId : undefined,
  ]
    .filter(Boolean)
    .join(" ") || undefined;

  const inputClasses = [
    "gm-input",
    error ? "gm-input--error" : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div className="gm-field">
      {label && (
        <label
          className="gm-field__label"
          htmlFor={inputId}
        >
          {label}
          {required && (
            <span
              className="gm-field__required"
              aria-hidden="true"
            >
              {" "}*
            </span>
          )}
        </label>
      )}

      <input
        {...props}
        id={inputId}
        required={required}
        disabled={disabled}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={inputClasses}
      />

      {helperText && !error && (
        <p
          id={helperId}
          className="gm-field__helper"
        >
          {helperText}
        </p>
      )}

      {error && (
        <p
          id={errorId}
          className="gm-field__error"
          role="alert"
        >
          {error}
        </p>
      )}
    </div>
  );
}
