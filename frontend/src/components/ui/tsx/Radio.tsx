
import { useId } from "react";
import type { InputHTMLAttributes } from "react";
import "../css/Radio.css";

export interface RadioProps
  extends Omit<
    InputHTMLAttributes<HTMLInputElement>,
    "type"
  > {
  label: string;
  description?: string;
}

export default function Radio({
  label,
  description,
  id,
  disabled = false,
  className = "",
  ...props
}: RadioProps) {
  const generatedId = useId();
  const radioId = id ?? generatedId;
  const descriptionId = `${radioId}-description`;

  const inputClasses = [
    "gm-radio__input",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div className="gm-radio">
      <input
        {...props}
        id={radioId}
        type="radio"
        disabled={disabled}
        aria-describedby={
          description ? descriptionId : undefined
        }
        className={inputClasses}
      />

      <div className="gm-radio__content">
        <label
          htmlFor={radioId}
          className="gm-radio__label"
        >
          {label}
        </label>

        {description && (
          <p
            id={descriptionId}
            className="gm-radio__description"
          >
            {description}
          </p>
        )}
      </div>
    </div>
  );
}
