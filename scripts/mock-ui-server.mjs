// 可视化验证用:静态托管 dist + mock 后端 API(与 e2e mock 同一套数据)。
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';

const DIST = new URL('../frontend/dist', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const PORT = 4199;

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };

const json = (res, obj) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };

const session = {
  id: 'line1', state: 'OPEN', controlState: 'ONLINE', device: 'LINE1', units: 1, lastError: '', config: 'line1.json', counters: {},
};

http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const path = url.pathname;
  if (path.startsWith('/api/v1/')) {
    if (path === '/api/v1/health') return json(res, { ok: true, version: 'dev', mode: 'integrated', sessions: 1, uptimeMs: 60000, user: 'dev', role: 'admin', vm: 'jvm', utf8: true });
    if (path === '/api/v1/ticket') return json(res, { ok: true, ticket: 't', expiresInMs: 30000 });
    if (path === '/api/v1/events') { res.writeHead(200, { 'Content-Type': 'text/event-stream' }); return res.end(': connected\n\n'); }
    if (path === '/api/v1/sessions') return json(res, { sessions: [session] });
    if (path === '/api/v1/overview') return json(res, { sessions: [{ ...session, hasPeer: true, port: 5000, counters: { emitted: 3, activeAlarms: 1 }, msgsPerMin: 6, s6f11PerMin: 2, lastEventAt: Date.now(), uptimeMs: 60000, hostSim: false }] });
    if (path === '/api/v1/sessions/line1/events') return json(res, { events: [{ id: 'beat', ceid: 2001, enabled: true, triggerType: 'periodic', periodMs: 1000, jitterMs: 0 }] });
    if (path === '/api/v1/sessions/line1/catalog') return json(res, { ceids: [{ ceid: 2001, owner: 'CH1', slot: 'TEMP_HIGH', reports: [1], source: 'local' }], reports: [{ rptid: 1, owner: 'CH1', slot: 'TEMP', svids: [1001], source: 'local' }], svids: [{ svid: 1001, owner: 'CH1', slot: 'TEMP', type: 'F4', unit: 'C' }] });
    if (path === '/api/v1/sessions/line1/alarms') return json(res, [{ alid: 1001, alcd: 'rance', text: 'TEMP HIGH', set: true, at: Date.now() }]);
    if (path === '/api/v1/sessions/line1/terminal') return json(res, []);
    if (path === '/api/v1/preview') return json(res, { from: 0, to: 60000, buckets: 4, bucketMs: 15000, svidTotal: 2, truncated: false, series: [{ svid: 1001, owner: 'CH1', unit: 'C', min: [10, 20, 30, 40], max: [20, 30, 40, 50], avg: [15, 25, 35, 45] }] });
    if (path === '/api/v1/messages') return json(res, { messages: [{ sessionId: 'line1', direction: 'in', stream: 1, function: 1, wbit: false, body: 'S1F1\n<L\n  <B 0x81>\n>.', at: 1700000000000 }], total: 1, limit: 500, truncated: false });
    if (path === '/api/v1/configs') return json(res, { configs: [{ name: 'line1.json', sizeBytes: 100, modifiedMs: 1 }] });
    if (path === '/api/v1/configs/line1.json') return json(res, { name: 'line1.json', text: '{"schemaVersion":5}' });
    if (path === '/api/v1/audit') return json(res, { audit: [{ at: 1700000000000, actor: 'dev', role: 'admin', action: 'trigger-ceid', target: 'line1', detail: 'ceid=2001 outcome=OK' }], limit: 200 });
    return json(res, {});
  }
  // 静态文件(未知路径回 index.html)
  /* ---- 3.0 v2 mock:主机独立命名空间 + 配置版本历史 ---- */
  const v2Hosts = [
    { id: 'host_01MDEMO0000000000000000A', name: '产线 A 主机', phase: 'CONNECTED', open: true,
      target: '127.0.0.1:5000', port: 5000, deviceId: 10, autoReconnect: true, retryCount: 0,
      nextRetryAt: 0, settingRevision: 3, appliedRevision: 3, session: '', mode: 'target',
      remote: true, lastError: '' },
    { id: 'host_01MDEMO0000000000000000B', name: '远端 CVD', phase: 'DISCONNECTED', open: false,
      target: '192.168.8.30:7000', port: 7000, deviceId: 5, autoReconnect: false, retryCount: 0,
      nextRetryAt: 0, settingRevision: 1, appliedRevision: 1, session: '', mode: 'target',
      remote: true, lastError: '' },
  ];
  const v2Templates = [
    { id: 'ht_01MDEMOTPL00000000000000A', name: '产线 A 目标', host: '127.0.0.1', port: 5000,
      deviceId: 10, note: '日常联调' },
  ];
  const v2Messages = [
    { seq: 2, sessionId: '', hostId: 'host_01MDEMO0000000000000000A', perspective: 'host',
      direction: 'in', stream: 1, function: 1, wbit: true, body: '<L[2] <A "MDLN"> <A "SOFTREV">>', at: Date.now() - 8000 },
    { seq: 1, sessionId: '', hostId: 'host_01MDEMO0000000000000000A', perspective: 'host',
      direction: 'out', stream: 1, function: 2, wbit: false, body: '<L[2] <A "SECS-SIM"> <A "2.0">>', at: Date.now() - 7500 },
  ];
  if (path === '/api/v2/hosts') {
    return json(res, { hosts: v2Hosts });
  }
  if (path.startsWith('/api/v2/hosts/') && path.endsWith('/messages')) {
    return json(res, { ok: true, total: v2Messages.length, messages: v2Messages });
  }
  if (path.startsWith('/api/v2/hosts/') && path.endsWith('/variables')) {
    return json(res, { ok: true, count: 2 });
  }
  if (path.startsWith('/api/v2/hosts/')) {
    const host = v2Hosts.find((h) => path === `/api/v2/hosts/${h.id}`);
    if (host !== undefined) {
      return json(res, host);
    }
    return json(res, { ok: false, error: 'host-not-found' }, 404);
  }
  if (path === '/api/v2/host-templates') {
    return json(res, { templates: v2Templates });
  }
  if (path.startsWith('/api/v2/host-templates/')) {
    return json(res, { ok: true });
  }
  /* 3.0 M4：事件计划执行台账（含 SVID before→after、报文摘要、试跑记录） */
  const v2Executions = [
    { id: 4, deviceId: 'line1', planId: 'beat', ceid: 2001, dryRun: false,
      scheduledAt: Date.now() - 900, firedAt: Date.now() - 880, status: 'SENT',
      messages: [{ kind: 's6f11', stream: 6, function: 11, wbit: true, elapsedMs: 3 }],
      effects: [{ svid: 1001, op: 'add', before: 24.5, after: 25.0 }], error: '' },
    { id: 3, deviceId: 'line1', planId: 'beat', ceid: 2001, dryRun: false,
      scheduledAt: Date.now() - 1900, firedAt: Date.now() - 1880, status: 'FAILED',
      messages: [{ kind: 'sml', error: 'SML 解析失败: 缺少结束符' }],
      effects: { items: [{ svid: 1001, op: 'set', before: 23.0, after: 24.5 }], rollback: 'rollback_skipped' },
      error: 'SML 解析失败: 缺少结束符; rollback_skipped' },
    { id: 2, deviceId: 'line1', planId: 'beat', ceid: 2001, dryRun: false,
      scheduledAt: Date.now() - 2900, firedAt: Date.now() - 2880, status: 'SKIPPED',
      messages: [], effects: [], error: '设备离线（skip）' },
    { id: 1, deviceId: 'line1', planId: 'beat', ceid: 2001, dryRun: true,
      scheduledAt: Date.now() - 60000, firedAt: Date.now() - 59980, status: 'SENT',
      messages: [{ kind: 's6f11', stream: 6, function: 11, wbit: true, elapsedMs: 2 }],
      effects: [{ svid: 1001, op: 'set', before: 20.0, after: 23.0 }], error: '' },
  ];
  if (/^\/api\/v2\/devices\/[^/]+\/runtime\/executions$/.test(path)) {
    const dryRun = url.searchParams.get('dryRun') ?? '0';
    const status = url.searchParams.get('status');
    let items = v2Executions.filter((item) => dryRun === 'all' || dryRun === '1' || !item.dryRun);
    if (status !== null) items = items.filter((item) => item.status === status);
    const limit = Number(url.searchParams.get('limit') ?? '100');
    return json(res, { items: items.slice(0, Number.isFinite(limit) ? limit : 100), count: items.length });
  }
  if (/^\/api\/v2\/devices\/[^/]+\/runtime\/plans\/[^/]+\/step$/.test(path)) {
    return json(res, { ok: true, planId: 'beat' });
  }
  if (/^\/api\/v2\/devices\/[^/]+\/runtime\/plans\/[^/]+\/enabled$/.test(path)) {
    return json(res, { ok: true, enabled: true });
  }
  if (/^\/api\/v2\/devices\/[^/]+\/config\/revisions$/.test(path)) {
    return json(res, { revisions: [
      { revision: 2, sha256: 'aabbccddeeff00112233', createdAt: Date.now() - 600000, createdBy: 'ui', note: '修改超时' },
      { revision: 1, sha256: '11223344556677889900', createdAt: Date.now() - 3600000, createdBy: 'migrate', note: 'created' },
    ] });
  }

  let file = join(DIST, path === '/' ? 'index.html' : path);
  try {
    const data = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream' });
    res.end(data);
  } catch {
    const data = await readFile(join(DIST, 'index.html'));
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(data);
  }
}).listen(PORT, () => console.log(`mock ui on http://127.0.0.1:${PORT}`));
