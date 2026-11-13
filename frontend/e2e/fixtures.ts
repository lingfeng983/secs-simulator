import { expect, type Page } from '@playwright/test';

/**
 * E2E 的假后端（3.0 IA）。
 *
 * <p>全部请求都由 Playwright 拦截：不起 Java 进程，也不受本机端口影响。
 * 这里的状态是**可变**的——新建/删除/连接/断开/启停都会改变后续 GET 的结果，
 * 否则"点完按钮再刷新看结果"这类回归根本测不出来。</p>
 */

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

/** 假后端的可变状态；每个用例重新创建（beforeEach 里 mockApi）。 */
export interface MockState {
  deviceOnline: boolean;
  hostConnected: boolean;
  eventEnabled: boolean;
  sessions: Array<{ id: string; device: string; controlState: string; state: string; port: number }>;
  hosts: Array<{
    id: string; name: string; phase: string; open: boolean; target: string; port: number;
    deviceId: number; autoReconnect: boolean; retryCount: number; settingRevision: number;
    appliedRevision: number; lastError: string;
  }>;
  templates: Array<{ id: string; label: string; source: string }>;
  hostTemplates: Array<{ id: string; name: string; host: string; port: number; deviceId: number; note: string }>;
  /** 记录被调用过的动作（只到路径），便于断言"按钮真的打到后端"。 */
  calls: string[];
  /** 带查询串的完整请求行（审计游标、台账过滤这类参数在这里看）。 */
  queries: string[];
}

function initialState(): MockState {
  return {
    deviceOnline: true,
    hostConnected: true,
    eventEnabled: true,
    sessions: [
      { id: 'line1', device: 'LINE1', controlState: 'ONLINE', state: 'OPEN', port: 5000 },
      { id: 'line2', device: 'LINE2', controlState: 'OFFLINE', state: 'OPEN', port: 5001 },
    ],
    hosts: [
      {
        id: 'host-1', name: '产线 A 主机', phase: 'CONNECTED', open: true, target: '127.0.0.1:5000',
        port: 5000, deviceId: 10, autoReconnect: true, retryCount: 0,
        settingRevision: 3, appliedRevision: 3, lastError: '',
      },
      {
        id: 'host-2', name: '远端 CVD', phase: 'DISCONNECTED', open: false, target: '192.168.8.30:7000',
        port: 7000, deviceId: 5, autoReconnect: false, retryCount: 0,
        settingRevision: 1, appliedRevision: 1, lastError: '',
      },
    ],
    templates: [
      { id: 'bare', label: '空白设备', source: 'builtin' },
      { id: 'etch', label: '刻蚀机', source: 'builtin' },
    ],
    hostTemplates: [
      { id: 'ht-1', name: '产线 A 目标', host: '127.0.0.1', port: 5000, deviceId: 10, note: '日常联调' },
    ],
    calls: [],
    queries: [],
  };
}

const sessionJson = (item: MockState['sessions'][number]) => ({
  id: item.id,
  state: item.state,
  controlState: item.controlState,
  device: item.device,
  units: 1,
  lastError: '',
  config: `${item.id}.json`,
  counters: {},
});

/** 一次 SML 发送的完整结果（界面按 sentSml/replySml 渲染）。 */
function sendResult(): unknown {
  return {
    ok: true, stream: 1, function: 13, wbit: true,
    sentSml: 'S1F13 W\n<L[0]>\n.',
    timedOut: false, replyStream: 1, replyFunction: 14, replyWbit: false,
    replySml: '<L[2] <A "SECS-SIM"> <A "3.0">>', elapsedMs: 5,
  };
}

const hostJson = (state: MockState, host: MockState['hosts'][number]) => ({
  ...host,
  session: '',
  mode: 'target',
  remote: true,
  nextRetryAt: 0,
});

