import { expect, test, type Page } from '@playwright/test';
import { mockApi, openSubTab, openTool, switchZone } from './fixtures';

/**
 * 主机域：列表页 + 详情（配置区 5 页 / 运行区 6 页）。
 *
 * <p>主机是 3.0 的独立域：不挂在设备下、每台实例各自连接、收发记录只看自己。
 * 这里把"新建 → 连接 → 发报文 → 采集 → 断开 → 删除"整条链路走一遍。</p>
 */

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

async function openHosts(page: Page): Promise<void> {
  await page.goto('/#/hosts');
  await expect(page.locator('#panel-hosts-v2')).toBeVisible();
}

async function openHost(page: Page, hostId = 'host-1'): Promise<void> {
  await page.goto(`/#/hosts/${hostId}/config/overview`);
  await expect(page.locator('.instance-head')).toContainText(hostId);
}

/* ==================== 列表页 ==================== */

test('主机列表：KPI、表格、搜索与行操作', async ({ page }) => {
  await openHosts(page);
  const panel = page.locator('#panel-hosts-v2');

  /* KPI 在面板外（页面级），表格与操作在面板内 */
  await expect(page.getByText('主机 · 可继续新建')).toBeVisible();
  await expect(page.getByText('已连接 · 各自独立')).toBeVisible();
  await expect(panel).toContainText('产线 A 主机');
  await expect(panel).toContainText('远端 CVD');
  await expect(panel).toContainText('127.0.0.1:5000');

  const search = panel.getByPlaceholder('搜索实例 id / 名称 / 目标');
  await search.fill('远端');
  await expect(panel.locator('tbody tr')).toHaveCount(1);
  await expect(panel).toContainText('远端 CVD');
  await search.fill('');
  await expect(panel.locator('tbody tr')).toHaveCount(2);
});

test('主机列表：连接 / 断开 / 详情 / 删除', async ({ page }) => {
  const state = await mockApi(page);
  await openHosts(page);
  const panel = page.locator('#panel-hosts-v2');

  /* host-2 未连接 → 连接 */
  const row2 = panel.locator('tbody tr', { hasText: 'host-2' }).first();
  await row2.getByRole('button', { name: '连接' }).click();
  await expect.poll(() => state.hosts.find((host) => host.id === 'host-2')?.open).toBe(true);
  await expect(row2.getByRole('button', { name: '断开' })).toBeVisible();

  /* host-1 已连接 → 断开 */
  const row1 = panel.locator('tbody tr', { hasText: 'host-1' }).first();
  await row1.getByRole('button', { name: '断开' }).click();
  await expect.poll(() => state.hosts.find((host) => host.id === 'host-1')?.open).toBe(false);

  /* 断开后才能删 */
  await expect(row1.getByRole('button', { name: '删除' })).toBeEnabled();
  await row1.getByRole('button', { name: '删除' }).click();
  await expect.poll(() => state.hosts.some((host) => host.id === 'host-1')).toBe(false);

  await panel.locator('tbody tr', { hasText: 'host-2' }).first().getByRole('button', { name: '详情' }).click();
  await expect(page.locator('.instance-head')).toContainText('host-2');
});

test('新建主机：模板一键填入 + 创建后进详情', async ({ page }) => {
  const state = await mockApi(page);
  await openHosts(page);
  await page.locator('#panel-hosts-v2').getByRole('button', { name: '＋ 新建主机' }).click();

  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('新建主机');
  /* 模板填入：名称/地址/端口/Device ID 一次带过来 */
  await dialog.getByLabel('从主机模板填入').selectOption('ht-1');
  await expect(dialog.getByLabel('名称')).toHaveValue('产线 A 目标');
  await expect(dialog.getByLabel('目标地址')).toHaveValue('127.0.0.1');

  await dialog.getByRole('button', { name: '创建' }).click();
  await expect.poll(() => state.hosts.length).toBe(3);
  await expect(page.locator('.instance-head')).toContainText('产线 A 目标');
});

