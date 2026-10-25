import { useMemo, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { controlStateLabel } from '../panels/SessionsPanel';
import { SessionsPanel } from '../panels/SessionsPanel';

/**
 * 工作台（总览 + 会话管理）。
 *
 * <p>只做两件事:一眼看清所有设备——KPI 行 + 设备卡墙(可按关键字/状态过滤,
 * 点卡片进设备页);以及管理现有设备——会话管理(新建/重载/上报/批量操作)直接嵌在下方。</p>
 */
export function WorkbenchPage({ backend, notifier, preferences, updatePreferences, goTo }: PanelBaseProps): JSX.Element {
  const [filter, setFilter] = useState('');
  const [stateFilter, setStateFilter] = useState<'all' | 'online' | 'offline'>('all');

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
      </div>

      {/* 过滤器:输入框 + 状态分段同一行,上下居中 */}
      <div className="toolbar workbench-filter">
        <input
          type="search"
          className="workbench-filter-input"
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
            ? '还没有设备。在下方「会话管理」从一份配置开一台,或重启后端加 --demo-session 看两台内置演示设备。'
            : '没有匹配过滤条件的设备。'}
        </p>
      ) : (
        <div className="device-grid">
          {cards.map((row) => (
            <button key={row.id} type="button" className="device-card" onClick={() => goTo?.(`device:${row.id}`)}>
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

      {/* 会话管理:开设备/关设备、批量操作、手动上报 CEID */}
      <SessionsPanel
        backend={backend}
        notifier={notifier}
        preferences={preferences}
        updatePreferences={updatePreferences}
        goTo={goTo}
      />
    </div>
  );
}
