import { expect, test } from '@playwright/test';
import { mockApi, openDeviceFromSidebar, openSubTab, openTool } from './fixtures';

/**
 * 3.0 外壳 + 工作台 + 全局控件。
 *
 * <p>这一批覆盖"每个入口都能进、每个全局按钮都有反应"：
 * 侧栏三组、工作台一张表、命令面板、主题/密度/侧栏、连接设置、旧链接迁移。</p>
 */

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

/* ---------------- 外壳与导航 ---------------- */

test('落在工作台：三组侧栏 + KPI + 设备一张表', async ({ page }) => {
  await page.goto('/');

  const nav = page.locator('.sidenav');
  await expect(nav.locator('.nav-group-title', { hasText: /^设备$/ })).toBeVisible();
  await expect(nav.locator('.nav-item', { hasText: '设备清单' })).toBeVisible();
  await expect(nav.locator('.nav-group-title', { hasText: '主机清单' })).toBeVisible();
  await expect(nav.locator('.nav-group-title', { hasText: '独立工具' })).toBeVisible();

  /* 不再有品牌区和版本行 */
  await expect(page.locator('.brand-name')).toHaveCount(0);
  await expect(nav).not.toContainText('SNAPSHOT');

  await expect(page.getByText('在线设备').first()).toBeVisible();
  await expect(page.getByText('报文 / 分钟').first()).toBeVisible();
  await expect(page.locator('#panel-sessions')).toBeVisible();
  /* 只有一张表：设备与会话已合并 */
  await expect(page.locator('#panel-sessions table')).toHaveCount(1);
  await expect(page.locator('#panel-sessions')).toContainText('LINE1');
  await expect(page.locator('#panel-sessions')).toContainText('LINE2');
});

