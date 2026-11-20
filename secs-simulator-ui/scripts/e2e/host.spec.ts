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
  await expect(page.locator('.host-page')).toHaveAttribute('data-host', hostId);
}

/* ==================== 列表页 ==================== */

test('主机列表：表格、搜索与行操作（无统计卡、无标题行）', async ({ page }) => {
  await openHosts(page);
  const panel = page.locator('#panel-hosts-v2');

  /* 顶部统计卡与面板标题行都已删除，一页只有"一行操作 + 一张表" */
  await expect(page.locator('.kpi-card')).toHaveCount(0);
  await expect(panel.locator('.panel-head')).toHaveCount(0);
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

  /* 删除要确认：单个和批量都走同一个确认框 */
  await row1.getByRole('button', { name: '删除' }).click();
  await expect(page.getByRole('dialog')).toContainText('确定删除主机「产线 A 主机」');
  await page.getByRole('dialog').locator('.modal-foot').getByRole('button', { name: '删除' }).click();
  await expect.poll(() => state.hosts.some((host) => host.id === 'host-1')).toBe(false);

  await panel.locator('tbody tr', { hasText: 'host-2' }).first().getByRole('button', { name: '详情' }).click();
  await expect(page.locator('.host-page')).toHaveAttribute('data-host', 'host-2');
});

test('主机清单：表头全选 + 批量连接 / 断开 / 删除', async ({ page }) => {
  const state = await mockApi(page);
  await openHosts(page);
  const panel = page.locator('#panel-hosts-v2');
  const batch = panel.locator('.table-toolbar');

  /* 没勾选时批量按钮全不可用 */
  await expect(batch.getByRole('button', { name: '批量连接' })).toBeDisabled();
  await expect(batch.getByRole('button', { name: '批量删除' })).toBeDisabled();

  const selectAll = panel.locator('thead').getByLabel('全选 / 清空');
  await selectAll.check();
  await expect(batch).toContainText('已选 2 台');

  /* 只有 host-2 没连上，批量连接只处理它 */
  await batch.getByRole('button', { name: '批量连接' }).click();
  await expect.poll(() => state.hosts.find((host) => host.id === 'host-2')?.open).toBe(true);
  /* 批量跑完勾选被清空，再勾一次之前先等它落定 */
  await expect(batch).not.toContainText('已选');

  /* 批量断开：两台都连着，逐台断开 */
  await selectAll.check();
  await batch.getByRole('button', { name: '批量断开' }).click();
  await expect.poll(() => state.hosts.filter((host) => host.open).length).toBe(0);
  await expect(batch).not.toContainText('已选');

  /* 批量删除要确认；删完清单空掉 */
  await selectAll.check();
  await batch.getByRole('button', { name: '批量删除' }).click();
  await expect(page.getByRole('dialog')).toContainText('确定删除选中的 2 台主机');
  await page.getByRole('dialog').locator('.modal-foot').getByRole('button', { name: '删除' }).click();
  await expect.poll(() => state.hosts.length).toBe(0);
  await expect(panel.locator('tbody tr')).toHaveCount(0);
});

