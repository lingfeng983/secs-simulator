import { expect, test, type Page } from '@playwright/test';

const token = process.env.E2E_REAL_TOKEN ?? 'e2e-token';

test.skip(process.env.E2E_REAL_URL === undefined, '需要 E2E_REAL_URL 指向真实 secs-sim');

/** 切到某个面板。界面改成侧边栏导航以后要先把面板点出来。 */
async function gotoPanel(page: Page, label: string, id: string): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: label }).first().click();
  await expect(page.locator(`#panel-${id}`)).toBeVisible();
}

test('real backend: identity, preview, uPlot and event toggle', async ({ page }) => {
  await page.goto(`/#token=${token}`);

  await expect(page.getByText('管理员').first()).toBeVisible();
  await expect(page.getByRole('cell', { name: 'demo' }).first()).toBeVisible();

  await gotoPanel(page, '曲线预览', 'preview');
  await page.getByRole('button', { name: '刷新曲线' }).click();
  await expect(page.locator('.spark canvas').first()).toBeVisible();

  await page.locator('label:has-text("图表") select').selectOption('uplot');
  await expect(page.locator('.uplot-block').first()).toBeVisible();
  await expect(page.locator('.uplot-host canvas').first()).toBeVisible();

  await gotoPanel(page, '事件编排', 'events');
  const eventSection = page.locator('#panel-events');
  await expect(eventSection.getByRole('cell', { name: 'demo-process-start' })).toBeVisible();
  await eventSection.getByRole('button', { name: '禁用' }).click();
  await expect(eventSection.getByRole('button', { name: '启用' })).toBeVisible();
});
