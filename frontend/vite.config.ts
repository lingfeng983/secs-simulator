import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 开发时用 Vite dev server，把 /api 代理到本地后端；
 * 生产时 vite build 产出 dist，由后端以 --web-root 同源托管。
 */
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:5000',
        changeOrigin: true,
      },
    },
  },
});
