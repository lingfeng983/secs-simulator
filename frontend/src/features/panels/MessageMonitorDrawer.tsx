import { useEffect, useMemo, useRef, useState } from 'react';
import type { WireMessage } from '../../api';
import { categorizeMessage, MESSAGE_CATEGORIES } from '../../shared/lib/messageFilter';
import { formatClock } from '../../shared/lib/format';
import { usePersistentState } from '../../state/usePersistentState';

/** 一次最多挂多少行 DOM；全量缓冲 5000 条，全渲染会掉帧。 */
const MAX_ROWS = 400;

const CATEGORY_LABEL: Record<string, string> = Object.fromEntries(
  MESSAGE_CATEGORIES.map((category) => [category.id, category.label]),
);

export interface MonitorScope {
  kind: 'device' | 'host' | 'none';
  id: string;
}

/** 抽屉里可切换的监控对象：一台设备或一台主机。 */
export interface MonitorTarget {
  kind: 'device' | 'host';
  id: string;
  /** 展示名（设备名 / 主机名）。 */
  label: string;
}

export interface MessageMonitorDrawerProps {
  open: boolean;
  onClose: () => void;
  messages: readonly WireMessage[];
  /** 当前路由指向的设备/主机：打开抽屉时默认看它。 */
  scope: MonitorScope;
  /** 全部可切换对象（设备 + 主机）。 */
  targets: readonly MonitorTarget[];
  /** 宽度由 App 持有：抽屉本体与 main-col 的让位必须用同一个值，不能各存一份 localStorage。 */
  width: number;
  onWidthChange: (width: number) => void;
}

type DirectionFilter = 'all' | 'recv' | 'sent';

function targetKey(target: { kind: 'device' | 'host'; id: string }): string {
  return `${target.kind}:${target.id}`;
}

/**
 * 右侧「实时消息监控」抽屉。
 *
 * <p>同一时刻只看一台设备或一台主机（下拉切换）——把多台设备的报文混在
 * 一条流里，S1F1 与 S6F11 交叉出现，根本分不清是谁在跟谁说话。每行只显示
 * 报文头（时间/方向/SxFy/归属），报文体默认折叠，点行才展开。</p>
 *
 * <p>主机报文的 in/out 与设备报文含义相反，这里按每条报文自己的视角换算，
 * 接收/发送用颜色与徽标区分。</p>
 */
export function MessageMonitorDrawer({
  open,
  onClose,
  messages,
  scope,
  targets,
  width,
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
  const currentKey = scope.kind === 'none' ? '' : `${scope.kind}:${scope.id}`;

  /* 切到另一台设备/主机时跟随当前路由；在同一页里手动选别的对象则保持选择。 */
  useEffect(() => {
    if (currentKey !== '') {
      setStoredTarget(currentKey);
    }
  }, [currentKey, setStoredTarget]);

  const available = useMemo(
    () => new Set(targets.map((target) => targetKey(target))),
    [targets],
  );
  const activeKey = available.has(storedTarget)
    ? storedTarget
    : available.has(currentKey)
      ? currentKey
      : (targets.length > 0 ? targetKey(targets[0]) : '');
  const activeTarget = targets.find((target) => targetKey(target) === activeKey) ?? null;

  /* 暂停时冻结一份快照，不跟着 SSE 继续滚 */
  if (!paused) {
    frozenRef.current = messages;
  }
  const source = paused ? frozenRef.current : messages;
  const maxAt = source.length > 0 ? Math.max(...source.map((message) => message.at)) : 0;

  const filtered = useMemo(() => source.filter((message) => {
    if (message.at <= clearAfter) {
      return false;
    }
    /* 一次只看一个对象：设备报文按 sessionId 认，主机报文按 hostId 认 */
    if (activeTarget !== null) {
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
  }), [source, clearAfter, activeTarget, direction, sf, keyword]);

  /* 收起时也把上面的 Hook 全部跑完：条件 return 放在 useMemo 之后，
     否则「收起 → 展开」会多出一个 Hook，React 直接报 Rendered more hooks。 */
  if (!open) {
    return null;
  }

  const visible = filtered.length > MAX_ROWS ? filtered.slice(-MAX_ROWS) : filtered;
  let recvCount = 0;
  let sentCount = 0;
  for (const message of filtered) {
    const sent = message.perspective === 'host'
      ? message.direction === 'in'
      : message.direction === 'out';
    if (sent) {
      sentCount += 1;
    } else {
      recvCount += 1;
    }
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
      style={{ width }}
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
            onWidthChange(Math.max(300, Math.min(720, Math.round(startWidth - (move.clientX - startX)))));
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
        <div className="monitor-head-line">
          <h2>实时消息监控</h2>
          <span className="spacer" />
          <button type="button" className="icon-btn" onClick={onClose} title="收起消息监控" aria-label="收起消息监控">×</button>
        </div>
        <div className="monitor-target">
          <label className="field field-wide">
            查看对象
            <select
              value={activeKey}
              onChange={(event) => setStoredTarget(event.target.value)}
              aria-label="选择要查看的设备或主机"
            >
              {targets.length === 0 && <option value="">（暂无设备/主机）</option>}
              {targets.some((target) => target.kind === 'device') && (
                <optgroup label="设备">
                  {targets.filter((target) => target.kind === 'device').map((target) => (
                    <option key={targetKey(target)} value={targetKey(target)}>
                      {target.label}（{target.id}）
                    </option>
                  ))}
                </optgroup>
              )}
              {targets.some((target) => target.kind === 'host') && (
                <optgroup label="主机">
                  {targets.filter((target) => target.kind === 'host').map((target) => (
                    <option key={targetKey(target)} value={targetKey(target)}>
                      {target.label}（{target.id}）
                    </option>
                  ))}
                </optgroup>
              )}
            </select>
          </label>
        </div>
      </div>

      <div className="monitor-filters">
        <label className="field">
          方向
          <select value={direction} onChange={(event) => setDirection(event.target.value as DirectionFilter)} aria-label="按方向过滤">
            <option value="all">收发都看</option>
            <option value="recv">只看接收</option>
            <option value="sent">只看发送</option>
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
