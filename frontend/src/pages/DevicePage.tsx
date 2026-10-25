import { useEffect } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { controlStateLabel } from '../panels/SessionsPanel';
import { PreviewPanel } from '../panels/PreviewPanel';
import { EventsPanel } from '../panels/EventsPanel';
import { AlarmsPanel } from '../panels/AlarmsPanel';
import { CatalogPanel } from '../panels/CatalogPanel';
import { ConfigPanel } from '../panels/ConfigPanel';
import { SendPanel } from '../panels/SendPanel';
import { HostSimPanel } from '../panels/HostSimPanel';
import { CollectorCard } from '../panels/tools/CollectorCard';
import { BurstCard } from '../panels/tools/BurstCard';
import { DEVICE_TABS, type DeviceTab } from '../router';

/**
 * 单设备全景页（schema v6 重设计的需求③④）。
 *
 * <p>进入即把该会话钉为全局 activeSession——现有面板全部按
 * "当前会话"取数,钉住之后零改动复用。三个 Tab:</p>
 * <ul>
 *   <li>监控:曲线(SVID 多选,当前时间轴+事件标记)、事件编排、报警、目录;</li>
 *   <li>配置:分段编辑器 + 每 SVID 值来源 + 单变量曲线预览;</li>
 *   <li>工具:SML 发送、压测(消息/事件/报警)、主机模拟、S1F3 采集器。</li>
 * </ul>
 */
export function DevicePage({
  sessionId,
  tab,
  backend,
  notifier,
  preferences,
  updatePreferences,
  goTo,
  liveEvents,
  streamLabel,
}: PanelBaseProps & { sessionId: string; tab: DeviceTab }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);

  /* 进入设备页就把全局会话钉到这台设备:所有面板按 activeSession 取数 */
  useEffect(() => {
    if (summary !== undefined && backend.activeSession !== sessionId) {
      backend.setActiveSession(sessionId);
    }
  }, [backend, sessionId, summary]);

  const baseProps: PanelBaseProps = {
    backend,
    notifier,
    preferences,
    updatePreferences,
    goTo,
    liveEvents,
    streamLabel,
  };

  const deviceEvents = (liveEvents ?? []).filter(
    (notice) => notice.type === 's6f11' && notice.sessionId === sessionId,
  );

  return (
    <div className="panel-stack">
      <div className="device-header">
        <div>
          <h2 className="device-name">{summary?.device ?? sessionId}</h2>
          <div className="device-header-sub">
            <span>{sessionId}</span>
            <span className={`badge tone-${summary?.controlState === 'ONLINE' ? 'ok' : summary?.controlState === 'COMMUNICATING' ? 'warn' : 'muted'}`}>
              {controlStateLabel(summary?.controlState)}
            </span>
            <span className="hint-inline">{summary?.state ?? ''}</span>
            {summary?.lastError ? <span className="danger">{summary.lastError}</span> : null}
          </div>
        </div>
        <button type="button" onClick={() => goTo?.('workbench')}>
          ← 回工作台
        </button>
      </div>

      <div className="segmented device-tabs" role="tablist">
        {DEVICE_TABS.map((value) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={tab === value}
            className={tab === value ? 'active' : ''}
            onClick={() => goTo?.(`device-tab:${sessionId}:${value}`)}
          >
            {value === 'monitor' ? '监控' : value === 'config' ? '配置' : '工具'}
          </button>
        ))}
      </div>

      {tab === 'monitor' && (
        <div className="panel-stack">
          <PreviewPanel {...baseProps} />
          <div className="two-col">
            <EventsPanel {...baseProps} />
            <AlarmsPanel {...baseProps} />
          </div>
          <details className="collapse">
            <summary>设备目录（SVID / RPTID / CEID 清册）</summary>
            <CatalogPanel {...baseProps} />
          </details>
          <section className="panel-section">
            <div className="section-head">
              <h2>本设备实时事件</h2>
            </div>
            {deviceEvents.length === 0 ? (
              <p className="hint">暂无实时事件;编排触发或手动上报后这里实时滚动。</p>
            ) : (
              <ul className="live-event-list">
                {deviceEvents.slice(0, 30).map((notice, index) => (
                  <li key={`${notice.at}-${index}`}>
                    <span className="mono">{new Date(notice.at).toLocaleTimeString()}</span>
                    <span>CEID {String(notice.data.ceid ?? '-')}</span>
                    {typeof notice.data.planId === 'string' && notice.data.planId !== '' && (
                      <span className="hint-inline">{notice.data.planId}</span>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}

      {tab === 'config' && <ConfigPanel {...baseProps} />}

      {tab === 'tools' && (
        <div className="panel-stack">
          <SendPanel {...baseProps} />
          <BurstCard {...baseProps} sessionId={sessionId} />
          <HostSimPanel {...baseProps} />
          <CollectorCard {...baseProps} sessionId={sessionId} />
        </div>
      )}
    </div>
  );
}
