// 可视化验证用:静态托管 dist + mock 后端 API(与 e2e mock 同一套数据)。
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';

const DIST = new URL('../frontend/dist', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const PORT = 4199;

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };

const json = (res, obj) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };

const session = {
  id: 'line1', state: 'OPEN', controlState: 'ONLINE', device: 'LINE1', units: 1, lastError: '', counters: {},
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