/** 主机视角的报文：收发记录页按 hostId 过滤。 */
function hostMessages(): unknown[] {
  return [
    {
      seq: 2, sessionId: '', hostId: 'host-1', perspective: 'host',
      direction: 'in', stream: 1, function: 1, wbit: true, body: '<L[2] <A "MDLN"> <A "SOFTREV">>', at: Date.now() - 8_000,
    },
    {
      seq: 1, sessionId: '', hostId: 'host-1', perspective: 'host',
      direction: 'out', stream: 1, function: 2, wbit: false, body: '<L[2] <A "SECS-SIM"> <A "3.0">>', at: Date.now() - 7_500,
    },
  ];
}

/** 事件计划执行台账（含成功/失败/跳过/试跑）。 */
function executions(): unknown[] {
  return [
    {
      id: 4, deviceId: 'line1', planId: 'beat', ceid: 2001, dryRun: false,
      scheduledAt: Date.now() - 900, firedAt: Date.now() - 880, status: 'SENT',
      messages: [{ kind: 's6f11', stream: 6, function: 11, wbit: true, elapsedMs: 3 }],
      effects: [{ svid: 1001, op: 'add', before: 24.5, after: 25.0 }], error: '',
    },
    {
      id: 3, deviceId: 'line1', planId: 'beat', ceid: 2001, dryRun: false,
      scheduledAt: Date.now() - 1900, firedAt: Date.now() - 1880, status: 'FAILED',
      messages: [{ kind: 'sml', error: 'SML 解析失败: 缺少结束符' }],
      effects: { items: [{ svid: 1001, op: 'set', before: 23.0, after: 24.5 }], rollback: 'rollback_skipped' },
      error: 'SML 解析失败: 缺少结束符; rollback_skipped',
    },
    {
      id: 2, deviceId: 'line1', planId: 'beat', ceid: 2001, dryRun: false,
      scheduledAt: Date.now() - 2900, firedAt: Date.now() - 2880, status: 'SKIPPED',
      messages: [], effects: [], error: '设备离线（skip）',
    },
    {
      id: 1, deviceId: 'line1', planId: 'beat', ceid: 2001, dryRun: true,
      scheduledAt: Date.now() - 60_000, firedAt: Date.now() - 59_980, status: 'SENT',
      messages: [{ kind: 's6f11', stream: 6, function: 11, wbit: true, elapsedMs: 2 }],
      effects: [{ svid: 1001, op: 'set', before: 20.0, after: 23.0 }], error: '',
    },
  ];
}

/**
 * 让页面跑在假后端上。
 * @returns 可变状态，用例里可以直接断言/改写
 */
