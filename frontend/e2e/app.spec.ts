import { expect, test, type Page } from '@playwright/test';
import { mockApi } from './fixtures';

/** 精简后的导航助手:侧栏静态入口(工作台/审计) + 设备动态项 + 设备子页。 */

/** 打开侧栏静态入口(工作台/审计)。 */
async function openStatic(page: Page, label: string, panelId: string): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: label }).first().click();
  await expect(page.locator(`#panel-${panelId}`)).toBeVisible();
}

/** 打开一台设备(默认落事件设置子页)。 */
async function openDevice(page: Page, device: string): Promise<void> {
  await page.locator('.sidenav .nav-group', { hasText: '设备' }).locator('.nav-item', { hasText: device }).first().click();
  await expect(page.locator('#panel-ceids')).toBeVisible();
}

/** 切设备子页。 */
async function openDeviceTab(page: Page, label: string, panelId: string): Promise<void> {
  await page.getByRole('tab', { name: label, exact: true }).click();
  await expect(page.locator(`#panel-${panelId}`)).toBeVisible();
}

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

test('lands on the workbench and opens a device from the table', async ({ page }) => {
  await page.goto('/');

  /* 默认落在工作台:菜单从顶部开始(没有品牌区),KPI 与设备会话表都在 */
  await expect(page.locator('.sidenav .nav-item').first()).toBeVisible();
  await expect(page.locator('.brand-name')).toHaveCount(0);
  await expect(page.getByText('在线设备').first()).toBeVisible();
  await expect(page.locator('#panel-sessions')).toBeVisible();
  const deviceTable = page.locator('[data-table="workbench-devices"], .table-scroll table').first();
  await expect(deviceTable.getByRole('cell', { name: 'LINE1', exact: true })).toBeVisible();

  /* 点表格行进设备页·事件设置(默认子页) */
  await page.locator('tr', { hasText: 'LINE1' }).first().click();
  await expect(page.getByRole('tab', { name: '事件', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#panel-ceids').getByRole('cell', { name: 'TEMP_HIGH' })).toBeVisible();
});

test('auto-opens the device config as raw JSON and validates/saves', async ({ page }) => {
  await page.goto('/#/device/line1/config');

  const configSection = page.locator('#panel-config');
  await expect(configSection).toBeVisible();
  /* 本设备的配置(line1.json)自动打开，但不再弹"已打开…"提示 */
  await expect(page.getByText(/已打开本设备的配置/)).toHaveCount(0);
  /* 配置页只保留 JSON 原文：没有结构化表单，也没有视图切换 */
  await expect(page.locator('textarea.config-editor')).toBeVisible();
  await expect(page.locator('textarea.config-editor')).toHaveValue(/schemaVersion/);
  await expect(configSection.getByRole('button', { name: '结构化编辑' })).toHaveCount(0);
  await expect(configSection.getByRole('button', { name: 'JSON 原文' })).toHaveCount(0);

  /* 校验 / 保存 / 重新载入 都在面板标题行 */
  await expect(configSection.getByRole('button', { name: '校验' })).toBeVisible();
  await expect(configSection.getByRole('button', { name: '保存' })).toBeVisible();
  await expect(configSection.getByRole('button', { name: '重新载入' })).toBeVisible();

  await configSection.getByRole('button', { name: '校验' }).click();
  await expect(page.getByText('校验通过')).toBeVisible();
  await configSection.getByRole('button', { name: '保存' }).click();
  await expect(configSection.getByText(/已保存 line1\.json/)).toBeVisible();
});

test('config JSON editor highlights, formats and fills the viewport', async ({ page }) => {
  await page.goto('/#/device/line1/config');
  const editor = page.locator('.config-json-editor');
  await expect(editor).toBeVisible();
  await expect(editor.locator('pre .json-key').first()).toBeVisible();

  const box = await editor.boundingBox();
  expect(box?.height ?? 0).toBeGreaterThan(300);

  const textarea = page.locator('textarea.config-editor');
  await textarea.fill('{"a":1,"b":[2,3]}');
  await editor.getByRole('button', { name: '格式化' }).click();
  await expect(textarea).toHaveValue(/{\n  "a": 1,/);
});

test('connection and structure pages host the structured config editors', async ({ page }) => {
  await page.goto('/#/device/line1/connection');
  await expect(page.getByRole('tab', { name: '连接' })).toHaveAttribute('aria-selected', 'true');
  const connection = page.locator('#panel-connection');
  await expect(connection).toBeVisible();
  /* 不再折叠：直接看到属性名和含义 */
  await expect(connection.getByText('会话 id', { exact: true })).toBeVisible();
  await expect(connection.getByText(/唯一标识/)).toBeVisible();
  /* 6 个连接字段 + 5 个 HSMS 超时 + 2 个 Trace 容量 */
  await expect(connection.locator('.session-field')).toHaveCount(13);

  await page.getByRole('tab', { name: '结构', exact: true }).click();
  const structure = page.locator('#panel-structure');
  await expect(structure).toBeVisible();
  await expect(structure.getByRole('heading', { name: '设备全局' })).toBeVisible();

  await structure.getByRole('tab', { name: '单元' }).click();
  await expect(structure.getByText('unitId', { exact: true })).toBeVisible();
  await expect(structure.getByText(/报告自动带 UNIT_ID/)).toBeVisible();

  await structure.getByRole('tab', { name: '模板' }).click();
  await expect(structure.getByText('在什么地方用')).toBeVisible();
  await expect(structure.getByText('模板（结构化编辑）')).toBeVisible();
});

test('toggles an event plan and shows the admin audit entry', async ({ page }) => {
  await page.goto('/#/device/line1/events');

  const eventSection = page.locator('#panel-events');
  await expect(eventSection.getByRole('cell', { name: 'beat' })).toBeVisible();
  await eventSection.getByRole('button', { name: '禁用' }).click();
  await expect(eventSection.getByRole('button', { name: '启用' })).toBeVisible();

  await openStatic(page, '审计', 'audit');
  await expect(page.locator('#panel-audit').getByText('trigger-ceid', { exact: true })).toBeVisible();
});

test('svids page lists variables and previews a curve', async ({ page }) => {
  await page.goto('/#/device/line1/svids');

  const svids = page.locator('#panel-svids');
  await expect(svids.getByRole('cell', { name: 'TEMP', exact: true })).toBeVisible();

  await svids.getByRole('button', { name: '预览', exact: true }).first().click();
  await expect(page.locator('#panel-svids .variable-chart canvas').first()).toBeVisible();
  /* 事件时刻标在曲线上（清单在图下方） */
  await expect(page.locator('#panel-svids .variable-chart-events').first()).toBeVisible();
  /* 图表工具条：缩放按钮 + 事件标记开关 */
  await expect(page.locator('#panel-svids .chart-toolbar').first()).toBeVisible();
  await expect(page.locator('#panel-svids .chart-toolbar').getByRole('button', { name: '＋' })).toBeVisible();
  await page.locator('#panel-svids').getByLabel('事件标记').uncheck();
  await expect(page.locator('#panel-svids .variable-chart-events')).toHaveCount(0);
  await page.locator('#panel-svids').getByLabel('事件标记').check();
  await expect(page.locator('#panel-svids .variable-chart-events').first()).toBeVisible();
});

test('event preview has zoom controls and a closable legend', async ({ page }) => {
  await page.goto('/#/device/line1/events');
  const events = page.locator('#panel-events');
  await events.getByRole('button', { name: '事件周期预览' }).click();

  await expect(events.locator('.event-preview .chart-toolbar')).toBeVisible();
  await expect(events.locator('.event-legend')).toBeVisible();
  await events.getByLabel('显示事件图例').uncheck();
  await expect(events.locator('.event-legend')).toHaveCount(0);
  await events.getByLabel('显示事件图例').check();
  await expect(events.locator('.event-legend')).toBeVisible();
});

test('command palette jumps to the host simulation entry', async ({ page }) => {
  await page.goto('/');

  await page.keyboard.press('Control+k');
  const palette = page.locator('.palette');
  await expect(palette).toBeVisible();
  await palette.locator('.palette-input').fill('主机模拟');
  await palette.locator('.command-item', { hasText: '前往 主机模拟' }).first().click();

  await expect(page.locator('#panel-hostsim-hosts')).toBeVisible();
});

test('events page exposes the cycle preview entry', async ({ page }) => {
  await page.goto('/#/device/line1/events');

  const events = page.locator('#panel-events');
  const preview = events.getByRole('button', { name: '事件周期预览' });
  await expect(preview).toBeVisible();
  await expect(preview).toBeEnabled();
});

test('filters sessions on the workbench', async ({ page }) => {
  await page.goto('/');

  const sessions = page.locator('#panel-sessions');
  await expect(sessions.getByRole('cell', { name: 'line1', exact: true })).toBeVisible();

  await sessions.getByPlaceholder('搜索会话 id / 设备 / 状态').fill('不存在的名字');
  await expect(sessions.getByText('没有匹配「不存在的名字」的行')).toBeVisible();

  await sessions.getByPlaceholder('搜索会话 id / 设备 / 状态').fill('LINE1');
  await expect(sessions.getByRole('cell', { name: 'line1', exact: true })).toBeVisible();
});

test('merges device overview and sessions into one table', async ({ page }) => {
  await page.goto('/');

  /* 只有一张表:设备与会话合并,不再是"总览 + 会话"两块 */
  await expect(page.locator('#panel-sessions')).toBeVisible();
  await expect(page.locator('#panel-devices')).toHaveCount(0);
  await expect(page.locator('#panel-sessions table')).toHaveCount(1);

  /* 读数与操作同处一行:端口/速率是读数,重载/关闭是操作(上报已移除) */
  const row = page.locator('#panel-sessions tbody tr').first();
  await expect(row.getByText('5000', { exact: true })).toBeVisible();
  await expect(row.getByRole('button', { name: '上报' })).toHaveCount(0);
  await expect(row.getByRole('button', { name: '重载' })).toBeVisible();
  await expect(row.getByRole('button', { name: '关闭' })).toBeVisible();

  /* 勾选后批量操作可用；同步配置至少要两台 */
  const batch = page.locator('#panel-sessions .batch-bar');
  await expect(batch.getByRole('button', { name: '批量同步配置' })).toBeDisabled();
  await row.getByLabel('选择 line1').check();
  await expect(batch).toContainText('已选 1 台');
  await expect(batch.getByRole('button', { name: '批量同步配置' })).toBeDisabled();
});

test('opens the command palette and jumps to a device', async ({ page }) => {
  await page.goto('/');

  await page.keyboard.press('Control+k');
  const palette = page.locator('.palette');
  await expect(palette).toBeVisible();

  await palette.locator('.palette-input').fill('LINE1');
  await expect(palette.getByText('打开设备 LINE1')).toBeVisible();
  await palette.getByText('打开设备 LINE1').click();

  await expect(page.getByRole('tab', { name: '事件', exact: true })).toBeVisible();
});

test('switches the theme and remembers it', async ({ page }) => {
  await page.goto('/');

  const before = await page.locator('html').getAttribute('data-theme');
  await page.getByRole('button', { name: '切换主题' }).click();
  const after = await page.locator('html').getAttribute('data-theme');

  expect(after).not.toBe(before);

  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', after ?? 'light');
});

test('shows a toast after batch reload from the sessions table', async ({ page }) => {
  await page.goto('/');

  const panel = page.locator('#panel-sessions');
  await panel.getByLabel('选择 line1').check();
  await panel.getByRole('button', { name: '批量重载' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
});

test('device subpages each show their own capability', async ({ page }) => {
  await page.goto('/');
  await openDevice(page, 'LINE1');
  await expect(page.getByRole('tab', { name: '事件', exact: true })).toBeVisible();

  /* 事件页:CEID 定义 + 压测上报；报告已独立成页 */
  const ceids = page.locator('#panel-ceids');
  await expect(ceids.getByRole('button', { name: '新增事件' })).toBeVisible();
  await expect(ceids.getByRole('button', { name: '新增报告' })).toHaveCount(0);
  await expect(ceids.getByRole('button', { name: '开始压测' })).toBeVisible();

  await openDeviceTab(page, '报告', 'reports');
  const reports = page.locator('#panel-reports');
  await expect(reports.getByRole('button', { name: '新增报告' })).toBeVisible();
  await expect(reports.getByRole('button', { name: '新增事件' })).toHaveCount(0);

  /* 事件编排:计划 CRUD */
  await openDeviceTab(page, '事件编排', 'events');
  await expect(page.locator('#panel-events').getByRole('button', { name: '新增计划' })).toBeVisible();

  /* 发送页:单发 + 压测发送同框 */
  await openDeviceTab(page, '发送', 'send');
  await expect(page.locator('#panel-send').getByRole('button', { name: '发送 SML' })).toBeVisible();
  await expect(page.locator('#panel-send').getByRole('button', { name: '压测发送' })).toBeVisible();

  /* 报警页:置位/清除在命令栏 + 报警风暴同框 */
  await openDeviceTab(page, '报警', 'alarms');
  await expect(page.locator('#panel-alarms').getByRole('button', { name: '置位报警' })).toBeVisible();
  await expect(page.locator('#panel-alarms').getByRole('button', { name: '清除报警' })).toBeVisible();
  await expect(page.locator('#panel-alarms').getByRole('button', { name: '报警风暴' })).toBeVisible();

  /* 主机模拟已移出设备页:设备页不再有「主机模拟」标签 */
  await expect(page.getByRole('tab', { name: '主机模拟' })).toHaveCount(0);
});

test('ceids page offers burst with catalog datalist', async ({ page }) => {
  await page.goto('/#/device/line1/ceids');

  const ceids = page.locator('#panel-ceids');
  await ceids.locator('label.field', { hasText: 'CEID' }).locator('select').selectOption('2001');
  await ceids.getByRole('button', { name: '开始压测' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
});

test('host sim: creating a local host instance works', async ({ page }) => {
  await page.goto('/#/hostsim/hosts');
  await expect(page.locator('#panel-hostsim-hosts')).toBeVisible();

  /* 选一台本机设备再新建 */
  const picker = page.locator('.host-picker');
  await expect(picker.getByLabel('选择本机设备')).toBeVisible();
  await picker.getByLabel('选择本机设备').selectOption({ index: 1 });

  const request = page.waitForRequest((req) =>
    req.method() === 'POST' && req.url().endsWith('/api/v1/host-sims'));
  await picker.getByRole('button', { name: '新建本机主机' }).click();
  const posted = await request;
  /* 请求体要带 session；之前是 400/连接被断开，前端只看到"按钮没反应" */
  expect(posted.postDataJSON()).toHaveProperty('session');
});

test('sidebar does not show the version line', async ({ page }) => {
  await page.goto('/');
  /* 菜单最下方不再展示 v2.0.0-SNAPSHOT · integrated */
  await expect(page.locator('.sidenav .foot-note')).toHaveCount(0);
  await expect(page.locator('.sidenav')).not.toContainText('SNAPSHOT');
});

test('host simulation is a top-level menu with separate collection and monitor pages', async ({ page }) => {
  await page.goto('/');

  /* 主菜单里有「主机模拟」入口 */
  const nav = page.locator('.sidenav');
  await expect(nav.locator('.nav-item', { hasText: '主机模拟' }).first()).toBeVisible();

  /* 进去后是五个子页:实例 / 消息发送 / S6F1 / S1F3 / 消息监控 */
  await nav.locator('.nav-item', { hasText: '主机模拟' }).first().click();
  await expect(page.locator('#panel-hostsim-hosts')).toBeVisible();
  await expect(page.getByRole('tab', { name: '主机实例' })).toBeVisible();
  await expect(page.getByRole('tab', { name: '① 消息发送' })).toBeVisible();
  await expect(page.getByRole('tab', { name: '② S6F1 采集' })).toBeVisible();
  await expect(page.getByRole('tab', { name: '③ S1F3 采集' })).toBeVisible();
  await expect(page.getByRole('tab', { name: '消息监控' })).toBeVisible();

  /* 各页独立,不挤在一起 */
  await page.getByRole('tab', { name: '② S6F1 采集' }).click();
  await expect(page.locator('#panel-hostsim-fdc')).toBeVisible();
  await expect(page.locator('#panel-hostsim-collect')).toHaveCount(0);

  await page.getByRole('tab', { name: '③ S1F3 采集' }).click();
  await expect(page.locator('#panel-hostsim-collect')).toBeVisible();
  await expect(page.locator('#panel-hostsim-fdc')).toHaveCount(0);

  await page.getByRole('tab', { name: '① 消息发送' }).click();
  await expect(page.locator('#panel-hostsim-messages')).toBeVisible();

  await page.getByRole('tab', { name: '消息监控' }).click();
  await expect(page.locator('#panel-hostsim-monitor')).toBeVisible();

  /* 主机实例页可以选本机设备或远程 SECS */
  await page.getByRole('tab', { name: '主机实例' }).click();
  await expect(page.getByLabel('选择本机设备')).toBeVisible();
  await expect(page.getByRole('button', { name: '新建远程主机' }).first()).toBeVisible();
});

test('device message monitor lists traffic and filters by category', async ({ page }) => {
  await page.goto('/#/device/line1/messages');

  const panel = page.locator('#panel-messages');
  await expect(panel).toBeVisible();
  await expect(panel.getByText('S1F1 W', { exact: true })).toBeVisible();
  await expect(panel.getByText('S6F11 W', { exact: true })).toBeVisible();
  await expect(panel.getByText('收 ← 主机').first()).toBeVisible();

  /* 屏蔽探测类：S1F1/S1F2 消失，上报类仍在 */
  await panel.getByRole('button', { name: /^探测/ }).click();
  await expect(panel.getByText('S1F1 W', { exact: true })).toHaveCount(0);
  await expect(panel.getByText('S6F11 W', { exact: true })).toBeVisible();

  await panel.getByRole('button', { name: '全部显示' }).click();
  await expect(panel.getByText('S1F1 W', { exact: true })).toBeVisible();
});

test('host simulation monitor page lists host traffic and filters it', async ({ page }) => {
  await page.goto('/#/hostsim/monitor');

  const panel = page.locator('#panel-hostsim-monitor');
  await expect(panel).toBeVisible();
  /* 本机主机的报文没有 hostId（设备侧发布），监控页按会话把它归到该主机上 */
  await expect(panel.getByText('发 → 设备').first()).toBeVisible();
  await expect(panel.getByText('S1F1 W', { exact: true })).toBeVisible();

  /* 方向过滤：只看主机发出 = 设备视角的 in */
  await panel.getByLabel('按方向过滤').selectOption('sent');
  await expect(panel.getByText('S1F1 W', { exact: true })).toBeVisible();
  await expect(panel.getByText('收 ← 设备').first()).toHaveCount(0);

  await panel.getByLabel('按方向过滤').selectOption('all');

  /* 类别过滤走 MessageFeed 的类别条：屏蔽事件后 S6F11 消失 */
  await panel.getByRole('button', { name: /^事件/ }).click();
  await expect(panel.getByText('S6F11 W', { exact: true })).toHaveCount(0);
  await expect(panel.getByText('S1F1 W', { exact: true })).toBeVisible();
  await panel.getByRole('button', { name: '全部显示' }).click();

  /* SxFy 过滤：只看 S6F1 —— 必须"S1F1 消失且 S6F1 仍在"，
     只断言消失会把"什么都过滤掉"也判成通过（大小写不一致时就是这样） */
  await panel.getByLabel('按 SxFy 过滤').fill('S6F1');
  await expect(panel.getByText('S1F1 W', { exact: true })).toHaveCount(0);
  await expect(panel.getByText('S6F1 W', { exact: true })).toBeVisible();

  /* 小写输入也要认（输入容错） */
  await panel.getByLabel('按 SxFy 过滤').fill('s6f1');
  await expect(panel.getByText('S1F1 W', { exact: true })).toHaveCount(0);
  await expect(panel.getByText('S6F1 W', { exact: true })).toBeVisible();

  await panel.getByRole('button', { name: '清空过滤' }).click();
  await expect(panel.getByText('S1F1 W', { exact: true })).toBeVisible();
});

test('host simulation message page sends and has stress controls, no log table', async ({ page }) => {
  await page.goto('/#/hostsim/messages');

  const panel = page.locator('#panel-hostsim-messages');
  await expect(panel).toBeVisible();
  /* 收发记录已搬到「消息监控」：这里不再有那张「收发记录（主机视角）」表 */
  await expect(panel.getByRole('heading', { name: '收发记录（主机视角）' })).toHaveCount(0);

  /* 压测发送：次数 / 间隔 / 按钮都在 */
  await expect(panel.getByLabel('压测次数')).toBeVisible();
  await expect(panel.getByLabel('压测间隔')).toBeVisible();
  await expect(panel.getByRole('button', { name: /压测发送/ })).toBeVisible();

  /* 单发仍按主机逐台列出结果 */
  await panel.getByRole('button', { name: /发送到 \d+ 台/ }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
});

test('host sim S6F1 page uses the top host picker and a hand-filled S2F23 for remote hosts', async ({ page }) => {
  await page.goto('/#/hostsim/fdc');

  const panel = page.locator('#panel-hostsim-fdc');
  await expect(panel).toBeVisible();
  /* 顶部已有主机选择：页面里不再重复一张主机汇总表 */
  await expect(panel.locator('table')).toHaveCount(0);

  /* 切到远程主机：报文改为手填 */
  await panel.getByLabel('当前主机实例').selectOption('host-remote');
  await expect(panel.getByLabel('远程主机的 S2F23 报文')).toBeVisible();
  await expect(panel.getByLabel('远程主机的 S2F23 报文')).toHaveValue(/S2F23 W/);
  /* 远程主机没有本地 catalog，不该出现变量多选 */
  await expect(panel.locator('.svid-check-grid')).toHaveCount(0);
});
