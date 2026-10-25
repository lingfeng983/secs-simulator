import { useEffect } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { controlStateLabel } from '../panels/SessionsPanel';
import { EventsPanel } from '../panels/EventsPanel';
import { CeidPanel } from '../panels/CeidPanel';
import { AlarmsPanel } from '../panels/AlarmsPanel';
import { SvidPanel } from '../panels/SvidPanel';
import { ConfigPanel } from '../panels/ConfigPanel';
import { SendPanel } from '../panels/SendPanel';
import { HostSimPanel } from '../panels/HostSimPanel';
import { LiveEventList } from '../ui/LiveEventList';
import { DEVICE_PAGES, DEVICE_PAGE_LABELS, type DevicePage } from '../router';

/** 设备子页的一句话说明（顶栏描述用）。 */
const DEVICE_PAGE_DESCRIPTIONS: Record<DevicePage, string> = {
  ceids: '该设备的事件设置:采集事件(CEID)与报告(RPTID)的增删改、压测上报。',
  events: '该设备的事件编排:引用已设置的事件做计划(增删改/启停/触发)与实时上报。',
  alarms: '该设备的报警与终端,可对编辑好的报警做压测风暴。',
  svids: '该设备的状态变量:增删改查、曲线预览与值设定。',
  config: '该设备的配置:分段编辑、每 SVID 值来源与曲线预览。',
  send: '向该设备手动发送 SML,同一条消息可压测发送。',
  hostsim: '主机模拟:FDC Trace 编排与 S1F3 数据采集(本机或远程 SECS)。',
};

/**
 * 单设备页——一个子页一个能力,不聚合。
 *
 * <p>进入设备页就把该会话钉为全局 activeSession——现有面板全部按
 * "当前会话"取数,钉住之后零改动复用。子页:</p>
 * <ul>
 *   <li>事件设置:CEID/报告 CRUD + 压测;事件编排:计划 CRUD + 本设备实时事件;报警:报警与风暴压测;</li>
 *   <li>状态变量:SVID 增删改查 + 曲线预览 + 值设定;配置;</li>
 *   <li>发送:SML 手发 + 消息压测;主机模拟:FDC Trace + S1F3 采集。</li>
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
}: PanelBaseProps & { sessionId: string; tab: DevicePage }): JSX.Element {
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
        {DEVICE_PAGES.map((value) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={tab === value}
            className={tab === value ? 'active' : ''}
            onClick={() => goTo?.(`device-tab:${sessionId}:${value}`)}
          >
            {DEVICE_PAGE_LABELS[value]}
          </button>
        ))}
      </div>

      {tab === 'ceids' && <CeidPanel {...baseProps} />}

      {tab === 'events' && (
        <div className="panel-stack">
          <EventsPanel {...baseProps} />
          <section className="panel-section">
            <div className="section-head">
              <h2>本设备实时事件</h2>
            </div>
            <LiveEventList
              events={deviceEvents}
              emptyText="暂无实时事件;编排触发或手动上报后这里实时滚动。"
            />
          </section>
        </div>
      )}

      {tab === 'alarms' && <AlarmsPanel {...baseProps} />}

      {tab === 'svids' && <SvidPanel {...baseProps} />}

      {tab === 'config' && <ConfigPanel {...baseProps} />}

      {tab === 'send' && <SendPanel {...baseProps} />}

      {tab === 'hostsim' && <HostSimPanel {...baseProps} />}
    </div>
  );
}

export { DEVICE_PAGE_DESCRIPTIONS };
