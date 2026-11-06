import type { ReactNode } from 'react';
import '../components/ui/css/states.css';

export interface SpecialPageProps {
  code?: string;
  title: string;
  description?: string;
  traceId?: string;
  actions: ReactNode;
}

/** Khung chung cho 5 trang lỗi cả trang. Render trong layout (giữ header và menu). */
export function SpecialPage({ code, title, description, traceId, actions }: SpecialPageProps) {
  return (
    <section className="ui-special-page" role="alert">
      {code && <p className="ui-special-page__code">{code}</p>}
      <h1 className="ui-state-title">{title}</h1>
      {description && <p className="ui-state-desc">{description}</p>}
      {traceId && <p className="ui-error-state__trace">Mã lỗi: {traceId}</p>}
      <div className="ui-special-page__actions">{actions}</div>
    </section>
  );
}