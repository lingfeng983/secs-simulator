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
  await expect(row.locator('td').nth(3)).toHaveText('5000');
  await expect(page.locator('.data-table tbody')).not.toContainText(':5000');
});

test('配置页不再显示"与磁盘一致"', async ({ page }) => {
  await page.goto('/#/device/line1/config');
  await expect(page.locator('#panel-config')).toBeVisible();

  await expect(page.getByText('与磁盘一致')).toHaveCount(0);
});

test('设备子页顶部操作条统一：同高、有底、主按钮在最右', async ({ page }) => {
  const cases = [
    { hash: '#/device/line1/connection', primary: '保存' },
    { hash: '#/device/line1/structure', primary: '保存' },
    { hash: '#/device/line1/config', primary: '保存' },
    { hash: '#/device/line1/send', primary: '发送 SML' },
    { hash: '#/device/line1/alarms', primary: '置位报警' },
    { hash: '#/device/line1/ceids', primary: '新增事件' },
    { hash: '#/device/line1/events', primary: '新增计划' },
    { hash: '#/device/line1/svids', primary: '新增状态变量' },
  ] as const;

  for (const item of cases) {
    await page.goto(`/${item.hash}`);

    const head = page.locator('.tab-page .panel-head').first();
    await expect(head, `命令栏应可见：${item.hash}`).toBeVisible();

    /* 一条浅色命令栏，而不是几个按钮直接浮在页面顶端 */
    const background = await head.evaluate((element) => getComputedStyle(element).backgroundColor);
    expect(background, `命令栏应有底色：${item.hash}`).not.toBe('rgba(0, 0, 0, 0)');

    const buttons = head.locator('.panel-actions > button');
    const count = await buttons.count();
    expect(count, `命令栏应有按钮：${item.hash}`).toBeGreaterThan(0);

    /* 所有按钮同一高度 */
    for (let index = 0; index < count; index += 1) {
      const height = await buttons.nth(index).evaluate((element) => element.getBoundingClientRect().height);
      expect(Math.round(height), `按钮高度应统一：${item.hash} #${index}`).toBe(30);
    }

    /* 主操作固定在最右 */
    const last = buttons.last();
    await expect(last).toHaveText(item.primary);
    await expect(last).toHaveClass(/primary/);

    /* 说明文字与按钮不能互相压住（换行也算正常，但矩形不能重叠） */
    const desc = head.locator('.panel-desc').first();
    if (await desc.count() > 0) {
      const descBox = await desc.boundingBox();
      const actionsBox = await head.locator('.panel-actions').first().boundingBox();
      if (descBox !== null && actionsBox !== null) {
        const overlaps = !(
          descBox.x + descBox.width <= actionsBox.x + 1
          || actionsBox.y >= descBox.y + descBox.height
          || descBox.y >= actionsBox.y + actionsBox.height
        );
        expect(overlaps, `说明与操作条重叠：${item.hash}`).toBe(false);
      }
    }

    /* 命令栏不该是一大块：单行按钮 + 一句说明 */
    const headHeight = await head.evaluate((element) => element.getBoundingClientRect().height);
    expect(headHeight, `命令栏过高：${item.hash}`).toBeLessThanOrEqual(96);
  }
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
  await page.goto('/#/device/line1/events');

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

  await page.goto('/#/device/line1/config');
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
    for (const hash of ['#/device/line1/config', '#/device/line1/send', '#/device/line1/connection']) {
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
