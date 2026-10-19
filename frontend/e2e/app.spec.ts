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
      return route.fulfill({ json: { ceids: [], reports: [], svids: [] } });
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
            { svid: 1001, min: [10, 20, 30, 40], max: [20, 30, 40, 50], avg: [15, 25, 35, 45] },
            { svid: 1002, min: [1, 2, 3, 4], max: [5, 6, 7, 8], avg: [3, 4, 5, 6] },
          ],
        },
      });
    }

    if (path === '/api/v1/messages') {
      return route.fulfill({ json: { messages: [], total: 0, limit: 500, truncated: false } });
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

test('loads identity, session, Canvas and uPlot preview', async ({ page }) => {
  await mockApi(page);
  await page.goto('/#token=e2e-token');

  await expect(page.getByText('管理员').first()).toBeVisible();
  await expect(page.getByRole('cell', { name: 'line1' }).first()).toBeVisible();

  await page.getByRole('button', { name: '刷新曲线' }).click();
  await expect(page.locator('.spark canvas')).toHaveCount(2);

  await page.locator('label:has-text("图表") select').selectOption('uplot');
  await expect(page.locator('.uplot-block')).toHaveCount(2);
  await expect(page.locator('.uplot-host canvas')).toHaveCount(2);
  await expect(page.locator('.uplot-host .u-legend').first()).toContainText('min');
});

test('opens a config and adds a curve through the structured editor', async ({ page }) => {
  await mockApi(page);
  await page.goto('/#token=e2e-token');

  const configSection = page.locator('section').filter({ hasText: '配置编辑器' }).first();
  await configSection.locator('select').first().selectOption('line1.json');
  await expect(page.getByText('模板（结构化编辑）')).toBeVisible();

  await page.getByRole('button', { name: '+ 曲线' }).click();
  await expect(page.locator('textarea.config-editor')).toHaveValue(/"curve"/);

  await configSection.getByRole('button', { name: '校验' }).click();
  await expect(page.getByText('校验通过')).toBeVisible();
  await configSection.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText(/已保存 line1\.json/)).toBeVisible();
});

test('toggles an event plan and shows the admin audit entry', async ({ page }) => {
  await mockApi(page);
  await page.goto('/#token=e2e-token');

  const eventSection = page.locator('section').filter({ hasText: '事件编排' }).first();
  await expect(eventSection.getByRole('cell', { name: 'beat' })).toBeVisible();
  await eventSection.getByRole('button', { name: '禁用' }).click();
  await expect(eventSection.getByRole('button', { name: '启用' })).toBeVisible();

  const auditSection = page.locator('section').filter({ hasText: '审计' }).first();
  await expect(auditSection.getByText('trigger-ceid', { exact: true })).toBeVisible();
});
