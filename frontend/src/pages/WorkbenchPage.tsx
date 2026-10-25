import { useMemo, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { controlStateLabel } from '../panels/SessionsPanel';
import { SessionsPanel } from '../panels/SessionsPanel';
import { MessagesPanel } from '../panels/MessagesPanel';

/**
 * 工作台（schema v6 重设计的需求②）。
 *
 * <p>三层内容,从"一眼全局"到"下钻入口"：</p>
 * <ol>
 *   <li><b>设备卡墙</b>——每台设备一张卡:连接/控制态、消息与事件速率、
 *       活动报警、最近事件;可按关键字/状态过滤;点卡片进设备页;</li>
 *   <li><b>实时事件流</b>——SSE 类型化通知(s6f11),可按设备过滤、暂停;</li>
 *   <li><b>会话管理</b>——嵌入 SessionsPanel,批量新建/上报/重载都在。</li>
 * </ol>
 */
export function WorkbenchPanel({ backend, notifier, preferences, updatePreferences, goTo, streamLabel, liveEvents = [] }: PanelBaseProps): JSX.Element {
  const [filter, setFilter] = useState('');
  const [stateFilter, setStateFilter] = useState<'all' | 'online' | 'offline'>('all');
  const [eventFilter, setEventFilter] = useState('');
  const [showSessions, setShowSessions] = useState(false);

  const rows = backend.overview;

  const cards = useMemo(() => {
    const keyword = filter.trim().toLowerCase();
    return rows.filter((row) => {
      if (keyword !== ''
        && !row.id.toLowerCase().includes(keyword)
        && !row.device.toLowerCase().includes(keyword)
        && !String(row.port).includes(keyword)) {
        return false;
      }
      if (stateFilter === 'online' && !(row.controlState === 'ONLINE')) {
        return false;
      }
      if (stateFilter === 'offline' && row.controlState === 'ONLINE') {
        return false;
      }
      return true;
    });
  }, [rows, filter, stateFilter]);

  const totals = useMemo(() => {
    let msgs = 0;
    let events = 0;
    let alarms = 0;
    let online = 0;
    for (const row of rows) {
      msgs += row.msgsPerMin;
      events += row.s6f11PerMin;
      alarms += row.counters.activeAlarms ?? 0;
      if (row.controlState === 'ONLINE') {
        online += 1;
      }
    }
    return { msgs, events, alarms, online };
  }, [rows]);

  const maxRate = Math.max(1, ...rows.map((row) => row.s6f11PerMin));

  const visibleEvents = useMemo(() => {
    const keyword = eventFilter.trim().toLowerCase();
    return liveEvents
      .filter((notice) => notice.type === 's6f11')
      .filter((notice) => keyword === ''
        || notice.sessionId.toLowerCase().includes(keyword)
        || String(notice.data.ceid ?? '').includes(keyword));
  }, [liveEvents, eventFilter]);

  const openDevice = (sessionId: string) => {
    goTo?.(`device:${sessionId}`);
  };

  return (
    <div className="panel-stack">
      {/* KPI 行 */}
      <div className="kpi-grid">
        <div className="kpi-card">
          <span className="kpi-label">在线设备</span>
          <span className="kpi-value">{totals.online}/{rows.length}</span>
        </div>
        <div className="kpi-card">
          <span className="kpi-label">报文 / 分钟</span>
          <span className="kpi-value">{totals.msgs}</span>
        </div>
        <div className="kpi-card">
          <span className="kpi-label">事件 / 分钟</span>
          <span className="kpi-value">{totals.events}</span>
        </div>
        <div className="kpi-card">
          <span className="kpi-label">活动报警</span>
          <span className={`kpi-value${totals.alarms > 0 ? ' danger' : ''}`}>{totals.alarms}</span>
        </div>
        <div className="kpi-card">
          <span className="kpi-label">事件流</span>
          <span className="kpi-value kpi-value-sm">{streamLabel ?? '-'}</span>
        </div>
      </div>

      {/* 过滤器 */}
      <div className="toolbar">
        <input
          type="search"
          placeholder="按设备 id / 名称 / 端口过滤"
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
          aria-label="设备过滤"
        />
        <div className="segmented" role="tablist">
          {([['all', '全部'], ['online', '在线'], ['offline', '离线']] as const).map(([value, label]) => (
            <button
              key={value}
              type="button"
              className={stateFilter === value ? 'active' : ''}
              onClick={() => setStateFilter(value)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* 设备卡墙 */}
      {cards.length === 0 ? (
        <p className="hint">
          {rows.length === 0
            ? '还没有设备。用「会话管理」从一份配置开一台,或重启后端加 --demo-session 看两台内置演示设备。'
            : '没有匹配过滤条件的设备。'}
        </p>
      ) : (
        <div className="device-grid">
          {cards.map((row) => (
            <button key={row.id} type="button" className="device-card" onClick={() => openDevice(row.id)}>
              <div className="device-card-head">
                <span className="device-card-title">{row.device}</span>
                <span className={`badge tone-${row.controlState === 'ONLINE' ? 'ok' : row.controlState === 'COMMUNICATING' ? 'warn' : 'muted'}`}>
                  {controlStateLabel(row.controlState)}
                </span>
              </div>
              <div className="device-card-sub">
                <span>{row.id}</span>
                <span>:{row.port}</span>
                <span>{row.units} 单元</span>
                {row.hostSim ? <span className="badge tone-info">主机模拟</span> : null}
              </div>
              <div className="device-card-stats">
                <span title="报文/分钟">✉ {row.msgsPerMin}/min</span>
                <span title="事件/分钟">⚡ {row.s6f11PerMin}/min</span>
                <span className={(row.counters.activeAlarms ?? 0) > 0 ? 'danger' : ''} title="活动报警">
                  ⚠ {row.counters.activeAlarms ?? 0}
                </span>
              </div>
              <div className="device-card-bar" aria-hidden>
                <span style={{ width: `${Math.round((row.s6f11PerMin / maxRate) * 100)}%` }} />
              </div>
              <div className="device-card-foot">
                最近事件:{row.lastEventAt > 0 ? new Date(row.lastEventAt).toLocaleTimeString() : '—'}
              </div>
            </button>
          ))}
        </div>
      )}

      {/* 实时事件流 */}
      <section className="panel-section">
        <div className="section-head">
          <h2>实时事件流（S6F11）</h2>
          <input
            type="search"
            placeholder="按设备 / CEID 过滤"
            value={eventFilter}
            onChange={(event) => setEventFilter(event.target.value)}
            aria-label="事件流过滤"
          />
        </div>
        {visibleEvents.length === 0 ? (
          <p className="hint">
            暂无实时事件。事件流来自 SSE 的类型化通知——连上后端、设备上报后这里实时滚动;
            也可以进入设备页看编排与手动触发。
          </p>
        ) : (
          <ul className="live-event-list">
            {visibleEvents.slice(0, 50).map((notice, index) => (
              <li key={`${notice.at}-${index}`}>
                <span className="mono">{new Date(notice.at).toLocaleTimeString()}</span>
                <button type="button" className="link" onClick={() => openDevice(notice.sessionId)}>
                  {notice.sessionId}
                </button>
                <span>CEID {String(notice.data.ceid ?? '-')}</span>
                {typeof notice.data.planId === 'string' && notice.data.planId !== '' && (
                  <span className="hint-inline">{notice.data.planId}</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* 全局报文 */}
      <section className="panel-section">
        <div className="section-head">
          <h2>最近报文</h2>
        </div>
        <MessagesPanel
          backend={backend}
          notifier={notifier}
          preferences={preferences}
          updatePreferences={updatePreferences}
        />
      </section>

      {/* 会话管理(可折叠):批量操作都在这里 */}
      <section className="panel-section">
        <div className="section-head">
          <h2>会话管理</h2>
          <button type="button" onClick={() => setShowSessions((previous) => !previous)}>
            {showSessions ? '收起' : '展开'}
          </button>
        </div>
        {showSessions && (
          <SessionsPanel
            backend={backend}
            notifier={notifier}
            preferences={preferences}
            updatePreferences={updatePreferences}
            goTo={goTo}
          />
        )}
      </section>
    </div>
  );
}
