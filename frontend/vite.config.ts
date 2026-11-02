import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'GREENMART_');
  const target =
    env.GREENMART_API_PROXY_TARGET ||
    (mode === 'mock' ? 'http://127.0.0.1:4010' : 'http://localhost:8080');
  const mockProxy = mode === 'mock';

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': {
          target,
          changeOrigin: true,
          ...(mockProxy
            ? { rewrite: (path: string) => path.replace(/^\/api\/v1(?=\/|$)/, '') || '/' }
            : {}),
        },
      },
    },
  };
});
