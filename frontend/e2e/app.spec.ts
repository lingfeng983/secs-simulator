import { expect, test, type Page } from '@playwright/test';

const CONFIG_TEXT = JSON.stringify(
  {
    schemaVersion: 5,
    session: { id: 'line1', passive: true, port: 5000, deviceId: 10 },
    equipment: {
      name: 'LINE1',
      idSpace: {},
      units: [
        {
          unitId: 1,
          name: 'CH1',
          injectUnitId: false,
          svids: [{ offset: 1, slot: 'TEMP', type: 'F4', unit: 'C' }],
          reports: [],
          ceids: [],
        },
      ],
    },
    events: { maxPerSec: 5, plans: [] },
    templates: {
      curves: [{ id: 'wave', type: 'sine', min: 0, max: 100, periodMs: 60000, phaseMs: 0 }],
      composites: [],
      instances: [],
    },
  },
  null,
  2,
);

async function mockApi(page: Page): Promise<void> {
  let eventEnabled = true;

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    if (path === '/api/v1/health') {
      return route.fulfill({
        json: {
          ok: true,
          version: 'e2e',
          mode: 'integrated',
          sessions: 1,
          uptimeMs: 60_000,
          user: 'e2e',
          role: 'admin',
          vm: 'jvm',
          utf8: true,
        },
      });
    }

    if (path === '/api/v1/ticket') {
      return route.fulfill({ json: { ok: true, ticket: 'e2e-ticket', expiresInMs: 30_000 } });
    }

    if (path === '/api/v1/events') {
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: ': connected\n\n',
      });
    }

    if (path === '/api/v1/sessions' && method === 'GET') {
      return route.fulfill({
        json: {
          sessions: [
            {
              id: 'line1',
              state: 'OPEN',
              controlState: 'ONLINE',
              device: 'LINE1',
              units: 1,
              lastError: '',
              config: 'line1.json',
              counters: {},
            },
          ],
        },
      });
    }

    if (path === '/api/v1/sessions' && method === 'POST') {
      return route.fulfill({ status: 201, json: { ok: true } });
    }

    if (path === '/api/v1/overview') {
      /* 工作台设备卡的数据源 */
      return route.fulfill({
        json: {
          sessions: [
            {
              id: 'line1',
              state: 'OPEN',
              controlState: 'ONLINE',
              hasPeer: true,
              device: 'LINE1',
              units: 1,
              port: 5000,
              counters: { emitted: 3, activeAlarms: 0 },
              msgsPerMin: 6,
              s6f11PerMin: 2,
              lastEventAt: 0,
              uptimeMs: 60_000,
              hostSim: false,
            },
          ],
        },
      });
    }

    if (path === '/api/v1/sessions/line1/events') {
      return route.fulfill({
        json: {
          events: [
            {
              id: 'beat',
              ceid: 2001,
              enabled: eventEnabled,
              triggerType: 'periodic',
              periodMs: 1000,
              jitterMs: 0,
              effects: [{ svid: 1001, op: 'set', value: 80 }],
            },
          ],
        },
      });
    }

    if (path === '/api/v1/sessions/line1/events/beat') {
      if (method === 'DELETE' || method === 'PUT') {
        return route.fulfill({ json: { ok: true } });
      }
      eventEnabled = url.searchParams.get('enabled') === 'true';
      return route.fulfill({ json: { ok: true } });
    }

    if (path === '/api/v1/sessions/line1/catalog') {
      return route.fulfill({
        json: {
          ceids: [{ ceid: 2001, owner: 'CH1', slot: 'TEMP_HIGH', reports: [1], source: 'local' }],
          reports: [{ rptid: 1, owner: 'CH1', slot: 'TEMP_REPORT', svids: [1001], source: 'local' }],
          svids: [{ svid: 1001, owner: 'CH1', slot: 'TEMP', type: 'F4', unit: 'C' }],
        },
      });
    }

    if (path === '/api/v1/sessions/line1/alarms') {
      return route.fulfill({ json: [] });
    }

    if (path === '/api/v1/sessions/line1/terminal') {
      return route.fulfill({ json: [] });
    }

    if (path === '/api/v1/preview') {
      return route.fulfill({
        json: {
          from: 0,
          to: 60_000,
          buckets: 4,
          bucketMs: 15_000,
          svidTotal: 1,
          truncated: false,
          series: [
            { svid: 1001, owner: 'CH1', unit: 'C', min: [10, 20, 30, 40], max: [20, 30, 40, 50], avg: [15, 25, 35, 45] },
          ],
        },
      });
    }

    if (path === '/api/v1/configs') {
      return route.fulfill({
        json: { configs: [{ name: 'line1.json', sizeBytes: CONFIG_TEXT.length, modifiedMs: 1 }] },
      });
    }

    if (path === '/api/v1/configs/line1.json') {
      if (method === 'GET') {
        return route.fulfill({ json: { name: 'line1.json', text: CONFIG_TEXT, modifiedMs: 1 } });
      }
      return route.fulfill({ json: { ok: true } });
    }

    if (path === '/api/v1/configs/line1.json/validate') {
      return route.fulfill({ json: { ok: true, valid: true, problems: [] } });
    }

    if (path === '/api/v1/audit') {
      return route.fulfill({
        json: {
          audit: [
            {
              at: 1_700_000_000_000,
              actor: 'e2e',
              role: 'admin',
              action: 'trigger-ceid',
              target: 'line1',
              detail: 'ceid=2001 outcome=OK',
            },
          ],
          limit: 200,
        },
      });
    }

    return route.fulfill({ json: {} });
  });
}

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

  /* 默认落在工作台:品牌、KPI、设备总览表与会话管理都在 */
  await expect(page.locator('.brand-name')).toContainText('SECS Simulator');
  await expect(page.getByText('在线设备').first()).toBeVisible();
  await expect(page.locator('#panel-sessions')).toBeVisible();
  const deviceTable = page.locator('[data-table="workbench-devices"], .table-scroll table').first();
  await expect(deviceTable.getByRole('cell', { name: 'LINE1', exact: true })).toBeVisible();

  /* 点表格行进设备页·事件设置(默认子页) */
  await page.locator('tr', { hasText: 'LINE1' }).first().click();
  await expect(page.getByRole('tab', { name: '事件设置' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#panel-ceids').getByRole('cell', { name: 'TEMP_HIGH' })).toBeVisible();
});

test('auto-opens the device config and adds a curve through the structured editor', async ({ page }) => {
  await page.goto('/#/device/line1/config');

  const configSection = page.locator('#panel-config');
  await expect(configSection).toBeVisible();
  /* 本设备的配置(line1.json)自动打开,不用手选 */
  await expect(configSection.getByText(/已打开本设备的配置 line1\.json/)).toBeVisible();
  await expect(page.getByText('模板（结构化编辑）')).toBeVisible();

  await page.getByRole('button', { name: '+ 曲线' }).click();
  await configSection.getByRole('button', { name: 'JSON 原文' }).click();
  await expect(page.locator('textarea.config-editor')).toHaveValue(/"curve"/);
  await configSection.getByRole('button', { name: '表单', exact: true }).click();

  await configSection.getByRole('button', { name: '校验' }).click();
  await expect(page.getByText('校验通过')).toBeVisible();
  await configSection.getByRole('button', { name: '保存' }).click();
  await expect(configSection.getByText(/已保存 line1\.json/)).toBeVisible();
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
  await expect(page.locator('#panel-svids .spark canvas').first()).toBeVisible();
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

test('opens the command palette and jumps to a device', async ({ page }) => {
  await page.goto('/');

  await page.keyboard.press('Control+k');
  const palette = page.locator('.palette');
  await expect(palette).toBeVisible();

  await palette.locator('.palette-input').fill('LINE1');
  await expect(palette.getByText('打开设备 LINE1')).toBeVisible();
  await palette.getByText('打开设备 LINE1').click();

  await expect(page.getByRole('tab', { name: '事件设置' })).toBeVisible();
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

test('shows a toast after triggering from the sessions table', async ({ page }) => {
  await page.goto('/');

  await page.locator('#panel-sessions').getByRole('button', { name: '上报', exact: true }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
  await expect(page.locator('.toast-title').first()).toContainText('触发 line1 CEID');
});

test('device subpages each show their own capability', async ({ page }) => {
  await page.goto('/');
  await openDevice(page, 'LINE1');
  await expect(page.getByRole('tab', { name: '事件设置' })).toBeVisible();

  /* 事件设置:CEID/报告 CRUD + 压测上报 */
  const ceids = page.locator('#panel-ceids');
  await expect(ceids.getByRole('button', { name: '新增事件' })).toBeVisible();
  await expect(ceids.getByRole('button', { name: '新增报告' })).toBeVisible();
  await expect(ceids.getByRole('button', { name: '开始压测' })).toBeVisible();

  /* 事件编排:计划 CRUD */
  await openDeviceTab(page, '事件编排', 'events');
  await expect(page.locator('#panel-events').getByRole('button', { name: '新增计划' })).toBeVisible();

  /* 发送页:单发 + 压测发送同框 */
  await openDeviceTab(page, '发送', 'send');
  await expect(page.locator('#panel-send').getByRole('button', { name: '发送 SML' })).toBeVisible();
  await expect(page.locator('#panel-send').getByRole('button', { name: '压测发送' })).toBeVisible();

  /* 报警页:置位/清除 + 报警风暴同框 */
  await openDeviceTab(page, '报警', 'alarms');
  await expect(page.locator('#panel-alarms').getByRole('button', { name: '置位', exact: true })).toBeVisible();
  await expect(page.locator('#panel-alarms').getByRole('button', { name: '报警风暴' })).toBeVisible();

  /* 主机模拟页:三面板分区 */
  await openDeviceTab(page, '主机模拟', 'hostsim');
  await expect(page.getByText('① 消息发送（自定义 + 预设）')).toBeVisible();
  await expect(page.getByText('② FDC Trace 数据采集（S2F23 → S6F1）')).toBeVisible();
  await expect(page.getByText('③ S1F3 数据采集（主机侧拉取）')).toBeVisible();
});

test('ceids page offers burst with catalog datalist', async ({ page }) => {
  await page.goto('/#/device/line1/ceids');

  const ceids = page.locator('#panel-ceids');
  await ceids.getByPlaceholder('如 2001').fill('2001');
  await ceids.getByRole('button', { name: '开始压测' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
});
