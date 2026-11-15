import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

/**
 * 本轮 UI 整改的回归：端口列、状态提示、设备子页操作条、导出落点、侧栏拖宽。
 *
 * <p>这些是"看着对"最容易回退的部分：DOM 结构在、CSS 一改就可能又变回
 * 一排高度不一的按钮。用计算样式与交互结果断言，而不是只查 class 名。</p>
 */

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

test('工作台端口列只显示数字，不带冒号', async ({ page }) => {
  await page.goto('/#/workbench');

  const row = page.locator('.data-table tbody tr').first();
  await expect(row.locator('td').nth(2)).toHaveText('5000');
  await expect(page.locator('.data-table tbody')).not.toContainText(':5000');
});

test('配置页不再显示"与磁盘一致"', async ({ page }) => {
  await page.goto('/#/device/line1/config/document');
  await expect(page.locator('#panel-config')).toBeVisible();

  await expect(page.getByText('与磁盘一致')).toHaveCount(0);
});

test('工作台在桌面宽度下没有横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/#/workbench');

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(1);
});

test('事件编排的计划显示归属，并按 CEID 的归属过滤', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/plans');

  const panel = page.locator('#panel-events');
  await expect(panel.locator('th', { hasText: '归属' })).toBeVisible();
  /* 计划 beat 引用 CEID 2001，mock 目录里它属于 CH1 */
  await expect(panel.locator('tbody tr').first()).toContainText('CH1');
});

test('导出会弹出保存位置，并在提示里说明存到哪里', async ({ page }) => {
  await page.addInitScript(() => {
    const host = window as unknown as {
      __savedNames: string[];
      showSaveFilePicker?: (options?: { suggestedName?: string }) => Promise<unknown>;
    };
    host.__savedNames = [];
    host.showSaveFilePicker = async (options) => {
      host.__savedNames.push(options?.suggestedName ?? '');
      return {
        name: options?.suggestedName ?? 'export.json',
        createWritable: async () => ({
          write: async () => undefined,
          close: async () => undefined,
        }),
      };
    };
  });

  await page.goto('/#/device/line1/config/document');
  await page.getByRole('button', { name: '导出 JSON' }).click();

  await expect.poll(
    () => page.evaluate(() => (window as unknown as { __savedNames: string[] }).__savedNames),
  ).toContain('line1.json');
  await expect(page.getByText(/已保存到「line1\.json」/)).toBeVisible();
});

test('不支持保存对话框时退回下载，并提示下载目录', async ({ page }) => {
  await page.addInitScript(() => {
    (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker = undefined;
  });

  await page.goto('/#/workbench');
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出 CSV' }).click();

  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('sessions.csv');
  await expect(page.getByText(/已开始下载「sessions\.csv」/)).toBeVisible();
});

test.describe('窄屏（390px）', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('设备命令栏换行，页面不横向溢出', async ({ page }) => {
    for (const hash of ['#/device/line1/config/document', '#/device/line1/runtime/manual', '#/device/line1/config/connection']) {
      await page.goto(`/${hash}`);
      /* hash 导航是同文档切换：刷新一次，确保量到的是这一页自己的布局 */
      await page.reload();
      await expect(page.locator('.tab-page .panel-head').first()).toBeVisible();

      await expect.poll(
        () => page.evaluate(
          () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
        ),
        { message: `横向溢出：${hash}` },
      ).toBeLessThanOrEqual(1);
    }
  });
});

test('左侧菜单可以拖动改变宽度，并记住宽度', async ({ page }) => {
  await page.goto('/#/workbench');

  const handle = page.locator('.sidenav-resizer');
  await expect(handle).toBeVisible();

  const before = await page.locator('.sidenav').evaluate((element) => element.getBoundingClientRect().width);
  const box = await handle.boundingBox();
  expect(box).not.toBeNull();
  if (box === null) {
    return;
  }

  await page.mouse.move(box.x + box.width / 2, box.y + 200);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 80, box.y + 200, { steps: 6 });
  await page.mouse.up();

  const after = await page.locator('.sidenav').evaluate((element) => element.getBoundingClientRect().width);
  expect(after - before).toBeGreaterThan(50);

  const stored = await page.evaluate(() => {
    const raw = localStorage.getItem('secs-sim-ui:preferences');
    return raw === null ? 0 : (JSON.parse(raw) as { sidebarWidth?: number }).sidebarWidth ?? 0;
  });
  expect(stored).toBeGreaterThan(240);

  /* 键盘也能调：焦点在手柄上按方向键 */
  await handle.focus();
  const beforeKey = await page.locator('.sidenav').evaluate((element) => element.getBoundingClientRect().width);
  await page.keyboard.press('ArrowLeft');
  await expect.poll(
    () => page.locator('.sidenav').evaluate((element) => element.getBoundingClientRect().width),
  ).toBeLessThan(beforeKey);

  /* 双击恢复默认宽度 */
  await handle.dblclick();
  await expect.poll(
    () => page.locator('.sidenav').evaluate((element) => Math.round(element.getBoundingClientRect().width)),
  ).toBe(248);

  /* 折叠后手柄必须消失：折叠态没有"宽度"可拖 */
  await page.getByRole('button', { name: '收起导航栏' }).click();
  await expect(page.locator('.sidenav-resizer')).toHaveCount(0);
});
