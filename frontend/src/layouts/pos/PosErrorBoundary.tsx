import { Component, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
  fallback: (error: unknown) => ReactNode;
}

export default class PosErrorBoundary extends Component<
  Props,
  { hasError: boolean; error?: unknown }
> {
  state: { hasError: boolean; error?: unknown } = { hasError: false };

  static getDerivedStateFromError(error: unknown) {
    return { hasError: true, error };
  }

  render() {
    return this.state.hasError ? this.props.fallback(this.state.error) : this.props.children;
  }
}