test('新建主机：模板套用方案，实例目标信息仍由自己填', async ({ page }) => {
  const state = await mockApi(page);
  await openHosts(page);
  await page.locator('#panel-hosts-v2').getByRole('button', { name: '＋ 新建主机' }).click();

  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('新建主机');
  /* 默认值：名称 host、目标地址 127.0.0.1 */
  await expect(dialog.getByLabel('名称')).toHaveValue('host');
  await expect(dialog.getByLabel('目标地址')).toHaveValue('127.0.0.1');
  /* 模板只带方案，不覆盖实例的名称/地址/端口/Device ID */
  await dialog.getByLabel('从主机模板套用方案').selectOption('ht-1');
  await expect(dialog).toContainText('产线 A 方案');
  await expect(dialog.getByLabel('名称')).toHaveValue('host');
  await expect(dialog.getByLabel('目标地址')).toHaveValue('127.0.0.1');
  /* 默认端口避开同一 IP 上已被占用的 5000（夹具里 host-1 就是 127.0.0.1:5000） */
  await expect(dialog.getByLabel('端口', { exact: true })).toHaveValue('5001');

  await dialog.getByRole('button', { name: '创建' }).click();
  await expect.poll(() => state.hosts.length).toBe(3);
  /* 模板里 1 份 Trace + 1 份采集方案被复制进新主机的方案库 */
  await expect.poll(() => state.hostPlans.length).toBe(2);
  /* 2026-11-20 起建完留在清单里（不再自动跳详情页） */
  await expect(page).toHaveURL(/#\/hosts$/);
  await expect(page.locator('#panel-hosts-v2 tbody tr')).toHaveCount(3);
});

test('操作栏带目标端点；新建主机可从已启动设备快速填入', async ({ page }) => {
  await openHost(page);
  const tag = page.locator('.detail-bar .endpoint-tag');
  /* target 后端已经拼成 host:port，这里只补 Device ID，不重复端口 */
  await expect(tag).toContainText('127.0.0.1:5000');
  await expect(tag).toContainText('| 10');

  await openHosts(page);
  await page.locator('#panel-hosts-v2').getByRole('button', { name: '＋ 新建主机' }).click();
  const dialog = page.getByRole('dialog');
  const form = dialog.locator('.session-form');
  const picker = dialog.getByLabel('快速连接设备');
  /* 只列已启动的设备（line1 在线、line2 端口在监听），加上一条"手动填写" */
  await expect(picker.locator('option')).toHaveCount(3);

  await picker.selectOption('line1');
  await expect(form.locator('input').nth(0)).toHaveValue('host-LINE1');
  /* 绑定在 0.0.0.0 的设备，主机要从回环地址连 */
  await expect(form.locator('input').nth(1)).toHaveValue('127.0.0.1');
  await expect(form.locator('input').nth(2)).toHaveValue('5000');
  await expect(form.locator('input').nth(3)).toHaveValue('10');
});

test('新建主机：默认值即可创建，清空目标地址才禁用', async ({ page }) => {
  await openHosts(page);
  await page.locator('#panel-hosts-v2').getByRole('button', { name: '＋ 新建主机' }).click();
  const dialog = page.getByRole('dialog');
  /* 默认名称 host / 默认地址 127.0.0.1（端口自动避开已占用）：打开即可创建 */
  await expect(dialog.getByRole('button', { name: '创建' })).toBeEnabled();
  await dialog.getByLabel('目标地址').fill('');
  await expect(dialog.getByRole('button', { name: '创建' })).toBeDisabled();
  await dialog.getByLabel('目标地址').fill('10.0.0.9');
  await dialog.getByLabel('端口', { exact: true }).fill('7000');
  await expect(dialog.getByRole('button', { name: '创建' })).toBeEnabled();
  await dialog.getByRole('button', { name: '取消' }).click();
});

test('新建主机：名称不能重名、同一个 IP 的端口不能重复', async ({ page }) => {
  await openHosts(page);
  await page.locator('#panel-hosts-v2').getByRole('button', { name: '＋ 新建主机' }).click();
  const dialog = page.getByRole('dialog');

  /* 与夹具里的 host-1（127.0.0.1:5000）撞名 + 撞端口：两条红字都出，创建不可点 */
  await dialog.getByLabel('名称').fill('产线 A 主机');
  await dialog.getByLabel('端口', { exact: true }).fill('5000');
  await expect(dialog).toContainText('已被占用');
  await expect(dialog).toContainText('同一个 IP 的端口不能重复');
  await expect(dialog.getByRole('button', { name: '创建' })).toBeDisabled();

  /* 换个名字、换个端口就能建 */
  await dialog.getByLabel('名称').fill('走查主机');
  await dialog.getByLabel('端口', { exact: true }).fill('5002');
  await expect(dialog.getByRole('button', { name: '创建' })).toBeEnabled();
  await dialog.getByRole('button', { name: '取消' }).click();
});

test('新建主机：「立即连接」默认开；关掉只建实例不建链', async ({ page }) => {
  const state = await mockApi(page);
  await openHosts(page);
  const panel = page.locator('#panel-hosts-v2');

  /* 默认勾选：创建后马上连上（与旧行为一致） */
  await panel.getByRole('button', { name: '＋ 新建主机' }).click();
  let dialog = page.getByRole('dialog');
  await expect(dialog.getByLabel('立即连接')).toBeChecked();
  await dialog.getByRole('button', { name: '创建' }).click();
  await expect.poll(() => state.hosts.find((host) => host.id === 'host-3')?.open).toBe(true);
  /* 2026-11-20 起建完留在清单里：新主机直接出现在表格里，要看细节点「详情」 */
  await expect(page).toHaveURL(/#\/hosts$/);
  await expect(panel.locator('tbody tr')).toHaveCount(3);

  /* 关掉开关：只建实例，停在断开态，之后可从列表再连 */
  await panel.getByRole('button', { name: '＋ 新建主机' }).click();
  dialog = page.getByRole('dialog');
  await dialog.getByLabel('立即连接').uncheck();
  await dialog.getByRole('button', { name: '创建' }).click();
  await expect.poll(() => state.hosts.length).toBe(4);
  await expect(panel.locator('tbody tr')).toHaveCount(4);
  const idle = state.hosts.find((host) => host.id === 'host-4');
  expect(idle?.open).toBe(false);
  expect(idle?.phase).toBe('DISCONNECTED');
});

/* ==================== 详情：配置区 ==================== */

test('主机配置区不再有连接页签，连接动作在实例头', async ({ page }) => {
  const state = await mockApi(page);
  await openHost(page);
  await switchZone(page, '配置区');
  const tabs = page.locator('.subtabs');
  await expect(tabs.getByRole('tab', { name: '连接', exact: true })).toHaveCount(0);
  /* 旧深链回落到概览 */
  await page.goto('/#/hosts/host-1/config/connection');
  await expect(page.locator('#panel-host-overview')).toBeVisible();

  const head = page.locator('.detail-bar');
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

  /* 预设现在是表格：每条给 SxFy 与说明，双击行看具体 SML 案例 */
  const rows = panel.locator('tbody tr');
  expect(await rows.count()).toBeGreaterThan(8);
  await expect(rows.first().locator('code')).toContainText('S1F1');
  await rows.first().dblclick();
  const dialog = page.getByRole('dialog');
  await expect(dialog.locator('pre')).toContainText('S1F1 W');
  await expect(dialog.getByRole('button', { name: '填入运行区发送' })).toBeVisible();
  await expect(dialog.getByRole('button', { name: '复制 SML' })).toBeVisible();
});

/* ==================== 详情：运行区 ==================== */

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
  await expect(drawer.getByLabel('选择要查看的设备或主机').locator('option:checked')).toContainText('host-1');
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

/* ==================== 骨架与空态 ==================== */

test('主机不存在时给出空态', async ({ page }) => {
  await page.goto('/#/hosts/ghost/config/overview');
  await expect(page.locator('#panel-host-missing')).toBeVisible();
  await page.getByRole('button', { name: '回主机列表' }).click();
  await expect(page.locator('#panel-hosts-v2')).toBeVisible();
});

test('主机模板页：套用新建会建出一台主机', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');
  await openTool(page, '主机模板');
  const panel = page.locator('#panel-host-templates');
  await expect(panel).toBeVisible();
  await panel.getByRole('button', { name: '套用新建' }).first().click();
  /* 目标地址/端口/Device ID 只在「建主机」这一步出现，模板弹框里没有 */
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('新建主机');
  await expect(dialog.getByLabel('目标地址')).toHaveValue('127.0.0.1');
  await dialog.getByRole('button', { name: '创建主机' }).click();
  await expect.poll(() => state.hosts.length).toBe(3);
  /* 内置「标准联调」含 1 份 Trace + 1 份采集方案 */
  await expect.poll(() => state.hostPlans.length).toBe(2);
  await expect(page.locator('.toast').first()).toContainText('已按模板');
});
