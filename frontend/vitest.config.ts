import { defineConfig } from 'vitest/config';

/** Vitest 只跑 src 下的单元/组件测试；e2e/ 归 Playwright。 */
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
});
