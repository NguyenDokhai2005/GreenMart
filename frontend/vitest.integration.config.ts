import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['tests/integration/**/*.integration.tsx'],
    testTimeout: 20_000,
    fileParallelism: false,
  },
});
