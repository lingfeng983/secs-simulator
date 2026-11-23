import { expect, test, type Page } from '@playwright/test';

test.skip(process.env.E2E_REAL_URL === undefined, '需要 E2E_REAL_URL 指向真实 secs-sim');

/**
 * 真实后端 E2E：页面由 Java 进程同源托管（dist），API 不做任何拦截。
 *
 * 这一套测的是「计划执行完之后，界面还能不能真的点」。它和 scripts/e2e
 * （mock 版）的分工是：mock 版证明组件渲染，这一套证明前后端契约仍然对得上。
 *
 * 结构按「两段式」信息架构组织：
 *   设备 配置区 11 页 / 运行区 3 页
 *   主机 配置区 4 页 / 运行区 3 页
 *   独立工具 4 页 + 工作台
 * 最后一组是「按钮扫荡」：逐页点所有非破坏性按钮，只断言不出错。
 */

/*
 * 页签文案 → 面板 id。
 *
 * 配置区与运行区有同名页（cycle / plans），所以配置区的面板 id 带 -config 后缀
 * 来区分；这是刻意的约定，不是随手命名。文案或 id 变了这里就要跟着改——
 * 它们是前后端与测试之间的契约。
 */
const DEVICE_CONFIG: ReadonlyArray<[string, string]> = [
  ['概览', 'overview'],
  ['链接配置', 'connection'],
  ['结构配置', 'structure'],
  ['设备常量', 'constants-config'],
  ['晶圆属性', 'context-config'],
  ['变量配置', 'svid-config'],
  ['事件与报告', 'ceid-report-config'],
  ['工艺周期', 'cycle'],
  ['事件计划', 'plan-config'],
  ['全量配置', 'config'],
  ['版本历史', 'revisions'],
];

const DEVICE_RUNTIME: ReadonlyArray<[string, string]> = [
  ['事件执行', 'events'],
  ['工艺周期', 'cycle-run'],
  ['消息发送', 'send'],
  ['报警风暴', 'alarms'],
];

const HOST_CONFIG: ReadonlyArray<[string, string]> = [
  ['概览', 'host-overview'],
  ['报文模板', 'host-presets'],
  ['Trace 设置', 'host-trace'],
  ['采集设置', 'host-collect'],
];

const HOST_RUNTIME: ReadonlyArray<[string, string]> = [
  ['消息发送', 'host-send'],
  ['Trace 采集', 'host-trace'],
  ['数据采集', 'host-collect'],
];

/** 这些按钮会改数据或停机，扫荡时跳过。 */
const DESTRUCTIVE = /删除|清空|重置|停止|断开|关闭|禁用|清除|退出|关机|下发|重载|保存|回滚/;

/** 收集页面级 JS 异常与 console.error，供每个用例收尾断言。 */
function watchErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => {
    if (m.type() === 'error') {
      const text = m.text();
      /* 资源 404 是静态托管的事，单独由导航用例覆盖，这里只记真正的脚本错误 */
      if (!/Failed to load resource|favicon/.test(text)) {
        errors.push('console: ' + text);
      }
    }
  });
  return errors;
}

async function gotoDevice(page: Page, index = 0): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: '设备清单' }).first().click();
  await page.locator('#panel-sessions').waitFor({ state: 'visible' });
  const rows = page.locator('#panel-sessions tbody tr');
  await expect(rows.first()).toBeVisible();
  await rows.nth(index).click();
  await expect(page.locator('.zone-switch')).toBeVisible();
}

async function openDevicePage(
  page: Page,
  zone: 'config' | 'runtime',
  label: string,
  panelId: string,
): Promise<void> {
  await page
    .locator('.zone-switch')
    .getByRole('tab', { name: zone === 'config' ? /配置区/ : /运行区/ })
    .click();
  const tab = page.locator('.subtabs').getByRole('tab', { name: label, exact: true });
  await expect(tab).toBeVisible();
  await tab.click();
  await expect(page.locator(`#panel-${panelId}`)).toBeVisible();
}

