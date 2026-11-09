import { useEffect, useRef } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { controlStateLabel } from '../../shared/lib/controlState';
import { EventsPanel } from '../panels/EventsPanel';
import { CeidPanel } from '../panels/CeidPanel';
import { AlarmsPanel } from '../panels/AlarmsPanel';
import { SvidPanel } from '../panels/SvidPanel';
import { ConfigPanel } from '../panels/ConfigPanel';
import { ConnectionPanel } from '../panels/ConnectionPanel';
import { StructurePanel } from '../panels/StructurePanel';
import { SendPanel } from '../panels/SendPanel';
import { MessagePanel } from '../panels/MessagePanel';
import { LiveEventList } from '../../shared/ui/LiveEventList';
import { Icon } from '../../shared/ui/Icon';
import { DEVICE_PAGES, DEVICE_PAGE_LABELS, type DevicePage } from '../../app/router';

/** 设备子页的一句话说明（顶栏描述用）。 */
const DEVICE_PAGE_DESCRIPTIONS: Record<DevicePage, string> = {
  ceids: '该设备的采集事件(CEID):定义事件及其绑定的报告,可压测上报。',
  reports: '该设备的报告(RPTID):定义一组变量,供事件携带上报。',
  events: '该设备的事件编排:引用已设置的事件做计划(增删改/启停/触发)与实时上报。',
  alarms: '该设备的报警与终端,可对编辑好的报警做压测风暴。',
  svids: '该设备的状态变量:增删改查、曲线预览与值设定。',
  connection: '该设备的连接参数:会话 id、端口、绑定地址、device-id 与 HSMS 超时。',
  structure: '该设备的结构:设备名/单元/SVID 属性与曲线、组合、实例模板。',
  config: '该设备的配置原文:只保留 JSON 全覆盖编辑,保存逐字节写回。',
  send: '向该设备手动发送 SML,同一条消息可压测发送。',
  messages: '该设备与主机的报文收发:按探测/采集/上报等类别屏蔽,只看当前关心的消息。',
};

/**
 * 单设备页——一个子页一个能力,不聚合。
 *
 * <p>进入设备页就把该会话钉为全局 activeSession——现有面板全部按
 * "当前会话"取数,钉住之后零改动复用。子页:</p>
 * <ul>
 *   <li>事件设置:CEID/报告 CRUD + 压测;事件编排:计划 CRUD + 本设备实时事件;报警:报警与风暴压测;</li>
 *   <li>状态变量:SVID 增删改查 + 曲线预览 + 值设定;配置;</li>
 *   <li>发送:SML 手发 + 消息压测;主机模拟:S6F1 + S1F3 采集。</li>
 * </ul>
 */
/** 控制状态 → 徽标色调。 */
function controlTone(state: string | undefined): 'ok' | 'warn' | 'danger' | 'muted' {
  switch (state) {
    case 'ONLINE':
      return 'ok';
    case 'COMMUNICATING':
      return 'warn';
    case 'OFFLINE':
      return 'danger';
    default:
      return 'muted';
  }
}

