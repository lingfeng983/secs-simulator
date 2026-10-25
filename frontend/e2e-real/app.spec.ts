import { expect, test, type Page } from '@playwright/test';

const token = process.env.E2E_REAL_TOKEN ?? 'e2e-token';

test.skip(process.env.E2E_REAL_URL === undefined, '需要 E2E_REAL_URL 指向真实 secs-sim');

/** 精简后的导航:工作台看设备列表(含会话表),设备子页一页一能力。 */

/** 打开一台设备的某个子页(先经工作台确认设备在,再走侧栏+子页 Tab)。 */
async function openDevicePage(page: Page, device: string, tabLabel: string, panelId: string): Promise<void> {
  await page.locator('.sidenav .nav-group', { hasText: '设备' }).locator('.nav-item', { hasText: device }).first().click();
  await expect(page.locator('#panel-preview')).toBeVisible();
  await page.getByRole('tab', { name: tabLabel, exact: true }).click();
  await expect(page.locator(`#panel-${panelId}`)).toBeVisible();
}

test('real backend: identity, preview, uPlot and event toggle', async ({ page }) => {
  await page.goto(`/#token=${token}`);

  await expect(page.getByText('管理员').first()).toBeVisible();

  /* 工作台:会话表里的设备列表 */
  await page.locator('.sidenav .nav-item', { hasText: '工作台' }).first().click();
  await expect(page.getByRole('cell', { name: 'demo' }).first()).toBeVisible();

  /* 设备页·曲线 */
  await openDevicePage(page, 'demo', '曲线', 'preview');
  await page.getByRole('button', { name: '刷新曲线' }).click();
  await expect(page.locator('.spark canvas').first()).toBeVisible();

  await page.locator('label:has-text("图表") select').selectOption('uplot');
  await expect(page.locator('.uplot-block').first()).toBeVisible();
  await expect(page.locator('.uplot-host canvas').first()).toBeVisible();

  /* 设备页·事件 */
  await openDevicePage(page, 'demo', '事件', 'events');
  const eventSection = page.locator('#panel-events');
  await expect(eventSection.getByRole('cell', { name: 'demo-process-start' })).toBeVisible();
  await eventSection.getByRole('button', { name: '禁用' }).click();
  await expect(eventSection.getByRole('button', { name: '启用' })).toBeVisible();
});