export async function mockApi(page: Page): Promise<MockState> {
  const state = initialState();

  /* 无头 Chromium 里 showSaveFilePicker 存在但没有真实对话框：
     统一去掉，让导出走"浏览器下载"这条可断言的回退路径。 */
  await page.addInitScript(() => {
    delete (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker;
  });

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    const ok = (body: unknown = { ok: true }, status = 200) => route.fulfill({ status, json: body });

    if (path !== '/api/v1/health' && path !== '/api/v1/events') {
      state.calls.push(`${method} ${path}`);
      if (url.search !== '') {
        state.queries.push(`${method} ${path}${url.search}`);
      }
    }

    /* ---------- 基础设施 ---------- */
    if (path === '/api/v1/health') {
      return ok({
        ok: true, version: 'e2e', mode: 'integrated', sessions: state.sessions.length,
        uptimeMs: 60_000, user: 'e2e', role: 'admin', vm: 'jvm', utf8: true,
      });
    }
    if (path === '/api/v1/ticket') {
      return ok({ ok: true, ticket: 'e2e-ticket', expiresInMs: 30_000 });
    }
    if (path === '/api/v1/events') {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: ': connected\n\n' });
    }

    /* ---------- 设备（v1 会话） ---------- */
    if (path === '/api/v1/sessions' && method === 'GET') {
      return ok({ sessions: state.sessions.map(sessionJson) });
    }
    if (path === '/api/v1/sessions' && method === 'POST') {
      const body = request.postDataJSON() as { config?: string } | null;
      const id = (body?.config ?? 'new.json').replace(/\.json$/i, '');
      state.sessions.push({ id, device: id.toUpperCase(), controlState: 'OFFLINE', state: 'OPEN', port: 5100 });
      return ok({ ok: true }, 201);
    }
    if (path === '/api/v1/sessions/bulk' && method === 'POST') {
      return ok({ created: ['dev-1'], failed: [] }, 201);
    }
    if (/^\/api\/v1\/sessions\/[^/]+$/.test(path) && method === 'DELETE') {
      const id = decodeURIComponent(path.split('/').pop() ?? '');
      state.sessions = state.sessions.filter((item) => item.id !== id);
      return ok({ ok: true });
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/reload$/.test(path)) {
      return ok({ ok: true });
    }
    if (path === '/api/v1/overview') {
      return ok({
        sessions: state.sessions.map((item) => ({
          id: item.id,
          state: item.state,
          controlState: item.controlState,
          hasPeer: item.controlState === 'ONLINE',
          device: item.device,
          units: 1,
          port: item.port,
          counters: { emitted: 3, activeAlarms: item.id === 'line1' ? 1 : 0 },
          msgsPerMin: item.controlState === 'ONLINE' ? 6 : 0,
          s6f11PerMin: item.controlState === 'ONLINE' ? 2 : 0,
          lastEventAt: 0,
          uptimeMs: 60_000,
          hostSim: false,
        })),
      });
    }

    /* 目录 / 事件 / 报告 / 报警 / 终端 / 预览 */
    if (/^\/api\/v1\/sessions\/[^/]+\/catalog$/.test(path)) {
      return ok({
        ceids: [{ ceid: 2001, owner: 'CH1', slot: 'TEMP_HIGH', reports: [1], source: 'local' }],
        reports: [{ rptid: 1, owner: 'CH1', slot: 'TEMP_REPORT', svids: [1001], source: 'local' }],
        svids: [{ svid: 1001, owner: 'CH1', slot: 'TEMP', type: 'F4', unit: 'C' }],
      });
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/events$/.test(path) && method === 'GET') {
      return ok({
        events: [{
          id: 'beat', ceid: 2001, owner: 'CH1', enabled: state.eventEnabled,
          triggerType: 'periodic', periodMs: 1000, jitterMs: 0,
          effects: [{ svid: 1001, op: 'set', value: 80 }],
        }],
      });
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/events(\/[^/]+)?$/.test(path)) {
      /* POST /events/{id}?enabled= 是启停；PUT/DELETE 是编辑与删除 */
      if (url.searchParams.has('enabled')) {
        state.eventEnabled = url.searchParams.get('enabled') === 'true';
        return ok({ ok: true });
      }
      if (method === 'PUT' || method === 'DELETE') {
        return ok({ ok: true });
      }
      return ok({ ok: true }, 201);
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/reports(\/\d+)?$/.test(path)) {
      return ok({ ok: true }, method === 'POST' ? 201 : 200);
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/svids(\/\d+)?(\/value)?$/.test(path)) {
      return ok({ ok: true }, method === 'POST' ? 201 : 200);
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/ceids(\/\d+)?$/.test(path)) {
      return ok({ ok: true }, method === 'POST' ? 201 : 200);
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/ceid\/\d+$/.test(path)) {
      return ok({ ok: true, outcome: 'OK' });
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/alarms$/.test(path)) {
      return ok([{ alid: 1001, alcd: 'rance', text: 'TEMP HIGH', set: true, at: Date.now() }]);
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/alarm\/\d+$/.test(path)) {
      return ok({ ok: true });
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/terminal$/.test(path)) {
      return ok([{ text: '设备就绪', at: Date.now() }]);
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/message$/.test(path)) {
      return ok(sendResult());
    }
    if (/^\/api\/v1\/sessions\/[^/]+\/burst(\/\d+)?$/.test(path)) {
      return ok({ ok: true, accepted: true, running: false, count: 3, sent: 3, failed: 0, skipped: 0,
        aborted: false, durationMs: 12, latencyP50: 3, latencyP95: 5, latencyMax: 6 });
    }
    if (path === '/api/v1/preview') {
      return ok({
        from: 0,
        to: 60_000,
        buckets: 4,
        bucketMs: 15_000,
        svidTotal: 1,
        truncated: false,
        series: [{ svid: 1001, owner: 'CH1', unit: 'C', min: [10, 20, 30, 40], max: [20, 30, 40, 50], avg: [15, 25, 35, 45] }],
        events: [{ at: 15_000, ceid: 2001, planId: 'beat' }, { at: 45_000, ceid: 2001, planId: 'beat' }],
      });
    }
    if (path === '/api/v1/messages') {
      return ok({
        messages: [
          { seq: 1, sessionId: 'line1', perspective: 'device', direction: 'in', stream: 1, function: 1, wbit: true, body: '', at: 1_000 },
          { seq: 2, sessionId: 'line1', perspective: 'device', direction: 'out', stream: 1, function: 2, wbit: false, body: '', at: 1_001 },
          { seq: 3, sessionId: 'line1', perspective: 'device', direction: 'in', stream: 1, function: 3, wbit: true, body: '<L[1] <U4 1001>>', at: 1_002 },
          { seq: 4, sessionId: 'line1', perspective: 'device', direction: 'out', stream: 1, function: 4, wbit: false, body: '<L[1] <F4 42.5>>', at: 1_003 },
          { seq: 5, sessionId: 'line1', perspective: 'device', direction: 'out', stream: 6, function: 11, wbit: true, body: '<L[3]>', at: 1_004 },
          { seq: 6, sessionId: 'line1', perspective: 'device', direction: 'out', stream: 6, function: 1, wbit: true, body: '<L[4]>', at: 1_005 },
          /* 主机视角：收发记录页要能按 hostId 认领 */
          ...hostMessages(),
        ],
        total: 8,
        limit: 500,
        truncated: false,
      });
    }

    /* ---------- 配置 / 模板 ---------- */
    if (path === '/api/v1/configs' && method === 'GET') {
      return ok({ configs: [
        { name: 'line1.json', sizeBytes: CONFIG_TEXT.length, modifiedMs: 1, template: 'etch' },
        { name: 'line2.json', sizeBytes: CONFIG_TEXT.length, modifiedMs: 2, template: 'bare' },
      ] });
    }
    if (path === '/api/v1/configs' && method === 'POST') {
      return ok({ ok: true }, 201);
    }
    if (/^\/api\/v1\/configs\/[^/]+$/.test(path)) {
      if (method === 'GET') {
        const name = decodeURIComponent(path.split('/').pop() ?? '');
        return ok({ name, text: CONFIG_TEXT, modifiedMs: 1 });
      }
      return ok({ ok: true });
    }
    if (/^\/api\/v1\/configs\/[^/]+\/validate$/.test(path)) {
      return ok({ ok: true, valid: true, problems: [] });
    }
    if (path === '/api/v1/config-templates') {
      return ok({ templates: state.templates.map((item) => ({ ...item, description: '内置模板' })) });
    }
    if (path === '/api/v1/templates' && method === 'GET') {
      return ok({ templates: state.templates });
    }
    if (path === '/api/v1/templates' && method === 'POST') {
      return ok({ ok: true }, 201);
    }
    if (/^\/api\/v1\/templates\/[^/]+$/.test(path)) {
      if (method === 'GET') {
        return ok({ id: 'etch', text: CONFIG_TEXT });
      }
      if (method === 'DELETE') {
        return ok({ ok: true });
      }
      return ok({ ok: true });
    }

    /* ---------- 审计 ---------- */
    if (path === '/api/v1/audit') {
      const entry = {
        at: 1_700_000_000_000, actor: 'e2e', role: 'admin', action: 'trigger-ceid',
        target: 'line1', detail: 'ceid=2001 outcome=OK',
      };
      const action = url.searchParams.get('action') ?? '';
      const actor = url.searchParams.get('actor') ?? '';
      const matched = (action === '' || action === entry.action) && (actor === '' || actor === entry.actor);
      return ok({ audit: matched ? [entry] : [], limit: 200 });
    }

    /* ---------- 主机实例（v2） ---------- */
    if (path === '/api/v2/hosts' && method === 'GET') {
      return ok({ hosts: state.hosts.map((host) => hostJson(state, host)) });
    }
    if (path === '/api/v2/hosts' && method === 'POST') {
      const body = request.postDataJSON() as { name?: string; host?: string; port?: number; deviceId?: number } | null;
      const id = `host-${state.hosts.length + 1}`;
      state.hosts.push({
        id, name: body?.name ?? id, phase: 'DISCONNECTED', open: false,
        target: `${body?.host ?? '127.0.0.1'}:${body?.port ?? 5000}`,
        port: body?.port ?? 5000, deviceId: body?.deviceId ?? 0,
        autoReconnect: false, retryCount: 0, settingRevision: 1, appliedRevision: 0, lastError: '',
      });
      return ok({ ok: true, hostId: id }, 201);
    }
    if (/^\/api\/v2\/hosts\/[^/]+\/messages$/.test(path)) {
      return ok({ ok: true, total: 2, messages: hostMessages() });
    }
    if (/^\/api\/v2\/hosts\/[^/]+\/variables$/.test(path)) {
      return ok({ ok: true, count: 2 });
    }
    if (/^\/api\/v2\/hosts\/[^/]+\/connect$/.test(path)) {
      const id = path.split('/')[4];
      const host = state.hosts.find((item) => item.id === id);
      if (host !== undefined) {
        host.open = true;
        host.phase = 'CONNECTED';
        host.appliedRevision = host.settingRevision;
      }
      return ok({ ok: true });
    }
    if (/^\/api\/v2\/hosts\/[^/]+\/disconnect$/.test(path)) {
      const id = path.split('/')[4];
      const host = state.hosts.find((item) => item.id === id);
      if (host !== undefined) {
        host.open = false;
        host.phase = 'DISCONNECTED';
      }
      return ok({ ok: true });
    }
    if (/^\/api\/v2\/hosts\/[^/]+\/apply$/.test(path)) {
      const id = path.split('/')[4];
      const host = state.hosts.find((item) => item.id === id);
      if (host !== undefined) {
        host.open = true;
        host.phase = 'CONNECTED';
        host.appliedRevision = host.settingRevision;
      }
      return ok({ ok: true });
    }
    if (/^\/api\/v2\/hosts\/[^/]+\/send$/.test(path)) {
      return ok(sendResult());
    }
    if (/^\/api\/v2\/hosts\/[^/]+$/.test(path)) {
      const id = path.split('/').pop() ?? '';
      const host = state.hosts.find((item) => item.id === id);
      if (method === 'DELETE') {
        state.hosts = state.hosts.filter((item) => item.id !== id);
        return ok({ ok: true });
      }
      if (host === undefined) {
        return ok({ ok: false, error: 'host-not-found' }, 404);
      }
      if (method === 'PUT') {
        const body = request.postDataJSON() as { name?: string; host?: string; port?: number; deviceId?: number } | null;
        host.name = body?.name ?? host.name;
        if (body?.host !== undefined) {
          host.target = `${body.host}:${body.port ?? host.port}`;
          host.port = body.port ?? host.port;
        }
        host.deviceId = body?.deviceId ?? host.deviceId;
        host.settingRevision += 1;
        return ok({ ok: true });
      }
      return ok(hostJson(state, host));
    }
    if (path === '/api/v2/host-templates' && method === 'GET') {
      return ok({ templates: state.hostTemplates });
    }
    if (path === '/api/v2/host-templates' && method === 'POST') {
      return ok({ ok: true }, 201);
    }
    if (/^\/api\/v2\/host-templates\/[^/]+$/.test(path) && method === 'DELETE') {
      const id = decodeURIComponent(path.split('/').pop() ?? '');
      state.hostTemplates = state.hostTemplates.filter((item) => item.id !== id);
      return ok({ ok: true });
    }

    /* ---------- 主机采集（v1 host-sims 兼容端点） ---------- */
    if (/^\/api\/v1\/host-sims\/[^/]+\/collector$/.test(path)) {
      if (method === 'GET') {
        return ok({
          configured: true, running: state.hostConnected, startCeid: 2001, stopCeid: 2002, intervalMs: 1000,
          svids: [{ id: 1001, name: 'TEMP', unit: 'C' }], samples: 12, decodeErrors: 0,
          series: [{
            svid: 1001, name: 'TEMP', unit: 'C',
            t: [1_000, 2_000, 3_000, 4_000], v: [20.5, 22.1, 21.4, 23.8],
          }],
        });
      }
      return ok({ ok: true });
    }
    if (/^\/api\/v1\/host-sims\/[^/]+\/collector\/(start|stop)$/.test(path)) {
      state.hostConnected = path.endsWith('/start');
      return ok({ ok: true });
    }

    /* ---------- 设备 v2：版本历史 / 台账 / 试跑 / 启停 ---------- */
    if (/^\/api\/v2\/devices\/[^/]+\/config\/revisions$/.test(path)) {
      return ok({ revisions: [
        { revision: 2, sha256: 'aabbccddeeff00112233', createdAt: Date.now() - 600_000, createdBy: 'ui', note: '修改超时' },
        { revision: 1, sha256: '11223344556677889900', createdAt: Date.now() - 3_600_000, createdBy: 'migrate', note: 'created' },
      ] });
    }
    if (/^\/api\/v2\/devices\/[^/]+\/runtime\/executions$/.test(path)) {
      const dryRun = url.searchParams.get('dryRun') ?? '0';
      const status = url.searchParams.get('status');
      const limit = Number(url.searchParams.get('limit') ?? '100');
      let items = executions().filter((item) => {
        const row = item as { dryRun: boolean };
        return dryRun === 'all' || dryRun === '1' || !row.dryRun;
      });
      if (status !== null && status !== '') {
        items = items.filter((item) => (item as { status: string }).status === status);
      }
      return ok({ items: items.slice(0, Number.isFinite(limit) ? limit : 100), count: items.length });
    }
    if (/^\/api\/v2\/devices\/[^/]+\/runtime\/plans\/[^/]+\/step$/.test(path)) {
      return ok({ ok: true, planId: 'beat' });
    }
    if (/^\/api\/v2\/devices\/[^/]+\/runtime\/plans\/[^/]+\/enabled$/.test(path)) {
      const body = request.postDataJSON() as { enabled?: boolean } | null;
      state.eventEnabled = body?.enabled ?? url.searchParams.get('enabled') === 'true';
      return ok({ ok: true, enabled: state.eventEnabled });
    }

    return ok({});
  });

  return state;
}