export function DevicePage({
  sessionId,
  tab,
  backend,
  notifier,
  preferences,
  updatePreferences,
  goTo,
  liveEvents,
  messages,
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
    messages,
  };

  /** roving tabindex 需要在方向键切换后把焦点交给新的 tab。 */
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);

  /** 方向键 / Home / End 在子页之间移动；Tab 只在"当前页"上停一次。 */
  const onTabKeyDown = (event: ReactKeyboardEvent<HTMLButtonElement>, index: number) => {
    const last = DEVICE_PAGES.length - 1;
    let next = -1;
    if (event.key === 'ArrowRight') {
      next = index === last ? 0 : index + 1;
    } else if (event.key === 'ArrowLeft') {
      next = index === 0 ? last : index - 1;
    } else if (event.key === 'Home') {
      next = 0;
    } else if (event.key === 'End') {
      next = last;
    }
    if (next < 0) {
      return;
    }
    event.preventDefault();
    goTo?.(`device-tab:${sessionId}:${DEVICE_PAGES[next]}`);
    /* 导航会触发重渲染；下一帧再把焦点放到新的 tab 上 */
    window.requestAnimationFrame(() => {
      tabRefs.current[next]?.focus();
    });
  };

  const deviceEvents = (liveEvents ?? []).filter(
    (notice) => notice.type === 's6f11' && notice.sessionId === sessionId,
  );

  /* 会话不存在（被关掉、或手输/收藏了旧地址）时不要继续渲染面板：
     面板按 activeSession 取数，会把"上一台设备"的数据挂在这个不存在的 id 下。 */
  const sessionKnown = backend.sessions.length === 0 || summary !== undefined;
  if (!sessionKnown) {
    return (
      <div className="panel-stack">
        <div className="empty-state">
          <p className="empty-text">会话 {sessionId} 不存在</p>
          <p className="empty-hint">它可能已被关闭。回到工作台重新选一台设备。</p>
          <div className="empty-action">
            <button type="button" onClick={() => goTo?.('workbench')}>
              ← 回工作台
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="panel-stack">
      <div className="device-header">
        <div className="device-identity">
          <span className="device-glyph" aria-hidden="true">
            <Icon name="sessions" size={19} />
          </span>
          <div className="device-headings">
            <h2 className="device-name">
              {summary?.device ?? sessionId}
              <span className={`badge tone-${controlTone(summary?.controlState)}`}>
                {controlStateLabel(summary?.controlState)}
              </span>
            </h2>
            <div className="device-header-sub">
              <span className="mono">{sessionId}</span>
              <span className="sep" aria-hidden="true" />
              <span className="mono">{summary?.state ?? '—'}</span>
              {summary?.units !== undefined && (
                <>
                  <span className="sep" aria-hidden="true" />
                  <span>{summary.units} 个单元</span>
                </>
              )}
              {summary?.lastError ? (
                <>
                  <span className="sep" aria-hidden="true" />
                  <span className="danger">{summary.lastError}</span>
                </>
              ) : null}
            </div>
          </div>
        </div>
        <div className="device-tools">
          <button type="button" onClick={() => goTo?.('workbench')}>
            ← 回工作台
          </button>
        </div>
      </div>

      <div className="device-tabs" role="tablist" aria-label="设备子页">
        {DEVICE_PAGES.map((value, index) => (
          <button
            key={value}
            ref={(element) => {
              tabRefs.current[index] = element;
            }}
            id={`device-tab-${value}`}
            type="button"
            role="tab"
            aria-selected={tab === value}
            aria-controls={`device-panel-${value}`}
            /* roving tabindex：整组 tab 只占一个 Tab 停靠点，组内用方向键移动 */
            tabIndex={tab === value ? 0 : -1}
            className={tab === value ? 'active' : ''}
            title={DEVICE_PAGE_DESCRIPTIONS[value]}
            onClick={() => goTo?.(`device-tab:${sessionId}:${value}`)}
            onKeyDown={(event) => onTabKeyDown(event, index)}
          >
            {DEVICE_PAGE_LABELS[value]}
          </button>
        ))}
      </div>

      {/* 面板按会话重挂：切设备时编辑器/自动打开的配置不能沿用上一台设备的状态 */}
      <div
        className="tab-page"
        role="tabpanel"
        id={`device-panel-${tab}`}
        aria-labelledby={`device-tab-${tab}`}
        tabIndex={0}
      >
      {tab === 'ceids' && <CeidPanel key={sessionId} {...baseProps} mode="events" />}

      {tab === 'reports' && <CeidPanel key={sessionId} {...baseProps} mode="reports" />}

      {tab === 'events' && (
        <div className="panel-stack">
          <EventsPanel key={sessionId} {...baseProps} />
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

      {tab === 'alarms' && <AlarmsPanel key={sessionId} {...baseProps} />}

      {tab === 'svids' && <SvidPanel key={sessionId} {...baseProps} />}

      {tab === 'connection' && <ConnectionPanel key={sessionId} {...baseProps} sessionId={sessionId} />}

      {tab === 'structure' && <StructurePanel key={sessionId} {...baseProps} sessionId={sessionId} />}

      {tab === 'config' && <ConfigPanel key={sessionId} {...baseProps} sessionId={sessionId} />}

      {tab === 'send' && <SendPanel key={sessionId} {...baseProps} />}

      {tab === 'messages' && <MessagePanel key={sessionId} {...baseProps} />}
      </div>
    </div>
  );
}

export { DEVICE_PAGE_DESCRIPTIONS };