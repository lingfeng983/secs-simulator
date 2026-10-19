import { expect, test } from '@playwright/test';

const token = process.env.E2E_REAL_TOKEN ?? 'e2e-token';

test.skip(process.env.E2E_REAL_URL === undefined, '需要 E2E_REAL_URL 指向真实 secs-sim');

test('real backend: identity, preview, uPlot and event toggle', async ({ page }) => {
  await page.goto(`/#token=${token}`);

  await expect(page.getByText('管理员').first()).toBeVisible();
  await expect(page.getByRole('cell', { name: 'demo' }).first()).toBeVisible();

  await page.getByRole('button', { name: '刷新曲线' }).click();
  await expect(page.locator('.spark canvas').first()).toBeVisible();

  await page.locator('label:has-text("图表") select').selectOption('uplot');
  await expect(page.locator('.uplot-block').first()).toBeVisible();
  await expect(page.locator('.uplot-host canvas').first()).toBeVisible();

  const eventSection = page.locator('section').filter({ hasText: '事件编排' }).first();
  await expect(eventSection.getByRole('cell', { name: 'demo-process-start' })).toBeVisible();
  await eventSection.getByRole('button', { name: '禁用' }).click();
  await expect(eventSection.getByRole('button', { name: '启用' })).toBeVisible();
});