/* ---------------- 导航助手（所有 spec 共用） ---------------- */

/** 侧栏「设备清单」里的静态入口（工作台）。 */
export async function openWorkbench(page: Page): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: '工作台' }).first().click();
  await expect(page.locator('#panel-sessions')).toBeVisible();
}

/** 点侧栏里的设备（先展开所在模板分组/平铺项都可点）。 */
export async function openDeviceFromSidebar(page: Page, device: string): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: device }).first().click();
  await expect(page.locator('.instance-head .instance-title')).toContainText(device);
}

/** 切配置区 / 运行区（ZoneBar 与子页签都是 role=tab，用按钮文案区分）。 */
export async function switchZone(page: Page, zone: '配置区' | '运行区'): Promise<void> {
  await page.locator('.zonebar').getByRole('tab', { name: new RegExp(`^${zone}`) }).click();
  await expect(page.locator('.zonebar').getByRole('tab', { name: new RegExp(`^${zone}`) }))
    .toHaveAttribute('aria-selected', 'true');
}

/** 切设备/主机详情里的子页签。 */
export async function openSubTab(page: Page, label: string): Promise<void> {
  await page.locator('.subtabs').getByRole('tab', { name: label, exact: true }).click();
  await expect(page.locator('.subtabs').getByRole('tab', { name: label, exact: true }))
    .toHaveAttribute('aria-selected', 'true');
}

/** 侧栏「独立工具」里的入口（主机模板 / 设备模板 / 审计）。 */
export async function openTool(page: Page, label: string): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: label }).first().click();
}
