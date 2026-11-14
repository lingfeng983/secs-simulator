import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

test('command palette moves focus in and out', async ({ page }) => {
  await page.goto('/#/workbench');
  await page.getByRole('button', { name: '切换主题' }).focus();
  await page.keyboard.press('Control+k');
  await expect(page.locator('.palette')).toBeVisible();
  /* 打开即聚焦输入框 */
  await expect(page.locator('.palette-input')).toBeFocused();
  /* Esc 关闭后焦点回到原处 */
  await page.keyboard.press('Escape');
  await expect(page.locator('.palette')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '切换主题' })).toBeFocused();
});

test('shortcut dialog traps Tab inside the overlay', async ({ page }) => {
  await page.goto('/#/workbench');
  await page.keyboard.press('?');
  const dialog = page.locator('.shortcut-help');
  await expect(dialog).toBeVisible();
  for (let index = 0; index < 6; index += 1) {
    await page.keyboard.press('Tab');
    const inside = await dialog.evaluate((node) => node.contains(document.activeElement));
    expect(inside).toBe(true);
  }
});

test('density toggle changes the row height token', async ({ page }) => {
  await page.goto('/#/workbench');
  const before = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--row-pad').trim());
  await page.getByRole('button', { name: '切换密度' }).click();
  const after = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--row-pad').trim());
  expect(after).not.toBe(before);
  await expect(page.locator('html')).toHaveAttribute('data-density', 'compact');
});

test('sidebar collapses to an icon rail and keeps navigation usable', async ({ page }) => {
  await page.goto('/#/workbench');
  await page.getByRole('button', { name: '收起导航栏' }).click();
  const side = page.locator('.sidenav');
  await expect(side).toHaveClass(/collapsed/);
  /* 折叠后设备项只剩"首字母徽标 + tooltip",仍要能点进设备页 */
  await expect(side.locator('.nav-monogram').first()).toHaveText('LI');
  await side.locator('.nav-item[title^="LINE1"]').click();
  /* 3.0 默认落运行区首页·事件计划 */
  await expect(page.locator('.subtabs').getByRole('tab', { name: '事件计划', exact: true }))
    .toHaveAttribute('aria-selected', 'true');
});

test('form fields keep the label on the left and the control on the right', async ({ page }) => {
  /* 结构化表单已从「配置」移到「连接」；字段排布规则不变 */
  await page.goto('/#/device/line1/config/connection');
  await page.waitForTimeout(800);

  const fields = page.locator('#panel-connection .field');
  const count = await fields.count();
  expect(count).toBeGreaterThan(3);

  for (let index = 0; index < Math.min(count, 6); index += 1) {
    const field = fields.nth(index);
    /* 描述是元素节点(标签列);控件是 input/select/textarea。
       注意:label 的第一个子节点可能是文本节点,不能按 children[0] 取。 */
    const label = await field.evaluate((node) => {
      const child = Array.from(node.children).find((item) =>
        !(item instanceof HTMLElement)
        || !['INPUT', 'SELECT', 'TEXTAREA'].includes(item.tagName));
      return child instanceof HTMLElement ? child.getBoundingClientRect() : null;
    });
    const control = await field.evaluate((node) => {
      const children = Array.from(node.children);
      const control = children.find((child) =>
        child instanceof HTMLElement
        && ['INPUT', 'SELECT', 'TEXTAREA'].includes(child.tagName));
      return control instanceof HTMLElement ? control.getBoundingClientRect() : null;
    });
    if (label === null || control === null) {
      continue;
    }
    /* 控件在描述右侧 */
    expect(control.x).toBeGreaterThanOrEqual(label.x + label.width - 1);
    /* 同一行:垂直区间有重叠 */
    const overlap = Math.min(label.y + label.height, control.y + control.height)
      - Math.max(label.y, control.y);
    expect(overlap).toBeGreaterThan(0);
    /* 上下居中:两者的垂直中心差不超过控件高度的一半 */
    const labelMid = label.y + label.height / 2;
    const controlMid = control.y + control.height / 2;
    expect(Math.abs(labelMid - controlMid)).toBeLessThan(control.height / 2 + 1);
  }
});
