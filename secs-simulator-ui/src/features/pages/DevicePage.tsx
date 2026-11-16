import { useEffect, useLayoutEffect, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { deviceStatus } from '../../shared/lib/deviceStatus';
import { EventsPanel } from '../panels/EventsPanel';
import { AlarmsPanel } from '../panels/AlarmsPanel';
import { ConfigPanel } from '../panels/ConfigPanel';
import { ConnectionPanel } from '../panels/ConnectionPanel';
import { StructurePanel } from '../panels/StructurePanel';
import { SendPanel } from '../panels/SendPanel';
import { ConstantsConfigPanel } from '../panels/ConstantsConfigPanel';
import { SvidConfigPanel } from '../panels/SvidConfigPanel';
import { ContextConfigPanel } from '../panels/ContextConfigPanel';
import { CeidReportConfigPanel } from '../panels/CeidReportConfigPanel';
import { PlanConfigPanel } from '../panels/PlanConfigPanel';
import { CyclePanel } from '../panels/CyclePanel';
import { EndpointTag, SubTabs, ZoneSwitch } from '../../shared/ui/zone';
import { Panel } from '../../shared/ui/Panel';
import { useDeviceEndpoint } from '../../state/deviceEndpoint';
import {
  DEVICE_CONFIG_PAGES,
  DEVICE_CONFIG_PAGE_LABELS,
  DEVICE_CONFIG_PAGE_NOTES,
  DEVICE_RUNTIME_PAGES,
  DEVICE_RUNTIME_PAGE_LABELS,
  DEVICE_RUNTIME_PAGE_NOTES,
  type DevicePage,
  type DeviceZone,
} from '../../app/router';

/**
 * 单设备详情页——两段式（设计 §3.2）：一条操作栏 + 配置区 / 运行区。
 * 配置区改「这台设备是什么」（保存后需重载），运行区操作当前运行。
 * 页面不按设备状态分家：没启动的设备同样有配置区与运行区两套页，
 * 只是运行区的数据要等启动之后才有。
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
  /* 数据库里还没启动的设备没有会话行：路由 key 允许是配置文件名（带或不带 .json）。
     只要配置库里找得到，这台设备的页面就照常展示——不卡设备状态。 */
  const configOnly = summary === undefined
    ? (backend.configList.find((entry) => entry.name === sessionId || entry.name === `${sessionId}.json`) ?? null)
    : null;
  const isConfigOnly = configOnly !== null;
  const configOnlyName = configOnly?.name ?? '';
  const configName = summary?.config ?? configOnlyName;
  const [busy, setBusy] = useState(false);
  /* 监听端点：配置 session 段的 绑定地址 / 端口 / Device ID。
     端口先用总览里的数兜底，配置读回来后再补上地址与 Device ID。 */
  const endpoint = useDeviceEndpoint(
    backend,
    configName,
    backend.overview.find((row) => row.id === sessionId)?.port,
  );

  /* 四态判据统一走 deviceStatus：设备页头、侧栏、设备清单必须说同一句话 */
  const status = isConfigOnly ? deviceStatus({ state: 'CLOSED' }) : deviceStatus(summary ?? {});

  const startDevice = () => {
    setBusy(true);
    /* 未启动的库内设备：「启动」= 按配置创建会话（与设备清单的「启动」同一动作） */
    const action = isConfigOnly
      ? backend.createSession(configOnlyName)
      : backend.connectSession(sessionId);
    void action.finally(() => setBusy(false));
  };
  const stopDevice = () => {
    setBusy(true);
    void backend.closeSession(sessionId).finally(() => setBusy(false));
  };
  /* 上线/下线只动 GEM 控制状态，不动链路：等价于主机发 S1F17 / S1F15 */
  const setOnline = (online: boolean) => {
    setBusy(true);
    void backend.setDeviceOnline(sessionId, online).finally(() => setBusy(false));
  };

  /* 进入设备页就把全局会话钉到这台设备：所有面板按 activeSession 取数。
     必须是 layout effect —— 面板的写操作闭包在 hook 创建时就捕获了当时的
     activeSession，用 useEffect 的话会先画出一帧（还指向上一台设备），
     这一帧里用户如果正好点「删除 SVID / 删除报告」，动的就是 A 设备的数据。 */
  const setActiveSession = backend.setActiveSession;
  const activeSession = backend.activeSession;
  useLayoutEffect(() => {
    if (summary !== undefined && activeSession !== sessionId) {
      setActiveSession(sessionId);
    }
  }, [activeSession, setActiveSession, sessionId, summary]);

  const baseProps: PanelBaseProps = {
    backend,
    notifier,
    preferences,
    updatePreferences,
    goTo,
    liveEvents,
    messages,
  };

  /* 会话与配置库里都找不到（被删掉、或手输/收藏了旧地址）时不要继续渲染面板：
     面板按 activeSession 取数，会把"上一台设备"的数据挂在这个不存在的 id 下。 */
  const sessionKnown = summary !== undefined || isConfigOnly || backend.sessions.length === 0;
  if (!sessionKnown) {
    return (
      <div className="panel-stack">
        <div className="empty-state">
          <p className="empty-text">设备 {sessionId} 不存在</p>
          <p className="empty-hint">它可能已被删除。回到设备清单重新选一台设备。</p>
          <div className="empty-action">
            <button type="button" onClick={() => goTo?.('workbench')}>
              ← 设备清单
            </button>
          </div>
        </div>
      </div>
    );
  }

  const pages = zone === 'config'
    ? DEVICE_CONFIG_PAGES.map((id) => ({ id, label: DEVICE_CONFIG_PAGE_LABELS[id], note: DEVICE_CONFIG_PAGE_NOTES[id] }))
    : DEVICE_RUNTIME_PAGES.map((id) => ({ id, label: DEVICE_RUNTIME_PAGE_LABELS[id], note: DEVICE_RUNTIME_PAGE_NOTES[id] }));
  /* 标题行不展示了，设备名仍要能被读屏与自动化认出来：挂在页面根节点的 aria-label 上 */
  const deviceLabel = summary?.device
    ?? (isConfigOnly ? configOnlyName : sessionId).replace(/\.json$/i, '');

  return (
    <div className="panel-stack device-page" role="region" aria-label={`设备 ${deviceLabel}`} data-device={deviceLabel}>
      {/* 标题行已删：设备名在页签与侧栏里都有，状态在设备清单里看。
          这一行只留两件事——切配置区/运行区、对这台设备动手。 */}
      <div className="detail-bar">
        <ZoneSwitch
          zone={zone}
          onChange={(next) => goTo?.(`device-zone:${sessionId}:${next}`)}
          configNote="▣ 修改的是设备定义;保存后需重启设备才会进入运行。"
          runtimeNote="◉ 这里的操作只对本次运行有效;重启后按配置恢复。"
        />
        {endpoint !== null && (
          <EndpointTag
            label="监听"
            host={endpoint.host}
            port={endpoint.port}
            deviceId={endpoint.deviceId}
            hint="监听地址：本机在哪个地址/端口等主机连进来，以及本设备的 Device ID（改配置后需重启设备生效）"
          />
        )}
        <span className="spacer" aria-hidden="true" />
        <div className="detail-actions">
          {status === 'stopped' ? (
            <button
              type="button"
              className="primary"
              disabled={busy}
              onClick={startDevice}
              title={isConfigOnly
                ? '按已保存的设备配置创建会话并启动端口监听'
                : '启动端口监听：主机随后可以连上来'}
            >
              启动设备
            </button>
          ) : (
            <button
              type="button"
              className="danger"
              disabled={busy}
              onClick={stopDevice}
              title="停止端口监听：链路断开、端口释放，设备仍保留在设备清单里"
            >
              停止设备
            </button>
          )}
          {status === 'offline' && (
            <button
              type="button"
              className="primary"
              disabled={busy}
              onClick={() => setOnline(true)}
              title="设备上线（等价主机发 S1F17）：上线后事件才按规则上报"
            >
              上线
            </button>
          )}
          {status === 'online' && (
            <button
              type="button"
              disabled={busy}
              onClick={() => setOnline(false)}
              title="设备下线（等价主机发 S1F15）：主动上报立即停止"
            >
              下线
            </button>
          )}
        </div>
      </div>

      <SubTabs
        pages={pages}
        active={page}
        onSelect={(next) => goTo?.(`device-page:${sessionId}:${zone}:${next}`)}
      />

      {/* 面板按会话重挂：切设备时编辑器/自动打开的配置不能沿用上一台设备的状态 */}
      <div className="tab-page">
        {zone === 'config' && page === 'overview' && (
          <DeviceOverviewPanel {...baseProps} sessionId={sessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'connection' && (
          <ConnectionPanel key={sessionId} {...baseProps} sessionId={sessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'structure' && (
          <StructurePanel key={sessionId} {...baseProps} sessionId={sessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'svids' && (
          <SvidConfigPanel key={sessionId} {...baseProps} sessionId={sessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'context' && (
          <ContextConfigPanel key={sessionId} {...baseProps} sessionId={sessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'events' && (
          <CeidReportConfigPanel key={sessionId} {...baseProps} sessionId={sessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'cycle' && (
          <CyclePanel key={sessionId} {...baseProps} sessionId={sessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'plans' && (
          <PlanConfigPanel key={sessionId} {...baseProps} sessionId={sessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'document' && (
          <ConfigPanel key={sessionId} {...baseProps} sessionId={sessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'constants' && (
          <ConstantsConfigPanel key={sessionId} {...baseProps} sessionId={sessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'revisions' && (
          <DeviceRevisionsPanel {...baseProps} sessionId={sessionId} configName={configName} />
        )}

        {/* 未启动的设备照样有运行区这几页：页面骨架不随状态变，
            只是运行数据要等设备启动后才有——所以放在页面内容区里说清楚，
            而不是把整个运行区换成一个"设备未启动"的独立页面。 */}
        {zone === 'runtime' && isConfigOnly && (
          <div className="empty-zone" role="status">
            <strong>运行区 · {pages.find((item) => item.id === page)?.label ?? '运行'}</strong>
            <div>这台设备还没有启动，运行数据要等它启动后才产生；配置区的定义随时可改。</div>
            <div style={{ marginTop: 10 }}>
              <button type="button" className="primary" disabled={busy} onClick={startDevice}>
                启动设备
              </button>
            </div>
          </div>
        )}
        {zone === 'runtime' && !isConfigOnly && page === 'plans' && (
          <EventsPanel key={sessionId} {...baseProps} />
        )}
        {zone === 'runtime' && !isConfigOnly && page === 'manual' && (
          <div className="panel-stack">
            <SendPanel key={sessionId} {...baseProps} />
            <AlarmsPanel key={sessionId + ':alarms'} {...baseProps} />
          </div>
        )}
      </div>
    </div>
  );
}

/** 配置区·概览:与其它配置子页一样用 Panel 外壳，只放这台设备的定义摘要。 */
function DeviceOverviewPanel({
  sessionId,
  configName,
  backend,
}: PanelBaseProps & { sessionId: string; configName: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const config = backend.configList.find((entry) => entry.name === configName);
  /* 未启动的库内设备没有会话行：设备名退回配置文件名（去 .json） */
  const deviceLabel = summary?.device ?? configName.replace(/\.json$/i, '');
  return (
    <Panel id="panel-overview" title="概览" headless>
      <table className="effects-table">
        <tbody>
          <tr><th>会话 id</th><td className="mono">{summary?.id ?? '—（未启动）'}</td></tr>
          <tr><th>设备名</th><td>{deviceLabel}</td></tr>
          <tr><th>配置文件</th><td className="mono">{configName || '—'}</td></tr>
          <tr><th>模板</th><td>{config?.template ?? '—'}</td></tr>
          <tr><th>单元数</th><td>{summary?.units ?? '—'}</td></tr>
          <tr><th>大小 / 修改时间</th><td className="mono">
            {config ? `${config.sizeBytes} B` : '—'}{config ? ` · ${new Date(config.modifiedMs).toLocaleString()}` : ''}
          </td></tr>
        </tbody>
      </table>
    </Panel>
  );
}

/** 配置区·版本历史:config_revision 快照(v2 端点)。 */
function DeviceRevisionsPanel({
  sessionId,
  configName,
  backend,
  notifier,
}: PanelBaseProps & { sessionId: string; configName?: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const name = summary?.config ?? configName ?? '';
  const [revisions, setRevisions] = useState<ReadonlyArray<{
    revision: number; sha256: string; createdAt: number; createdBy: string; note: string;
  }>>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (name === '' || backend.client === null) {
      return;
    }
    let cancelled = false;
    backend.client.configRevisions(name, 50)
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
  }, [name, backend.client, notifier]);

  return (
    <section className="panel-section">
      <div className="section-head">
        <h2>配置版本历史</h2>
      </div>
      {error !== '' && <p className="inline-note danger">{error}</p>}
      {revisions.length === 0 && error === '' ? (
        <p className="hint">还没有版本记录;在「配置原文」保存一次就会留一个版本(每设备保留最近 200 版)。</p>
      ) : (
        <table className="effects-table">
          <thead>
            <tr>
              <th>版本</th><th>时间</th><th>操作者</th><th>备注</th>
              <th title="这次保存内容的校验值：两次保存的值相同，说明文件逐字节一致">校验值</th>
            </tr>
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

/** 运行区·实时状态已下线：三态徽标、单元数与累计上报都收进实例头。 */
