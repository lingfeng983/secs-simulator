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
  sessions: Array<{ id: string; config: string; device: string; controlState: string; state: string; port: number }>;
  cycle: { state: 'STOPPED' | 'RUNNING' | 'FINISHED'; startedAt: number; waferIndex: number; phaseMs: number; totalWafers: number; firedCount: number; hasCycle: boolean };
  hosts: Array<{
    id: string; name: string; phase: string; open: boolean; target: string; port: number;
    deviceId: number; autoReconnect: boolean; retryCount: number; settingRevision: number;
    appliedRevision: number; lastError: string;
  }>;
  templates: Array<{ id: string; label: string; source: string }>;
  hostTemplates: Array<{ id: string; name: string; host: string; port: number; deviceId: number; note: string }>;
  /** 套用模板复制出来的主机方案，按主机 + 类型分桶。 */
  hostPlans: Array<{ hostId: string; kind: string; plan: Record<string, unknown> }>;
  /** 记录被调用过的动作（只到路径），便于断言"按钮真的打到后端"。 */
  calls: string[];
  /** 带查询串的完整请求行（审计游标、台账过滤这类参数在这里看）。 */
  queries: string[];
  /** 已被删除（软删）的配置文件名：列表里不再出现，用来验"删设备"这条路径。 */
  deletedConfigs: string[];
  /** 批量新建出来的配置文件名：列表里要能跟着出现。 */
  createdConfigs: string[];
}

