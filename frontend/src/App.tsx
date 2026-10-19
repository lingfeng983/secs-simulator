import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import uPlot from 'uplot';
import {
  createClient,
  hasRole,
  readToken,
  roleLabel,
  saveToken,
  type AlarmState,
  type Catalog,
  type ConfigEntry,
  type EventPlanView,
  type Health,
  type SessionSummary,
  type TerminalMessage,
  type PreviewData,
  type PreviewSeries,
  type WireMessage,
} from './api';
import { TemplateEditor } from './TemplateEditor';
import { mergeMessages, type MessageEntry } from './messageHistory';

interface LogLine {
  at: string;
  text: string;
}

const MAX_LOG = 400;
const MAX_MESSAGES = 200;

/** 事件编排的触发规则摘要。 */
function triggerLabel(plan: EventPlanView): string {
  switch (plan.triggerType) {
    case 'at':
      return `一次性 @ ${plan.atMs ?? 0} ms`;
    case 'periodic':
      return `每 ${plan.periodMs ?? 0} ms${plan.jitterMs ? ` ± ${plan.jitterMs} ms` : ''}`;
    case 'onCondition':
      return `${plan.expression ?? ''}（连续 ${plan.debounceMs ?? 0} ms）`;
    case 'afterEvent':
      return `${plan.sourcePlan ?? '?'} 之后 ${plan.delayMs ?? 0} ms`;
    default:
      return plan.triggerType;
  }
}

/** 可选的时间窗：后端引擎时钟从进程启动起算，时间窗要相对"现在"而不是 0。 */
const WINDOWS: ReadonlyArray<{ label: string; ms: number }> = [
  { label: '最近 1 分钟', ms: 60_000 },
  { label: '最近 5 分钟', ms: 300_000 },
  { label: '最近 15 分钟', ms: 900_000 },
];

/** 预览图里选中的桶区间；跨所有 SVID 共享，便于对齐读数。 */
interface ZoomRange {
  start: number;
  end: number;
}

interface SparklineProps {
  series: PreviewSeries;
  fromMs: number;
  bucketMs: number;
  zoom: ZoomRange | null;
  onZoom: (zoom: ZoomRange | null) => void;
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}

function clampZoom(zoom: ZoomRange | null, count: number): { start: number; end: number } {
  if (count <= 1) {
    return { start: 0, end: 0 };
  }
  const start = clamp(zoom?.start ?? 0, 0, count - 1);
  const end = clamp(zoom?.end ?? count - 1, start + 1, count - 1);
  return { start, end };
}

function formatValue(value: number): string {
  if (!Number.isFinite(value)) {
    return '-';
  }
  if (Math.abs(value) >= 1000) {
    return value.toFixed(1);
  }
  return value.toFixed(3).replace(/0+$/, '').replace(/\.$/, '');
}

function formatSeconds(seconds: number): string {
  if (seconds >= 60) {
    const minutes = Math.floor(seconds / 60);
    return `${minutes}m${(seconds - minutes * 60).toFixed(1)}s`;
  }
  return `${seconds.toFixed(2)}s`;
}

/**
 * 单条 SVID 的曲线：min/max 包络 + avg 线。
 *
 * <p>悬停读数与拖动放大都在浏览器里做：预览数据已经按桶取回，
 * 放大只是换一个可见桶区间，不需要再打一次后端。</p>
 */