test('新建主机：目标地址为空时创建按钮禁用', async ({ page }) => {
  await openHosts(page);
  await page.locator('#panel-hosts-v2').getByRole('button', { name: '＋ 新建主机' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('button', { name: '创建' })).toBeDisabled();
  await dialog.getByLabel('目标地址').fill('10.0.0.9');
  await dialog.getByLabel('端口').fill('7000');
  await expect(dialog.getByRole('button', { name: '创建' })).toBeEnabled();
  await dialog.getByRole('button', { name: '取消' }).click();
});

/* ==================== 详情：配置区 ==================== */

test('主机配置区·概览：改设置 → 保存 → 版本 +1 → 使设置生效', async ({ page }) => {
  const state = await mockApi(page);
  await openHost(page);
  const panel = page.locator('#panel-host-overview');

  await expect(panel).toContainText('当前设置 v3');
  await panel.getByLabel('名称').fill('产线 A 主机（改）');
  await panel.getByRole('button', { name: '保存设置' }).click();
  await expect.poll(() => state.hosts.find((host) => host.id === 'host-1')?.settingRevision).toBe(4);

  /* 设置版本与生效版本不一致 → 顶部出现「设置待生效」 */
  await expect(page.locator('.pending-banner')).toContainText('设置待生效');
  await page.locator('.pending-banner').getByRole('button', { name: '使设置生效' }).click();
  await expect.poll(() => state.hosts.find((host) => host.id === 'host-1')?.appliedRevision).toBe(4);
  await expect(page.locator('.pending-banner')).toHaveCount(0);
});

test('主机配置区不再有连接页签，连接动作在实例头', async ({ page }) => {
  const state = await mockApi(page);
  await openHost(page);
  await switchZone(page, '配置区');
  const tabs = page.locator('.subtabs');
  await expect(tabs.getByRole('tab', { name: '连接', exact: true })).toHaveCount(0);
  /* 旧深链回落到概览 */
  await page.goto('/#/hosts/host-1/config/connection');
  await expect(page.locator('#panel-host-overview')).toBeVisible();

  const head = page.locator('.instance-head');
  await head.getByRole('button', { name: '断开连接' }).click();
  await expect.poll(() => state.hosts.find((host) => host.id === 'host-1')?.open).toBe(false);

  await head.getByRole('button', { name: '连接', exact: true }).click();
  await expect.poll(() => state.hosts.find((host) => host.id === 'host-1')?.open).toBe(true);

  await head.getByRole('button', { name: '使设置生效' }).click();
  await expect.poll(() => state.calls.some((call) => call.endsWith('/apply'))).toBe(true);
});

test('主机配置区·报文模板：每条预设都给具体 SML 案例', async ({ page }) => {
  await openHost(page);
  await openSubTab(page, '报文模板');
  const panel = page.locator('#panel-host-presets');
  await expect(panel).toBeVisible();

  /* 不再只有 SxFy 名字：每条都带 SML 正文 */
  const cases = panel.locator('.preset-case');
  expect(await cases.count()).toBeGreaterThan(8);
  await expect(cases.first().locator('pre')).toContainText('S1F1 W');
  await expect(panel.getByRole('button', { name: '填入运行区发送' }).first()).toBeVisible();
  await expect(panel.getByRole('button', { name: '复制 SML' }).first()).toBeVisible();
});

test('主机配置区·Trace 设置：只生成不发（readOnly）', async ({ page }) => {
  await openHost(page);
  await openSubTab(page, 'Trace 设置');
  const panel = page.locator('#panel-host-trace');
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('Trace 设置');

  await panel.getByRole('button', { name: '生成 S2F23' }).click();
  await expect(panel.locator('pre.sml-result')).toContainText('S2F23 W');
  await expect(panel.getByRole('button', { name: '生成并发送' })).toHaveCount(0);
});

test('主机配置区·采集设置：保存方案 + 下发配置', async ({ page }) => {
  const state = await mockApi(page);
  await openHost(page);
  await openSubTab(page, '采集设置');
  const panel = page.locator('#panel-host-collect');
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('采集设置（S1F3 配置库）');

  /* 配置区能把当前参数存成一份方案，运行区一键下发 */
  await panel.getByLabel('配置名称（留空自动生成）').fill('温度 1s');
  await panel.getByRole('button', { name: '保存为配置' }).click();
  await expect(panel.locator('.plan-row')).toContainText('温度 1s');

  await panel.getByRole('button', { name: '下发配置' }).click();
  await expect(panel.getByText(/采集中|已配置|未配置/)).toBeVisible();
  await expect.poll(() => state.calls.some((call) => call.includes('/collector'))).toBe(true);
});

/* ==================== 详情：运行区 ==================== */

test('主机运行区·实时状态：相位与版本读数', async ({ page }) => {
  await openHost(page, 'host-2');
  await page.goto('/#/hosts/host-2/runtime/status');
  await expect(page.getByRole('heading', { name: '实时状态' })).toBeVisible();
  await expect(page.locator('.kpi-grid')).toContainText('链路状态');
  await expect(page.locator('.kpi-grid')).toContainText('最新设置版本');
  /* 断开的主机在运行区给出重连入口 */
  await expect(page.locator('.empty-zone')).toContainText('链路已断开，主机仍在列表里');
});

test('主机运行区·断开状态：空态里的重新连接可用', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/#/hosts/host-2/runtime/status');
  await page.locator('.empty-zone').getByRole('button', { name: '重新连接' }).click();
  await expect.poll(() => state.hosts.find((host) => host.id === 'host-2')?.open).toBe(true);
});

