import { expect, test } from '@playwright/test';
import { mockApi, openDeviceFromSidebar, openSubTab, switchZone } from './fixtures';

/**
 * 设备域：配置区 6 页 + 运行区 6 页，每页的关键控件都点一遍。
 *
 * <p>断言分两层：页面"该出现的东西在"（内容与空态），以及按钮"真的打到后端"
 * （可变 mock 的 calls / 状态）。只查 DOM 存在会把"点了没反应"放过去。</p>
 */

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

/** 从工作台进设备的运行区（侧栏树是最短路径）。 */
async function gotoDevice(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/');
  await openDeviceFromSidebar(page, 'LINE1');
}

/* ==================== 配置区 ==================== */

test('配置区·概览：设备定义摘要一眼读完', async ({ page }) => {
  await page.goto('/#/device/line1/config/overview');
  /* 子页签已说明是哪一页：面板不再有标题行，内容直接开始 */
  await expect(page.locator('#panel-overview')).toBeVisible();
  await expect(page.locator('#panel-overview .panel-head')).toHaveCount(0);
  const table = page.locator('#panel-overview table').first();
  await expect(table).toContainText('line1');
  await expect(table).toContainText('LINE1');
  await expect(table).toContainText('line1.json');
});

test('操作栏带监听端点：地址:端口 | DeviceID', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/plans');
  const tag = page.locator('.detail-bar .endpoint-tag');
  /* 监听地址取配置 session 段；没写 bindHost 时按后端默认的 0.0.0.0 */
  await expect(tag).toContainText('0.0.0.0:5000');
  await expect(tag).toContainText('| 10');
});

