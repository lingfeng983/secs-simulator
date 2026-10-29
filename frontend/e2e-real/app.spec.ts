import { expect, test, type Page } from '@playwright/test';

test.skip(process.env.E2E_REAL_URL === undefined, '需要 E2E_REAL_URL 指向真实 secs-sim');

/**
 * 真实后端 E2E:页面由 Java 进程同源托管(dist),API 不做拦截。
 * 设备子页为 事件/报告/事件编排/报警/状态变量/连接/结构/配置/发送/消息。
 */

/** 从工作台设备总览表进入第一台设备的指定子页。 */
async function openFirstDeviceTab(page: Page, tabLabel: string, panelId: string): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: '工作台' }).first().click();
  await page.locator('#panel-sessions').waitFor({ state: 'visible' });
  /* 总览表第一行(排除表头):点设备名进设备页 */
  await page.locator('main table tbody tr').first().getByRole('button', { name: /.+/ }).first().click();
  await page.getByRole('tab', { name: '事件', exact: true }).waitFor({ state: 'visible' });
  if (tabLabel !== '事件') {
    await page.getByRole('tab', { name: tabLabel, exact: true }).click();
  }
  await expect(page.locator(`#panel-${panelId}`)).toBeVisible();
}

test('real backend: workbench table, svid preview, plan toggle and config auto-open', async ({ page }) => {
  await page.goto('/#/workbench');

  /* 工作台:设备总览表与会话管理都在,行内能看到设备名 */
  await page.locator('.sidenav .nav-item', { hasText: '工作台' }).first().click();
  await expect(page.locator('main table tbody tr').first()).toBeVisible();
  await expect(page.locator('#panel-sessions')).toBeVisible();

  /* 状态变量:目录可见,逐变量曲线预览出图 */
  await openFirstDeviceTab(page, '状态变量', 'svids');
  const svids = page.locator('#panel-svids');
  await expect(svids.getByRole('row').nth(1)).toBeVisible();
  await svids.getByRole('button', { name: '预览', exact: true }).first().click();
  await expect(page.locator('#panel-svids .variable-chart canvas').first()).toBeVisible();

  /* 事件编排:计划表可启停 */
  await openFirstDeviceTab(page, '事件编排', 'events');
  const events = page.locator('#panel-events');
  await expect(events.getByRole('row').nth(1)).toBeVisible();
  const firstToggle = events.getByRole('button', { name: '禁用', exact: true }).first();
  if (await firstToggle.isVisible()) {
    await firstToggle.click();
    await expect(events.getByRole('button', { name: '启用', exact: true }).first()).toBeVisible();
  }

  /* 配置:自动打开本设备的配置文件（只显示 JSON，不再有"已打开…"提示） */
  await openFirstDeviceTab(page, '配置', 'config');
  await expect(page.locator('#panel-config textarea.config-editor')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/已打开本设备的配置/)).toHaveCount(0);
});
