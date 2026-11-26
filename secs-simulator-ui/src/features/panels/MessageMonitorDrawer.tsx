import { useEffect, useMemo, useRef, useState } from 'react';
import type { WireMessage } from '../../api';
import { categorizeMessage, MESSAGE_CATEGORIES } from '../../shared/lib/messageFilter';
import { formatClock } from '../../shared/lib/format';
import { usePersistentState } from '../../state/usePersistentState';
import { DEFAULT_REGION, type RegionsState } from '../../state/useRegions';

/** 一次最多挂多少行 DOM；全量缓冲 5000 条，全渲染会掉帧。 */
const MAX_ROWS = 400;

const CATEGORY_LABEL: Record<string, string> = Object.fromEntries(
  MESSAGE_CATEGORIES.map((category) => [category.id, category.label]),
);

export interface MonitorScope {
  kind: 'device' | 'host' | 'none';
  id: string;
}

/** 抽屉里可切换的监控对象：全部渠道 / 一台设备 / 一台主机。 */
export interface MonitorTarget {
  kind: 'all' | 'device' | 'host';
  id: string;
  /** 展示名（设备名 / 主机名）。 */
  label: string;
  /** 所属区域；缺省归到 Default。 */
  region?: string;
}

/** 「全部渠道」伪对象：不过滤对象，设备与主机两侧、收发全部可见。
 *  放在查看对象的第一位且是默认选中——工艺周期的片内事件（device 视角）
 *  和主机侧收到的副本都在这里可见（2026-11-18 用户要求）。 */
export const ALL_TARGET: MonitorTarget = {
  kind: 'all',
  id: '__all__',
  label: '全部渠道（收发）',
};

export interface MessageMonitorDrawerProps {
  open: boolean;
  onClose: () => void;
  messages: readonly WireMessage[];
  /** 当前路由指向的设备/主机：打开抽屉时默认看它。 */
  scope: MonitorScope;
  /** 全部可切换对象（设备 + 主机）。 */
  targets: readonly MonitorTarget[];
  /** 区域列表：先选区域，再在区域里选对象。 */
  regions?: RegionsState;
  /** 只展示最近多少分钟以内的报文。 */
  windowMin: number;
  /** 宽度由 App 持有：抽屉本体与 main-col 的让位必须用同一个值，不能各存一份 localStorage。 */
  width: number;
  /** 拖动时的宽度上下限，来自「通用设置」。 */
  minWidth: number;
  maxWidth: number;
  onWidthChange: (width: number) => void;
}

type DirectionFilter = 'all' | 'recv' | 'sent';

function targetKey(target: { kind: 'all' | 'device' | 'host'; id: string }): string {
  return `${target.kind}:${target.id}`;
}

function regionOf(target: MonitorTarget): string {
  return target.region === undefined || target.region === '' ? DEFAULT_REGION : target.region;
}

/**
 * 右侧「实时消息监控」抽屉。
 *
 * <p>同一时刻只看一台设备或一台主机——把多台设备的报文混在
 * 一条流里，S1F1 与 S6F11 交叉出现，根本分不清是谁在跟谁说话。每行只显示
 * 报文头（时间/方向/SxFy/归属），报文体默认折叠，点行才展开。</p>
 *
 * <p>头部只有一行：先选区域、再选该区域里的对象，关闭按钮在行尾。
 * 列表默认只回看最近 N 分钟（N 在「通用设置」里配，默认 60），
 * 不然一打开就是几千条历史报文，找当前这一来一回反而更慢。</p>
 */
