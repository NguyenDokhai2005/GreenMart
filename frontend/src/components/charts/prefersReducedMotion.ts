/** Tắt hiệu ứng vẽ biểu đồ khi người dùng chọn giảm chuyển động. */
export function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  );
}
