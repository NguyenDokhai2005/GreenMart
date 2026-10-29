
import { useId } from "react";
import type { SelectHTMLAttributes } from "react";
import "../css/Select.css";

export interface SelectOption {
  label: string;
  value: string;
  disabled?: boolean;
}

export interface SelectProps
  extends Omit<SelectHTMLAttributes<HTMLSelectElement>, "children"> {
  label?: string;
  options: SelectOption[];
  placeholder?: string;
  helperText?: string;
  error?: string;
}

export default function Select({
  label,
  options,
  placeholder = "Vui lòng chọn",
  helperText,
  error,
  id,
  required = false,
  disabled = false,
  className = "",
  ...props
}: SelectProps) {
  const generatedId = useId();
  const selectId = id ?? generatedId;
  const helperId = `${selectId}-helper`;
  const errorId = `${selectId}-error`;

  const describedBy = [
    helperText ? helperId : undefined,
    error ? errorId : undefined,
  ]
    .filter(Boolean)
    .join(" ") || undefined;

  const selectClasses = [
    "gm-select",
    error ? "gm-select--error" : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div className="gm-field gm-select-field">
      {label && (
        <label
          className="gm-field__label"
          htmlFor={selectId}
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

      <div className="gm-select__wrapper">
        <select
          {...props}
          id={selectId}
          required={required}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={selectClasses}
        >
          <option value="" disabled>
            {placeholder}
          </option>

          {options.map((option) => (
            <option
              key={option.value}
              value={option.value}
              disabled={option.disabled}
            >
              {option.label}
            </option>
          ))}
        </select>

        <svg
          className="gm-select__icon"
          viewBox="0 0 20 20"
          width="20"
          height="20"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="m5 7.5 5 5 5-5"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </div>

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
