import { useMemo } from 'react';
import { Panel, StatCard } from '../ui/Panel';
import { DataTable, type Column } from '../ui/DataTable';
import { EmptyState } from '../ui/EmptyState';
import { Icon } from '../ui/Icon';
import { formatClock, formatCount, formatDuration } from '../lib/format';
import type { SessionSummary } from '../api';
import { controlStateLabel } from './SessionsPanel';
import type { PanelBaseProps } from './types';

/** 会话控制状态 → 徽标色调。 */
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

/**
 * 总览工作台。
 *
 * <p>打开应用第一眼看到的页面：六个 KPI 卡回答「现在健不健康」，
 * 快捷操作覆盖最常用的四个动作（上报 / 发送 / 新建 / 刷新），
 * 下面两栏是最近报文与事件流——不用切面板就能确认系统在动。</p>
 */
export function OverviewPanel({ backend, goTo, streamState, streamLabel }: PanelBaseProps): JSX.Element {
  const { sessions, alarms, messages, messageTotal, log, health, eventPlans, hostSimOpen, hostSimTarget, activeSession, ceid } = backend;

  const online = sessions.filter((item) => item.controlState === 'ONLINE').length;
  const enabledPlans = eventPlans.filter((plan) => plan.enabled).length;
  const recentMessages = useMemo(() => messages.slice(-7).reverse(), [messages]);
  const recentLog = useMemo(() => log.slice(-9).reverse(), [log]);

  const streamTone =
    streamState === 'connected' ? 'ok' : streamState === 'reconnecting' ? 'warn' : streamState === undefined || streamState === 'idle' ? undefined : 'danger';

  const sessionColumns: Column<SessionSummary>[] = [
    { key: 'id', header: '会话', sortValue: (row) => row.id, render: (row) => <span className="mono">{row.id}</span> },
    {
      key: 'control',
      header: '控制',
      sortValue: (row) => row.controlState ?? '',
      render: (row) => <span className={`badge badge-${controlTone(row.controlState)}`}>{controlStateLabel(row.controlState)}</span>,
    },
    { key: 'device', header: '设备', sortValue: (row) => row.device },
    {
      key: 'emitted',
      header: '已上报',
      align: 'right',
      sortValue: (row) => row.counters.emitted ?? 0,
      render: (row) => formatCount(row.counters.emitted),
    },
  ];

  return (
    <div className="ov-page" id="panel-overview">
      <div className="ov-kpis">
        <StatCard label="在线会话" value={`${online} / ${sessions.length}`} hint={sessions.length === 0 ? '还没有会话' : '控制状态 ONLINE'} tone={online > 0 ? 'ok' : undefined} />
        <StatCard label="活动报警" value={alarms.length} hint={alarms.length > 0 ? '需要处理' : '无置位报警'} tone={alarms.length > 0 ? 'danger' : 'ok'} />
        <StatCard label="报文总数" value={formatCount(messageTotal)} hint="服务端累计" />
        <StatCard label="事件流" value={streamLabel ?? '-'} hint="SSE 推送通道" tone={streamTone} />
        <StatCard label="事件计划" value={`${enabledPlans} / ${eventPlans.length}`} hint="启用 / 总数" />
        <StatCard
          label="主机模拟"
          value={hostSimOpen ? '已连接' : '未连接'}
          hint={hostSimOpen && hostSimTarget !== '' ? hostSimTarget : activeSession === '' ? '未选会话' : `会话 ${activeSession}`}
          tone={hostSimOpen ? 'ok' : undefined}
        />
      </div>

      <div className="ov-grid">
        <section className="panel ov-card ov-quick">
          <div className="panel-head-lite">
            <h3 className="ov-card-title">快捷操作</h3>
            <span className="ov-card-meta mono">
              {health === null ? '后端未连接' : `v${health.version} · ${health.mode} · 运行 ${formatDuration(health.uptimeMs ?? 0)}`}
            </span>
          </div>
          <div className="quick-actions">
            <button
              type="button"
              className="primary"
              disabled={activeSession === ''}
              title={activeSession === '' ? '先在顶栏选择一个会话' : `对 ${activeSession} 触发一次 S6F11（CEID ${ceid}）`}
              onClick={() => void backend.trigger(activeSession, ceid)}
            >
              <Icon name="events" size={15} /> 上报 CEID {ceid}
            </button>
            <button type="button" onClick={() => goTo?.('send')} title="向当前会话发一条 SML">
              <Icon name="send" size={15} /> 手动发送
            </button>
            <button type="button" onClick={() => goTo?.('sessions')} title="新建 / 批量新建设备会话">
              <Icon name="plus" size={15} /> 新建会话
            </button>
            <button type="button" onClick={() => goTo?.('preview')} title="查看 SVID 曲线">
              <Icon name="preview" size={15} /> 曲线预览
            </button>
            <button type="button" onClick={() => void backend.refreshAll()} disabled={backend.refreshing}>
              <Icon name="refresh" size={15} /> {backend.refreshing ? '刷新中…' : '刷新全部'}
            </button>
            <button type="button" onClick={() => void backend.loadHistory()} title="从服务端载入报文历史">
              <Icon name="messages" size={15} /> 载入报文历史
            </button>
          </div>
          <p className="hint">
            当前操作会话：<span className="mono">{activeSession === '' ? '（无）' : activeSession}</span>
            ；CEID 可在「设备目录」里按名称挑选填入。
          </p>
        </section>

        <section className="panel ov-card ov-sessions">
          <div className="panel-head-lite">
            <h3 className="ov-card-title">会话一览</h3>
            <button type="button" className="link-button" onClick={() => goTo?.('sessions')}>
              全部会话 →
            </button>
          </div>
          <DataTable
            columns={sessionColumns}
            rows={sessions}
            rowKey={(row) => row.id}
            emptyText="暂无会话（用 --demo-session / --config 启动，或到「会话」页新建）"
            defaultSort={{ key: 'id', direction: 'asc' }}
          />
        </section>

        <section className="panel ov-card ov-messages">
          <div className="panel-head-lite">
            <h3 className="ov-card-title">最近报文</h3>
            <button type="button" className="link-button" onClick={() => goTo?.('messages')}>
              报文面板 →
            </button>
          </div>
          {recentMessages.length === 0 ? (
            <EmptyState text="还没有报文" hint="触发一次上报或点「载入报文历史」" />
          ) : (
            <ul className="mini-feed">
              {recentMessages.map((entry) => (
                <li key={entry.key} className="mini-feed-row">
                  <span className={`feed-dir ${entry.msg.direction}`} title={entry.msg.direction === 'in' ? '设备 → 主机' : '主机 → 设备'}>
                    <Icon name={entry.msg.direction === 'in' ? 'arrowIn' : 'arrowOut'} size={13} />
                  </span>
                  <span className="mono feed-sxfy">
                    S{entry.msg.stream}F{entry.msg.function}
                    {entry.msg.wbit ? ' W' : ''}
                  </span>
                  <span className="mono feed-session">{entry.msg.sessionId}</span>
                  <span className="feed-body">{entry.msg.body.split('\n')[0] || '（无 body）'}</span>
                  <span className="feed-time">{formatClock(entry.msg.at)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="panel ov-card ov-log">
          <div className="panel-head-lite">
            <h3 className="ov-card-title">事件流动态</h3>
            <button type="button" className="link-button" onClick={() => goTo?.('console')}>
              事件流面板 →
            </button>
          </div>
          {recentLog.length === 0 ? (
            <EmptyState text="事件流还没有输出" hint="连接建立、重连、暂停都会记在这里" />
          ) : (
            <ul className="mini-feed">
              {recentLog.map((line, index) => (
                <li key={`${line.at}-${index}`} className="mini-feed-row">
                  <span className="feed-time">{line.at}</span>
                  <span className="feed-log-text">{line.text}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