async function gotoHost(page: Page, index = 0): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: '主机清单' }).first().click();
  await page.locator('#panel-hosts-v2').waitFor({ state: 'visible' });
  const rows = page.locator('#panel-hosts-v2 tbody tr');
  await expect(rows.first()).toBeVisible();
  await rows.nth(index).getByRole('button', { name: '详情' }).click();
  await expect(page.locator('.zone-switch')).toBeVisible();
}

async function openHostPage(
  page: Page,
  zone: 'config' | 'runtime',
  label: string,
  panelId: string,
): Promise<void> {
  await page
    .locator('.zone-switch')
    .getByRole('tab', { name: zone === 'config' ? /配置区/ : /运行区/ })
    .click();
  const tab = page.locator('.subtabs').getByRole('tab', { name: label, exact: true });
  await expect(tab).toBeVisible();
  await tab.click();
  await expect(page.locator(`#panel-${panelId}`)).toBeVisible();
}

/* ─────────────────────────── 种子数据 ─────────────────────────── */

test.beforeAll(async ({ request }) => {
  /* 1. 至少两台设备：模板建出来的配置才有 SVID / 计划 / 事件可点 */
  const configs = await (await request.get('/api/v1/configs')).json();
  if (!Array.isArray(configs.configs) || configs.configs.length === 0) {
    for (const t of ['etch', 'cvd']) {
      await request.post('/api/v1/configs', { data: { name: `${t}.json`, template: t } });
    }
  }

  /*
   * 2. 把设备都启动起来。
   * 运行区的三个页在设备未启动时只会渲染 empty-zone 占位（这是刻意的产品行为），
   * 要测真实面板就必须先有会话。已经在跑的跳过，避免端口重复绑定。
   */
  const running = new Set(
    ((await (await request.get('/api/v1/sessions')).json()).sessions ?? []).map(
      (s: { config?: string }) => s.config ?? '',
    ),
  );
  const all = (await (await request.get('/api/v1/configs')).json()).configs ?? [];
  for (const item of all) {
    const name = item.name as string;
    if (!running.has(name)) {
      await request.post('/api/v1/sessions', { data: { config: name } });
    }
  }

  /* 3. 至少一台主机：主机两段式页签需要进详情页 */
  const hosts = await (await request.get('/api/v2/hosts')).json();
  const list = hosts.hosts ?? hosts.items ?? [];
  if (!Array.isArray(list) || list.length === 0) {
    await request.post('/api/v2/hosts', {
      data: { name: 'e2e-host', host: '127.0.0.1', port: 5999, deviceId: 10, connect: false },
    });
  }
});

/* ─────────────────────── 1. 设备两段式全页签 ─────────────────────── */

test('设备 · 配置区 11 页逐页可见且无脚本异常', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/#/workbench');
  await gotoDevice(page);
  for (const [label, id] of DEVICE_CONFIG) {
    await openDevicePage(page, 'config', label, id);
  }
  expect(errors, '配置区遍历期间不应有脚本异常').toEqual([]);
});

test('设备 · 运行区 3 页逐页可见且无脚本异常', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/#/workbench');
  await gotoDevice(page);
  for (const [label, id] of DEVICE_RUNTIME) {
    await openDevicePage(page, 'runtime', label, id);
  }
  expect(errors, '运行区遍历期间不应有脚本异常').toEqual([]);
});

/* ─────────────────────── 2. 主机两段式全页签 ─────────────────────── */

test('主机 · 配置区 4 页 + 运行区 3 页逐页可见', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/#/workbench');
  await gotoHost(page);
  for (const [label, id] of HOST_CONFIG) {
    await openHostPage(page, 'config', label, id);
  }
  for (const [label, id] of HOST_RUNTIME) {
    await openHostPage(page, 'runtime', label, id);
  }
  expect(errors, '主机页遍历期间不应有脚本异常').toEqual([]);
});

/* ───────────────────────── 3. 独立工具页 ───────────────────────── */

