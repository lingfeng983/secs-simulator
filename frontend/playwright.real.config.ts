import { defineConfig, devices } from '@playwright/test';

/**
 * 真实后端 E2E：页面由 Java 进程同源托管，API 不做拦截。
 *
 * 运行前需要外部启动 secs-sim（见 CI 的 frontend-e2e-real job），
 * 并用 E2E_REAL_URL / E2E_REAL_TOKEN 指向它。
 */
export default defineConfig({
  testDir: './e2e-real',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_REAL_URL ?? 'http://127.0.0.1:5000',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium-real',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1400, height: 1100 } },
    },
  ],
});
