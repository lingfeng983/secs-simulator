/* 零依赖前端：只用 fetch + EventSource，先验证 API 契约，再决定要不要上框架。 */

const params = new URLSearchParams(location.search);
const TOKEN_KEY = 'secs-sim-token';

let token = params.get('token') || sessionStorage.getItem(TOKEN_KEY) || '';
if (params.has('token')) {
  sessionStorage.setItem(TOKEN_KEY, token);
}

const el = (id) => document.getElementById(id);
const logBox = el('log');

function log(text) {
  const stamp = new Date().toLocaleTimeString();
  logBox.textContent += '[' + stamp + '] ' + text + '\n';
  if (logBox.textContent.length > 60000) {
    logBox.textContent = logBox.textContent.slice(-40000);
  }
  logBox.scrollTop = logBox.scrollHeight;
}

function showTokenPanel() {
  el('token-panel').hidden = false;
  el('token-save').onclick = () => {
    const value = el('token-input').value.trim();
    if (!value) return;
    sessionStorage.setItem(TOKEN_KEY, value);
    token = value;
    el('token-panel').hidden = true;
    connect();
  };
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { ...(options.headers || {}), Authorization: 'Bearer ' + token },
  });

  if (response.status === 401) {
    showTokenPanel();
    throw new Error('401 未授权');
  }
  if (!response.ok) {
    throw new Error(response.status + ' ' + (await response.text()).slice(0, 200));
  }
  return response.status === 204 ? null : response.json();
}

function cell(text) {
  const td = document.createElement('td');
  td.textContent = text;
  return td;
}

function renderSessions(sessions) {
  const body = el('sessions').tBodies[0];
  body.replaceChildren();

  for (const session of sessions) {
    const row = document.createElement('tr');
    row.append(
      cell(session.id),
      cell(session.state),
      cell(session.device || '-'),
      cell(session.units),
      cell(session.counters.emitted ?? 0),
      cell(session.counters.rejected ?? 0),
      cell(session.counters.skipped ?? 0),
      cell(session.counters.traceFrames ?? 0),
    );

    const action = document.createElement('td');
    const trigger = document.createElement('button');
    trigger.textContent = '上报';
    trigger.onclick = async () => {
      const ceid = Number(el('ceid').value || 0);
      try {
        await api('/api/v1/sessions/' + encodeURIComponent(session.id) + '/ceid/' + ceid,
                  { method: 'POST' });
        log('已触发 ' + session.id + ' CEID ' + ceid);
      } catch (e) {
        log('触发失败: ' + e.message);
      }
    };
    action.append(trigger);
    row.append(action);

    body.append(row);
  }
}

async function refresh() {
  try {
    const health = await fetch('/api/v1/health').then((r) => r.json());
    el('mode').textContent = health.mode;
    el('version').textContent = health.version;
    el('session-count').textContent = health.sessions ?? 0;

    const data = await api('/api/v1/sessions');
    renderSessions(data.sessions || []);
  } catch (e) {
    log('刷新失败: ' + e.message);
  }
}

let stream;

function connect() {
  if (!token) {
    showTokenPanel();
    return;
  }

  if (stream) {
    stream.close();
  }

  stream = new EventSource('/api/v1/events?token=' + encodeURIComponent(token));

  stream.addEventListener('open', () => {
    el('stream').textContent = '已连接';
    log('事件流已连接');
  });

  stream.addEventListener('heartbeat', (event) => {
    el('stream').textContent = '心跳 ' + new Date(event.data | 0).toLocaleTimeString();
  });

  stream.addEventListener('error', () => {
    el('stream').textContent = '断开，重连中';
  });

  refresh();
  setInterval(refresh, 2000);
}

log('页面加载完成；token ' + (token ? '已就绪' : '缺失'));
connect();