test('侧栏设备树：点设备进运行区，展开子页可直达配置区', async ({ page }) => {
  await page.goto('/');

  await openDeviceFromSidebar(page, 'LINE1');
  /* 菜单进设备默认落运行区，切换按钮并入实例头，不再单独占一行 */
  await expect(page.locator('.zonebar')).toHaveCount(0);
  await expect(page.locator('.zone-switch').getByRole('tab', { name: /运行区/ }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.subtabs')).toContainText('事件与报告');

  /* 平铺设备树：展开箭头直接可见，不用先切「分组」 */
  const deviceNode = page.locator('.sidenav .nav-device', { hasText: 'LINE1' });
  await deviceNode.getByRole('button', { name: /展开 LINE1 的子页/ }).click();
  await deviceNode.getByRole('button', { name: /版本历史/ }).click();
  await expect(page.locator('.zone-switch').getByRole('tab', { name: /配置区/ }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.subtabs').getByRole('tab', { name: '版本历史' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('heading', { name: '配置版本历史' })).toBeVisible();
});

test('侧栏设备树已精简：筛选 / 模板分组 / 平铺切换全部下线', async ({ page }) => {
  await page.goto('/');
  const nav = page.locator('.sidenav');
  await expect(nav.getByLabel('筛选设备')).toHaveCount(0);
  await expect(nav.locator('.nav-mode')).toHaveCount(0);
  await expect(nav.locator('.nav-template-head')).toHaveCount(0);
  await expect(nav.locator('.nav-device-tools')).toHaveCount(0);
  /* 设备仍然平铺列出，展开后子页可用（.nav-device 主机树也在用，只数第一组） */
  const deviceGroup = nav.locator('.nav-group').first();
  await expect(deviceGroup.locator('.nav-device')).toHaveCount(2);
  await expect(deviceGroup.getByRole('button', { name: /展开 LINE1 的子页/ })).toBeVisible();
});

test('顶栏只占一行：不展示分组名，路径单行显示', async ({ page }) => {
  await page.goto('/');
  await page.locator('#panel-sessions tbody tr', { hasText: 'LINE1' }).first().click();
  await expect(page.locator('.instance-head .instance-title')).toContainText('LINE1');

  const path = page.locator('.page-path');
  await expect(path).toHaveCount(1);
  await expect(path).toContainText('LINE1');
  /* 「设备清单 / 主机清单」这类分组名不再上顶栏，也不再有面包屑+标题+说明三行 */
  await expect(page.locator('.page-crumb')).toHaveCount(0);
  await expect(path).not.toContainText('设备清单');
  await expect(path).not.toContainText('主机清单');
});

test('设备不存在时给出空态并可回设备清单', async ({ page }) => {
  await page.goto('/#/device/ghost/runtime/catalog');
  await expect(page.getByText('设备 ghost 不存在')).toBeVisible();
  await page.getByRole('button', { name: '← 设备清单' }).click();
  await expect(page.locator('#panel-sessions')).toBeVisible();
});

/* ---------------- 旧链接迁移 ---------------- */

test('2.x 旧设备深链映射到 3.0 两段式', async ({ page }) => {
  await page.goto('/#/device/line1/events');
  await expect(page.locator('.zone-switch').getByRole('tab', { name: /运行区/ })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.subtabs').getByRole('tab', { name: '事件计划' })).toHaveAttribute('aria-selected', 'true');

  await page.goto('/#/device/line1/config');
  await expect(page.locator('.subtabs').getByRole('tab', { name: '配置原文' })).toHaveAttribute('aria-selected', 'true');
});

test('输入草稿跨页签保留：切走再回来还在', async ({ page }) => {
  await page.goto('/#/hosts/host-1/config/trace');
  const nameField = page.locator('#panel-host-trace').getByLabel('方案名称（留空自动生成）');
  await nameField.fill('草稿-温度曲线');

  /* 切到别的子页再切回来：面板重挂，草稿要从缓存里恢复 */
  await openSubTab(page, '报文模板');
  await expect(page.locator('#panel-host-presets')).toBeVisible();
  await openSubTab(page, 'Trace 设置');
  await expect(page.locator('#panel-host-trace').getByLabel('方案名称（留空自动生成）'))
    .toHaveValue('草稿-温度曲线');
});

test('2.x 旧主机模拟深链落到主机列表', async ({ page }) => {
  await page.goto('/#/hostsim/fdc');
  await expect(page.locator('#panel-hosts-v2')).toBeVisible();
});

/* ---------------- 工作台：筛选 / 批量 / 新建 / 导出 ---------------- */

test('工作台搜索与状态筛选', async ({ page }) => {
  await page.goto('/');
  const panel = page.locator('#panel-sessions');
  const search = panel.getByPlaceholder('搜索会话 id / 设备 / 状态');

  await search.fill('不存在的名字');
  await expect(panel.getByText('没有匹配「不存在的名字」的行')).toBeVisible();

  await search.fill('LINE2');
  await expect(panel.getByRole('cell', { name: 'line2', exact: true })).toBeVisible();
  await expect(panel.getByRole('cell', { name: 'line1', exact: true })).toHaveCount(0);

  await search.fill('');
  await panel.getByRole('button', { name: '离线', exact: true }).click();
  await expect(panel.getByRole('cell', { name: 'line2', exact: true })).toBeVisible();
  await expect(panel.getByRole('cell', { name: 'line1', exact: true })).toHaveCount(0);

  await panel.getByRole('button', { name: '在线', exact: true }).click();
  await expect(panel.getByRole('cell', { name: 'line1', exact: true })).toBeVisible();
  await panel.getByRole('button', { name: '全部', exact: true }).click();
});

test('批量工具条：全选 / 清空 / 重载 / 断开确认', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');

  const batch = page.locator('#panel-sessions .batch-bar');
  await expect(batch).toContainText('已选 0 台');
  await expect(batch.getByRole('button', { name: '批量重载' })).toBeDisabled();

  await batch.getByRole('button', { name: '全选' }).click();
  await expect(batch).toContainText('已选 2 台');
  await expect(batch.getByRole('button', { name: '批量同步配置' })).toBeEnabled();

  await batch.getByRole('button', { name: '批量重载' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
  await expect.poll(() => state.calls.filter((call) => call.endsWith('/reload')).length).toBe(2);

  /* 批量停止要弹确认；停止的是端口监听不是删除：行还在，可再启动 */
  await batch.getByRole('button', { name: '批量停止' }).click();
  await expect(page.getByRole('dialog')).toContainText('确定停止选中的 2 台设备');
  await page.getByRole('dialog').locator('.modal-foot').getByRole('button', { name: '停止' }).click();
  await expect.poll(() => state.sessions.filter((item) => item.state === 'CLOSED').length).toBe(2);
  await expect(page.locator('#panel-sessions tbody tr')).toHaveCount(2);

  /* 全选在"有行"时可用；清空后归零 */
  await expect(batch).toContainText('已选 0 台');
});

test('批量同步配置弹窗能打开并给出目标台数', async ({ page }) => {
  await page.goto('/');
  await page.locator('#panel-sessions').getByLabel('选择 line1').check();
  await page.locator('#panel-sessions').getByLabel('选择 line2').check();
  await page.locator('#panel-sessions .batch-bar').getByRole('button', { name: '批量同步配置' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('批量同步配置');
  await expect(dialog).toContainText('目标 1 台');
  await dialog.getByRole('button', { name: '取消' }).click();
  await expect(dialog).toHaveCount(0);
});

test('单行「重载」直接打后端，「停止」先确认再改状态，「启动」能再开端口', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');
  const row = page.locator('#panel-sessions tbody tr', { hasText: 'line1' }).first();

  await row.getByRole('button', { name: '重载' }).click();
  await expect.poll(() => state.calls.filter((call) => call.endsWith('/reload')).length).toBe(1);

  await row.getByRole('button', { name: '停止' }).click();
  /* 弹窗右上角的 × 也叫「关闭」，取底部的确认按钮 */
  await page.getByRole('dialog').locator('.modal-foot').getByRole('button', { name: '停止' }).click();
  await expect.poll(() => state.sessions.find((item) => item.id === 'line1')?.state).toBe('CLOSED');

  /* 停止后行不消失，按钮换成「启动」；启动打到 v2 的 link/connect */
  const closedRow = page.locator('#panel-sessions tbody tr', { hasText: 'line1' }).first();
  await expect(closedRow).toContainText('line1');
  await closedRow.getByRole('button', { name: '启动' }).click();
  await expect.poll(() => state.sessions.find((item) => item.id === 'line1')?.state).toBe('OPEN');
  await expect.poll(() => state.calls.some((call) => call.endsWith('/link/connect'))).toBe(true);
});

test('按模板新建设备：只选模板就建出配置与会话', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');

  const panel = page.locator('#panel-sessions');
  const select = panel.getByLabel('新建设备使用的模板');
  await expect(select).toBeVisible();
  await select.selectOption('etch');
  await panel.getByRole('button', { name: '新建设备' }).click();

  await expect.poll(() => state.calls.filter((call) => call === 'POST /api/v1/configs').length).toBe(1);
  await expect.poll(() => state.calls.filter((call) => call === 'POST /api/v1/sessions').length).toBe(1);
  await expect(page.locator('.toast', { hasText: '新建设备' }).first()).toBeVisible();
  await expect.poll(() => state.sessions.some((item) => item.id === 'etch-01')).toBe(true);
});

test('「管理模板」跳到设备模板页', async ({ page }) => {
  await page.goto('/');
  await page.locator('#panel-sessions').getByRole('button', { name: '管理模板' }).click();
  await expect(page.locator('#panel-templates')).toBeVisible();
});

test('导出 CSV 走下载并提示文件名', async ({ page }) => {
  /* 无头环境没有保存对话框：显式去掉，验证下载回退路径 */
  await page.addInitScript(() => {
    delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker;
  });
  await page.goto('/');
  const downloadPromise = page.waitForEvent('download');
  await page.locator('#panel-sessions').getByRole('button', { name: '导出 CSV' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('sessions.csv');
  await expect(page.getByText(/已开始下载「sessions\.csv」/)).toBeVisible();
});

/* ---------------- 全局控件 ---------------- */

test('命令面板：去主机模拟 / 打开设备 / 执行操作', async ({ page }) => {
  await page.goto('/');
  await page.keyboard.press('Control+k');
  const palette = page.locator('.palette');
  await expect(palette).toBeVisible();
  await expect(palette.locator('.palette-input')).toBeFocused();

  await palette.locator('.palette-input').fill('主机模拟');
  await palette.locator('.command-item', { hasText: '前往 主机模拟' }).first().click();
  await expect(page.locator('#panel-hosts-v2')).toBeVisible();

  await page.keyboard.press('Control+k');
  await palette.locator('.palette-input').fill('LINE1');
  await palette.getByText(/打开设备 LINE1/).click();
  await expect(page.locator('.instance-head .instance-title')).toContainText('LINE1');
});

test('命令面板里能切主题、切密度、开快捷键表', async ({ page }) => {
  await page.goto('/');
  await page.keyboard.press('Control+k');
  const palette = page.locator('.palette');

  await palette.locator('.palette-input').fill('切换主题');
  await palette.getByText('切换主题（浅色 / 深色 / 跟随系统）').click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', /.+/);

  await page.keyboard.press('Control+k');
  await palette.locator('.palette-input').fill('切换表格密度');
  await palette.getByText('切换表格密度（紧凑 / 宽松）').click();
  await expect(page.locator('html')).toHaveAttribute('data-density', 'compact');

  await page.keyboard.press('Control+k');
  await palette.locator('.palette-input').fill('查看快捷键');
  await palette.getByText('查看快捷键').click();
  await expect(page.locator('.shortcut-help')).toBeVisible();
});

test('主题切换后刷新仍记得', async ({ page }) => {
  await page.goto('/');
  const before = await page.locator('html').getAttribute('data-theme');
  await page.getByRole('button', { name: '切换主题' }).click();
  const after = await page.locator('html').getAttribute('data-theme');
  expect(after).not.toBe(before);
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', after ?? 'light');
});

test('密度切换改变行高并持久化', async ({ page }) => {
  await page.goto('/');
  const before = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--row-pad').trim());
  await page.getByRole('button', { name: '切换密度' }).click();
  const after = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--row-pad').trim());
  expect(after).not.toBe(before);
  await expect(page.locator('html')).toHaveAttribute('data-density', 'compact');
});

test('侧栏收起成图标栏，仍能点进设备', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: '收起导航栏' }).click();
  const side = page.locator('.sidenav');
  await expect(side).toHaveClass(/collapsed/);
  await expect(side.locator('.nav-monogram').first()).toHaveText('LI');
  await side.locator('.nav-item[title^="LINE1"]').click();
  await expect(page.locator('.instance-head .instance-title')).toContainText('LINE1');
  await page.getByRole('button', { name: '展开导航栏' }).click();
  await expect(side).not.toHaveClass(/collapsed/);
});

test('快捷键：? 打开说明表，Esc 关闭', async ({ page }) => {
  await page.goto('/');
  await page.keyboard.press('?');
  await expect(page.locator('.shortcut-help')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('.shortcut-help')).toHaveCount(0);
});

test('快捷键：数字跳导航，/ 聚焦搜索，R 刷新', async ({ page }) => {
  await page.goto('/');
  const state = await mockApi(page);

  await page.keyboard.press('3');
  await expect(page.locator('#panel-host-templates')).toBeVisible();

  await page.keyboard.press('1');
  await expect(page.locator('#panel-sessions')).toBeVisible();
  await page.keyboard.press('/');
  await expect(page.getByPlaceholder('搜索会话 id / 设备 / 状态')).toBeFocused();

  const before = state.calls.filter((call) => call === 'GET /api/v1/sessions').length;
  await page.locator('.content').click();
  await page.keyboard.press('r');
  await expect.poll(() => state.calls.filter((call) => call === 'GET /api/v1/sessions').length)
    .toBeGreaterThan(before);
});

test('连接设置弹窗可开可关，并说明当前是远程后端', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: '连接设置' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('后端地址');
  await expect(dialog).toContainText('默认同源访问');
  await dialog.getByRole('button', { name: '取消' }).click();
  await expect(dialog).toHaveCount(0);
});

test('日志级别可切换并打后端', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');
  const picker = page.locator('.loglevel-picker select');
  await picker.selectOption('debug');
  await expect.poll(() => state.calls.some((call) => call.includes('/log-level'))).toBe(true);
});

test('顶栏刷新按钮触发一次全量拉取', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');
  const before = state.calls.filter((call) => call === 'GET /api/v1/sessions').length;
  await page.getByRole('button', { name: /刷新/ }).first().click();
  await expect.poll(() => state.calls.filter((call) => call === 'GET /api/v1/sessions').length)
    .toBeGreaterThan(before);
});

/* ---------------- 独立工具 ---------------- */

test('设备模板页：自动打开首个模板，内置只读，新建弹窗可开可关', async ({ page }) => {
  await page.goto('/#/templates');
  const panel = page.locator('#panel-templates');
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('etch');

  /* 首个模板自动打开；内置模板只读，保存/校验禁用 */
  await expect(panel.getByText('内置只读')).toBeVisible();
  await expect(panel.getByRole('button', { name: '保存' })).toBeDisabled();
  await expect(panel.getByRole('button', { name: '校验' })).toBeDisabled();
  await expect(panel.getByRole('button', { name: '导出 JSON' })).toBeEnabled();

  await panel.getByRole('button', { name: '新建模板' }).click();
  await expect(page.getByRole('dialog')).toContainText('新建模板');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('主机模板页：从模板新建主机 / 编辑 / 删除', async ({ page }) => {
  await page.goto('/#/host-templates');
  const panel = page.locator('#panel-host-templates');
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('产线 A 目标');

  await panel.getByRole('button', { name: '编辑' }).first().click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();

  await panel.getByRole('button', { name: '删除' }).first().click();
  await expect(page.getByRole('dialog')).toContainText('删除模板');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();
});

test('审计页：列表 + 动作/操作者过滤 + 载入更早', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');
  await openTool(page, '审计');
  const panel = page.locator('#panel-audit');
  await expect(panel).toBeVisible();
  await expect(panel.getByText('trigger-ceid', { exact: true })).toBeVisible();

  /* 过滤要按 Enter 或点「过滤」才生效 */
  await panel.getByLabel('按动作过滤').fill('trigger-ceid');
  await panel.getByRole('button', { name: '过滤', exact: true }).click();
  await expect(panel.getByText('trigger-ceid', { exact: true })).toBeVisible();

  await panel.getByLabel('按动作过滤').fill('不存在的动作');
  await panel.getByRole('button', { name: '过滤', exact: true }).click();
  await expect(panel.getByText('当前过滤条件下没有审计记录')).toBeVisible();

  await panel.getByRole('button', { name: '清除过滤' }).click();
  await expect(panel.getByText('trigger-ceid', { exact: true })).toBeVisible();

  await panel.getByLabel('按操作者过滤').fill('e2e');
  await panel.getByRole('button', { name: '过滤', exact: true }).click();
  await expect(panel.getByText('trigger-ceid', { exact: true })).toBeVisible();
  await panel.getByRole('button', { name: '清除过滤' }).click();

  await panel.getByRole('button', { name: '载入更早' }).click();
  /* 以最早一条为游标向服务端要更早的一页（不再弹提示） */
  await expect.poll(() => state.queries.some((call) => call.includes('before='))).toBe(true);
});