function Sparkline({ series, fromMs, bucketMs, zoom, onZoom }: SparklineProps): JSX.Element {
  const ref = useRef<HTMLCanvasElement | null>(null);
  const dragAnchor = useRef<number | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [drag, setDrag] = useState<ZoomRange | null>(null);

  const count = series.avg.length;
  const visible = clampZoom(zoom, count);
  const viewStart = visible.start;
  const viewEnd = visible.end;

  useEffect(() => {
    const canvas = ref.current;
    const context = canvas?.getContext('2d');
    if (canvas == null || context == null) {
      return;
    }

    const width = canvas.width;
    const height = canvas.height;
    context.clearRect(0, 0, width, height);

    if (count === 0) {
      return;
    }

    let low = Number.POSITIVE_INFINITY;
    let high = Number.NEGATIVE_INFINITY;
    for (let index = viewStart; index <= viewEnd; index += 1) {
      low = Math.min(low, series.min[index]);
      high = Math.max(high, series.max[index]);
    }
    if (!Number.isFinite(low) || !Number.isFinite(high)) {
      return;
    }
    if (!(high > low)) {
      high = low + 1;
    }

    const span = Math.max(1, viewEnd - viewStart);
    const x = (index: number) => ((index - viewStart) / span) * (width - 1);
    const y = (value: number) => height - 2 - ((value - low) / (high - low)) * (height - 4);

    /* 三条水平参考线：没有刻度也要让人看出"高/中/低" */
    context.strokeStyle = 'rgba(128, 128, 128, 0.22)';
    context.lineWidth = 1;
    for (let line = 0; line <= 3; line += 1) {
      const at = 2 + (line / 3) * (height - 4);
      context.beginPath();
      context.moveTo(0, at);
      context.lineTo(width, at);
      context.stroke();
    }

    context.beginPath();
    for (let index = viewStart; index <= viewEnd; index += 1) {
      const value = series.max[index];
      if (index === viewStart) {
        context.moveTo(x(index), y(value));
      } else {
        context.lineTo(x(index), y(value));
      }
    }
    for (let index = viewEnd; index >= viewStart; index -= 1) {
      context.lineTo(x(index), y(series.min[index]));
    }
    context.closePath();
    context.fillStyle = 'rgba(68, 147, 248, 0.22)';
    context.fill();

    context.beginPath();
    for (let index = viewStart; index <= viewEnd; index += 1) {
      const value = series.avg[index];
      if (index === viewStart) {
        context.moveTo(x(index), y(value));
      } else {
        context.lineTo(x(index), y(value));
      }
    }
    context.strokeStyle = '#4493f8';
    context.lineWidth = 1.5;
    context.stroke();

    if (drag !== null) {
      const from = Math.min(drag.start, drag.end);
      const to = Math.max(drag.start, drag.end);
      const left = clamp(x(from), 0, width);
      const right = clamp(x(to), 0, width);
      context.fillStyle = 'rgba(68, 147, 248, 0.16)';
      context.fillRect(left, 0, Math.max(1, right - left), height);
    }

    if (hover !== null) {
      const index = clamp(hover, viewStart, viewEnd);
      const atX = x(index);
      context.strokeStyle = 'rgba(128, 128, 128, 0.75)';
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(atX, 0);
      context.lineTo(atX, height);
      context.stroke();

      context.fillStyle = '#4493f8';
      context.beginPath();
      context.arc(atX, y(series.avg[index]), 3, 0, Math.PI * 2);
      context.fill();
    }
  }, [series, hover, drag, viewStart, viewEnd, count]);

  const bucketAt = (event: ReactPointerEvent<HTMLCanvasElement>): number | null => {
    const canvas = ref.current;
    if (canvas === null) {
      return null;
    }
    const rect = canvas.getBoundingClientRect();
    if (rect.width === 0) {
      return null;
    }
    const ratio = clamp((event.clientX - rect.left) / rect.width, 0, 1);
    return Math.round(viewStart + ratio * Math.max(0, viewEnd - viewStart));
  };

  const onPointerDown = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const index = bucketAt(event);
    if (index === null) {
      return;
    }
    event.currentTarget.setPointerCapture(event.pointerId);
    dragAnchor.current = index;
    setDrag({ start: index, end: index });
    setHover(index);
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const index = bucketAt(event);
    if (index === null) {
      return;
    }
    setHover(index);
    if (dragAnchor.current !== null) {
      setDrag({ start: dragAnchor.current, end: index });
    }
  };

  const finishDrag = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const anchor = dragAnchor.current;
    const index = bucketAt(event);
    dragAnchor.current = null;
    setDrag(null);

    if (anchor !== null && index !== null && Math.abs(index - anchor) >= 3) {
      onZoom({ start: Math.min(anchor, index), end: Math.max(anchor, index) });
    }
  };

  const hovered = hover === null ? null : clamp(hover, viewStart, viewEnd);
  const readout =
    hovered === null
      ? '悬停读数'
      : `${formatSeconds((fromMs + hovered * bucketMs) / 1000)}  min ${formatValue(series.min[hovered])}  max ${formatValue(series.max[hovered])}  avg ${formatValue(series.avg[hovered])}`;

  return (
    <div className="spark">
      <span className="spark-id">{series.svid}</span>
      <canvas
        ref={ref}
        width={760}
        height={72}
        title="悬停看读数；横向拖动选择区间放大；双击重置缩放"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={finishDrag}
        onPointerCancel={() => {
          dragAnchor.current = null;
          setDrag(null);
        }}
        onPointerLeave={() => {
          if (dragAnchor.current === null) {
            setHover(null);
          }
        }}
        onDoubleClick={() => onZoom(null)}
      />
      <span className="spark-readout">{readout}</span>
    </div>
  );
}

