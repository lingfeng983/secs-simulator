import { expect, test, type Page } from '@playwright/test';

test.skip(process.env.E2E_REAL_URL === undefined, '需要 E2E_REAL_URL 指向真实 secs-sim');

/**
 * 真实后端 E2E:页面由 Java 进程同源托管(dist),API 不做拦截。
 * 设备页为两段式:配置区(概览/连接/结构/配置原文/设备常量/版本历史)
 * 与运行区(事件计划/事件与报告/状态变量/手动操作)。
 */

/** 从「设备清单」总览表进入第一台设备,切到指定区/子页。 */
async function openFirstDevicePage(
  page: Page,
  zone: 'config' | 'runtime',
  tabLabel: string,
  panelId: string,
): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: '设备清单' }).first().click();
  await page.locator('#panel-sessions').waitFor({ state: 'visible' });
  /* 总览表第一行进设备页(默认落在运行区首页) */
  await page.locator('#panel-sessions tbody tr').first().click();
  await page
    .locator('.zone-switch')
    .getByRole('tab', { name: zone === 'config' ? /配置区/ : /运行区/ })
    .click();
  const tab = page.locator('.subtabs').getByRole('tab', { name: tabLabel });
  await expect(tab).toBeVisible();
  await tab.click();
  await expect(page.locator(`#panel-${panelId}`)).toBeVisible();
}

test('real backend: workbench table, svid preview, plan toggle and config auto-open', async ({ page }) => {
  await page.goto('/#/workbench');

  /* 设备清单:总览表在,行内能看到设备名 */
  await page.locator('.sidenav .nav-item', { hasText: '设备清单' }).first().click();
  await expect(page.locator('main table tbody tr').first()).toBeVisible();
  await expect(page.locator('#panel-sessions')).toBeVisible();

  /* 状态变量:目录可见,逐变量曲线预览出图 */
  await openFirstDevicePage(page, 'runtime', '状态变量', 'svids');
  const svids = page.locator('#panel-svids');
  await expect(svids.getByRole('row').nth(1)).toBeVisible();
  await svids.getByRole('button', { name: '预览', exact: true }).first().click();
  await expect(page.locator('#panel-svids .variable-chart canvas').first()).toBeVisible();

  /* 事件计划:计划表可启停 */
  await openFirstDevicePage(page, 'runtime', '事件计划', 'events');
  const events = page.locator('#panel-events');
  await expect(events.getByRole('row').nth(1)).toBeVisible();
  const firstToggle = events.getByRole('button', { name: '禁用', exact: true }).first();
  if (await firstToggle.isVisible()) {
    await firstToggle.click();
    await expect(events.getByRole('button', { name: '启用', exact: true }).first()).toBeVisible();
  }

  /* 配置原文:自动打开本设备的配置文件（只显示 JSON，不再有"已打开…"提示） */
  await openFirstDevicePage(page, 'config', '配置原文', 'config');
  await expect(page.locator('#panel-config textarea.config-editor')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/已打开本设备的配置/)).toHaveCount(0);
});