test('配置区·连接：13 个字段 + 校验 / 保存 / 重新载入', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/#/device/line1/config/connection');

  const panel = page.locator('#panel-connection');
  await expect(panel).toBeVisible();
  await expect(panel.getByText('会话 id', { exact: true })).toBeVisible();
  await expect(panel.getByText(/唯一标识/)).toBeVisible();
  await expect(panel.locator('.session-field')).toHaveCount(13);

  await panel.getByRole('button', { name: '校验' }).click();
  await expect(panel.getByText('校验通过')).toBeVisible();
  await expect.poll(() => state.calls.some((call) => call.endsWith('/validate'))).toBe(true);

  await panel.getByRole('button', { name: '保存' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
  await expect.poll(() => state.calls.some((call) => call.startsWith('PUT /api/v1/configs/'))).toBe(true);

  await panel.getByRole('button', { name: '重新载入' }).click();
  await expect(panel.getByText('有未保存改动')).toHaveCount(0);
});

test('配置区·配置原文：JSON 高亮、格式化、校验、保存、导入导出', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/#/device/line1/config/document');

  const panel = page.locator('#panel-config');
  const editor = panel.locator('.config-json-editor');
  await expect(editor).toBeVisible();
  await expect(editor.locator('pre .json-key').first()).toBeVisible();

  const textarea = panel.locator('textarea.config-editor');
  await expect(textarea).toHaveValue(/schemaVersion/);

  /* 格式化：压缩的 JSON 变成缩进格式 */
  await textarea.fill('{"a":1,"b":[2,3]}');
  await editor.getByRole('button', { name: '格式化' }).click();
  await expect(textarea).toHaveValue(/{\n  "a": 1,/);

  await panel.getByRole('button', { name: '校验' }).click();
  await expect(panel.getByText('校验通过')).toBeVisible();

  await panel.getByRole('button', { name: '保存' }).click();
  await expect(panel.getByText(/已保存 line1\.json/)).toBeVisible();

  /* 导出走保存对话框（Chromium 无 API 时回落下载） */
  const downloadPromise = page.waitForEvent('download');
  await panel.getByRole('button', { name: '导出 JSON' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('line1.json');

  /* 重新载入：没有改动时不该弹确认 */
  await panel.getByRole('button', { name: '重新载入' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect.poll(() => state.calls.filter((call) => call.startsWith('GET /api/v1/configs/line1.json')).length)
    .toBeGreaterThan(1);
});

test('配置区·配置原文：改了再重新载入要确认丢改动', async ({ page }) => {
  await page.goto('/#/device/line1/config/document');
  const panel = page.locator('#panel-config');
  await panel.locator('textarea.config-editor').fill('{"draft":true}');
  await expect(panel.getByText('有未保存改动')).toBeVisible();

  await panel.getByRole('button', { name: '重新载入' }).click();
  await expect(page.getByRole('dialog')).toContainText('丢弃未保存的改动');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();
  await expect(panel.locator('textarea.config-editor')).toHaveValue('{"draft":true}');

  await panel.getByRole('button', { name: '重新载入' }).click();
  await page.getByRole('dialog').getByRole('button', { name: /丢弃/ }).click();
  await expect(panel.locator('textarea.config-editor')).toHaveValue(/schemaVersion/);
});

test('配置区·版本历史：列出快照与 SHA 前缀', async ({ page }) => {
  await page.goto('/#/device/line1/config/revisions');
  const table = page.locator('.tab-page table').first();
  await expect(table).toContainText('v2');
  await expect(table).toContainText('修改超时');
  await expect(table).toContainText('aabbccddeeff');
});

/* ==================== 运行区 ==================== */

test('设备清单：离线可手动上线，在线可下线（等价 S1F17 / S1F15）', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');

  /* line2 在夹具里是"端口开着但没建链"（启动态）。先停再启，拿到一次干净的建链：
     建链只到 COMMUNICATING（已连接、仍离线），上线要单独一步。 */
  const row = page.locator('#panel-sessions tbody tr', { hasText: 'line2' }).first();
  await row.getByRole('button', { name: '停止' }).click();
  await page.getByRole('dialog').locator('.modal-foot').getByRole('button', { name: '停止' }).click();
  await expect.poll(() => state.sessions.find((item) => item.id === 'line2')?.state).toBe('CLOSED');

  await row.getByRole('button', { name: '启动' }).click();
  await expect.poll(() => state.sessions.find((item) => item.id === 'line2')?.controlState)
    .toBe('COMMUNICATING');

  /* 已连接但离线：徽标是「离线」，行上出现「上线」而不是「下线」 */
  await expect(row.locator('.badge', { hasText: '离线' })).toBeVisible();
  await expect(row.getByRole('button', { name: '下线' })).toHaveCount(0);

  await row.getByRole('button', { name: '上线' }).click();
  await expect.poll(() => state.calls.some((call) => call.endsWith('/link/online'))).toBe(true);
  await expect.poll(() => state.sessions.find((item) => item.id === 'line2')?.controlState).toBe('ONLINE');
  await expect(row.locator('.badge', { hasText: '在线' })).toBeVisible();

  await row.getByRole('button', { name: '下线' }).click();
  await expect.poll(() => state.sessions.find((item) => item.id === 'line2')?.controlState).toBe('OFFLINE');
  await expect(row.getByRole('button', { name: '上线' })).toHaveCount(0);
});

test('运行区·事件计划：设备离线时启动只到「等待上线」，消息不会真发', async ({ page }) => {
  const state = await mockApi(page);
  /* line2 置成"已连接但离线"：严格 GEM 下这种状态不能上报 S6F11 */
  const line2 = state.sessions.find((item) => item.id === 'line2');
  if (line2 !== undefined) {
    line2.state = 'OPEN';
    line2.controlState = 'COMMUNICATING';
  }
  await page.goto('/#/device/line2/runtime/plans');

  const row = page.locator('#panel-events tbody tr', { hasText: 'beat' }).first();
  await expect(row.locator('.badge', { hasText: '已停止' })).toBeVisible();

  await row.getByRole('button', { name: '启动' }).click();
  /* 启动了 ≠ 在发：离线时状态必须是「等待上线」，否则操作员会盯着一个不发报文的"运行中" */
  await expect(row.locator('.badge', { hasText: '等待上线' })).toBeVisible();
  await expect(row.locator('.badge', { hasText: '运行中' })).toHaveCount(0);
  /* 徽标 title 要说清楚为什么没在发、怎么才能发 */
  await expect(row.locator('.badge', { hasText: '等待上线' })).toHaveAttribute('title', /上线|S1F17/);
});

test('实时消息监控抽屉：单对象切换 + 报文折叠 + 收发配色', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/plans');
  /* 运行区不再单独摆一块消息列表，子页里也没有「报文收发」 */
  await expect(page.locator('#panel-messages')).toHaveCount(0);
  await expect(page.locator('.subtabs')).not.toContainText('报文收发');

  const drawer = page.locator('.monitor-drawer');
  await expect(drawer).toHaveCount(0);
  await page.locator('.monitor-toggle').click();
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('button', { name: '收起消息监控' })).toBeVisible();
  /* 顶栏按钮不再带消息条数 */
  await expect(page.locator('.monitor-toggle .monitor-count')).toHaveCount(0);

  /* 抽屉是侧边栏：主内容让出同样的宽度，抽屉内部不横向溢出 */
  const drawerBox = await drawer.boundingBox();
  const mainBox = await page.locator('.main-col').boundingBox();
  expect(drawerBox).not.toBeNull();
  expect(mainBox).not.toBeNull();
  if (drawerBox !== null && mainBox !== null) {
    expect(drawerBox.width).toBeGreaterThan(300);
    expect(mainBox.x + mainBox.width).toBeLessThanOrEqual(drawerBox.x + 1);
  }
  expect(await drawer.evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1);

  /* 默认只看当前设备 */
  await expect(drawer.getByLabel('选择要查看的设备或主机')).toHaveValue('device:line1');
  await expect(drawer.getByLabel('选择要查看的设备或主机').locator('option:checked')).toContainText('line1');

  /* 接收 / 发送用不同的行样式与徽标区分 */
  await expect(drawer.locator('.mm-row.recv').first()).toBeVisible();
  await expect(drawer.locator('.mm-row.sent').first()).toBeVisible();
  await expect(drawer.locator('.mm-chip.recv').first()).toBeVisible();
  await expect(drawer.locator('.mm-chip.sent').first()).toBeVisible();
  await expect(drawer.getByText('S1F1 W', { exact: true })).toBeVisible();

  /* 报文体默认不展示，点行才展开 */
  await expect(drawer.locator('.mm-body')).toHaveCount(0);
  const s6f11 = drawer.locator('.mm-row', { hasText: 'S6F11 W' }).first();
  await s6f11.click();
  await expect(drawer.locator('.mm-body')).toHaveCount(1);
  await expect(drawer.locator('.mm-body')).toContainText('<L[3]>');
  await s6f11.click();
  await expect(drawer.locator('.mm-body')).toHaveCount(0);

  /* 一次只看一个对象：切到主机后，设备独有的 S6F11 不再出现 */
  await drawer.getByLabel('选择要查看的设备或主机').selectOption('host:host-1');
  await expect(drawer.getByLabel('选择要查看的设备或主机').locator('option:checked')).toContainText('host-1');
  await expect(drawer.getByText('S6F11 W', { exact: true })).toHaveCount(0);
  await expect(drawer.getByText('S1F1 W', { exact: true })).toBeVisible();
  await drawer.getByLabel('选择要查看的设备或主机').selectOption('device:line1');
  await expect(drawer.getByLabel('选择要查看的设备或主机').locator('option:checked')).toContainText('line1');
  await expect(drawer.getByText('S6F11 W', { exact: true })).toBeVisible();

  await drawer.getByLabel('按方向过滤').selectOption('sent');
  await expect(drawer.locator('.mm-row.recv')).toHaveCount(0);
  await drawer.getByLabel('按方向过滤').selectOption('all');

  await drawer.getByLabel('按 SxFy 过滤').fill('S6F11');
  await expect(drawer.getByText('S1F1 W', { exact: true })).toHaveCount(0);
  await expect(drawer.getByText('S6F11 W', { exact: true })).toBeVisible();
  await drawer.getByLabel('按 SxFy 过滤').fill('');

  await drawer.getByRole('button', { name: '收起消息监控' }).click();
  await expect(drawer).toHaveCount(0);
});

test('运行区·手动操作：发送 SML / 压测 / 报警置位清除 / 报警风暴', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/#/device/line1/runtime/manual');

  const send = page.locator('#panel-send');
  await expect(send).toBeVisible();
  await send.locator('textarea.sml-editor').fill('S1F13 W');
  await send.getByRole('button', { name: '发送 SML' }).click();
  await expect(send.getByText(/发送 line1\s+S1F13 W/)).toBeVisible();

  await send.getByRole('button', { name: '压测发送' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();

  const alarms = page.locator('#panel-alarms');
  await expect(alarms).toBeVisible();
  await alarms.getByRole('button', { name: '置位报警' }).click();
  await expect.poll(() => state.calls.filter((call) => call.includes('/alarm/')).length).toBeGreaterThan(0);
  await alarms.getByRole('button', { name: '清除报警' }).click();
  await expect.poll(() => state.calls.filter((call) => call.includes('/alarm/')).length).toBeGreaterThan(1);
  await alarms.getByRole('button', { name: '报警风暴' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
});

/* ==================== 两段式骨架 ==================== */