interface UplotSeriesProps {
  series: PreviewSeries;
  fromMs: number;
  bucketMs: number;
}

/**
 * uPlot 版本的单条曲线。
 *
 * <p>Canvas 版本适合轻量总览；uPlot 提供图例开关、框选缩放、双击复位和光标读数。
 * 两条路径共享同一份 {@link PreviewData}，切换只影响渲染，不重新请求后端。</p>
 */
function UplotSeries({ series, fromMs, bucketMs }: UplotSeriesProps): JSX.Element {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const plotRef = useRef<uPlot | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) {
      return;
    }

    const x = series.avg.map((_, index) => (fromMs + index * bucketMs) / 1000);
    const data: uPlot.AlignedData = [x, series.min, series.max, series.avg];
    const plot = new uPlot(
      {
        width: Math.max(320, host.clientWidth || 760),
        height: 220,
        padding: [12, 12, 4, 4],
        cursor: {
          drag: { x: true, y: false, setScale: true },
          points: { show: false },
        },
        legend: { show: true, live: true },
        scales: { x: { time: false } },
        series: [
          { label: 't(s)' },
          { label: 'min', stroke: 'rgba(68, 147, 248, 0.35)', width: 1, points: { show: false } },
          { label: 'max', stroke: 'rgba(68, 147, 248, 0.35)', width: 1, points: { show: false } },
          { label: 'avg', stroke: '#4493f8', width: 1.8, points: { show: false } },
        ],
        bands: [{ series: [1, 2], fill: 'rgba(68, 147, 248, 0.18)' }],
        axes: [
          { stroke: '#8b949e', grid: { stroke: 'rgba(128, 128, 128, 0.18)' } },
          { stroke: '#8b949e', grid: { stroke: 'rgba(128, 128, 128, 0.18)' } },
        ],
      },
      data,
      host,
    );
    plotRef.current = plot;

    const resize = () => {
      const width = host.clientWidth;
      if (width > 0 && plotRef.current !== null) {
        plotRef.current.setSize({ width, height: 220 });
      }
    };
    resize();

    let observer: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(resize);
      observer.observe(host);
    }

    return () => {
      observer?.disconnect();
      plot.destroy();
      plotRef.current = null;
    };
  }, [series, fromMs, bucketMs]);

  return (
    <div className="uplot-block">
      <div className="uplot-title">SVID {series.svid}</div>
      <div className="uplot-host" ref={hostRef} />
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
  const [chartMode, setChartMode] = useState<'canvas' | 'uplot'>('canvas');
  const [zoom, setZoom] = useState<ZoomRange | null>(null);
  const [alarmSession, setAlarmSession] = useState('');
  const [alid, setAlid] = useState(1001);
  const [alarmText, setAlarmText] = useState('TEMP HIGH');
  const [alarmCategory, setAlarmCategory] = useState(1);
  const [alarms, setAlarms] = useState<AlarmState[]>([]);
  const [terminalMessages, setTerminalMessages] = useState<TerminalMessage[]>([]);
  const [newConfig, setNewConfig] = useState('demo');
  const [catalogSession, setCatalogSession] = useState('');
  const [smlSession, setSmlSession] = useState('');
  const [smlDraft, setSmlDraft] = useState('S1F1 W.');
  const [smlResult, setSmlResult] = useState('');
  const [smlBusy, setSmlBusy] = useState(false);
  const [eventSession, setEventSession] = useState('');
  const [eventPlans, setEventPlans] = useState<EventPlanView[]>([]);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [configList, setConfigList] = useState<ConfigEntry[]>([]);
  const [configName, setConfigName] = useState('');
  const [configDraft, setConfigDraft] = useState('');
  const [configBaseline, setConfigBaseline] = useState('');
  const [configProblems, setConfigProblems] = useState<string[]>([]);
  const [configNotice, setConfigNotice] = useState('');
  const [newConfigName, setNewConfigName] = useState('');
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

  /* 会话列表变化后保证手动发送下拉框指向一个存在的会话 */
  useEffect(() => {
    if (sessions.length === 0) {
      if (smlSession !== '') {
        setSmlSession('');
      }
      return;
    }
    if (!sessions.some((session) => session.id === smlSession)) {
      setSmlSession(sessions[0].id);
    }
  }, [sessions, smlSession]);

  /* 会话列表变化后保证事件编排下拉框指向一个存在的会话 */
  useEffect(() => {
    if (sessions.length === 0) {
      if (eventSession !== '') {
        setEventSession('');
      }
      return;
    }
    if (!sessions.some((session) => session.id === eventSession)) {
      setEventSession(sessions[0].id);
    }
  }, [sessions, eventSession]);

  /*
   * 事件编排列表：轮询。
   *
   * <p>运行时的启用状态不属于配置：主机/操作员随时可能改，
   * 只在切换会话时拉一次会让界面一直显示旧状态。</p>
   */
  useEffect(() => {
    if (client === null || eventSession === '') {
      setEventPlans([]);
      return;
    }

    let cancelled = false;
    const load = async () => {
      try {
        const next = await client.events(eventSession);
        if (!cancelled) {
          setEventPlans(next);
        }
      } catch {
        if (!cancelled) {
          setEventPlans([]);
        }
      }
    };

    void load();
    const timer = window.setInterval(load, 2000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [client, eventSession]);

  /*
   * 配置目录列表：轮询。
   *
   * <p>只轮询列表，不轮询正文——正文正在编辑时自动刷新会把用户正在写的东西冲掉。
   * 列表本身很少变，5 秒一次足够。
   * </p>
   */
  const refreshConfigs = useCallback(async () => {
    if (client === null) {
      return;
    }
    try {
      setConfigList(await client.configs());
    } catch {
      /* 读不到目录先不打扰用户，下一轮再试 */
    }
  }, [client]);

  useEffect(() => {
    if (client === null) {
      setConfigList([]);
      return;
    }

    void refreshConfigs();
    const timer = window.setInterval(() => void refreshConfigs(), 5000);
    return () => {
      window.clearInterval(timer);
    };
  }, [client, refreshConfigs]);

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
      setZoom(null);
      append(`已刷新 ${target.id} 的曲线（${Math.round((to - from) / 1000)} 秒窗口）`);
    } catch (error) {
      append(`预览失败: ${(error as Error).message}`);
    }
  };

  /** 下载当前时间窗的二进制预览；格式是 SCSP v1。 */
  const onDownloadPreview = async () => {
    if (client === null) {
      return;
    }
    const target = sessions.find((session) => session.id === previewSession) ?? sessions[0];
    if (target === undefined) {
      return;
    }

    try {
      const fresh = await client.health();
      setHealth(fresh);
      const to = Math.max(1, fresh.uptimeMs ?? 0);
      const from = Math.max(0, to - windowMs);
      const blob = await client.previewBinary(target.id, from, to, 120);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `preview-${target.id}-${Date.now()}.bin`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
      append(`已下载 ${target.id} 的二进制曲线（${blob.size} 字节）`);
    } catch (error) {
      append(`下载二进制曲线失败: ${(error as Error).message}`);
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

  /**
   * 载入历史报文，补上 SSE 断线/刷新期间缺的那一段。
   *
   * <p>按全部字段去重后按时间合并，避免"历史覆盖实时"。
   * 只保留面板上限 {@link MAX_MESSAGES} 条，完整历史在服务端文件里。</p>
   */
  const onLoadHistory = async () => {
    if (client === null) {
      return;
    }
    try {
      const page = await client.messages('', '', 1_000);
      setMessages((previous) => {
        const merged = mergeMessages(previous, page.messages, nextMessageKey.current, MAX_MESSAGES);
        nextMessageKey.current = merged.nextKey;
        return merged.entries;
      });
      append(
        page.truncated
          ? `已载入最近 ${page.messages.length} 条历史（共 ${page.total} 条，面板只保留最近 ${MAX_MESSAGES} 条）`
          : `已载入历史 ${page.messages.length} 条`,
      );
    } catch (error) {
      append(`载入历史失败: ${(error as Error).message}`);
    }
  };

  /** 从服务端导出 JSONL：不受面板 200 条上限影响，最多 5000 条。 */
  const onExportMessages = async () => {
    if (client === null) {
      return;
    }
    try {
      const text = await client.messagesJsonl('', '', 5_000);
      if (text.trim() === '') {
        append('没有可导出的报文');
        return;
      }
      const blob = new Blob([text], { type: 'application/x-ndjson;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `messages-${new Date().toISOString().replace(/[:.]/g, '-')}.jsonl`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
      append(`已导出报文历史 ${text.trim().split('\n').length} 行`);
    } catch (error) {
      append(`导出报文失败: ${(error as Error).message}`);
    }
  };

  /**
   * 手动发送 SML。
   *
   * <p>会话没有对端时服务端回 409，这里只负责把结果讲清楚：
   * "发出去了但主机没回"和"根本没发出去"是两件事。</p>
   */
  const onSendSml = async () => {
    if (client === null || smlDraft.trim() === '') {
      return;
    }
    const target = sessions.find((session) => session.id === smlSession) ?? sessions[0];
    if (target === undefined) {
      return;
    }

    setSmlBusy(true);
    setSmlResult('');
    try {
      const result = await client.sendSml(target.id, smlDraft);
      const lines = [
        `发送 ${target.id}  S${result.stream}F${result.function}${result.wbit ? ' W' : ''}`,
        result.sentSml === '' ? '（无 body）' : result.sentSml,
      ];
      if (result.replySml != null) {
        lines.push(
          '',
          `回复 S${result.replyStream}F${result.replyFunction}${result.replyWbit ? ' W' : ''}`,
          result.replySml,
        );
      } else if (result.timedOut) {
        lines.push('', 'W 位报文已发出，但主机未在 T3 内回复');
      } else {
        lines.push('', '未请求回复（W=0）');
      }
      lines.push('', `耗时 ${result.elapsedMs} ms`);
      setSmlResult(lines.join('\n'));
      append(
        `手动发送 ${target.id} S${result.stream}F${result.function}${
          result.replySml != null
            ? ` -> S${result.replyStream}F${result.replyFunction}`
            : result.timedOut
              ? ' -> 等待回复超时'
              : ' -> 已发送'
        }`,
      );
    } catch (error) {
      const message = (error as Error).message;
      setSmlResult(`发送失败：${message}`);
      append(`手动发送失败: ${message}`);
    } finally {
      setSmlBusy(false);
    }
  };

  /** 运行时启用/禁用一个编排计划；不写回配置，重载会恢复配置里的状态。 */
  const onToggleEvent = async (plan: EventPlanView) => {
    if (client === null || eventSession === '') {
      return;
    }
    try {
      const status = await client.setEventEnabled(eventSession, plan.id, !plan.enabled);
      append(
        status === 200
          ? `事件计划 ${plan.id} 已${plan.enabled ? '禁用' : '启用'}`
          : status === 404
            ? `事件计划 ${plan.id} 操作失败：会话或计划不存在`
            : `事件计划 ${plan.id} 操作失败（HTTP ${status}）`,
      );
      if (status === 200) {
        setEventPlans(await client.events(eventSession));
      }
    } catch (error) {
      append(`事件计划操作失败: ${(error as Error).message}`);
    }
  };

  /** 编辑区里的内容相对磁盘有没有变。 */
  const configDirty = configDraft !== configBaseline;

  /** 打开一份配置。有未保存改动时先问一句：悄悄丢掉别人正在写的东西最招人烦。 */
  const openConfig = async (name: string) => {
    if (client === null) {
      return;
    }
    if (configDirty && !window.confirm(`${configName} 有未保存的改动，切换会丢掉。继续？`)) {
      return;
    }

    try {
      const text = await client.configText(name);
      setConfigName(name);
      setConfigDraft(text);
      setConfigBaseline(text);
      setConfigProblems([]);
      setConfigNotice(`已打开 ${name}`);
    } catch (error) {
      append(`打开配置失败: ${(error as Error).message}`);
    }
  };

  /** 丢掉本地改动，重新拉磁盘上的原文。 */
  const reloadConfig = async () => {
    if (client === null || configName === '') {
      return;
    }
    try {
      const text = await client.configText(configName);
      setConfigDraft(text);
      setConfigBaseline(text);
      setConfigProblems([]);
      setConfigNotice(`已重新载入 ${configName}`);
    } catch (error) {
      append(`重新载入失败: ${(error as Error).message}`);
    }
  };

  /** 只校验不落盘：用户想确认改动能不能编译，不该被迫先覆盖一份能用的配置。 */
  const validateConfig = async () => {
    if (client === null || configName === '') {
      return;
    }
    try {
      const result = await client.validateConfig(configName, configDraft);
      setConfigProblems(result.problems);
      setConfigNotice(result.valid ? '校验通过' : '校验未通过');
    } catch (error) {
      append(`校验失败: ${(error as Error).message}`);
    }
  };

  const saveConfig = async () => {
    if (client === null || configName === '') {
      return;
    }
    try {
      const result = await client.saveConfig(configName, configDraft);
      if (result.status === 200) {
        setConfigProblems([]);
        setConfigBaseline(configDraft);
        setConfigNotice(`已保存 ${configName}（已在运行的会话需要重载才生效）`);
        append(`配置已保存: ${configName}`);
        void refreshConfigs();
        return;
      }

      setConfigProblems(result.problems);
      setConfigNotice(result.status === 422 ? '保存被拒：配置有问题，未落盘' : `保存失败（HTTP ${result.status}）`);
    } catch (error) {
      append(`保存失败: ${(error as Error).message}`);
    }
  };

  /**
   * 新建：让后端生成一份能直接编译的骨架。
   *
   * <p>骨架不在前端写死：只有一份判据，才不会出现"界面上给的模板起不来"。
   * </p>
   */
  const onNewConfig = async () => {
    if (client === null) {
      return;
    }

    const name = newConfigName.trim();
    if (name === '') {
      return;
    }
    if (!name.toLowerCase().endsWith('.json')) {
      setConfigNotice('文件名必须以 .json 结尾');
      return;
    }
    if (configDirty && !window.confirm(`${configName} 有未保存的改动，新建会丢掉。继续？`)) {
      return;
    }

    try {
      const status = await client.createConfig(name);
      if (status === 409) {
        setConfigNotice('已经有同名的配置了，直接从上面选它来改');
        return;
      }
      if (status !== 201) {
        setConfigNotice(`新建失败（HTTP ${status}）`);
        return;
      }

      const text = await client.configText(name);
      setConfigName(name);
      setConfigDraft(text);
      setConfigBaseline(text);
      setConfigProblems([]);
      setConfigNotice(`已新建 ${name}，改完点「保存」才会落盘`);
      void refreshConfigs();
    } catch (error) {
      append(`新建配置失败: ${(error as Error).message}`);
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
            <label className="ceid">
              图表
              <select
                value={chartMode}
                onChange={(event) => setChartMode(event.target.value === 'uplot' ? 'uplot' : 'canvas')}
              >
                <option value="canvas">Canvas</option>
                <option value="uplot">uPlot</option>
              </select>
            </label>
            <button type="button" onClick={() => void onPreview()} disabled={sessions.length === 0}>
              刷新曲线
            </button>
            <button
              type="button"
              onClick={() => void onDownloadPreview()}
              disabled={sessions.length === 0}
              title="SCSP v1：固定头 + 每条序列的 min/max/avg 双精度数组"
            >
              下载二进制
            </button>
          </div>
          {preview === null ? (
            <p className="hint">选好会话与时间窗后点"刷新曲线"，按当前配置算出 min/max 包络 + avg 线。</p>
          ) : (
            <>
              <div className="preview-meta">
                <span className="hint">
                  {preview.series.length} 条曲线 · {preview.buckets} 桶 × {preview.bucketMs} ms
                  {preview.from !== undefined ? ` · 窗口 ${Math.round((preview.to - preview.from) / 1000)} 秒` : ''}
                </span>
                {chartMode === 'canvas' && zoom !== null && (
                  <>
                    <span className="hint">
                      已放大：桶 {zoom.start}–{zoom.end}
                      （约 {Math.round(((zoom.end - zoom.start + 1) * preview.bucketMs) / 1000)} 秒）
                    </span>
                    <button type="button" onClick={() => setZoom(null)}>
                      重置缩放
                    </button>
                  </>
                )}
              </div>
              {preview.truncated === true && (
                <p className="hint">
                  后端只返回前 {preview.series.length} 条曲线
                  {preview.svidTotal !== undefined ? `（共 ${preview.svidTotal} 个 SVID）` : ''}。
                  这是预览上限，不是曲线消失了。
                </p>
              )}
              {preview.series.length === 0 ? (
                <p className="hint">该会话没有可预览的 SVID。</p>
              ) : (
                <>
                  <p className="hint">
                    {chartMode === 'uplot'
                      ? '拖动框选放大，双击复位；点击图例可隐藏/显示 min/max/avg。'
                      : '悬停看读数；在曲线上横向拖动选择区间放大；双击或点「重置缩放」恢复。'}
                  </p>
                  {chartMode === 'uplot' ? (
                    <div className="uplots">
                      {preview.series.map((series) => (
                        <UplotSeries
                          key={series.svid}
                          series={series}
                          fromMs={preview.from}
                          bucketMs={preview.bucketMs}
                        />
                      ))}
                    </div>
                  ) : (
                    <div className="sparks">
                      {preview.series.map((series) => (
                        <Sparkline
                          key={series.svid}
                          series={series}
                          fromMs={preview.from}
                          bucketMs={preview.bucketMs}
                          zoom={zoom}
                          onZoom={setZoom}
                        />
                      ))}
                    </div>
                  )}
                </>
              )}
            </>
          )}
        </section>

        <section>
          <h2>手动发送</h2>
          <div className="preview-controls">
            <select value={smlSession} onChange={(event) => setSmlSession(event.target.value)}>
              {sessions.length === 0 && <option value="">（无会话）</option>}
              {sessions.map((session) => (
                <option key={session.id} value={session.id}>
                  {session.id}
                </option>
              ))}
            </select>
            <button
              type="button"
              onClick={() => void onSendSml()}
              disabled={!canOperate || smlBusy || smlDraft.trim() === ''}
              title={canOperate ? '发送到选中的会话（W 位会等待回复）' : operateHint}
            >
              {smlBusy ? '发送中…' : '发送 SML'}
            </button>
            <button type="button" onClick={() => setSmlDraft('S1F1 W.')}>
              示例 S1F1
            </button>
            <button
              type="button"
              onClick={() =>
                setSmlDraft(
                  'S5F1 W\n<L\n  <B 0x81>\n  <U2 1001>\n  <A "ON FIRE">\n>.',
                )
              }
            >
              示例 S5F1
            </button>
          </div>
          <textarea
            className="sml-editor"
            spellCheck={false}
            value={smlDraft}
            onChange={(event) => setSmlDraft(event.target.value)}
          />
          <p className="hint">
            报文头写 <code>SxFy</code>，可带 <code>W</code>；数据项支持
            <code>L/A/B/BOOLEAN/I1..I8/U1..U8/F4/F8</code>。W 位会等 T3，
            没有对端时服务端回 409。
            {canOperate ? '' : ` 当前${role === undefined ? '身份未确认' : `角色为${roleLabel(role)}`}，发送已禁用。`}
          </p>
          {smlResult !== '' && <pre className="sml-result">{smlResult}</pre>}
        </section>

        <section>
          <h2>事件编排</h2>
          <div className="preview-controls">
            <select value={eventSession} onChange={(event) => setEventSession(event.target.value)}>
              {sessions.length === 0 && <option value="">（无会话）</option>}
              {sessions.map((session) => (
                <option key={session.id} value={session.id}>
                  {session.id}
                </option>
              ))}
            </select>
            <span className="hint">
              这里改的是运行时状态，不写回配置；重载会话会恢复配置里的启用状态。
            </span>
          </div>
          <table>
            <thead>
              <tr>
                <th>计划</th>
                <th>CEID</th>
                <th>触发规则</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {eventPlans.map((plan) => (
                <tr key={plan.id}>
                  <td>{plan.id}</td>
                  <td>{plan.ceid}</td>
                  <td>{triggerLabel(plan)}</td>
                  <td>{plan.enabled ? '启用' : '禁用'}</td>
                  <td>
                    <button
                      type="button"
                      onClick={() => void onToggleEvent(plan)}
                      disabled={!canOperate}
                      title={
                        canOperate
                          ? plan.enabled
                            ? '禁用后不再触发；已排队的延迟事件会清掉'
                            : '重新启用；禁用期间的事件不补发'
                          : operateHint
                      }
                    >
                      {plan.enabled ? '禁用' : '启用'}
                    </button>
                  </td>
                </tr>
              ))}
              {eventPlans.length === 0 && (
                <tr>
                  <td colSpan={5} className="hint">
                    {sessions.length === 0 ? '暂无会话' : '该会话没有事件编排计划'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
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
          <h2>配置编辑器</h2>
          <div className="preview-controls">
            <select
              value={configList.some((entry) => entry.name === configName) ? configName : ''}
              onChange={(event) => {
                if (event.target.value !== '') {
                  void openConfig(event.target.value);
                }
              }}
            >
              <option value="">（选择配置）</option>
              {configList.map((entry) => (
                <option key={entry.name} value={entry.name}>
                  {entry.name}（{entry.sizeBytes} 字节）
                </option>
              ))}
            </select>
            <input
              placeholder="新文件名.json"
              value={newConfigName}
              onChange={(event) => setNewConfigName(event.target.value)}
            />
            <button
              type="button"
              onClick={() => void onNewConfig()}
              disabled={newConfigName.trim() === '' || !canOperate}
              title={canOperate ? '新建一份能直接编译的配置骨架' : operateHint}
            >
              新建
            </button>
            <button type="button" onClick={() => void reloadConfig()} disabled={configName === ''}>
              重新载入
            </button>
            <button type="button" onClick={() => void validateConfig()} disabled={configName === ''}>
              校验
            </button>
            <button
              type="button"
              onClick={() => void saveConfig()}
              disabled={configName === '' || !canOperate}
              title={canOperate ? '校验通过后原子落盘' : operateHint}
            >
              保存
            </button>
          </div>

          {configName === '' ? (
            <p className="hint">
              从上面挑一份配置，或输入新文件名点「新建」。配置只认 <code>--work-dir</code> 下的文件名。
            </p>
          ) : (
            <>
              <p className="hint">
                {configName}
                {configDirty ? '（有未保存的改动）' : '（与磁盘一致）'}
                {configNotice !== '' ? `　${configNotice}` : ''}
              </p>
              <textarea
                className="config-editor"
                spellCheck={false}
                value={configDraft}
                onChange={(event) => {
                  setConfigDraft(event.target.value);
                  setConfigProblems([]);
                }}
              />
              <TemplateEditor
                text={configDraft}
                onChange={(next) => {
                  setConfigDraft(next);
                  setConfigProblems([]);
                  setConfigNotice('结构化编辑已修改草稿，保存前请校验');
                }}
              />
              {configProblems.length > 0 && (
                <ul className="problems">
                  {configProblems.map((problem, index) => (
                    <li key={index}>{problem}</li>
                  ))}
                </ul>
              )}
            </>
          )}
          <p className="hint">
            编辑的是原始 JSON 文本；不动模板表单时保存逐字节写回，多写的键、空格和缩进都不会被动过。
            模板表单改动会保留未知键，但会把整份文件重排成 2 空格缩进。
            校验按真实启动路径走（解析 → 编译设备模型 → 建模板图），过了才会落盘。
            {canOperate ? '' : ` 当前${role === undefined ? '身份未确认' : `角色为${roleLabel(role)}`}，保存已禁用。`}
          </p>
        </section>

        <section>
          <h2>
            报文
            <span className="message-actions">
              <button type="button" onClick={() => void onLoadHistory()}>
                载入历史
              </button>
              <button type="button" onClick={() => void onExportMessages()}>
                导出 JSONL
              </button>
              <button
                type="button"
                onClick={() => setMessages([])}
                title="只清界面；服务端日志文件不动"
              >
                清空
              </button>
            </span>
          </h2>
          <p className="hint">
            SSE 只推“现在”；断线或刷新后点「载入历史」补齐。落盘位置是
            <code>--work-dir/logs/messages.jsonl</code>，每个进程最多回看最近 5000 条。
          </p>
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