function initialState(): MockState {
  return {
    deviceOnline: true,
    hostConnected: true,
    cycle: { state: 'STOPPED', startedAt: 0, waferIndex: 0, phaseMs: 0, totalWafers: 25, firedCount: 0, hasCycle: true },
      eventEnabled: false,
    sessions: [
      { id: 'line1', config: 'line1.json', device: 'LINE1', controlState: 'ONLINE', state: 'OPEN', port: 5000 },
      { id: 'line2', config: 'line2.json', device: 'LINE2', controlState: 'OFFLINE', state: 'OPEN', port: 5001 },
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
    /* 主机模板的 note 里装的是方案包 JSON；界面解码后显示方案份数 */
    hostTemplates: [
      {
        id: 'ht-1', name: '产线 A 方案', host: '-', port: 5000, deviceId: 0,
        note: JSON.stringify({
          v: 1,
          description: '日常联调',
          tracePlans: [{ id: 'ht-1-t', name: '温度 Trace', trid: 1, dsper: 1000, totsmp: 100, repgsz: 1, svids: '1001, 1003', createdAt: 1 }],
          collectPlans: [{ id: 'ht-1-c', name: '温度采集', startCeid: '2001', stopCeid: '2002', intervalMs: '1000', svids: '1001, 1003', createdAt: 1 }],
        }),
      },
    ],
    hostPlans: [],
    calls: [],
    queries: [],
    deletedConfigs: [],
    createdConfigs: [],
  };
}

const sessionJson = (item: MockState['sessions'][number]) => ({
  id: item.id,
  state: item.state,
  controlState: item.controlState,
  device: item.device,
  units: 1,
  lastError: '',
  config: item.config,
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
    /* 设备按 S2F23 推上来的 Trace：TRID=1，三个采样值，运行区要按 TRID 画趋势 */
    {
      seq: 3, sessionId: '', hostId: 'host-1', perspective: 'host',
      direction: 'out', stream: 6, function: 1, wbit: true,
      body: '<L[4] <U4 1> <U4 3> <A "120000"> <L[3] <F4 23.8> <F4 24.1> <F4 23.9>>>',
      at: Date.now() - 7_000,
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
      /* B1：会话 id 由服务端生成（ses_ ULID），配置名只记在 config 字段 */
      const configName = body?.config ?? 'new.json';
      const stem = configName.replace(/\.json$/i, '');
      const id = `ses_e2e${state.sessions.length + 1}`;
      state.sessions.push({
        id, config: configName, device: stem.toUpperCase(),
        controlState: 'OFFLINE', state: 'OPEN', port: 5100,
      });
      return ok({ ok: true, id }, 201);
    }
    if (path === '/api/v1/sessions/bulk' && method === 'POST') {
      return ok({ created: ['ses_bulk1'], failed: [] }, 201);
    }
    if (/^\/api\/v1\/sessions\/[^/]+$/.test(path) && method === 'DELETE') {
      /* 3.0：DELETE 的语义是断开链路，设备留在列表里（列表行不能消失） */
      const id = decodeURIComponent(path.split('/').pop() ?? '');
      const session = state.sessions.find((item) => item.id === id);
      if (session === undefined) {
        return ok({ ok: false, error: 'unknown-session' }, 404);
      }
      session.state = 'CLOSED';
      session.controlState = 'OFFLINE';
      return ok({ ok: true, phase: 'DISCONNECTED' });
    }
    if (/^\/api\/v2\/devices\/[^/]+\/link\/connect$/.test(path) && method === 'POST') {
      const id = decodeURIComponent(path.split('/')[4] ?? '');
      const session = state.sessions.find((item) => item.id === id);
      if (session === undefined) {
        return ok({ ok: false, error: 'device-not-found' }, 404);
      }
      session.state = 'OPEN';
      /* 严格 GEM：建链只到 COMMUNICATING（已连接、仍离线），上线要等 S1F17 或手动点 */
      session.controlState = 'COMMUNICATING';
      return ok({ ok: true, phase: 'LISTENING' });
    }
    if (/^\/api\/v2\/devices\/[^/]+\/link\/(online|offline)$/.test(path) && method === 'POST') {
      const id = decodeURIComponent(path.split('/')[4] ?? '');
      const session = state.sessions.find((item) => item.id === id);
      if (session === undefined) {
        return ok({ ok: false, error: 'device-not-found' }, 404);
      }
      if (session.state === 'CLOSED') {
        /* 没有链路就没有"在线"可言，后端回 409 */
        return ok({ ok: false, error: 'not-connected' }, 409);
      }
      session.controlState = path.endsWith('/online') ? 'ONLINE' : 'OFFLINE';
      return ok({ ok: true, action: path.split('/').pop() });
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
          counters: { emitted: 3, activeAlarms: item.config === 'line1.json' ? 1 : 0 },
          msgsPerMin: item.controlState === 'ONLINE' ? 6 : 0,
          s6f11PerMin: item.controlState === 'ONLINE' ? 2 : 0,
          lastEventAt: 0,
          uptimeMs: 60_000,
          hostSim: false,
        })),
      });
    }

    /* 工艺周期（2026-11-16 M3/M4）：GET 运行态，start/stop 改状态 */
    if (/^\/api\/v2\/devices\/([^/]+)\/cycle$/.test(path) && method === 'GET') {
      return ok(state.cycle);
    }
    if (/^\/api\/v2\/devices\/([^/]+)\/cycle\/start$/.test(path) && method === 'POST') {
      state.cycle.state = 'RUNNING';
      state.cycle.startedAt = 1;
      state.cycle.firedCount = 0;
      return ok({ ok: true, state: 'RUNNING' }, 200);
    }
    if (/^\/api\/v2\/devices\/([^/]+)\/cycle\/stop$/.test(path) && method === 'POST') {
      state.cycle.state = 'STOPPED';
      return ok({ ok: true, state: 'STOPPED' }, 200);
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
      const sessionId = decodeURIComponent(path.split('/')[4] ?? '');
      const session = state.sessions.find((item) => item.id === sessionId);
      /* 运行态按严格 GEM 推：启用了 + 设备在线才真在发，否则是"等待上线" */
      const online = session !== undefined && session.state === 'OPEN' && session.controlState === 'ONLINE';
      return ok({
        events: [{
          id: 'beat', ceid: 2001, owner: 'CH1', enabled: state.eventEnabled,
          status: state.eventEnabled ? (online ? 'running' : 'waiting') : 'stopped',
          statusReason: state.eventEnabled && !online ? 'offline' : '',
          nextFireAt: state.eventEnabled && online ? 61_000 : 0,
          nextFireInMs: state.eventEnabled && online ? 900 : 0,
          triggerType: 'periodic', periodMs: 1000, jitterMs: 0,
          effects: [{ svid: 1001, op: 'set', value: 80 }],
        }],
      });
    }
    /* 手动触发：v2 运行时端点（POST = 置位方向；清除是 DELETE） */
    if (/^\/api\/v2\/devices\/[^/]+\/runtime\/ceid\/\d+$/.test(path) && method === 'POST') {
      return ok({ ok: true, outcome: 'OK' });
    }
    /* 活动报警清单：v2 信封 {"alarms":[…]}（按 ALID 升序），面板靠它打底 */
    if (/^\/api\/v2\/devices\/[^/]+\/runtime\/alarms$/.test(path) && method === 'GET') {
      return ok({ alarms: [{ alid: 1001, text: 'TEMP HIGH', category: 1, atMillis: Date.now() - 3_000 }] });
    }
    if (/^\/api\/v2\/devices\/[^/]+\/runtime\/alarm\/\d+$/.test(path)
        && (method === 'POST' || method === 'DELETE')) {
      return ok({ ok: true });
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
          /* 时间戳按"刚才"生成：监控抽屉默认只回看最近 1 小时，
             写死 1970 年的时间会让这些历史报文被时间窗直接滤掉 */
          { seq: 1, sessionId: 'line1', perspective: 'device', direction: 'in', stream: 1, function: 1, wbit: true, body: '', at: Date.now() - 6_000 },
          { seq: 2, sessionId: 'line1', perspective: 'device', direction: 'out', stream: 1, function: 2, wbit: false, body: '', at: Date.now() - 5_900 },
          { seq: 3, sessionId: 'line1', perspective: 'device', direction: 'in', stream: 1, function: 3, wbit: true, body: '<L[1] <U4 1001>>', at: Date.now() - 5_800 },
          { seq: 4, sessionId: 'line1', perspective: 'device', direction: 'out', stream: 1, function: 4, wbit: false, body: '<L[1] <F4 42.5>>', at: Date.now() - 5_700 },
          { seq: 5, sessionId: 'line1', perspective: 'device', direction: 'out', stream: 6, function: 11, wbit: true, body: '<L[3]>', at: Date.now() - 5_600 },
          { seq: 6, sessionId: 'line1', perspective: 'device', direction: 'out', stream: 6, function: 1, wbit: true, body: '<L[4]>', at: Date.now() - 5_500 },
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
        ...[...state.createdConfigs].map((name) => ({
          name, sizeBytes: CONFIG_TEXT.length, modifiedMs: 3, template: 'etch',
        })),
      ].filter((entry) => !state.deletedConfigs.includes(entry.name)) });
    }
    if (path === '/api/v1/configs' && method === 'POST') {
      return ok({ ok: true }, 201);
    }
    if (path === '/api/v1/devices/bulk' && method === 'POST') {
      const body = request.postDataJSON() as {
        prefix?: string; start?: number; count?: number; portBase?: number; startNow?: boolean;
      } | null;
      /* 夹具照真后端的规则来：名字 = 前缀 + 序号（序号从 start 起），Device ID 恒为 0，
         设备名重复整批拒绝（一台都不建） */
      const prefix = body?.prefix ?? 'dev-';
      const start = body?.start ?? 1;
      const count = body?.count ?? 1;
      const portBase = body?.portBase ?? 5100;
      const names = Array.from({ length: count }, (_, index) => `${prefix}${start + index}.json`);
      const existing = ['line1.json', 'line2.json', ...state.createdConfigs, ...state.deletedConfigs];
      const conflicts = names.filter((name) => existing.includes(name));
      if (conflicts.length > 0) {
        return ok({ created: [], failed: [], conflicts }, 409);
      }
      state.createdConfigs.push(...names);
      const created = names.map((name, index) => {
        const stem = name.replace(/\.json$/, '');
        const sessionId = body?.startNow === false ? '' : `ses_bulk${state.sessions.length + index + 1}`;
        if (sessionId !== '') {
          state.sessions.push({
            id: sessionId, config: name, device: stem.toUpperCase(),
            controlState: 'OFFLINE', state: 'OPEN', port: portBase + index,
          });
        }
        return {
          name, deviceName: stem, port: portBase + index, deviceId: 0,
          sessionId, sessionError: '',
        };
      });
      return ok({ created, failed: [], conflicts: [] }, 201);
    }
    if (/^\/api\/v1\/configs\/[^/]+$/.test(path) && method === 'DELETE') {
      const name = decodeURIComponent(path.split('/').pop() ?? '');
      state.deletedConfigs.push(name);
      /* 真后端删配置前会先停掉它的会话；夹具照做，否则清单里还留着这一行 */
      state.sessions = state.sessions.filter((item) => `${item.id}.json` !== name);
      return ok({ ok: true, name });
    }
    if (/^\/api\/v1\/configs\/[^/]+$/.test(path)) {
      if (method === 'GET') {
        const name = decodeURIComponent(path.split('/').pop() ?? '');
        /* line2 的配置端口与总览里的 5001 对齐，端口列读配置也不会串台 */
        const text = name.startsWith('line2')
          ? CONFIG_TEXT.replace('"port": 5000', '"port": 5001')
          : CONFIG_TEXT;
        return ok({ name, text, modifiedMs: 1 });
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
      const body = request.postDataJSON() as {
        name?: string; host?: string; port?: number; deviceId?: number; connect?: boolean;
      } | null;
      const id = `host-${state.hosts.length + 1}`;
      /* 「立即连接」开关：缺省创建即建链；关掉只建实例，停在断开态 */
      const connect = body?.connect !== false;
      state.hosts.push({
        id, name: body?.name ?? id, phase: connect ? 'CONNECTED' : 'DISCONNECTED', open: connect,
        target: `${body?.host ?? '127.0.0.1'}:${body?.port ?? 5000}`,
        port: body?.port ?? 5000, deviceId: body?.deviceId ?? 0,
        autoReconnect: false, retryCount: 0, settingRevision: 1,
        appliedRevision: connect ? 1 : 0, lastError: '',
      });
      return ok({ ok: true, hostId: id }, 201);
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
      const body = request.postDataJSON() as {
        id?: string; name?: string; host?: string; port?: number; deviceId?: number; note?: string;
      } | null;
      const id = body?.id || `ht-${state.hostTemplates.length + 1}`;
      const row = {
        id, name: body?.name ?? id, host: body?.host ?? '-', port: body?.port ?? 5000,
        deviceId: body?.deviceId ?? 0, note: body?.note ?? '',
      };
      const index = state.hostTemplates.findIndex((item) => item.id === id);
      if (index >= 0) {
        state.hostTemplates[index] = row;
      } else {
        state.hostTemplates.push(row);
      }
      return ok({ ok: true }, 201);
    }
    if (/^\/api\/v2\/host-templates\/[^/]+$/.test(path) && method === 'DELETE') {
      const id = decodeURIComponent(path.split('/').pop() ?? '');
      state.hostTemplates = state.hostTemplates.filter((item) => item.id !== id);
      return ok({ ok: true });
    }
    /* 主机方案库：套用模板时逐条复制方案到这里；详情页从这里读回 */
    if (/^\/api\/v2\/hosts\/[^/]+\/plans$/.test(path)) {
      const hostId = path.split('/')[4];
      const kind = url.searchParams.get('kind') ?? '';
      if (method === 'GET') {
        return ok(state.hostPlans
          .filter((item) => item.hostId === hostId && item.kind === kind)
          .map((item) => item.plan));
      }
      if (method === 'POST') {
        const plan = (request.postDataJSON() ?? {}) as Record<string, unknown>;
        state.hostPlans.push({ hostId, kind, plan });
        return ok({ ok: true });
      }
      return ok({ ok: true });
    }
    if (/^\/api\/v2\/hosts\/[^/]+\/plans\/[^/]+$/.test(path) && method === 'DELETE') {
      const planId = decodeURIComponent(path.split('/').pop() ?? '');
      state.hostPlans = state.hostPlans.filter((item) => item.plan.id !== planId);
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
      state.eventEnabled = url.searchParams.get('enabled') === 'true';
      return ok({ ok: true });
    }

    return ok({});
  });

  return state;
}

