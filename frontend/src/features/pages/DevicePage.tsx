import { useEffect, useMemo, useState } from 'react';
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
import { InstanceHead, SubTabs, ZoneBar } from '../../shared/ui/zone';
import {
  DEVICE_CONFIG_PAGES,
  DEVICE_CONFIG_PAGE_LABELS,
  DEVICE_RUNTIME_PAGES,
  DEVICE_RUNTIME_PAGE_LABELS,
  type DevicePage,
  type DeviceZone,
} from '../../app/router';

/** 设备子页的一句话说明（顶栏描述用）。 */
export const DEVICE_PAGE_DESCRIPTIONS: Record<DevicePage, string> = {
  overview: '设备定义摘要:名称/模板/自动连接与配置版本。',
  connection: '该设备的连接参数:会话 id、端口、绑定地址、device-id 与 HSMS 超时。',
  structure: '该设备的结构:设备名/单元/SVID 属性与曲线、组合、实例模板。',
  document: '该设备的配置原文:只保留 JSON 全覆盖编辑,保存逐字节写回。',
  constants: '设备常量(ECV):主机用 S2F13/F14 查询、S2F15/F16 写入的调机参数。',
  revisions: '配置版本历史:每次保存留一份快照,可对比与回滚。',
  status: '当前运行的实时状态:链路相位、控制状态与计数。',
  plans: '该设备的事件编排:引用已设置的事件做计划(增删改/启停/触发)与实时上报。',
  catalog: '该设备的事件与报告:运行时临时增删改 CEID/RPTID,手动触发上报。',
  svids: '该设备的状态变量:曲线预览与值设定。',
  messages: '该设备与主机的报文收发:按类别屏蔽,只看当前关心的消息。',
  manual: '手动操作:向该设备发送 SML、压测,以及报警置位/清除。',
};

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

/**
 * 单设备详情页——两段式（设计 §3.2）：实例头 + 配置区 / 运行区。
 * 配置区改「这台设备是什么」（保存后需重载），运行区操作当前运行。
 * 进入设备页就把该会话钉为全局 activeSession——现有面板全部按
 * "当前会话"取数，钉住之后零改动复用。
 */