test('主机运行区·消息发送：预设填入 + 发送', async ({ page }) => {
  const state = await mockApi(page);
  await openHost(page);
  await switchZone(page, '运行区');
  await openSubTab(page, '消息发送');

  const panel = page.locator('#panel-host-send');
  await expect(panel).toBeVisible();
  const textarea = panel.getByLabel('本主机 SML 报文内容');
  await expect(textarea).toHaveValue('S1F13 W');

  /* 快捷预设按钮会替换草稿 */
  await panel.locator('.preview-controls button').first().click();
  await expect(textarea).not.toHaveValue('');

  await panel.getByRole('button', { name: '发送', exact: true }).click();
  await expect.poll(() => state.calls.some((call) => call.endsWith('/send'))).toBe(true);
  await expect(panel.locator('.sml-result').first()).toBeVisible();
});

test('主机运行区·Trace 采集：生成并发送', async ({ page }) => {
  const state = await mockApi(page);
  await openHost(page);
  await switchZone(page, '运行区');
  await openSubTab(page, 'Trace 采集');

  const panel = page.locator('#panel-host-trace');
  await expect(panel).toContainText('Trace 采集（S2F23 → S6F1）');
  await panel.getByRole('button', { name: '生成并发送' }).click();
  await expect.poll(() => state.calls.some((call) => call.endsWith('/send'))).toBe(true);
});

test('主机运行区·Trace 采集：S6F1 参数按 TRID 画趋势，可勾选参数', async ({ page }) => {
  await page.goto('/#/hosts/host-1/runtime/trace');
  const charts = page.locator('#panel-host-trace-charts');
  await expect(charts).toBeVisible();
  await expect(charts).toContainText('S6F1 参数趋势');
  await expect(charts.getByRole('heading', { name: /TRID 1/ })).toBeVisible();

  /* 默认只画前两个参数，不是把 S6F1 里的值全铺开 */
  await expect(charts.locator('.collector-chart')).toHaveCount(2);
  await expect(charts.locator('.collector-chart canvas').first()).toBeVisible();

  /* 取消一个参数 → 曲线少一条；选择按 TRID 单独保存 */
  await charts.locator('.param-picker .check-inline').first().locator('input').uncheck();
  await expect(charts.locator('.collector-chart')).toHaveCount(1);
  await expect(charts.locator('.param-picker .check-inline').first().locator('input')).not.toBeChecked();
});

test('主机运行区·数据采集：曲线与手动启停', async ({ page }) => {
  await openHost(page);
  await page.goto('/#/hosts/host-1/runtime/collect');
  const panel = page.locator('#panel-host-collect');
  await expect(panel).toBeVisible();

  /* mock 返回 4 个采样点 → 画一张曲线 */
  await expect(panel.locator('.collector-chart canvas').first()).toBeVisible();
  await expect(panel).toContainText('23.80');

  const start = panel.getByRole('button', { name: '手动开始' });
  const stop = panel.getByRole('button', { name: '手动停止' });
  /* 采集中：停止可用、开始禁用 */
  await expect(stop).toBeEnabled();
  await expect(start).toBeDisabled();
  await stop.click();
  await expect.poll(() => page.locator('#panel-host-collect').getByRole('button', { name: '手动开始' }).isEnabled())
    .toBe(true);
});

