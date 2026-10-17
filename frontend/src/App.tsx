import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  createClient,
  readToken,
  saveToken,
  type Health,
  type SessionSummary,
  type PreviewData,
  type PreviewSeries,
  type WireMessage,
} from './api';

interface LogLine {
  at: string;
  text: string;
}

const MAX_LOG = 400;

/** 单条 SVID 的曲线：min/max 包络 + avg 线。 */
function Sparkline({ series }: { series: PreviewSeries }): JSX.Element {
  const ref = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = ref.current;
    const context = canvas?.getContext('2d');
    if (canvas == null || context == null) {
      return;
    }

    const width = canvas.width;
    const height = canvas.height;
    context.clearRect(0, 0, width, height);

    const count = series.min.length;
    if (count === 0) {
      return;
    }

    let low = Math.min(...series.min);
    let high = Math.max(...series.max);
    if (!(high > low)) {
      high = low + 1;
    }

    const x = (index: number) => (index / Math.max(1, count - 1)) * (width - 1);
    const y = (value: number) => height - 2 - ((value - low) / (high - low)) * (height - 4);

    context.beginPath();
    series.max.forEach((value, index) => (index === 0 ? context.moveTo(x(0), y(value)) : context.lineTo(x(index), y(value))));
    for (let index = count - 1; index >= 0; index -= 1) {
      context.lineTo(x(index), y(series.min[index]));
    }
    context.closePath();
    context.fillStyle = 'rgba(68, 147, 248, 0.22)';
    context.fill();

    context.beginPath();
    series.avg.forEach((value, index) => (index === 0 ? context.moveTo(x(0), y(value)) : context.lineTo(x(index), y(value))));
    context.strokeStyle = '#4493f8';
    context.lineWidth = 1.5;
    context.stroke();
  }, [series]);

  return (
    <div className="spark">
      <span>{series.svid}</span>
      <canvas ref={ref} width={760} height={56} />
    </div>
  );
}

export function App(): JSX.Element {
  const [token, setToken] = useState<string>(() => readToken());
  const [draftToken, setDraftToken] = useState('');
  const [health, setHealth] = useState<Health | null>(null);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [ceid, setCeid] = useState(2001);
  const [log, setLog] = useState<LogLine[]>([]);
  const [streamState, setStreamState] = useState('未连接');
  const [messages, setMessages] = useState<WireMessage[]>([]);
  const [preview, setPreview] = useState<PreviewData | null>(null);
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
    stream.addEventListener('message', (event) => {
      try {
        const parsed = JSON.parse(event.data) as WireMessage;
        setMessages((previous) => [...previous.slice(-199), parsed]);
      } catch {
        append('收到无法解析的报文事件');
      }
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

  const onPreview = async () => {
    const first = sessions[0];
    if (client === null || first === undefined) {
      return;
    }
    try {
      setPreview(await client.preview(first.id, 0, 60_000, 120));
      append('已刷新曲线预览（60 秒 / 120 桶）');
    } catch (error) {
      append(`预览失败: ${(error as Error).message}`);
    }
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
          <h2>
            曲线预览{' '}
            <button type="button" onClick={() => void onPreview()} disabled={sessions.length === 0}>
              预览 60 秒
            </button>
          </h2>
          {preview === null ? (
            <p className="hint">点上面的按钮，按当前配置算出一分钟的曲线（min/max 包络 + avg 线）。</p>
          ) : (
            <div className="sparks">
              {preview.series.map((series) => (
                <Sparkline key={series.svid} series={series} />
              ))}
            </div>
          )}
        </section>

        <section>
          <h2>
            报文 <button type="button" onClick={() => setMessages([])}>清空</button>
          </h2>
          <table className="messages">
            <thead>
              <tr>
                <th>时间</th>
                <th>会话</th>
                <th>方向</th>
                <th>报文</th>
                <th>报文体</th>
              </tr>
            </thead>
            <tbody>
              {messages.map((message, index) => (
                <tr key={`${message.at}-${index}`}>
                  <td>{new Date(message.at).toLocaleTimeString()}</td>
                  <td>{message.sessionId}</td>
                  <td>{message.direction === 'in' ? '← 收' : '→ 发'}</td>
                  <td>
                    S{message.stream}F{message.function}
                    {message.wbit ? ' W' : ''}
                  </td>
                  <td className="body" title={message.body}>
                    {message.body.length > 120 ? `${message.body.slice(0, 120)}…` : message.body}
                  </td>
                </tr>
              ))}
              {messages.length === 0 && (
                <tr>
                  <td colSpan={5} className="hint">
                    暂无报文（主机连上并收发报文后会出现在这里）
                  </td>
                </tr>
              )}
            </tbody>
          </table>
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
