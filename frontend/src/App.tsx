import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  createClient,
  hasRole,
  readToken,
  roleLabel,
  saveToken,
  type AlarmState,
  type Catalog,
  type Health,
  type SessionSummary,
  type TerminalMessage,
  type PreviewData,
  type PreviewSeries,
  type WireMessage,
} from './api';

interface LogLine {
  at: string;
  text: string;
}

/** 报文表的一行：key 只增不减，避免 slice 之后 index 位移导致整表重建。 */
interface MessageEntry {
  key: number;
  msg: WireMessage;
}

const MAX_LOG = 400;
const MAX_MESSAGES = 200;

/** 可选的时间窗：后端引擎时钟从进程启动起算，时间窗要相对"现在"而不是 0。 */
const WINDOWS: ReadonlyArray<{ label: string; ms: number }> = [
  { label: '最近 1 分钟', ms: 60_000 },
  { label: '最近 5 分钟', ms: 300_000 },
  { label: '最近 15 分钟', ms: 900_000 },
];

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

    let low = Number.POSITIVE_INFINITY;
    let high = Number.NEGATIVE_INFINITY;
    for (let index = 0; index < count; index += 1) {
      low = Math.min(low, series.min[index]);
      high = Math.max(high, series.max[index]);
    }
    if (!Number.isFinite(low) || !Number.isFinite(high)) {
      return;
    }
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
  const [messages, setMessages] = useState<MessageEntry[]>([]);
  const [preview, setPreview] = useState<PreviewData | null>(null);
  const [previewSession, setPreviewSession] = useState('');
  const [windowMs, setWindowMs] = useState(WINDOWS[0].ms);
  const [alarmSession, setAlarmSession] = useState('');
  const [alid, setAlid] = useState(1001);
  const [alarmText, setAlarmText] = useState('TEMP HIGH');
  const [alarmCategory, setAlarmCategory] = useState(1);
  const [alarms, setAlarms] = useState<AlarmState[]>([]);
  const [terminalMessages, setTerminalMessages] = useState<TerminalMessage[]>([]);
  const [newConfig, setNewConfig] = useState('demo');
  const [catalogSession, setCatalogSession] = useState('');
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const logRef = useRef<HTMLPreElement | null>(null);
  const nextMessageKey = useRef(0);
  const openedOnce = useRef(false);
  const lastStreamErrorAt = useRef(0);

  const client = useMemo(() => (token === '' ? null : createClient(token)), [token]);

  /*
   * 会改变设备状态的操作（触发 CEID、报警置位/清除）至少要 operator。
   * 角色未知时按"没有权限"处理：按钮灰掉并说明原因，好过让人点了才吃 403。
   * 后端仍然最终裁决，这里只是不制造无谓的尝试。
   */
  const role = health?.role;
  const canOperate = hasRole(role, 'operator');
  /* 按钮灰掉时要说清是"角色不够"还是"还不知道角色"，否则用户会以为界面坏了 */
  const operateHint =
    role === undefined
      ? '尚未取到身份信息，连上后端后才能判断权限'
      : `当前角色为${roleLabel(role)}（只读），需要操作员或管理员`;

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
      const [healthResult, sessionsResult] = await Promise.allSettled([client.health(), client.sessions()]);

      if (cancelled) {
        return;
      }

      if (healthResult.status === 'fulfilled') {
        setHealth(healthResult.value);
      } else {
        append(`健康检查失败: ${(healthResult.reason as Error).message}`);
      }

      if (sessionsResult.status === 'fulfilled') {
        setSessions(sessionsResult.value);
      } else {
        append(`会话列表刷新失败: ${(sessionsResult.reason as Error).message}`);
      }
    };

    refresh();
    const timer = window.setInterval(refresh, 2000);

    let stream: EventSource | null = null;
    let retryTimer = 0;
    let disposed = false;
    let lastTicketErrorAt = 0;

    const scheduleReconnect = () => {
      if (disposed || retryTimer !== 0) {
        return;
      }
      retryTimer = window.setTimeout(() => {
        retryTimer = 0;
        void connect();
      }, 2000);
    };

    const connect = async () => {
      if (disposed) {
        return;
      }

      try {
        /* 票据是一次性的：每次（重）连都要重新换一张，不能复用 URL */
        const ticket = await client.ticket();
        if (disposed) {
          return;
        }

        const source = new EventSource(client.eventsUrl(ticket));
        stream = source;

        source.addEventListener('open', () => {
          setStreamState('已连接');
          if (openedOnce.current) {
            /* 断线期间的报文后端不补发，界面必须明说，否则用户会以为中间没有报文 */
            append('事件流已重连：断线期间的报文不会补发');
            void refresh();
          }
          openedOnce.current = true;
        });
        source.addEventListener('heartbeat', (event) => {
          const at = Number(event.data);
          setStreamState(Number.isFinite(at) ? `心跳 ${new Date(at).toLocaleTimeString()}` : '心跳');
        });
        source.addEventListener('message', (event) => {
          try {
            const parsed = JSON.parse(event.data) as WireMessage;
            const key = nextMessageKey.current;
            nextMessageKey.current += 1;
            setMessages((previous) => {
              const next = [...previous, { key, msg: parsed }];
              return next.length > MAX_MESSAGES ? next.slice(next.length - MAX_MESSAGES) : next;
            });
          } catch {
            append('收到无法解析的报文事件');
          }
        });
        source.addEventListener('error', () => {
          const state = source.readyState === EventSource.CLOSED ? '已关闭' : '连接中断';
          setStreamState(`断开（${state}）`);

          const now = Date.now();
          if (now - lastStreamErrorAt.current > 5000) {
            lastStreamErrorAt.current = now;
            append(`事件流断开（${state}），2 秒后用新票据重连`);
          }

          /* 票据只能用一次，自动重连会带着旧票据被 401，必须自己换票重连 */
          source.close();
          if (stream === source) {
            stream = null;
          }
          scheduleReconnect();
        });
      } catch (error) {
        const now = Date.now();
        if (now - lastTicketErrorAt > 10_000) {
          lastTicketErrorAt = now;
          append(`获取事件流票据失败: ${(error as Error).message}`);
        }
        setStreamState('等待重连');
        scheduleReconnect();
      }
    };

    void connect();

    return () => {
      cancelled = true;
      disposed = true;
      window.clearInterval(timer);
      if (retryTimer !== 0) {
        window.clearTimeout(retryTimer);
      }
      stream?.close();
    };
  }, [client, append]);

  useEffect(() => {
    if (logRef.current !== null) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [log]);

  /* 会话列表变化后保证预览下拉框指向一个存在的会话 */
  useEffect(() => {
    if (sessions.length === 0) {
      if (previewSession !== '') {
        setPreviewSession('');
      }
      return;
    }
    if (!sessions.some((session) => session.id === previewSession)) {
      setPreviewSession(sessions[0].id);
    }
  }, [sessions, previewSession]);

  /* 会话列表变化后保证报警下拉框指向一个存在的会话 */
  useEffect(() => {
    if (sessions.length === 0) {
      if (alarmSession !== '') {
        setAlarmSession('');
      }
      return;
    }
    if (!sessions.some((session) => session.id === alarmSession)) {
      setAlarmSession(sessions[0].id);
    }
  }, [sessions, alarmSession]);

  /* 活动报警 / 终端消息轮询：只在选中会话时跑 */
  useEffect(() => {
    if (client === null || alarmSession === '') {
      setAlarms([]);
      setTerminalMessages([]);
      return;
    }

    let cancelled = false;
    const load = async () => {
      try {
        const [nextAlarms, nextTerminal] = await Promise.all([
          client.alarms(alarmSession),
          client.terminalMessages(alarmSession),
        ]);
        if (!cancelled) {
          setAlarms(nextAlarms);
          setTerminalMessages(nextTerminal);
        }
      } catch {
        /* 会话可能刚好被关闭，下一轮再试 */
      }
    };

    void load();
    const timer = window.setInterval(load, 2000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [client, alarmSession]);

  /* 会话列表变化后保证目录下拉框指向一个存在的会话 */
  useEffect(() => {
    if (sessions.length === 0) {
      if (catalogSession !== '') {
        setCatalogSession('');
      }
      return;
    }
    if (!sessions.some((session) => session.id === catalogSession)) {
      setCatalogSession(sessions[0].id);
    }
  }, [sessions, catalogSession]);

  /*
   * 设备目录：CEID 的号段按单元独立，跨会话复用编号会指错事件，
   * 所以目录必须跟着选中的会话换。
   *
   * 而且要轮询：主机随时可能用 S2F33/S2F35/S2F37 改定义，
   * 只在切换时拉一次的话，"来源"列会一直停在"本地"，
   * 恰好在最需要它的时候说谎。
   */
  useEffect(() => {
    if (client === null || catalogSession === '') {
      setCatalog(null);
      return;
    }

    let cancelled = false;
    const load = async () => {
      try {
        const next = await client.catalog(catalogSession);
        if (!cancelled) {
          setCatalog(next);
        }
      } catch {
        if (!cancelled) {
          setCatalog(null);
        }
      }
    };

    void load();
    const timer = window.setInterval(load, 2000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [client, catalogSession]);

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
    if (client === null) {
      return;
    }

    const target = sessions.find((session) => session.id === previewSession) ?? sessions[0];
    if (target === undefined) {
      return;
    }

    try {
      /* 先取一次 uptime，时间窗才是"最近 N 分钟"而不是"开局 N 秒" */
      const fresh = await client.health();
      setHealth(fresh);
      const to = Math.max(1, fresh.uptimeMs ?? 0);
      const from = Math.max(0, to - windowMs);
      setPreview(await client.preview(target.id, from, to, 120));
      append(`已刷新 ${target.id} 的曲线（${Math.round((to - from) / 1000)} 秒窗口）`);
    } catch (error) {
      append(`预览失败: ${(error as Error).message}`);
    }
  };

  const onAlarm = async (set: boolean) => {
    if (client === null) {
      return;
    }

    const target = sessions.find((session) => session.id === alarmSession) ?? sessions[0];
    if (target === undefined) {
      return;
    }

    try {
      const status = await client.alarm(target.id, alid, set, alarmText, alarmCategory);
      append(`报警${set ? '置位' : '清除'} ${target.id} ALID ${alid} -> HTTP ${status}`);
    } catch (error) {
      append(`报警失败: ${(error as Error).message}`);
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

  /**
   * 会话生命周期操作的状态码文案。
   *
   * <p>服务端已经在日志里记了原因，这里只负责把状态码说成人话：
   * 400 分成"配置有问题"和"名字不合法"两种可能，光一个 400 谁也看不出该改哪。
   * </p>
   */
  const lifecycleMessage = (status: number, what: string): string => {
    switch (status) {
      case 400:
        return `${what}失败（配置不合法或读不到，详见服务端日志）`;
      case 404:
        return `${what}失败：会话不存在`;
      case 409:
        return `${what}失败：标识已被占用，或该会话没有配置来源`;
      default:
        return `${what} -> HTTP ${status}`;
    }
  };

  const onCreateSession = async () => {
    if (client === null) {
      return;
    }
    try {
      const status = await client.createSession(newConfig.trim());
      append(lifecycleMessage(status, '新建会话'));
    } catch (error) {
      append(`新建会话失败: ${(error as Error).message}`);
    }
  };

  const onCloseSession = async (session: SessionSummary) => {
    if (client === null) {
      return;
    }
    try {
      const status = await client.closeSession(session.id);
      append(lifecycleMessage(status, `关闭会话 ${session.id}`));
    } catch (error) {
      append(`关闭会话失败: ${(error as Error).message}`);
    }
  };

  const onReloadSession = async (session: SessionSummary) => {
    if (client === null) {
      return;
    }
    try {
      const status = await client.reloadSession(session.id);
      append(lifecycleMessage(status, `重载会话 ${session.id}`));
    } catch (error) {
      append(`重载会话失败: ${(error as Error).message}`);
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
          <dt>身份</dt>
          <dd>
            {role === undefined
              ? '-'
              : `${health?.user ?? '?'}（${roleLabel(role)}）`}
          </dd>
          <dt>事件流</dt>
          <dd>{streamState}</dd>
        </dl>
      </header>

      {token === '' && (
        <section className="token-panel">
          <p className="hint">
            需要访问令牌（启动时打印在 <code>READY</code> 行里，或由桌面壳注入）。
            用 <code>--auth-users</code> 启动时填账号口令，不是那条 token。
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
                <th>上报</th>
                <th>生命周期</th>
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
                    <button
                      type="button"
                      onClick={() => void onTrigger(session)}
                      disabled={!canOperate}
                      title={canOperate ? '手动触发一次 S6F11' : operateHint}
                    >
                      上报
                    </button>
                  </td>
                  <td>
                    <button
                      type="button"
                      onClick={() => void onReloadSession(session)}
                      disabled={!canOperate}
                      title={canOperate ? '按配置文件重新装载（配置有问题时旧会话不受影响）' : operateHint}
                    >
                      重载
                    </button>
                    <button
                      type="button"
                      onClick={() => void onCloseSession(session)}
                      disabled={!canOperate}
                      title={canOperate ? '关闭并移除该会话（端口立即解绑）' : operateHint}
                    >
                      关闭
                    </button>
                  </td>
                </tr>
              ))}
              {sessions.length === 0 && (
                <tr>
                  <td colSpan={10} className="hint">
                    暂无会话（用 --demo-session / --config 启动，或在下方新建）
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          <div className="preview-controls">
            <input
              placeholder="demo 或 work-dir 下的配置文件名"
              value={newConfig}
              onChange={(event) => setNewConfig(event.target.value)}
              disabled={!canOperate}
            />
            <button type="button" onClick={() => void onCreateSession()} disabled={!canOperate || newConfig.trim() === ''}>
              新建会话
            </button>
            <span className="hint">
              配置只认 <code>--work-dir</code> 下的文件名，不接受路径。
            </span>
          </div>

          <label className="ceid">
            CEID
            <input
              type="number"
              min={1}
              value={ceid}
              onChange={(event) => setCeid(Number(event.target.value) || 1)}
              disabled={!canOperate}
            />
          </label>
          {!canOperate && <p className="hint">当前为只读身份：上报与报警操作需要操作员或管理员。</p>}
          {canOperate && <p className="hint">CEID 可在下方"设备目录"里按名称挑选，不用背数字。</p>}
        </section>

        <section>
          <h2>报警</h2>
          <div className="preview-controls">
            <select value={alarmSession} onChange={(event) => setAlarmSession(event.target.value)}>
              {sessions.length === 0 && <option value="">（无会话）</option>}
              {sessions.map((session) => (
                <option key={session.id} value={session.id}>
                  {session.id}
                </option>
              ))}
            </select>
            <label className="ceid">
              ALID
              <input
                type="number"
                min={1}
                value={alid}
                onChange={(event) => setAlid(Number(event.target.value) || 1)}
              />
            </label>
            <label className="ceid">
              类别
              <input
                type="number"
                min={0}
                max={7}
                value={alarmCategory}
                onChange={(event) => setAlarmCategory(Number(event.target.value) || 0)}
              />
            </label>
            <input
              placeholder="报警文本"
              value={alarmText}
              onChange={(event) => setAlarmText(event.target.value)}
            />
            <button
              type="button"
              onClick={() => void onAlarm(true)}
              disabled={sessions.length === 0 || !canOperate}
              title={canOperate ? '置位报警（S5F1）' : operateHint}
            >
              置位
            </button>
            <button
              type="button"
              onClick={() => void onAlarm(false)}
              disabled={sessions.length === 0 || !canOperate}
              title={canOperate ? '清除报警（S5F1）' : operateHint}
            >
              清除
            </button>
          </div>
          <p className="hint">
            置位/清除会向主机发送 S5F1（带 W 位，等待 S5F2）。
            {canOperate ? '' : ` 当前${role === undefined ? '身份未确认' : `角色为${roleLabel(role)}`}，操作已禁用。`}
          </p>

          <h3>活动报警</h3>
          <table>
            <thead>
              <tr>
                <th>ALID</th>
                <th>类别</th>
                <th>文本</th>
                <th>置位时间</th>
              </tr>
            </thead>
            <tbody>
              {alarms.map((alarm) => (
                <tr key={alarm.alid}>
                  <td>{alarm.alid}</td>
                  <td>{alarm.category}</td>
                  <td>{alarm.text}</td>
                  <td>{new Date(alarm.atMillis).toLocaleTimeString()}</td>
                </tr>
              ))}
              {alarms.length === 0 && (
                <tr>
                  <td colSpan={4} className="hint">
                    暂无活动报警
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          <h3>终端消息</h3>
          <table>
            <thead>
              <tr>
                <th>TID</th>
                <th>文本</th>
                <th>收到时间</th>
              </tr>
            </thead>
            <tbody>
              {terminalMessages.map((message, index) => (
                <tr key={`${message.atMillis}-${index}`}>
                  <td>{message.tid}</td>
                  <td>{message.text}</td>
                  <td>{new Date(message.atMillis).toLocaleTimeString()}</td>
                </tr>
              ))}
              {terminalMessages.length === 0 && (
                <tr>
                  <td colSpan={3} className="hint">
                    暂无终端消息
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </section>

        <section>
          <h2>曲线预览</h2>
          <div className="preview-controls">
            <select value={previewSession} onChange={(event) => setPreviewSession(event.target.value)}>
              {sessions.length === 0 && <option value="">（无会话）</option>}
              {sessions.map((session) => (
                <option key={session.id} value={session.id}>
                  {session.id}
                </option>
              ))}
            </select>
            <select value={windowMs} onChange={(event) => setWindowMs(Number(event.target.value))}>
              {WINDOWS.map((item) => (
                <option key={item.ms} value={item.ms}>
                  {item.label}
                </option>
              ))}
            </select>
            <button type="button" onClick={() => void onPreview()} disabled={sessions.length === 0}>
              刷新曲线
            </button>
          </div>
          {preview === null ? (
            <p className="hint">选好会话与时间窗后点"刷新曲线"，按当前配置算出 min/max 包络 + avg 线。</p>
          ) : (
            <div className="sparks">
              {preview.series.map((series) => (
                <Sparkline key={series.svid} series={series} />
              ))}
            </div>
          )}
        </section>

        <section>
          <h2>设备目录</h2>
          <div className="preview-controls">
            <select value={catalogSession} onChange={(event) => setCatalogSession(event.target.value)}>
              {sessions.length === 0 && <option value="">（无会话）</option>}
              {sessions.map((session) => (
                <option key={session.id} value={session.id}>
                  {session.id}
                </option>
              ))}
            </select>
            <span className="hint">
              CEID / RPTID / SVID 的号段按单元独立，不同会话之间不能照搬。
            </span>
          </div>
          {catalog !== null && (catalog.hostReports! > 0 || catalog.hostLinks! > 0 || catalog.hostAllDisabled) && (
            <p className="hint">
              主机已下发 {catalog.hostReports} 条报告定义、{catalog.hostLinks} 条事件链接
              {catalog.hostAllDisabled ? '，并把全部事件上报关掉了（S2F37）' : ''}；
              下面标了「主机」的条目就是主机覆盖过的配置（ADR-0006）。
            </p>
          )}

          <h3>采集事件（CEID）</h3>
          <table>
            <thead>
              <tr>
                <th>CEID</th>
                <th>归属</th>
                <th>名称</th>
                <th>携带报告</th>
                <th>来源</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {(catalog?.ceids ?? []).map((entry) => (
                <tr key={entry.ceid}>
                  <td>{entry.ceid}</td>
                  <td>{entry.owner || '-'}</td>
                  <td>{entry.slot || '-'}</td>
                  <td>{entry.reports.join(', ') || '-'}</td>
                  <td>
                    {entry.source === 'host' ? '主机' : '本地'}
                    {entry.enabled === false ? '（已禁用）' : ''}
                  </td>
                  <td>
                    <button
                      type="button"
                      onClick={() => setCeid(entry.ceid)}
                      disabled={!canOperate}
                      title={canOperate ? '填入上方的 CEID 框' : operateHint}
                    >
                      填入
                    </button>
                  </td>
                </tr>
              ))}
              {(catalog === null || catalog.ceids.length === 0) && (
                <tr>
                  <td colSpan={6} className="hint">
                    {catalog === null ? '该会话没有提供目录' : '该会话没有定义采集事件'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          <h3>报告（RPTID）</h3>
          <table>
            <thead>
              <tr>
                <th>RPTID</th>
                <th>归属</th>
                <th>名称</th>
                <th>变量</th>
                <th>来源</th>
              </tr>
            </thead>
            <tbody>
              {(catalog?.reports ?? []).map((entry) => (
                <tr key={entry.rptid}>
                  <td>{entry.rptid}</td>
                  <td>{entry.owner || '-'}</td>
                  <td>{entry.slot || '-'}</td>
                  <td>{entry.svids.join(', ') || '-'}</td>
                  <td>{entry.source === 'host' ? '主机' : '本地'}</td>
                </tr>
              ))}
              {(catalog === null || catalog.reports.length === 0) && (
                <tr>
                  <td colSpan={5} className="hint">
                    {catalog === null ? '该会话没有提供目录' : '该会话没有定义报告'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          <h3>状态变量（SVID）</h3>
          <table>
            <thead>
              <tr>
                <th>SVID</th>
                <th>归属</th>
                <th>名称</th>
                <th>类型</th>
                <th>单位</th>
              </tr>
            </thead>
            <tbody>
              {(catalog?.svids ?? []).map((entry) => (
                <tr key={entry.svid}>
                  <td>{entry.svid}</td>
                  <td>{entry.owner}</td>
                  <td>{entry.slot}</td>
                  <td>{entry.type}</td>
                  <td>{entry.unit || '-'}</td>
                </tr>
              ))}
              {(catalog === null || catalog.svids.length === 0) && (
                <tr>
                  <td colSpan={5} className="hint">
                    {catalog === null ? '该会话没有提供目录' : '该会话没有定义状态变量'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
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
              {messages.map((entry) => (
                <tr key={entry.key}>
                  <td>{new Date(entry.msg.at).toLocaleTimeString()}</td>
                  <td>{entry.msg.sessionId}</td>
                  <td>{entry.msg.direction === 'in' ? '← 收' : '→ 发'}</td>
                  <td>
                    S{entry.msg.stream}F{entry.msg.function}
                    {entry.msg.wbit ? ' W' : ''}
                  </td>
                  <td className="body" title={entry.msg.body}>
                    {entry.msg.body.length > 120 ? `${entry.msg.body.slice(0, 120)}…` : entry.msg.body}
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