/* ---------------- 导航助手（所有 spec 共用） ---------------- */

/** 侧栏「设备」组里的静态入口（原「工作台」，3.0 第三轮改名「设备清单」）。 */
export async function openWorkbench(page: Page): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: '设备清单' }).first().click();
  await expect(page.locator('#panel-sessions')).toBeVisible();
}

/** 点侧栏里的设备（先展开所在模板分组/平铺项都可点），默认落在运行区。 */
export async function openDeviceFromSidebar(page: Page, device: string): Promise<void> {
  await page.locator('.sidenav .nav-item', { hasText: device }).first().click();
  await expect(page.locator('.device-page')).toHaveAttribute('data-device', device);
  /* 打开设备默认进运行区：要动手的页在前面 */
  await expect(page.locator('.zone-switch').getByRole('tab', { name: /运行区/ }))
    .toHaveAttribute('aria-selected', 'true');
}

/** 切配置区 / 运行区：切换条在页面上方的操作栏里（.zone-switch），文案带 ▣ / ◉ 前缀。 */
export async function switchZone(page: Page, zone: '配置区' | '运行区'): Promise<void> {
  const tab = page.locator('.zone-switch').getByRole('tab', { name: new RegExp(zone) });
  await tab.click();
  await expect(tab).toHaveAttribute('aria-selected', 'true');
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
