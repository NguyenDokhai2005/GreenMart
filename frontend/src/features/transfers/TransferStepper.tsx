import { TRANSFER_STEPS } from './transferStatus';
import type { StepState } from './transferStatus';

const Check = () => (
  <svg
    width="14"
    height="14"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="3"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d="M5 12l5 5 9-10" />
  </svg>
);
const Cross = () => (
  <svg
    width="14"
    height="14"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="3"
    strokeLinecap="round"
    aria-hidden="true"
  >
    <path d="M6 6l12 12M18 6L6 18" />
  </svg>
);

export default function TransferStepper({
  states,
}: {
  states: readonly [StepState, StepState, StepState];
}) {
  return (
    <ol className="ck-stepper" aria-label="Tiến trình phiếu chuyển kho">
      {TRANSFER_STEPS.map((step, i) => {
        const state = states[i] ?? 'todo';
        return (
          <li
            key={step.title}
            className={`ck-step ck-step--${state}`}
            aria-current={state === 'current' ? 'step' : undefined}
          >
            <span className="ck-step__dot">
              {state === 'done' ? <Check /> : state === 'cancelled' ? <Cross /> : i + 1}
            </span>
            <span className="ck-step__text">
              <b>{step.title}</b>
              <small>{step.who}</small>
            </span>
          </li>
        );
      })}
    </ol>
  );
}