export function MessageMonitorDrawer({
  open,
  onClose,
  messages,
  scope,
  targets,
  regions,
  windowMin,
  width,
  minWidth,
  maxWidth,
  onWidthChange,
}: MessageMonitorDrawerProps): JSX.Element | null {
  const [direction, setDirection] = usePersistentState<DirectionFilter>('secs.ui.monitor.direction', 'all');
  const [sf, setSf] = useState('');
  const [keyword, setKeyword] = useState('');
  const [clearAfter, setClearAfter] = useState(0);
  const [paused, setPaused] = useState(false);
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const frozenRef = useRef<readonly WireMessage[]>([]);

  const [storedTarget, setStoredTarget] = usePersistentState<string>('secs.ui.monitor.target', '');
  const [regionPick, setRegionPick] = usePersistentState<string>('secs.ui.monitor.region', '');
  const currentKey = scope.kind === 'none' ? '' : `${scope.kind}:${scope.id}`;

  /* 切到另一台设备/主机时跟随当前路由，并把区域切回它所在的区域；
     在同一页里手动选别的对象则保持选择。 */
  useEffect(() => {
    if (currentKey !== '') {
      setStoredTarget(currentKey);
      setRegionPick('');
    }
  }, [currentKey, setStoredTarget, setRegionPick]);

  const regionNames = regions?.names ?? [DEFAULT_REGION];
  const currentTarget = targets.find((target) => targetKey(target) === currentKey) ?? null;
  const currentRegion = currentTarget === null ? '' : regionOf(currentTarget);
  /* 空串 = 跟随当前对象；选了具体区域就以它为准 */
  const activeRegion = regionNames.includes(regionPick)
    ? regionPick
    : (currentRegion !== '' ? currentRegion : (regionNames[0] ?? DEFAULT_REGION));

  const scopedTargets = useMemo(
    () => [ALL_TARGET, ...targets.filter((target) => regionOf(target) === activeRegion)],
    [targets, activeRegion],
  );
  const available = useMemo(
    () => new Set(scopedTargets.map((target) => targetKey(target))),
    [scopedTargets],
  );
  const activeKey = available.has(storedTarget)
    ? storedTarget
    : (scopedTargets.length > 0 ? targetKey(scopedTargets[0]) : '');
  const activeTarget = scopedTargets.find((target) => targetKey(target) === activeKey) ?? null;

  /* 时间窗要用"现在"做基准：定时器每 30 秒推一次，老报文才会自己退场 */
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    if (!open) {
      return;
    }
    setNowMs(Date.now());
    const timer = window.setInterval(() => setNowMs(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, [open]);

  /* N5：载入更早——临时放宽挂载行数上限；过滤条件变化时归零 */
  const [extraRows, setExtraRows] = useState(0);
  useEffect(() => {
    setExtraRows(0);
  }, [direction, sf, keyword, activeKey, clearAfter]);

  /* 暂停时冻结一份快照，不跟着 SSE 继续滚 */
  if (!paused) {
    frozenRef.current = messages;
  }
  const source = paused ? frozenRef.current : messages;
  /* reduce 代替 Math.max(...map)：5000 条缓冲展开成参数会爆栈，且并入 useMemo 不再每次渲染重算 */
  const maxAt = useMemo(() => source.reduce((max, message) => Math.max(max, message.at), 0), [source]);
  const cutoff = nowMs - windowMin * 60_000;

  const filtered = useMemo(() => source.filter((message) => {
    if (message.at <= clearAfter) {
      return false;
    }
    /* 默认只回看最近 N 分钟 */
    if (message.at < cutoff) {
      return false;
    }
    /* 「全部渠道」：不过滤对象——设备与主机两侧的副本都可见（评审/用户要求） */
    if (activeTarget !== null && activeTarget.kind !== 'all') {
      if (activeTarget.kind === 'host') {
        if (message.hostId !== activeTarget.id) {
          return false;
        }
      } else if (message.sessionId !== activeTarget.id
        || (message.hostId ?? '') !== ''
        || message.perspective === 'host') {
        return false;
      }
    }
    const sent = message.perspective === 'host'
      ? message.direction === 'in'
      : message.direction === 'out';
    if (direction === 'sent' && !sent) {
      return false;
    }
    if (direction === 'recv' && sent) {
      return false;
    }
    const sfKey = sf.trim().toLowerCase().replace(/\s+/g, '');
    if (sfKey !== '' && !`s${message.stream}f${message.function}`.startsWith(sfKey)) {
      return false;
    }
    if (keyword.trim() !== '' && !message.body.toLowerCase().includes(keyword.trim().toLowerCase())) {
      return false;
    }
    return true;
  }), [source, clearAfter, cutoff, activeTarget, direction, sf, keyword]);

  /* 收发计数并入 useMemo：不在渲染体里每次全量循环 */
  const { recvCount, sentCount } = useMemo(() => {
    let recv = 0;
    let sentN = 0;
    for (const message of filtered) {
      const sent = message.perspective === 'host'
        ? message.direction === 'in'
        : message.direction === 'out';
      if (sent) {
        sentN += 1;
      } else {
        recv += 1;
      }
    }
    return { recvCount: recv, sentCount: sentN };
  }, [filtered]);

  const rowLimit = MAX_ROWS + extraRows;
  const visible = filtered.length > rowLimit ? filtered.slice(-rowLimit) : filtered;

  /* 收起时也把上面的 Hook 全部跑完：条件 return 放在 useMemo 之后，
     否则「收起 → 展开」会多出一个 Hook，React 直接报 Rendered more hooks。 */
  if (!open) {
    return null;
  }

  const rowKey = (message: WireMessage, index: number): string =>
    `${message.seq ?? `${message.at}-${index}`}`;

  const toggleRow = (key: string) => {
    setExpanded((previous) => {
      const next = new Set(previous);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  return (
    <aside
      className="monitor-drawer"
      /* 宽度走外壳上的 --monitor-w（已按窗口比例收敛），和内容区让出的宽度永远一致 */
      style={{ width: 'var(--monitor-w)' }}
      aria-label="实时消息监控"
    >
      <div className="monitor-resize"
        role="separator"
        aria-orientation="vertical"
        aria-label="左右拖动调整消息监控宽度"
        title="拖动调整宽度"
        onPointerDown={(event) => {
          const startX = event.clientX;
          const startWidth = width;
          event.currentTarget.setPointerCapture(event.pointerId);
          const onMove = (move: PointerEvent) => {
            onWidthChange(Math.max(minWidth, Math.min(maxWidth, Math.round(startWidth - (move.clientX - startX)))));
          };
          const onUp = () => {
            window.removeEventListener('pointermove', onMove);
            window.removeEventListener('pointerup', onUp);
          };
          window.addEventListener('pointermove', onMove);
          window.addEventListener('pointerup', onUp);
        }}
      />
      <div className="monitor-head">
        {/* 只有一行：区域 → 对象 → 关闭。标题行删掉，抽屉自己就是"实时消息监控" */}
        <div className="monitor-target">
          <label className="field monitor-region">
            区域
            <select
              value={activeRegion}
              onChange={(event) => setRegionPick(event.target.value)}
              aria-label="按区域筛选"
            >
              {regionNames.map((name) => <option key={name} value={name}>{name}</option>)}
            </select>
          </label>
          <label className="field field-wide monitor-target-pick">
            查看对象
            <select
              value={activeKey}
              onChange={(event) => setStoredTarget(event.target.value)}
              aria-label="选择要查看的设备或主机"
            >
              {scopedTargets.length === 0 && <option value="">（该区域暂无设备/主机）</option>}
              {scopedTargets.some((target) => target.kind === 'all') && (
                <option value={targetKey(ALL_TARGET)}>全部渠道（收发，含设备与主机两侧）</option>
              )}
              {scopedTargets.some((target) => target.kind === 'device') && (
                <optgroup label="设备">
                  {scopedTargets.filter((target) => target.kind === 'device').map((target) => (
                    <option key={targetKey(target)} value={targetKey(target)}>
                      {target.label}（{target.id}）
                    </option>
                  ))}
                </optgroup>
              )}
              {scopedTargets.some((target) => target.kind === 'host') && (
                <optgroup label="主机">
                  {scopedTargets.filter((target) => target.kind === 'host').map((target) => (
                    <option key={targetKey(target)} value={targetKey(target)}>
                      {target.label}（{target.id}）
                    </option>
                  ))}
                </optgroup>
              )}
            </select>
          </label>
          <button
            type="button"
            className="icon-btn monitor-close"
            onClick={onClose}
            title="收起消息监控"
            aria-label="收起消息监控"
          >
            ×
          </button>
        </div>
      </div>

      <div className="monitor-filters">
        <label className="field">
          方向
          <select value={direction} onChange={(event) => setDirection(event.target.value as DirectionFilter)} aria-label="按方向过滤">
            <option value="all">收发</option>
            <option value="recv">接收</option>
            <option value="sent">发送</option>
          </select>
        </label>
        <label className="field">
          SxFy
          <input value={sf} data-draft-skip onChange={(event) => setSf(event.target.value)} placeholder="如 S6F1" aria-label="按 SxFy 过滤" />
        </label>
        <label className="field field-wide">
          内容
          <input value={keyword} data-draft-skip onChange={(event) => setKeyword(event.target.value)} placeholder="搜报文体" aria-label="按报文内容过滤" />
        </label>
        <div className="monitor-actions">
          <button type="button" onClick={() => setPaused((previous) => !previous)}>{paused ? '继续' : '暂停'}</button>
          <button type="button" onClick={() => setClearAfter(maxAt)} title="清掉当前画面，只显示之后的新报文">清理</button>
          <button type="button" onClick={() => { setDirection('all'); setSf(''); setKeyword(''); setClearAfter(0); }}>重置</button>
          {filtered.length > visible.length && (
            <button type="button" onClick={() => setExtraRows((previous) => previous + MAX_ROWS)} title="多显示更早的报文">载入更早</button>
          )}
        </div>
      </div>

      <div className="monitor-stats">
        <span className="mm-chip recv">接收 {recvCount}</span>
        <span className="mm-chip sent">发送 {sentCount}</span>
        {paused && <span className="badge tone-warn">已暂停</span>}
      </div>

      <div className="monitor-list" role="log" aria-label="实时报文">
        {visible.length === 0 && <p className="hint">当前条件下还没有报文。</p>}
        {visible.slice().reverse().map((message, index) => {
          const isHost = message.perspective === 'host';
          const sent = isHost ? message.direction === 'in' : message.direction === 'out';
          const key = rowKey(message, index);
          const isOpen = expanded.has(key);
          return (
            <div
              className={`mm-row ${sent ? 'sent' : 'recv'}${isOpen ? ' open' : ''}`}
              key={key}
              role="button"
              tabIndex={0}
              aria-expanded={isOpen}
              title={isOpen ? '点击收起报文体' : '点击展开报文体'}
              onClick={() => toggleRow(key)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  toggleRow(key);
                }
              }}
            >
              <div className="mm-row-top">
                <span className="mm-caret" aria-hidden="true">{isOpen ? '▾' : '▸'}</span>
                <span className="mono mm-time">{formatClock(message.at)}</span>
                <span className={`mm-chip ${sent ? 'sent' : 'recv'}`}>{sent ? '发送' : '接收'}</span>
                <span className="mono mm-sf">S{message.stream}F{message.function}{message.wbit ? ' W' : ''}</span>
                <span className="spacer" />
                <span className="mm-cat hint-inline">{CATEGORY_LABEL[categorizeMessage(message.stream, message.function)]}</span>
              </div>
              {isOpen && (
                <div className="mono mm-body">
                  {message.body === '' ? '（空）' : message.body}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </aside>
  );
}