test('主机消息统一收进右侧抽屉：只看本主机 + 收发配色', async ({ page }) => {
  await page.goto('/#/hosts/host-1/runtime/status');
  /* 运行区不再有独立的收发记录子页/面板 */
  await expect(page.locator('#panel-host-monitor')).toHaveCount(0);
  await expect(page.locator('.subtabs')).not.toContainText('收发记录');

  const drawer = page.locator('.monitor-drawer');
  await page.locator('.monitor-toggle').click();
  await expect(drawer).toBeVisible();

  /* 只看当前主机：设备视角的报文被过滤掉 */
  await expect(drawer.getByLabel('选择要查看的设备或主机')).toHaveValue('host:host-1');
  await expect(drawer.getByText(/只看主机 host-1/)).toBeVisible();
  await expect(drawer.getByText('S1F1 W', { exact: true })).toBeVisible();
  await expect(drawer.getByText('S1F2', { exact: true })).toBeVisible();

  /* 主机视角：direction=in 是主机发出，out 是主机收到 */
  await expect(drawer.locator('.mm-row.sent')).toHaveCount(1);
  await expect(drawer.locator('.mm-row.recv')).toHaveCount(2);

  await drawer.getByLabel('按方向过滤').selectOption('sent');
  await expect(drawer.locator('.mm-row.recv')).toHaveCount(0);
  await drawer.getByLabel('按方向过滤').selectOption('all');

  await drawer.getByLabel('按 SxFy 过滤').fill('S1F2');
  await expect(drawer.getByText('S1F1 W', { exact: true })).toHaveCount(0);
  await expect(drawer.getByText('S1F2', { exact: true })).toBeVisible();
  await drawer.getByLabel('按 SxFy 过滤').fill('');

  await drawer.getByLabel('按报文内容过滤').fill('SECS-SIM');
  await expect(drawer.getByText('S1F2', { exact: true })).toBeVisible();
  await expect(drawer.getByText('S1F1 W', { exact: true })).toHaveCount(0);
  await drawer.getByLabel('按报文内容过滤').fill('');
});

test('主机运行区·手动操作页签已删除：旧深链回落到实时状态', async ({ page }) => {
  await openHost(page);
  await page.goto('/#/hosts/host-1/runtime/manual');
  await expect(page.locator('#panel-host-manual')).toHaveCount(0);
  await expect(page.locator('.subtabs')).not.toContainText('手动操作');
  await expect(page.locator('.subtabs').getByRole('tab', { name: '实时状态', exact: true }))
    .toHaveAttribute('aria-selected', 'true');
});

/* ==================== 骨架与空态 ==================== */

test('主机两段式：配置区 4 页、运行区 4 页（连接/手动操作已删除）', async ({ page }) => {
  await openHost(page);
  const tabs = page.locator('.subtabs');
  await expect(tabs.getByRole('tab')).toHaveCount(4);
  await expect(tabs).toContainText('报文模板');
  await expect(tabs).toContainText('Trace 设置');
  await expect(tabs).not.toContainText('连接');

  await switchZone(page, '运行区');
  await expect(tabs.getByRole('tab')).toHaveCount(4);
  await expect(tabs).not.toContainText('收发记录');
  await expect(tabs).not.toContainText('手动操作');
  await expect(tabs).toContainText('数据采集');
});

test('主机不存在时给出空态', async ({ page }) => {
  await page.goto('/#/hosts/ghost/config/overview');
  await expect(page.locator('#panel-host-missing')).toBeVisible();
  await page.getByRole('button', { name: '回主机列表' }).click();
  await expect(page.locator('#panel-hosts-v2')).toBeVisible();
});

test('侧栏主机树：点实例默认进运行区，展开后可直达子页', async ({ page }) => {
  await page.goto('/#/workbench');
  const nav = page.locator('.sidenav');
  await expect(nav.locator('.nav-group-title', { hasText: '主机清单' })).toBeVisible();
  await nav.locator('.nav-device', { hasText: '产线 A 主机' }).getByRole('button', { name: /产线 A 主机/ }).first().click();
  await expect(page.locator('.instance-head')).toContainText('产线 A 主机');
  /* 需求 8：菜单点进来默认运行区，配置区由实例头上的切换按钮进入 */
  await expect(page.locator('.zone-switch').getByRole('tab', { name: /运行区/ }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.subtabs')).toContainText('实时状态');

  await page.locator('.nav-device', { hasText: '产线 A 主机' }).getByRole('button', { name: /展开 产线 A 主机 的子页/ }).click();
  await page.locator('.nav-device', { hasText: '产线 A 主机' }).getByRole('button', { name: /数据采集/ }).click();
  await expect(page.locator('#panel-host-collect')).toBeVisible();
});

test('主机模板页：套用新建会建出一台主机', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');
  await openTool(page, '主机模板');
  const panel = page.locator('#panel-host-templates');
  await expect(panel).toBeVisible();
  await panel.getByRole('button', { name: '套用新建' }).first().click();
  await expect.poll(() => state.hosts.length).toBe(3);
  await expect(page.locator('.toast').first()).toContainText('已按模板');
});