test('独立工具 4 页（主机模板 / 设备模板 / 参数模板 / 审计）可进入', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/#/workbench');
  const tools: ReadonlyArray<[string, string]> = [
    ['主机模板', 'host-templates'],
    ['设备模板', 'device-templates'],
    ['参数模板', 'param-templates'],
    ['审计', 'audit'],
  ];
  for (const [nav, panel] of tools) {
    await page.locator('.sidenav .nav-item', { hasText: nav }).first().click();
    const byId = page.locator(`#panel-${panel}`);
    if (await byId.count()) {
      await expect(byId).toBeVisible();
    } else {
      /* 面板 id 与导航名不同名时，退化为断言主区域渲染出了表格或空态 */
      await expect(
        page.locator('main').getByRole('table').or(page.locator('main .empty-state')).first(),
      ).toBeVisible();
    }
  }
  expect(errors, '独立工具页遍历期间不应有脚本异常').toEqual([]);
});

/* ─────────────────── 4. 设备页关键交互（真点击） ─────────────────── */

test('设备 · 变量配置：逐变量曲线预览能出图', async ({ page }) => {
  await page.goto('/#/workbench');
  await gotoDevice(page);
  await openDevicePage(page, 'config', '变量配置', 'svid-config');
  const panel = page.locator('#panel-svid-config');
  await expect(panel.getByRole('row').nth(1)).toBeVisible();
  await panel.getByRole('button', { name: '预览', exact: true }).first().click();
  /* 预览走 Modal + ParamCurveChart（SVG，不是 canvas）；Overlay 是 createPortal 到 body 的 */
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.locator('svg.param-curve-chart').first()).toBeVisible();
});

test('设备 · 事件执行：计划启停可来回切换', async ({ page }) => {
  await page.goto('/#/workbench');
  await gotoDevice(page);
  await openDevicePage(page, 'runtime', '事件执行', 'events');
  const panel = page.locator('#panel-events');
  /* #panel-events 自己就是 region「事件执行」 */
  await expect(panel).toBeVisible();

  const disable = panel.getByRole('button', { name: '禁用', exact: true }).first();
  if (await disable.isVisible()) {
    await disable.click();
    await expect(panel.getByRole('button', { name: '启用', exact: true }).first()).toBeVisible();
    /* 还原，避免影响后续用例与人工查看 */
    await panel.getByRole('button', { name: '启用', exact: true }).first().click();
    await expect(panel.getByRole('button', { name: '禁用', exact: true }).first()).toBeVisible();
  } else {
    /* 内置模板不带事件计划：空态下「全部启动/停止」必须禁用，不能是能点但无效果的假按钮 */
    await expect(panel.getByText(/还没有事件计划/)).toBeVisible();
    await expect(panel.getByRole('button', { name: '全部启动' })).toBeDisabled();
    await expect(panel.getByRole('button', { name: '全部停止' })).toBeDisabled();
  }
});

test('设备 · 全量配置：自动载入本设备配置且无多余提示', async ({ page }) => {
  await page.goto('/#/workbench');
  await gotoDevice(page);
  await openDevicePage(page, 'config', '全量配置', 'config');
  await expect(
    page.locator('#panel-config textarea').or(page.locator('#panel-config .config-editor')).first(),
  ).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/已打开本设备的配置/)).toHaveCount(0);
});

test('设备 · 版本历史：表格渲染且能触发对比', async ({ page }) => {
  await page.goto('/#/workbench');
  await gotoDevice(page);
  await openDevicePage(page, 'config', '版本历史', 'revisions');
  const panel = page.locator('#panel-revisions');
  await expect(panel.getByRole('row').nth(1)).toBeVisible();
  /* 「对比当前草稿」是这一页唯一安全的动作按钮；无未保存草稿时按设计禁用 */
  const compare = panel.getByRole('button', { name: /对比/ }).first();
  await expect(compare).toBeVisible();
  if (await compare.isEnabled()) {
    await compare.click();
  } else {
    /* 禁用必须有明确原因（没有草稿可对比），不是渲染坏了的假按钮 */
    await expect(compare).toBeDisabled();
    await expect(compare).toHaveAttribute('title', /草稿/);
  }
});

/* ───────────────────────── 5. 弹窗与浮层 ───────────────────────── */

