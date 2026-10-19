import { defineConfig, devices } from '@playwright/test';

/**
 * 前端 E2E：用 Vite preview 托管构建产物，API 全部由 Playwright 拦截。
 *
 * 这样不需要真的启动 Java 进程，也不会受本机端口/防火墙影响；
 * 测试证明的是"页面在真实 Chromium 里能加载、能点击、能渲染 uPlot"。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1400, height: 1100 } },
    },
  ],
});
