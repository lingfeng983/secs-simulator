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
              counters: {},
            },
          ],
        },
      });
    }

    if (path === '/api/v1/sessions' && method === 'POST') {
      return route.fulfill({ status: 201, json: { ok: true } });
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
            },
          ],
        },
      });
    }

    if (path === '/api/v1/sessions/line1/events/beat') {
      eventEnabled = url.searchParams.get('enabled') === 'true';
      return route.fulfill({ json: { ok: true } });
    }

    if (path === '/api/v1/sessions/line1/catalog') {
      return route.fulfill({
        json: {
          ceids: [{ ceid: 2001, owner: 'CH1', slot: 'TEMP_HIGH', reports: [1], source: 'local' }],
          reports: [{ rptid: 1, owner: 'CH1', slot: 'TEMP', svids: [1001], source: 'local' }],
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
          svidTotal: 2,
          truncated: false,
          series: [
            { svid: 1001, owner: 'CH1', unit: 'C', min: [10, 20, 30, 40], max: [20, 30, 40, 50], avg: [15, 25, 35, 45] },
            { svid: 1002, owner: 'CH1', unit: 'Torr', min: [1, 2, 3, 4], max: [5, 6, 7, 8], avg: [3, 4, 5, 6] },
          ],
        },
      });
    }

    if (path === '/api/v1/messages') {
      return route.fulfill({
        json: {
          messages: [
            {
              sessionId: 'line1',
              direction: 'in',
              stream: 1,
              function: 1,
              wbit: false,
              body: 'S1F1\n<L\n  <B 0x81>\n>.',
              at: 1_700_000_000_000,
            },
          ],
          total: 1,
          limit: 500,
          truncated: false,
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
        return route.fulfill({ json: { name: 'line1.json', text: CONFIG_TEXT } });
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

/** 切到某个面板。界面改成侧边栏导航以后要先把面板点出来。 */
async function gotoPanel(page: Page, label: string): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: label }).first().click();
  await expect(page.locator(`.panel[id="panel-${panelId(label)}"]`)).toBeVisible();
}

function panelId(label: string): string {
  switch (label) {
    case '会话':
      return 'sessions';
    case '报警':
      return 'alarms';
    case '事件编排':
      return 'events';
    case '曲线预览':
      return 'preview';
    case '设备目录':
      return 'catalog';
    case '报文':
      return 'messages';
    case '手动发送':
      return 'send';
    case '配置编辑器':
      return 'config';
    case '审计':
      return 'audit';
    default:
      return 'console';
  }
}

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

test('loads identity, session, Canvas and uPlot preview', async ({ page }) => {
  await page.goto('/#token=e2e-token');

  await expect(page.getByText('管理员').first()).toBeVisible();
  await expect(page.getByRole('cell', { name: 'line1' }).first()).toBeVisible();

  await gotoPanel(page, '曲线预览');
  await page.getByRole('button', { name: '刷新曲线' }).click();
  await expect(page.locator('.spark canvas')).toHaveCount(2);

  await page.locator('label:has-text("图表") select').selectOption('uplot');
  await expect(page.locator('.uplot-block')).toHaveCount(2);
  await expect(page.locator('.uplot-host canvas')).toHaveCount(2);
  await expect(page.locator('.uplot-host .u-legend').first()).toContainText('min');

  await page.locator('label:has-text("图表") select').selectOption('multi');
  await expect(page.locator('.multi-axis-host canvas')).toHaveCount(1);
  await expect(page.locator('.multi-axis-host .u-axis')).toHaveCount(3);
});

test('opens a config and adds a curve through the structured editor', async ({ page }) => {
  await page.goto('/#token=e2e-token');

  await gotoPanel(page, '配置编辑器');
  const configSection = page.locator('#panel-config');
  await configSection.locator('select').first().selectOption('line1.json');
  await expect(page.getByText('模板（结构化编辑）')).toBeVisible();

  await page.getByRole('button', { name: '+ 曲线' }).click();
  await expect(page.locator('textarea.config-editor')).toHaveValue(/"curve"/);

  await configSection.getByRole('button', { name: '校验' }).click();
  await expect(page.getByText('校验通过')).toBeVisible();
  await configSection.getByRole('button', { name: '保存' }).click();
  await expect(configSection.getByText(/已保存 line1\.json/)).toBeVisible();
});

test('toggles an event plan and shows the admin audit entry', async ({ page }) => {
  await page.goto('/#token=e2e-token');

  await gotoPanel(page, '事件编排');
  const eventSection = page.locator('#panel-events');
  await expect(eventSection.getByRole('cell', { name: 'beat' })).toBeVisible();
  await eventSection.getByRole('button', { name: '禁用' }).click();
  await expect(eventSection.getByRole('button', { name: '启用' })).toBeVisible();

  await gotoPanel(page, '审计');
  await expect(page.locator('#panel-audit').getByText('trigger-ceid', { exact: true })).toBeVisible();
});

test('filters sessions with the table search box', async ({ page }) => {
  await page.goto('/#token=e2e-token');

  const sessions = page.locator('#panel-sessions');
  await expect(sessions.getByRole('cell', { name: 'line1', exact: true })).toBeVisible();

  await sessions.getByPlaceholder('搜索会话 id / 设备 / 状态').fill('不存在的名字');
  await expect(sessions.getByText('没有匹配「不存在的名字」的行')).toBeVisible();

  await sessions.getByPlaceholder('搜索会话 id / 设备 / 状态').fill('LINE1');
  await expect(sessions.getByRole('cell', { name: 'line1', exact: true })).toBeVisible();
});

test('opens the command palette and jumps to a panel', async ({ page }) => {
  await page.goto('/#token=e2e-token');

  await page.keyboard.press('Control+k');
  const palette = page.locator('.palette');
  await expect(palette).toBeVisible();

  await palette.locator('.palette-input').fill('设备目录');
  await expect(palette.getByText('前往 设备目录')).toBeVisible();
  await palette.getByText('前往 设备目录').click();

  await expect(page.locator('#panel-catalog')).toBeVisible();
});

test('switches the theme and remembers it', async ({ page }) => {
  await page.goto('/#token=e2e-token');

  const before = await page.locator('html').getAttribute('data-theme');
  await page.getByRole('button', { name: '切换主题' }).click();
  const after = await page.locator('html').getAttribute('data-theme');

  expect(after).not.toBe(before);

  /* 刷新以后仍然是刚才选的主题 */
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', after ?? 'light');
});

test('shows a toast after an action instead of only logging it', async ({ page }) => {
  await page.goto('/#token=e2e-token');

  await page.locator('#panel-sessions').getByRole('button', { name: '上报' }).click();
  await expect(page.locator('.toast').first()).toBeVisible();
  await expect(page.locator('.toast-title').first()).toContainText('触发 line1 CEID');
});

test('loads message history and filters it', async ({ page }) => {
  await page.goto('/#token=e2e-token');

  await gotoPanel(page, '报文');
  await page.getByRole('button', { name: '载入历史' }).click();
  await expect(page.locator('#panel-messages').getByRole('cell', { name: 'S1F1', exact: true })).toBeVisible();

  await page.locator('#panel-messages').getByPlaceholder('搜索报文体 / SxFy').fill('S9F9');
  await expect(page.locator('#panel-messages').getByText('当前过滤条件下没有报文')).toBeVisible();
});

test('fills the CEID from the device catalog', async ({ page }) => {
  await page.goto('/#token=e2e-token');

  await gotoPanel(page, '设备目录');
  await page.locator('#panel-catalog').getByRole('button', { name: '填入' }).first().click();

  await gotoPanel(page, '会话');
  await expect(page.locator('#panel-sessions').locator('input[type="number"]').first()).toHaveValue('2001');
});
