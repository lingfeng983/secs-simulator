import { useMemo } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { DeviceOpsPanel } from '../panels/DeviceOpsPanel';

/**
 * 工作台（总览 + 设备管理）。
 *
 * <p>两段:上头一条"仪表读数"(在线/报文/事件/报警,全部会话合计),
 * 下面一块"设备与会话"——一行一台设备,读数、勾选、生命周期操作都在这一行上。
 * 原来的"设备总览"和"会话"两张表合并进这一块,同一台设备不再出现两次。</p>
 */
export function WorkbenchPage({ backend, notifier, preferences, updatePreferences, goTo }: PanelBaseProps): JSX.Element {
  const rows = backend.overview;

  const totals = useMemo(() => {
    let msgs = 0;
    let events = 0;
    let alarms = 0;
    let online = 0;
    for (const row of rows) {
      msgs += row.msgsPerMin;
      events += row.s6f11PerMin;
      alarms += row.counters.activeAlarms ?? 0;
      /* 严格 GEM：只有 ONLINE 才算在线。COMMUNICATING 是"连着但没上线"，
         此时设备一条 S6F11 都发不出去，算进在线会让读数自欺欺人。 */
      if (row.controlState === 'ONLINE') {
        online += 1;
      }
    }
    return { msgs, events, alarms, online };
  }, [rows]);

  const offline = rows.length - totals.online;

  return (
    <div className="panel-stack">
      {/* 仪表读数:一眼看完整个模拟器的健康度 */}
      <div className="kpi-grid">
        <div className={`kpi-card ${totals.online > 0 ? 'tone-ok' : ''}`}>
          <div className="kpi-text">
            <span className="kpi-label">在线设备</span>
            <span className="kpi-hint">{offline > 0 ? `${offline} 台离线` : '全部在线'}</span>
          </div>
          <span className="kpi-value">
            {totals.online}
            <span className="unit">/ {rows.length}</span>
          </span>
        </div>
        <div className="kpi-card tone-info">
          <div className="kpi-text">
            <span className="kpi-label">报文 / 分钟</span>
            <span className="kpi-hint">全部会话合计</span>
          </div>
          <span className="kpi-value">{totals.msgs}</span>
        </div>
        <div className="kpi-card">
          <div className="kpi-text">
            <span className="kpi-label">事件 / 分钟</span>
            <span className="kpi-hint">S6F11 上报速率</span>
          </div>
          <span className="kpi-value">{totals.events}</span>
        </div>
        <div className={`kpi-card ${totals.alarms > 0 ? 'tone-danger' : ''}`}>
          <div className="kpi-text">
            <span className="kpi-label">活动报警</span>
            <span className="kpi-hint">{totals.alarms > 0 ? '需要处理' : '无未处理报警'}</span>
          </div>
          <span className={`kpi-value${totals.alarms > 0 ? ' danger' : ''}`}>{totals.alarms}</span>
        </div>
      </div>

      {/* 设备与会话:一张表读完,勾选后批量操作 */}
      <DeviceOpsPanel
        backend={backend}
        notifier={notifier}
        preferences={preferences}
        updatePreferences={updatePreferences}
        goTo={goTo}
      />
    </div>
  );
}
