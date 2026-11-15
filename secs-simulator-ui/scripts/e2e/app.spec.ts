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

test('落在工作台：三组侧栏 + 设备一张表（无统计卡、无标题行）', async ({ page }) => {
  await page.goto('/');

  const nav = page.locator('.sidenav');
  await expect(nav.locator('.nav-group-title', { hasText: /^设备$/ })).toBeVisible();
  await expect(nav.locator('.nav-item', { hasText: '设备清单' })).toBeVisible();
  await expect(nav.locator('.nav-group-title', { hasText: '主机清单' })).toBeVisible();
  await expect(nav.locator('.nav-group-title', { hasText: '独立工具' })).toBeVisible();

  /* 不再有品牌区和版本行 */
  await expect(page.locator('.brand-name')).toHaveCount(0);
  await expect(nav).not.toContainText('SNAPSHOT');

  /* 顶部统计卡与面板标题行都已删除：一页只有"一行操作 + 一张表" */
  await expect(page.locator('.kpi-card')).toHaveCount(0);
  await expect(page.locator('#panel-sessions .panel-head')).toHaveCount(0);
  await expect(page.locator('#panel-sessions')).toBeVisible();
  /* 只有一张表：设备与会话已合并 */
  await expect(page.locator('#panel-sessions table')).toHaveCount(1);
  await expect(page.locator('#panel-sessions')).toContainText('LINE1');
  await expect(page.locator('#panel-sessions')).toContainText('LINE2');
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

test('顶部信息栏只有图标：不显示页面路径，也没有按钮文字', async ({ page }) => {
  await page.goto('/');
  await page.locator('#panel-sessions tbody tr', { hasText: 'LINE1' }).first().click();
  await expect(page.locator('.device-page')).toHaveAttribute('data-device', 'LINE1');

  /* 最左是 GUI 图标区，且只有图标 */
  const bar = page.locator('.titlebar');
  await expect(bar).toHaveCount(1);
  await expect(bar.locator('.titlebar-brand .app-mark')).toBeVisible();
  await expect(bar.locator('.titlebar-brand')).not.toContainText('SECS');

  /* 页面路径与按钮文字都下线：顶栏只承担图标动作 */
  await expect(bar.locator('.titlebar-path')).toHaveCount(0);
  await expect(bar.locator('.settings-btn')).toHaveCount(0);
  await expect(bar).not.toContainText('LINE1');
  await expect(bar).not.toContainText('通用设置');
  await expect(bar).not.toContainText('消息监控');

  /* 顶栏是一条通栏：左侧菜单从它下方开始，不直通顶部 */
  const barBox = await bar.boundingBox();
  const navBox = await page.locator('.sidenav').boundingBox();
  expect(barBox).not.toBeNull();
  expect(navBox).not.toBeNull();
  if (barBox !== null && navBox !== null) {
    expect(barBox.height).toBeLessThanOrEqual(56);
    expect(navBox.y).toBeGreaterThanOrEqual(barBox.y + barBox.height - 1);
  }
});

test('右侧消息监控从顶栏下方开始，顶栏始终完整可见', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/plans');
  await page.locator('.monitor-toggle').click();
  const drawer = page.locator('.monitor-drawer');
  await expect(drawer).toBeVisible();

  const barBox = await page.locator('.titlebar').boundingBox();
  const drawerBox = await drawer.boundingBox();
  expect(barBox).not.toBeNull();
  expect(drawerBox).not.toBeNull();
  if (barBox !== null && drawerBox !== null) {
    /* 抽屉不直通顶部：上沿落在信息栏下沿之下 */
    expect(drawerBox.y).toBeGreaterThanOrEqual(barBox.y + barBox.height - 1);
  }
});

test('消息监控头部：先区域后对象，标题行已下线，关闭在行尾', async ({ page }) => {
  await page.goto('/#/device/line1/runtime/plans');
  await page.locator('.monitor-toggle').click();
  const drawer = page.locator('.monitor-drawer');
  await expect(drawer).toBeVisible();

  /* 「实时消息监控」标题行删除，头部只剩一行 */
  await expect(drawer.locator('.monitor-head h2')).toHaveCount(0);
  await expect(drawer.locator('.monitor-head')).not.toContainText('实时消息监控');

  const region = drawer.getByLabel('按区域筛选');
  const target = drawer.getByLabel('选择要查看的设备或主机');
  const close = drawer.getByRole('button', { name: '收起消息监控' });
  await expect(region).toBeVisible();
  await expect(target).toBeVisible();
  await expect(close).toBeVisible();

  /* 顺序：区域 → 对象 → 关闭 */
  const regionBox = await region.boundingBox();
  const targetBox = await target.boundingBox();
  const closeBox = await close.boundingBox();
  expect(regionBox).not.toBeNull();
  expect(targetBox).not.toBeNull();
  expect(closeBox).not.toBeNull();
  if (regionBox !== null && targetBox !== null && closeBox !== null) {
    expect(regionBox.x).toBeLessThan(targetBox.x);
    expect(closeBox.x).toBeGreaterThan(targetBox.x);
  }

  /* 方向下拉的三档文案 */
  await expect(drawer.getByLabel('按方向过滤').locator('option')).toHaveText(['收发', '接收', '发送']);
});

test('页签右键：关闭其他 / 关闭全部', async ({ page }) => {
  await page.goto('/');
  await page.locator('#panel-sessions tbody tr', { hasText: 'LINE1' }).first().click();
  await expect(page.locator('.device-page')).toHaveAttribute('data-device', 'LINE1');
  await page.locator('.sidenav .nav-item', { hasText: '主机清单' }).first().click();

  const bar = page.locator('.tabbar');
  await expect(bar.locator('.app-tab')).toHaveCount(3);

  /* 右键「主机清单」→ 关闭其他：只剩工作台与它 */
  await bar.locator('.app-tab', { hasText: '主机清单' }).click({ button: 'right' });
  await expect(page.getByRole('menu')).toBeVisible();
  await page.getByRole('menuitem', { name: '关闭其他' }).click();
  await expect(bar.locator('.app-tab')).toHaveCount(2);
  await expect(bar.locator('.app-tab', { hasText: '设备清单' })).toBeVisible();
  await expect(bar.locator('.app-tab', { hasText: '主机清单' })).toBeVisible();

  /* 再右键 → 关闭全部：只剩常驻的工作台 */
  await bar.locator('.app-tab', { hasText: '主机清单' }).click({ button: 'right' });
  await page.getByRole('menuitem', { name: '关闭全部' }).click();
  await expect(bar.locator('.app-tab')).toHaveCount(1);
  await expect(bar.locator('.app-tab', { hasText: '设备清单' })).toBeVisible();
});

test('字号与主色作用到全局：表格正文与表头都跟着变', async ({ page }) => {
  await page.goto('/');
  const beforeCell = await page.locator('#panel-sessions tbody td').first()
    .evaluate((node) => parseFloat(getComputedStyle(node).fontSize));

  await page.getByRole('button', { name: '通用设置' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('字体大小').fill('17');
  await dialog.getByLabel('主色').selectOption('blue');
  await dialog.getByRole('button', { name: '完成' }).click();

  /* 正文与表头（原本是写死的 12.5px / 10.5px）都要跟着放大 */
  const afterCell = await page.locator('#panel-sessions tbody td').first()
    .evaluate((node) => parseFloat(getComputedStyle(node).fontSize));
  const headSize = await page.locator('#panel-sessions thead th').first()
    .evaluate((node) => parseFloat(getComputedStyle(node).fontSize));
  expect(afterCell).toBeGreaterThan(beforeCell);
  expect(headSize).toBeGreaterThan(12);

  /* 主色变量落到根上，表头底色不再是中性灰 */
  const accent = await page.evaluate(() => getComputedStyle(document.documentElement)
    .getPropertyValue('--accent').trim());
  expect(accent).toBe('#2563eb');
  const headBg = await page.locator('#panel-sessions thead th').first()
    .evaluate((node) => getComputedStyle(node).backgroundColor);
  expect(headBg).not.toBe('rgb(246, 248, 251)');
});

test('设备不存在时给出空态并可回设备清单', async ({ page }) => {
  await page.goto('/#/device/ghost/runtime/catalog');
  await expect(page.getByText('设备 ghost 不存在')).toBeVisible();
  await page.getByRole('button', { name: '← 设备清单' }).click();
  await expect(page.locator('#panel-sessions')).toBeVisible();
});

/* ---------------- 旧链接迁移 ---------------- */

test('2.x 旧主机模拟深链落到主机列表', async ({ page }) => {
  await page.goto('/#/hostsim/fdc');
  await expect(page.locator('#panel-hosts-v2')).toBeVisible();
});

/* ---------------- 工作台：筛选 / 批量 / 新建 / 导出 ---------------- */

test('工作台搜索与状态筛选', async ({ page }) => {
  await page.goto('/');
  const panel = page.locator('#panel-sessions');
  const search = panel.getByPlaceholder('搜索设备名 / 会话 id / 状态');

  await search.fill('不存在的名字');
  await expect(panel.getByText('没有匹配「不存在的名字」的行')).toBeVisible();

  await search.fill('LINE2');
  await expect(panel.getByRole('cell', { name: 'LINE2', exact: true })).toBeVisible();
  await expect(panel.getByRole('cell', { name: 'LINE1', exact: true })).toHaveCount(0);

  /* 状态筛选是四态下拉（启动 / 停止 / 离线 / 在线），不再是三个按钮 */
  await search.fill('');
  const statusFilter = panel.getByLabel('按状态筛选设备');
  /* line2 是"端口已开、主机还没连"= 启动档；line1 才是 ONLINE */
  await statusFilter.selectOption('started');
  await expect(panel.getByRole('cell', { name: 'LINE2', exact: true })).toBeVisible();
  await expect(panel.getByRole('cell', { name: 'LINE1', exact: true })).toHaveCount(0);

  await statusFilter.selectOption('online');
  await expect(panel.getByRole('cell', { name: 'LINE1', exact: true })).toBeVisible();
  await expect(panel.getByRole('cell', { name: 'LINE2', exact: true })).toHaveCount(0);

  await statusFilter.selectOption('');
});

test('批量工具条：表头全选 / 重启 / 停止确认', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');

  /* 批量动作就在表格工具条这一行（标题行已删） */
  const batch = page.locator('#panel-sessions .table-toolbar');
  await expect(batch).not.toContainText('已选');
  await expect(batch.getByRole('button', { name: '批量重启' })).toBeDisabled();
  /* 工具条上不再有「全选 / 清空选择」两个按钮：全选挪到表头复选框 */
  await expect(batch.getByRole('button', { name: '全选' })).toHaveCount(0);
  await expect(batch.getByRole('button', { name: '清空选择' })).toHaveCount(0);

  /* 表头复选框：勾上=全选，再点=清空 */
  const selectAll = page.locator('#panel-sessions thead').getByLabel('全选 / 清空');
  await selectAll.check();
  await expect(batch).toContainText('已选 2 台');
  await expect(batch.getByRole('button', { name: '批量同步配置' })).toBeEnabled();

  await batch.getByRole('button', { name: '批量重启' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
  await expect.poll(() => state.calls.filter((call) => call.endsWith('/reload')).length).toBe(2);

  /* 批量停止要弹确认；停止的是端口监听不是删除：行还在，可再启动 */
  await batch.getByRole('button', { name: '批量停止' }).click();
  await expect(page.getByRole('dialog')).toContainText('确定停止选中的 2 台设备');
  await page.getByRole('dialog').locator('.modal-foot').getByRole('button', { name: '停止' }).click();
  await expect.poll(() => state.sessions.filter((item) => item.state === 'CLOSED').length).toBe(2);
  await expect(page.locator('#panel-sessions tbody tr')).toHaveCount(2);

  /* 批量跑完勾选被清空（没勾选就不显示计数） */
  await expect(batch).not.toContainText('已选');

  /* 再勾一次全选、再点一次清空：复选框两个方向都对 */
  await selectAll.check();
  await expect(batch).toContainText('已选 2 台');
  await selectAll.uncheck();
  await expect(batch).not.toContainText('已选');
});

test('单行「删除」先确认，再把设备从清单里删掉', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');

  const row = page.locator('#panel-sessions tbody tr', { hasText: 'line2' }).first();
  await row.getByRole('button', { name: '删除' }).click();
  await expect(page.getByRole('dialog')).toContainText('确定删除设备 LINE2');
  await page.getByRole('dialog').locator('.modal-foot').getByRole('button', { name: '删除' }).click();

  await expect.poll(() => state.calls.filter((call) => call === 'DELETE /api/v1/configs/line2.json').length).toBe(1);
  /* 删完这一行就从清单里消失 */
  await expect(page.locator('#panel-sessions tbody tr')).toHaveCount(1);
  await expect(page.locator('#panel-sessions')).not.toContainText('LINE2');
});

test('批量同步配置弹窗能打开并给出目标台数', async ({ page }) => {
  await page.goto('/');
  await page.locator('#panel-sessions').getByLabel('选择 LINE1').check();
  await page.locator('#panel-sessions').getByLabel('选择 LINE2').check();
  await page.locator('#panel-sessions .table-toolbar').getByRole('button', { name: '批量同步配置' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('批量同步配置');
  await expect(dialog).toContainText('目标 1 台');
  await dialog.getByRole('button', { name: '取消' }).click();
  await expect(dialog).toHaveCount(0);
});

test('单行「重启」直接打后端，「停止」先确认再改状态，「启动」能再开端口', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');
  const row = page.locator('#panel-sessions tbody tr', { hasText: 'line1' }).first();

  await row.getByRole('button', { name: '重启' }).click();
  await expect.poll(() => state.calls.filter((call) => call.endsWith('/reload')).length).toBe(1);

  await row.getByRole('button', { name: '停止' }).click();
  /* 弹窗右上角的 × 也叫「关闭」，取底部的确认按钮 */
  await page.getByRole('dialog').locator('.modal-foot').getByRole('button', { name: '停止' }).click();
  await expect.poll(() => state.sessions.find((item) => item.id === 'line1')?.state).toBe('CLOSED');

  /* 停止后行不消失，按钮换成「启动」；启动打到 v2 的 link/connect */
  const closedRow = page.locator('#panel-sessions tbody tr', { hasText: 'line1' }).first();
  /* 会话列已删：行里能看到的只有设备名（大写） */
  await expect(closedRow).toContainText('LINE1');
  await closedRow.getByRole('button', { name: '启动' }).click();
  await expect.poll(() => state.sessions.find((item) => item.id === 'line1')?.state).toBe('OPEN');
  await expect.poll(() => state.calls.some((call) => call.endsWith('/link/connect'))).toBe(true);
});

test('新增设备弹框：填链接信息 + 选模板，建出配置与会话', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');

  const panel = page.locator('#panel-sessions');
  /* 头部不再有模板下拉与「管理模板」：新增入口只有一个按钮 */
  await expect(panel.getByLabel('新建设备使用的模板')).toHaveCount(0);
  await expect(panel.getByRole('button', { name: '管理模板' })).toHaveCount(0);

  await panel.getByRole('button', { name: '新建设备' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('新增设备');
  await dialog.getByLabel('设备名称').fill('etch-a');
  await dialog.getByLabel('新建设备使用的模板').selectOption('etch');
  await dialog.getByLabel('监听地址').fill('0.0.0.0');
  await dialog.getByLabel('端口').fill('5110');
  await dialog.getByLabel('Device ID').fill('21');
  await dialog.getByRole('button', { name: '创建设备' }).click();

  await expect.poll(() => state.calls.filter((call) => call === 'POST /api/v1/configs').length).toBe(1);
  await expect.poll(() => state.calls.filter((call) => call === 'POST /api/v1/sessions').length).toBe(1);
  await expect(page.locator('.toast', { hasText: '已新建设备' }).first()).toBeVisible();
  await expect.poll(() => state.sessions.some((item) => item.id === 'etch-a')).toBe(true);
});

test('设备清单：批量动作与新建都在表格工具条这一行', async ({ page }) => {
  await page.goto('/');
  const panel = page.locator('#panel-sessions');
  /* 标题行已删，页面级按钮全部落在表格工具条里 */
  await expect(panel.locator('.panel-head')).toHaveCount(0);
  const header = panel.locator('.table-toolbar');
  await expect(header).toBeVisible();
  await expect(header.getByRole('button', { name: '新建设备' })).toBeVisible();
  await expect(header.getByRole('button', { name: '批量同步配置' })).toBeVisible();
  await expect(header.getByRole('button', { name: '批量重启' })).toBeVisible();
  await expect(header.getByRole('button', { name: '批量停止' })).toBeVisible();
  /* 没勾选时不显示"已选 N 台" */
  await expect(header).not.toContainText('已选 0 台');
  /* 旧的常驻提示不再占版面 */
  await expect(panel).not.toContainText('配置只认');
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

test('命令面板：去主机清单 / 打开设备 / 执行操作', async ({ page }) => {
  await page.goto('/');
  await page.keyboard.press('Control+k');
  const palette = page.locator('.palette');
  await expect(palette).toBeVisible();
  await expect(palette.locator('.palette-input')).toBeFocused();

  await palette.locator('.palette-input').fill('主机清单');
  await palette.locator('.command-item', { hasText: '前往 主机清单' }).first().click();
  await expect(page.locator('#panel-hosts-v2')).toBeVisible();

  await page.keyboard.press('Control+k');
  await palette.locator('.palette-input').fill('LINE1');
  await palette.getByText(/打开设备 LINE1/).click();
  await expect(page.locator('.device-page')).toHaveAttribute('data-device', 'LINE1');
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

test('密度切换改变行高并持久化（入口在通用设置）', async ({ page }) => {
  await page.goto('/');
  const before = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--row-pad').trim());
  await page.getByRole('button', { name: '通用设置' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('表格密度').selectOption('compact');
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
  await expect(page.locator('.device-page')).toHaveAttribute('data-device', 'LINE1');
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
  await expect(page.getByPlaceholder('搜索设备名 / 会话 id / 状态')).toBeFocused();

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

test('日志级别在通用设置里切换并打后端', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/');
  await page.getByRole('button', { name: '通用设置' }).click();
  await page.getByRole('dialog').getByLabel('后端日志级别').selectOption('debug');
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

test('设备模板页：每行都有编辑，编辑器与校验/保存全在弹框里', async ({ page }) => {
  await page.goto('/#/templates');
  const panel = page.locator('#panel-templates');
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('etch');
  await expect(panel.locator('th', { hasText: '操作' })).toBeVisible();
  /* 表格下方不再挂编辑器：校验/导入/导出/保存都只出现在弹框里 */
  await expect(panel.getByRole('button', { name: '校验' })).toHaveCount(0);
  await expect(panel.getByRole('button', { name: '保存' })).toHaveCount(0);
  await expect(panel.getByRole('button', { name: '编辑' })).toHaveCount(2);

  await panel.getByRole('button', { name: '编辑' }).first().click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('编辑设备模板');
  await expect(dialog).toContainText('内置模板 · 只能另存');
  await expect(dialog.getByRole('button', { name: '校验' })).toBeEnabled();
  await expect(dialog.getByRole('button', { name: '导入 JSON' })).toBeEnabled();
  await expect(dialog.getByRole('button', { name: '导出 JSON' })).toBeEnabled();
  await expect(dialog.getByRole('button', { name: '另存为模板' })).toBeEnabled();
  /* 配置原文收进了页签里的 JSON 编辑器，弹框默认停在概览 */
  await dialog.getByRole('tab', { name: '配置原文' }).click();
  await expect(dialog.getByLabel('模板 JSON 原文')).toBeVisible();
  await dialog.getByRole('button', { name: '取消' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);

  await panel.getByRole('button', { name: '新建模板' }).click();
  await expect(page.getByRole('dialog')).toContainText('新建设备模板');
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('主机模板页：模板只装方案，编辑弹框没有目标地址/端口/Device ID', async ({ page }) => {
  await page.goto('/#/host-templates');
  const panel = page.locator('#panel-host-templates');
  await expect(panel).toBeVisible();
  /* 两份内置默认模板 + 一条自定义模板，列里只谈方案 */
  await expect(panel).toContainText('标准联调');
  await expect(panel).toContainText('高频采样');
  await expect(panel).toContainText('产线 A 方案');
  await expect(panel).toContainText('Trace 方案');
  await expect(panel).toContainText('采集方案');

  await panel.getByRole('button', { name: '编辑' }).first().click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('Trace 方案');
  await expect(dialog).toContainText('采集方案');
  await expect(dialog.getByLabel('目标地址')).toHaveCount(0);
  await expect(dialog.getByLabel('端口')).toHaveCount(0);
  await expect(dialog.getByLabel('Device ID')).toHaveCount(0);
  await dialog.getByRole('button', { name: '取消' }).click();

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

/* ---------------- 顶部标签 / 通用设置 / 区域分组 ---------------- */

test('顶部标签：打开过的页面留下标签，可切换也可关闭', async ({ page }) => {
  await page.goto('/');
  const bar = page.locator('.tabbar');
  await expect(bar).toBeVisible();
  await expect(bar.locator('.app-tab', { hasText: '设备清单' })).toBeVisible();

  await openDeviceFromSidebar(page, 'LINE1');
  await expect(bar.locator('.app-tab', { hasText: 'LINE1' })).toBeVisible();

  await openTool(page, '主机清单');
  await expect(bar.locator('.app-tab', { hasText: '主机清单' })).toBeVisible();

  /* 标签按三类上色：设备 / 主机 / 其他，颜色由 data-tab-kind 决定 */
  await expect(bar.locator('.app-tab', { hasText: '设备清单' })).toHaveAttribute('data-tab-kind', 'device');
  await expect(bar.locator('.app-tab', { hasText: 'LINE1' })).toHaveAttribute('data-tab-kind', 'device');
  await expect(bar.locator('.app-tab', { hasText: '主机清单' })).toHaveAttribute('data-tab-kind', 'host');

  /* 点回设备标签：回到这台设备最后停留的子页 */
  await bar.locator('.app-tab', { hasText: 'LINE1' }).locator('.app-tab-label').click();
  await expect(page.locator('.device-page')).toHaveAttribute('data-device', 'LINE1');

  /* 关掉主机标签：当前不在该标签上，页面不跳；标签消失 */
  await bar.locator('.app-tab', { hasText: '主机清单' }).getByRole('button', { name: /关闭/ }).click();
  await expect(bar.locator('.app-tab', { hasText: '主机清单' })).toHaveCount(0);
  await expect(page.locator('.device-page')).toHaveAttribute('data-device', 'LINE1');
});

test('通用设置：主题 / 主色 / 布局 / 密度落到根元素并跨刷新保留', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: '通用设置' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('通用设置');

  await dialog.getByLabel('主色').selectOption('blue');
  await dialog.getByLabel('整体布局').selectOption('centered');
  await dialog.getByLabel('表格密度').selectOption('compact');
  await dialog.getByLabel('主题').selectOption('dark');

  await expect(page.locator('html')).toHaveAttribute('data-accent', 'blue');
  await expect(page.locator('html')).toHaveAttribute('data-layout', 'centered');
  await expect(page.locator('html')).toHaveAttribute('data-density', 'compact');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

  await dialog.getByRole('button', { name: '完成' }).click();
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-accent', 'blue');
  await expect(page.locator('html')).toHaveAttribute('data-layout', 'centered');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
});

test('区域分组：设备可改区域，左侧菜单按区域归类', async ({ page }) => {
  await page.goto('/');
  const panel = page.locator('#panel-sessions');

  /* 通过筛选框的「＋ 新建区域」建一个区域（会弹一次输入框） */
  page.once('dialog', (dialog) => void dialog.accept('A 区'));
  await panel.getByLabel('按区域筛选设备').selectOption('__new__');
  await expect(panel.getByLabel('按区域筛选设备').locator('option', { hasText: 'A 区' })).toHaveCount(1);

  /* 回到全部区域，把 LINE1 归到新区域：侧栏随即出现该分组 */
  await panel.getByLabel('按区域筛选设备').selectOption('');
  await panel.getByLabel('LINE1 的区域').selectOption('A 区');
  await expect(page.locator('.sidenav .nav-region-head', { hasText: 'A 区' })).toBeVisible();

  /* 侧栏按区域分组：A 区下面只有 LINE1，LINE2 留在 Default */
  const groupA = page.locator('.sidenav .nav-region', { hasText: 'A 区' });
  await expect(groupA.locator('.nav-device', { hasText: 'LINE1' })).toBeVisible();
  await expect(groupA.locator('.nav-device', { hasText: 'LINE2' })).toHaveCount(0);
  /* 顶部的区域筛选框已经下线 */
  await expect(page.locator('.sidenav .nav-region-filter')).toHaveCount(0);
});

test('设备概览与其它配置页共用面板外壳', async ({ page }) => {
  await page.goto('/#/device/line1/config/overview');
  const panel = page.locator('#panel-overview');
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('会话 id');
  await expect(panel).toContainText('配置文件');
});