export function DevicePage({
  sessionId,
  zone,
  page,
  backend,
  notifier,
  preferences,
  updatePreferences,
  goTo,
  liveEvents,
  messages,
}: PanelBaseProps & { sessionId: string; zone: DeviceZone; page: DevicePage }): JSX.Element {
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
          <p className="empty-text">设备 {sessionId} 不存在</p>
          <p className="empty-hint">它可能已被删除。回到工作台重新选一台设备。</p>
          <div className="empty-action">
            <button type="button" onClick={() => goTo?.('workbench')}>
              ← 回工作台
            </button>
          </div>
        </div>
      </div>
    );
  }

  const pages = zone === 'config'
    ? DEVICE_CONFIG_PAGES.map((id) => ({ id, label: DEVICE_CONFIG_PAGE_LABELS[id] }))
    : DEVICE_RUNTIME_PAGES.map((id) => ({ id, label: DEVICE_RUNTIME_PAGE_LABELS[id] }));

  return (
    <div className="panel-stack">
      <InstanceHead
        title={summary?.device ?? sessionId}
        badge={
          <span className={`badge tone-${controlTone(summary?.controlState)}`}>
            {controlStateLabel(summary?.controlState)}
          </span>
        }
        subtitle={
          <>
            <span className="mono">{sessionId}</span> · {summary?.state ?? '—'}
            {summary?.units !== undefined ? ` · ${summary.units} 个单元` : ''}
            {summary?.lastError ? <span className="danger"> · {summary.lastError}</span> : ''}
          </>
        }
        metrics={[
          { label: '会话状态', value: summary?.state ?? '—' },
          { label: '控制状态', value: controlStateLabel(summary?.controlState) },
          { label: '单元数', value: summary?.units ?? '—' },
        ]}
        actions={
          <button type="button" onClick={() => goTo?.('workbench')}>
            ← 工作台
          </button>
        }
      />

      <ZoneBar
        zone={zone}
        onChange={(next) => goTo?.(`device-zone:${sessionId}:${next}`)}
        configNote="▣ 修改的是设备定义;保存后需重载才会进入运行。"
        runtimeNote="◉ 这里的操作只对本次运行有效;重载后按配置恢复。"
      />
      <SubTabs
        pages={pages}
        active={page}
        onSelect={(next) => goTo?.(`device-page:${sessionId}:${zone}:${next}`)}
      />

      {/* 面板按会话重挂：切设备时编辑器/自动打开的配置不能沿用上一台设备的状态 */}
      <div className="tab-page">
        {zone === 'config' && page === 'overview' && (
          <DeviceOverviewPanel {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'config' && page === 'connection' && (
          <ConnectionPanel key={sessionId} {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'config' && page === 'structure' && (
          <StructurePanel key={sessionId} {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'config' && page === 'document' && (
          <ConfigPanel key={sessionId} {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'config' && page === 'constants' && (
          <DeviceConstantsPanel {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'config' && page === 'revisions' && (
          <DeviceRevisionsPanel {...baseProps} sessionId={sessionId} />
        )}

        {zone === 'runtime' && page === 'status' && (
          <DeviceRuntimeStatusPanel {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'runtime' && page === 'plans' && (
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
        {zone === 'runtime' && page === 'catalog' && <CeidPanel key={sessionId} {...baseProps} mode="events" />}
        {zone === 'runtime' && page === 'svids' && <SvidPanel key={sessionId} {...baseProps} />}
        {zone === 'runtime' && page === 'messages' && <MessagePanel key={sessionId} {...baseProps} />}
        {zone === 'runtime' && page === 'manual' && (
          <div className="panel-stack">
            <SendPanel key={sessionId} {...baseProps} />
            <AlarmsPanel key={sessionId + ':alarms'} {...baseProps} />
          </div>
        )}
      </div>
    </div>
  );
}

/** 配置区·概览:设备定义摘要(从配置原文提炼,不重复编辑入口)。 */
function DeviceOverviewPanel({
  sessionId,
  backend,
}: PanelBaseProps & { sessionId: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const config = backend.configList.find((entry) => entry.name === summary?.config);
  return (
    <section className="panel-section">
      <div className="section-head">
        <h2>设备定义摘要</h2>
      </div>
      <table className="effects-table">
        <tbody>
          <tr><th>会话 id</th><td className="mono">{sessionId}</td></tr>
          <tr><th>设备名</th><td>{summary?.device ?? '—'}</td></tr>
          <tr><th>配置文件</th><td className="mono">{summary?.config ?? '—'}</td></tr>
          <tr><th>模板</th><td>{config?.template ?? '—'}</td></tr>
          <tr><th>单元数</th><td>{summary?.units ?? '—'}</td></tr>
          <tr><th>大小 / 修改时间</th><td className="mono">
            {config ? `${config.sizeBytes} B` : '—'}{config ? ` · ${new Date(config.modifiedMs).toLocaleString()}` : ''}
          </td></tr>
        </tbody>
      </table>
      <p className="hint">连接 / 结构 / 常量 / 原文的编辑在配置区的其它子页;保存后需重载使生效。</p>
    </section>
  );
}

/** 配置区·设备常量:从配置原文提炼 ECV 列表(只读快照;S2F15 回写后此处随之更新)。 */
function DeviceConstantsPanel({
  sessionId,
  backend,
}: PanelBaseProps & { sessionId: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const configName = summary?.config ?? '';
  const [text, setText] = useState<string | undefined>(undefined);
  useEffect(() => {
    if (configName === '' || backend.client === null) {
      return;
    }
    let cancelled = false;
    backend.client.configText(configName)
      .then((body) => {
        if (!cancelled) {
          setText(body.text);
        }
      })
      .catch(() => {
        /* 读不到就显示空列表 */
      });
    return () => {
      cancelled = true;
    };
  }, [configName, backend.client]);
  const constants = useMemoConstants(text, configName);
  return (
    <section className="panel-section">
      <div className="section-head">
        <h2>设备常量（ECV）</h2>
      </div>
      {constants.length === 0 ? (
        <p className="hint">配置里没有 equipment.constants 段:主机用 S2F13 查询会得到空列表,S2F15 会回 EAC=1。</p>
      ) : (
        <table className="effects-table">
          <thead>
            <tr><th>ECID</th><th>名称</th><th>类型</th><th>当前值</th><th>单位</th></tr>
          </thead>
          <tbody>
            {constants.map((constant) => (
              <tr key={constant.id}>
                <td className="mono">{constant.id}</td>
                <td>{constant.name}</td>
                <td className="mono">{constant.type}</td>
                <td className="mono">{constant.value}</td>
                <td>{constant.unit || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="hint">主机写 S2F15 后,值写回配置正文(只替换 constants 子树)并升一个版本;此处随正文刷新。</p>
    </section>
  );
}

/** 从配置原文解析 constants(轻量 JSON 解析;原文坏了就显示空)。 */
function useMemoConstants(text: string | undefined, configName: string):
  ReadonlyArray<{ id: number; name: string; type: string; value: number; unit: string }> {
  return useMemo(() => {
    if (text === undefined || configName === '') {
      return [];
    }
    try {
      const root = JSON.parse(text) as {
        equipment?: { constants?: Array<{ id: number; name?: string; type?: string; value?: number; unit?: string }> };
      };
      return (root.equipment?.constants ?? []).map((constant) => ({
        id: constant.id,
        name: constant.name ?? '',
        type: constant.type ?? '',
        value: constant.value ?? 0,
        unit: constant.unit ?? '',
      }));
    } catch {
      return [];
    }
  }, [text, configName]);
}

/** 配置区·版本历史:config_revision 快照(v2 端点)。 */
function DeviceRevisionsPanel({
  sessionId,
  backend,
  notifier,
}: PanelBaseProps & { sessionId: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const configName = summary?.config ?? '';
  const [revisions, setRevisions] = useState<ReadonlyArray<{
    revision: number; sha256: string; createdAt: number; createdBy: string; note: string;
  }>>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (configName === '' || backend.client === null) {
      return;
    }
    let cancelled = false;
    backend.client.configRevisions(configName, 50)
      .then((body) => {
        if (!cancelled) {
          setRevisions(body);
          setError('');
        }
      })
      .catch((e: Error) => {
        if (!cancelled) {
          setError(e.message);
          setRevisions([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [configName, backend.client, notifier]);

  return (
    <section className="panel-section">
      <div className="section-head">
        <h2>配置版本历史</h2>
      </div>
      {error !== '' && <p className="inline-note danger">{error}</p>}
      {revisions.length === 0 && error === '' ? (
        <p className="hint">还没有版本记录;在「配置原文」保存一次就会留一份快照(每设备保留最近 200 版)。</p>
      ) : (
        <table className="effects-table">
          <thead>
            <tr><th>版本</th><th>时间</th><th>操作者</th><th>备注</th><th>SHA-256</th></tr>
          </thead>
          <tbody>
            {revisions.map((revision) => (
              <tr key={revision.revision}>
                <td className="mono">v{revision.revision}</td>
                <td className="mono">{new Date(revision.createdAt).toLocaleString()}</td>
                <td>{revision.createdBy}</td>
                <td>{revision.note || '—'}</td>
                <td className="mono hint-inline">{revision.sha256.slice(0, 12)}…</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/** 运行区·实时状态:链路相位/控制状态/计数。 */
function DeviceRuntimeStatusPanel({
  sessionId,
  backend,
}: PanelBaseProps & { sessionId: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  return (
    <section className="panel-section">
      <div className="section-head">
        <h2>实时状态</h2>
      </div>
      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-value">{summary?.state ?? '—'}</div>
          <div className="kpi-label">会话状态</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{controlStateLabel(summary?.controlState)}</div>
          <div className="kpi-label">控制状态</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{summary?.counters.emitted ?? '—'}</div>
          <div className="kpi-label">累计上报</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{summary?.counters.activeAlarms ?? '—'}</div>
          <div className="kpi-label">活动报警</div>
        </div>
      </div>
      {summary?.lastError ? <p className="inline-note danger">{summary.lastError}</p> : null}
      <p className="hint">链路相位由连接状态实时驱动;设备断开后定义保留,重连后运行态编辑恢复。</p>
    </section>
  );
}
