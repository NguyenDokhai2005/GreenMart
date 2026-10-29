
import { useEffect, useId, useRef } from "react";
import type { InputHTMLAttributes } from "react";
import "../css/Checkbox.css";

export interface CheckboxProps
  extends Omit<
    InputHTMLAttributes<HTMLInputElement>,
    "type"
  > {
  label: string;
  description?: string;
  indeterminate?: boolean;
}

export default function Checkbox({
  label,
  description,
  indeterminate = false,
  id,
  disabled = false,
  className = "",
  ...props
}: CheckboxProps) {
  const generatedId = useId();
  const checkboxId = id ?? generatedId;
  const descriptionId = `${checkboxId}-description`;
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.indeterminate = indeterminate;
    }
  }, [indeterminate]);

  const inputClasses = [
    "gm-checkbox__input",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div className="gm-checkbox">
      <input
        {...props}
        ref={inputRef}
        id={checkboxId}
        type="checkbox"
        disabled={disabled}
        aria-describedby={
          description ? descriptionId : undefined
        }
        className={inputClasses}
      />

      <div className="gm-checkbox__content">
        <label
          htmlFor={checkboxId}
          className="gm-checkbox__label"
        >
          {label}
        </label>

        {description && (
          <p
            id={descriptionId}
            className="gm-checkbox__description"
          >
            {description}
          </p>
        )}
      </div>
    </div>
  );
}
