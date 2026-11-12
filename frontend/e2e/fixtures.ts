import { type Page } from '@playwright/test';

export const CONFIG_TEXT = JSON.stringify(
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

export async function mockApi(page: Page): Promise<void> {
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
              owner: 'CH1',
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
          events: [
            { at: 15000, ceid: 2001, planId: 'beat' },
            { at: 45000, ceid: 2001, planId: 'beat' },
          ],
        },
      });
    }

    if (path === '/api/v1/messages') {
      /* 消息监视面板的数据源：历史补拉一批足够覆盖多个类别 */
      return route.fulfill({
        json: {
          messages: [
            { sessionId: 'line1', direction: 'in', stream: 1, function: 1, wbit: true, body: '', at: 1_000 },
            { sessionId: 'line1', direction: 'out', stream: 1, function: 2, wbit: false, body: '', at: 1_001 },
            { sessionId: 'line1', direction: 'in', stream: 1, function: 3, wbit: true, body: '<L[1] <U4 1001>>', at: 1_002 },
            { sessionId: 'line1', direction: 'out', stream: 1, function: 4, wbit: false, body: '<L[1] <F4 42.5>>', at: 1_003 },
            { sessionId: 'line1', direction: 'out', stream: 6, function: 11, wbit: true, body: '<L[3]>', at: 1_004 },
            /* S6F1：主机监控页的 SxFy 过滤要有可命中的目标（否则"全滤掉"也能让断言通过） */
            { sessionId: 'line1', direction: 'out', stream: 6, function: 1, wbit: true, body: '<L[4]>', at: 1_005 },
          ],
          total: 6,
          limit: 500,
          truncated: false,
        },
      });
    }

    if (path === '/api/v1/host-sims') {
      if (method === 'POST') {
        /* 新建主机实例：POST 要回 201，前端据此刷新列表 */
        return route.fulfill({ status: 201, json: { ok: true } });
      }
      return route.fulfill({
        json: {
          hosts: [
            { id: 'host-1', session: 'line1', open: true, target: '127.0.0.1:5000', port: 5000, deviceId: 10, mode: 'local', remote: false, lastError: '' },
            /* 远程主机：对端不在本进程，用来验证"手填报文 / 不进设备页"的分支 */
            { id: 'host-remote', session: 'line1', open: true, target: '192.168.1.50:5000', port: 5000, deviceId: 5, mode: 'remote', remote: true, lastError: '' },
          ],
        },
      });
    }

    if (path === '/api/v1/host-sims/host-1/collector') {
      return route.fulfill({
        json: { configured: true, running: false, startCeid: 2001, stopCeid: 2002, intervalMs: 1000, svids: [{ id: 1001, name: 'TEMP', unit: 'C' }], samples: 12, decodeErrors: 0 },
      });
    }

    if (path === '/api/v1/host-sims/host-remote/collector') {
      /* 远程主机：没有 name/unit，SVID 是手填的 */
      return route.fulfill({ json: { configured: false, running: false } });
    }

    /* 主机级压测：POST 只受理，GET 回已完成的一轮统计 */
    if (path === '/api/v1/host-sims/host-1/burst' || path === '/api/v1/host-sims/host-remote/burst') {
      if (method === 'POST') {
        return route.fulfill({
          status: 202,
          json: { ok: true, accepted: true, running: true, count: 3, sent: 0, failed: 0 },
        });
      }
      return route.fulfill({
        json: {
          ok: true, running: false, accepted: true, count: 3,
          sent: 3, failed: 0, skipped: 0, aborted: false,
          durationMs: 12, latencyP50: 3, latencyP95: 5, latencyMax: 6,
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
