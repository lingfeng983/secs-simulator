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
  await expect(page.getByRole('heading', { name: '设备定义摘要' })).toBeVisible();
  const table = page.locator('.tab-page table').first();
  await expect(table).toContainText('line1');
  await expect(table).toContainText('LINE1');
  await expect(table).toContainText('line1.json');
  await expect(page.getByText('保存后需重载使生效')).toBeVisible();
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

test('配置区·结构：设备全局 / 单元 / 模板三个分区都能切', async ({ page }) => {
  await page.goto('/#/device/line1/config/structure');
  const panel = page.locator('#panel-structure');
  await expect(panel).toBeVisible();
  await expect(panel.getByRole('heading', { name: '设备全局' })).toBeVisible();

  await panel.getByRole('tab', { name: '单元' }).click();
  await expect(panel.getByText('unitId', { exact: true })).toBeVisible();
  await expect(panel.getByText(/报告自动带 UNIT_ID/)).toBeVisible();

  await panel.getByRole('tab', { name: '模板' }).click();
  await expect(panel.getByText('在什么地方用')).toBeVisible();
  await expect(panel.getByText('模板（结构化编辑）')).toBeVisible();

  await panel.getByRole('tab', { name: '设备全局' }).click();
  await expect(panel.getByRole('heading', { name: '设备全局' })).toBeVisible();
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

test('配置区·设备常量：没有 constants 段时给出明确说明', async ({ page }) => {
  await page.goto('/#/device/line1/config/constants');
  await expect(page.getByRole('heading', { name: '设备常量（ECV）' })).toBeVisible();
  await expect(page.getByText(/没有 equipment.constants 段/)).toBeVisible();
});

test('配置区·版本历史：列出快照与 SHA 前缀', async ({ page }) => {
  await page.goto('/#/device/line1/config/revisions');
  const table = page.locator('.tab-page table').first();
  await expect(table).toContainText('v2');
  await expect(table).toContainText('修改超时');
  await expect(table).toContainText('aabbccddeeff');
});

/* ==================== 运行区 ==================== */

test('运行区不再有实时状态页签，实例头直接给四态与启停', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/status');
  /* 旧 status 深链回落到运行区首页（事件计划） */
  await expect(page.locator('.subtabs').getByRole('tab', { name: '事件计划', exact: true }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.subtabs')).not.toContainText('实时状态');

  const head = page.locator('.instance-head');
  /* 四态：停止 / 启动 / 离线（已连接但没上线）/ 在线 */
  await expect(head.locator('.badge').first()).toHaveText(/停止|启动|离线|在线/);
  await expect(head).toContainText('设备状态');
  /* 停止态给「启动设备」，其余给「停止设备」 */
  await expect(head.getByRole('button', { name: /启动设备|停止设备/ })).toBeVisible();
});

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

test('运行区·事件计划：计划卡 + 台账 + 只看失败 / 含试跑', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/plans');

  const panel = page.locator('#panel-events');
  await expect(panel.getByRole('cell', { name: 'beat' })).toBeVisible();

  const timeline = page.locator('#plan-executions');
  await expect(timeline).toBeVisible();
  await expect(timeline.getByRole('heading', { name: '执行时间轴' })).toBeVisible();
  /* 计划卡：累计次数 + 单步试跑 */
  await expect(timeline).toContainText('CEID 2001');
  await expect(timeline.getByRole('button', { name: '单步试跑' })).toBeVisible();
  await expect(timeline.getByText('SENT').first()).toBeVisible();
  await expect(timeline.getByText('FAILED').first()).toBeVisible();

  /* 只看失败：状态徽标只剩 FAILED（计划卡上的"最后状态"不算行） */
  await timeline.getByLabel('只看失败').check();
  await expect(timeline.locator('.badge', { hasText: 'SENT' })).toHaveCount(0);
  await expect(timeline.locator('.badge', { hasText: 'FAILED' }).first()).toBeVisible();
  await timeline.getByLabel('只看失败').uncheck();
  await expect(timeline.locator('.badge', { hasText: 'SENT' }).first()).toBeVisible();

  /* 含试跑：默认不含 dryRun，勾上后出现 */
  await expect(timeline.locator('.badge', { hasText: '试跑' })).toHaveCount(0);
  await timeline.getByLabel('含试跑').check();
  await expect(timeline.locator('.badge', { hasText: '试跑' }).first()).toBeVisible();
});

test('运行区·事件计划：单步试跑打后端、导出 CSV', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/#/device/line1/runtime/plans');
  const timeline = page.locator('#plan-executions');

  await timeline.getByRole('button', { name: '单步试跑' }).click();
  await expect.poll(() => state.calls.some((call) => call.endsWith('/step'))).toBe(true);
  await expect(page.locator('.toast').first()).toBeVisible();

  const downloadPromise = page.waitForEvent('download');
  await timeline.getByRole('button', { name: '导出 CSV' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('plan-executions.csv');
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

test('运行区·事件计划：默认停止，行按钮（预览 / 触发 / 启动 / 编辑 / 删除）', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/#/device/line1/runtime/plans');
  const panel = page.locator('#panel-events');
  const row = panel.locator('tbody tr', { hasText: 'beat' }).first();

  /* 计划默认禁用：先显示「已停止」，点「启动」后才运行 */
  await expect(row.locator('.badge', { hasText: '已停止' })).toBeVisible();
  await row.getByRole('button', { name: '预览' }).click();
  await expect(panel.locator('.event-preview')).toBeVisible();

  await row.getByRole('button', { name: '触发' }).click();
  await expect.poll(() => state.calls.some((call) => call.includes('/ceid/2001'))).toBe(true);

  await row.getByRole('button', { name: '启动' }).click();
  await expect(row.getByRole('button', { name: '停止' })).toBeVisible();
  /* line1 在夹具里是 ONLINE：启动后是真在发，所以是「运行中」而不是「等待上线」 */
  await expect(row.locator('.badge', { hasText: '运行中' })).toBeVisible();

  await row.getByRole('button', { name: '编辑' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();

  await row.getByRole('button', { name: '删除' }).click();
  await expect(page.getByRole('dialog')).toContainText('删除编排计划');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();
});

test('运行区·事件计划：新增计划弹窗与事件周期预览', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/plans');
  const panel = page.locator('#panel-events');

  await panel.getByRole('button', { name: '新增计划' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('触发类型');
  await dialog.getByRole('button', { name: '取消' }).click();

  await panel.getByRole('button', { name: '事件周期预览' }).click();
  await expect(panel.locator('.event-preview')).toBeVisible();
  await expect(panel.locator('.event-legend')).toBeVisible();
  await panel.getByLabel('显示事件图例').uncheck();
  await expect(panel.locator('.event-legend')).toHaveCount(0);
});

test('运行区·事件与报告：事件/报告两张表 + 新增 + 压测', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/#/device/line1/runtime/catalog');

  /* 面板 id 随分段切换（事件=panel-ceids / 报告=panel-reports），用页面容器做锚点 */
  const pageBody = page.locator('.tab-page');
  await expect(pageBody).toContainText('事件与报告');
  await expect(page.locator('#panel-ceids').getByRole('cell', { name: 'TEMP_HIGH' })).toBeVisible();

  await page.locator('#panel-ceids').getByRole('button', { name: '新增事件' }).click();
  await expect(page.getByRole('dialog')).toContainText('新增采集事件');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();

  /* 切到报告：同一页里管理 RPTID */
  await pageBody.getByRole('tab', { name: /^报告/ }).click();
  const reports = page.locator('#panel-reports');
  await expect(reports).toBeVisible();
  await expect(reports.getByText('报告（RPTID）—— 事件携带的变量组')).toBeVisible();
  await expect(reports.getByRole('cell', { name: 'TEMP_REPORT' })).toBeVisible();
  await reports.getByRole('button', { name: '新增报告' }).click();
  await expect(page.getByRole('dialog')).toContainText('新增报告');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();

  await pageBody.getByRole('tab', { name: /^事件/ }).click();

  /* 压测上报：选 CEID 再开始 */
  await page.locator('#panel-ceids select').first().selectOption('2001');
  await page.locator('#panel-ceids').getByRole('button', { name: '开始压测' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
  await expect.poll(() => state.calls.some((call) => call.includes('/burst'))).toBe(true);
});

test('运行区·事件与报告：编辑 / 删除行按钮', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/catalog');
  const row = page.locator('#panel-ceids tbody tr', { hasText: 'TEMP_HIGH' }).first();

  await row.getByRole('button', { name: '编辑' }).click();
  await expect(page.getByRole('dialog')).toContainText('编辑事件 CEID 2001');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();

  await row.getByRole('button', { name: '删除' }).click();
  await expect(page.getByRole('dialog')).toContainText('删除采集事件');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();
});

test('运行区·状态变量：预览曲线、设定值、编辑、删除', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/svids');
  const panel = page.locator('#panel-svids');
  await expect(panel.getByRole('cell', { name: 'TEMP', exact: true })).toBeVisible();

  /* 预览：画布 + 工具条 + 事件竖线开关 */
  await panel.getByRole('button', { name: '预览', exact: true }).first().click();
  await expect(panel.locator('.variable-chart canvas').first()).toBeVisible();
  await expect(panel.locator('.variable-chart-events').first()).toBeVisible();
  await expect(panel.locator('.chart-toolbar').first()).toBeVisible();
  await expect(panel.locator('.chart-toolbar').getByRole('button', { name: '＋' })).toBeVisible();
  await panel.getByLabel('事件标记').uncheck();
  await expect(panel.locator('.variable-chart-events')).toHaveCount(0);
  await panel.getByLabel('事件标记').check();
  await panel.getByRole('button', { name: '收起' }).click();

  /* 设定值 */
  const row = panel.locator('tbody tr', { hasText: 'TEMP' }).first();
  await row.getByRole('button', { name: '设定' }).click();
  await expect(page.getByRole('dialog')).toContainText('设定 SVID 1001 的值');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();

  /* 编辑 */
  await row.getByRole('button', { name: '编辑' }).click();
  await expect(page.getByRole('dialog')).toContainText('编辑状态变量 SVID 1001');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();

  /* 删除确认 */
  await row.getByRole('button', { name: '删除' }).click();
  await expect(page.getByRole('dialog')).toContainText('删除状态变量');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();
});

test('运行区·状态变量：新增弹窗能填能取消', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/svids');
  const panel = page.locator('#panel-svids');
  await panel.getByRole('button', { name: '新增状态变量' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('新增状态变量');
  await dialog.getByLabel('符号名').fill('PRESS_PV');
  await dialog.getByRole('button', { name: '保存' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
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

test('配置区 / 运行区切换会换掉子页签集合', async ({ page }) => {
  await gotoDevice(page);
  await switchZone(page, '配置区');
  const tabs = page.locator('.subtabs');
  await expect(tabs).toContainText('配置原文');
  await expect(tabs).toContainText('版本历史');
  await expect(tabs).not.toContainText('事件计划');

  await switchZone(page, '运行区');
  await expect(tabs).toContainText('事件计划');
  await expect(tabs).toContainText('手动操作');
  await expect(tabs).not.toContainText('配置原文');
});

test('设备头部的返回设备清单与指标', async ({ page }) => {
  await gotoDevice(page);
  const head = page.locator('.instance-head');
  await expect(head).toContainText('LINE1');
  await expect(head).toContainText('line1');
  await expect(head).toContainText('设备状态');
  /* 配置区 / 运行区切换就在这同一行，不再单独占一行 */
  await expect(head.locator('.zone-switch')).toBeVisible();
  await expect(page.locator('.zonebar')).toHaveCount(0);
  await head.getByRole('button', { name: '← 设备清单' }).click();
  await expect(page.locator('#panel-sessions')).toBeVisible();
});

test('运行区各子页都能直达（深链）', async ({ page }) => {
  const pages = [
    { page: 'plans', tab: '事件计划', text: '事件编排' },
    { page: 'catalog', tab: '事件与报告', text: '事件与报告' },
    { page: 'svids', tab: '状态变量', text: '状态变量' },
    { page: 'manual', tab: '手动操作', text: '手动发送' },
  ] as const;
  for (const item of pages) {
    await page.goto(`/#/device/line1/runtime/${item.page}`);
    await expect(page.locator('.tab-page'), item.page).toContainText(item.text);
    await expect(page.locator('.subtabs').getByRole('tab', { name: item.tab, exact: true }))
      .toHaveAttribute('aria-selected', 'true');
  }
  void openSubTab;
});

test('旧消息深链回落到运行区首页，并自动拉开监控抽屉', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/messages');
  await expect(page.locator('#panel-messages')).toHaveCount(0);
  await expect(page.locator('.subtabs').getByRole('tab', { name: '事件计划', exact: true }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.monitor-drawer')).toBeVisible();
});