test('弹窗：新增设备打开后可用 Esc 关闭', async ({ page }) => {
  await page.goto('/#/workbench');
  await page.locator('.sidenav .nav-item', { hasText: '设备清单' }).first().click();
  await page.locator('#panel-sessions').waitFor({ state: 'visible' });
  await page.getByRole('button', { name: /新建|新增设备/ }).first().click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
});

test('弹窗：实时消息监控抽屉展开时，弹窗底部按钮仍可点击（层级回归）', async ({ page }) => {
  await page.goto('/#/workbench');
  await page.locator('.sidenav .nav-item', { hasText: '设备清单' }).first().click();
  await page.locator('#panel-sessions').waitFor({ state: 'visible' });

  /* 拉开右侧抽屉 */
  const drawerToggle = page.getByRole('button', { name: /消息监控|监控/ }).first();
  if (await drawerToggle.isVisible()) {
    await drawerToggle.click();
    await page.waitForTimeout(300);
  }

  await page.getByRole('button', { name: /新建|新增设备/ }).first().click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();

  /* 关键断言：底部按钮中心点命中的是按钮自己，不是抽屉 */
  const cancel = dialog.locator('.modal-foot').getByRole('button').first();
  const hit = await cancel.evaluate((el) => {
    const r = el.getBoundingClientRect();
    const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    return top === el || el.contains(top) || (top?.contains(el) ?? false);
  });
  expect(hit, '弹窗底部按钮不应被抽屉遮挡').toBe(true);
});

test('命令面板可打开并关闭', async ({ page }) => {
  await page.goto('/#/workbench');
  await page.keyboard.press('Control+k');
  const palette = page.locator('.command-palette, [role="dialog"]').first();
  if (await palette.isVisible()) {
    await page.keyboard.press('Escape');
    await expect(palette).toHaveCount(0);
  }
});

/* ─────────────────── 6. 按钮扫荡：只点不破坏的 ─────────────────── */

test('按钮扫荡：全站非破坏性按钮点击后不出现 JS 异常与错误提示', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/#/workbench');

  const targets: ReadonlyArray<[string, string]> = [
    ['设备清单', '#panel-sessions'],
    ['主机清单', '#panel-hosts-v2'],
    ['主机模板', '#panel-host-templates'],
    ['设备模板', '#panel-device-templates'],
    ['参数模板', '#panel-param-templates'],
    ['审计', '#panel-audit'],
  ];

  let clicked = 0;
  for (const [nav, panelSel] of targets) {
    const navItem = page.locator('.sidenav .nav-item', { hasText: nav }).first();
    if (!(await navItem.count())) {
      continue;
    }
    await navItem.click();
    await page.waitForTimeout(200);
    const panel = page.locator(panelSel);
    if (!(await panel.count())) {
      continue;
    }
    const buttons = panel.getByRole('button');
    const total = Math.min(await buttons.count(), 14);
    for (let i = 0; i < total; i += 1) {
      const btn = buttons.nth(i);
      if (!(await btn.isVisible().catch(() => false))) {
        continue;
      }
      const name = ((await btn.textContent()) ?? '').trim();
      if (!name || DESTRUCTIVE.test(name)) {
        continue;
      }
      await btn.click({ timeout: 3_000 }).catch(() => undefined);
      await page.waitForTimeout(120);
      /* 弹窗/抽屉可能被打开，关掉再继续 */
      const dialog = page.getByRole('dialog');
      if (await dialog.isVisible().catch(() => false)) {
        await page.keyboard.press('Escape');
        await page.waitForTimeout(80);
      }
      clicked += 1;
    }
  }

  /* 至少点到了东西，否则说明选择器全失效，这个用例就变成了空转 */
  expect(clicked, '扫荡应至少点到 10 个按钮').toBeGreaterThan(10);
  const errorToasts = await page.locator('.toast.toast-error').count();
  expect(errorToasts, '扫荡后不应出现错误提示').toBe(0);
  expect(errors, '扫荡期间不应有脚本异常').toEqual([]);
});
