import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createClient, readToken, saveToken, type Health, type SessionSummary } from './api';

interface LogLine {
  at: string;
  text: string;
}

const MAX_LOG = 400;

export function App(): JSX.Element {
  const [token, setToken] = useState<string>(() => readToken());
  const [draftToken, setDraftToken] = useState('');
  const [health, setHealth] = useState<Health | null>(null);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [ceid, setCeid] = useState(2001);
  const [log, setLog] = useState<LogLine[]>([]);
  const [streamState, setStreamState] = useState('未连接');
  const logRef = useRef<HTMLPreElement | null>(null);

  const client = useMemo(() => (token === '' ? null : createClient(token)), [token]);

  const append = useCallback((text: string) => {
    setLog((previous) => {
      const next = [...previous, { at: new Date().toLocaleTimeString(), text }];
      return next.length > MAX_LOG ? next.slice(next.length - MAX_LOG) : next;
    });
  }, []);

  useEffect(() => {
    if (client === null) {
      return;
    }

    let cancelled = false;

    const refresh = async () => {
      try {
        const [nextHealth, nextSessions] = await Promise.all([client.health(), client.sessions()]);
        if (!cancelled) {
          setHealth(nextHealth);
          setSessions(nextSessions);
        }
      } catch (error) {
        if (!cancelled) {
          append(`刷新失败: ${(error as Error).message}`);
        }
      }
    };

    refresh();
    const timer = window.setInterval(refresh, 2000);

    const stream = new EventSource(client.eventsUrl());
    stream.addEventListener('open', () => setStreamState('已连接'));
    stream.addEventListener('heartbeat', (event) => {
      setStreamState(`心跳 ${new Date(Number(event.data)).toLocaleTimeString()}`);
    });
    stream.addEventListener('error', () => setStreamState('断开，重连中'));

    return () => {
      cancelled = true;
      window.clearInterval(timer);
      stream.close();
    };
  }, [client, append]);

  useEffect(() => {
    if (logRef.current !== null) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [log]);

  const onSaveToken = () => {
    const value = draftToken.trim();
    if (value === '') {
      return;
    }
    saveToken(value);
    setToken(value);
    append('token 已保存');
  };

  const onTrigger = async (session: SessionSummary) => {
    if (client === null) {
      return;
    }
    try {
      const status = await client.trigger(session.id, ceid);
      append(`触发 ${session.id} CEID ${ceid} -> HTTP ${status}`);
    } catch (error) {
      append(`触发失败: ${(error as Error).message}`);
    }
  };

  return (
    <div className="app">
      <header>
        <h1>SECS Simulator</h1>
        <dl className="health">
          <dt>形态</dt>
          <dd>{health?.mode ?? '-'}</dd>
          <dt>版本</dt>
          <dd>{health?.version ?? '-'}</dd>
          <dt>会话</dt>
          <dd>{health?.sessions ?? sessions.length}</dd>
          <dt>事件流</dt>
          <dd>{streamState}</dd>
        </dl>
      </header>

      {token === '' && (
        <section className="token-panel">
          <p>
            需要访问令牌（启动时打印在 <code>READY</code> 行里，或由桌面壳注入）。
          </p>
          <input
            type="password"
            placeholder="token"
            value={draftToken}
            onChange={(event) => setDraftToken(event.target.value)}
          />
          <button type="button" onClick={onSaveToken}>
            保存
          </button>
        </section>
      )}

      <main>
        <section>
          <h2>会话</h2>
          <table>
            <thead>
              <tr>
                <th>id</th>
                <th>状态</th>
                <th>设备</th>
                <th>单元</th>
                <th>已上报</th>
                <th>限流丢弃</th>
                <th>禁用跳过</th>
                <th>S6F1</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {sessions.map((session) => (
                <tr key={session.id}>
                  <td>{session.id}</td>
                  <td>{session.state}</td>
                  <td>{session.device || '-'}</td>
                  <td>{session.units}</td>
                  <td>{session.counters.emitted ?? 0}</td>
                  <td>{session.counters.rejected ?? 0}</td>
                  <td>{session.counters.skipped ?? 0}</td>
                  <td>{session.counters.traceFrames ?? 0}</td>
                  <td>
                    <button type="button" onClick={() => void onTrigger(session)}>
                      上报
                    </button>
                  </td>
                </tr>
              ))}
              {sessions.length === 0 && (
                <tr>
                  <td colSpan={9} className="hint">
                    暂无会话（用 --demo-session 或 --config 启动一个）
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          <label className="ceid">
            CEID
            <input
              type="number"
              min={1}
              value={ceid}
              onChange={(event) => setCeid(Number(event.target.value))}
            />
          </label>
        </section>

        <section>
          <h2>事件流</h2>
          <pre className="log" ref={logRef}>
            {log.map((line, index) => `[${line.at}] ${line.text}`).join('\n')}
          </pre>
        </section>
      </main>
    </div>
  );
}
