import { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import type { BackendController } from '../../state/useBackend';
import { deviceStatus, DEVICE_STATUS_LABEL } from '../../shared/lib/deviceStatus';
import { diffLines, foldUnchanged } from '../../shared/lib/textDiff';
import { isJsonObject, numberField, parseConfigDocument, type JsonObject } from '../editors/templateConfig';
import { useDraftSnapshot } from '../../state/configDraftStore';
import { EventsPanel } from '../panels/EventsPanel';
import { CycleRuntimePanel } from '../panels/CycleRuntimePanel';
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
import { useDirtyPages } from '../../state/configDraftStore';
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
  const configOnlyName = configOnly?.name ?? '';
  /* 从设备清单点进来时路由 key 是配置文件名；在这页点「启动设备」之后会话 id
     变成 ULID / legacy stem，路由 key 却还是文件名——只按 id 找的话这一帧
     找不到会话，页面就一直认为"还没启动"，运行区永远停在「启动设备」上、
     面板也拿不到运行数据（2026-11-18 用户反馈）。所以再按配置来源反查一次：
     同一份配置开出来的会话，无论 id 是什么都算"这台设备已经启动"。 */
  const session = summary ?? (configOnlyName === ''
    ? undefined
    : backend.sessions.find((item) => item.config === configOnlyName));
  /* 会话 id：面板取数、启停、上线都用它（不是路由 key） */
  const deviceSessionId = session?.id ?? sessionId;
  const isConfigOnly = session === undefined && configOnly !== null;
  const configName = session?.config ?? configOnlyName;
  const [busy, setBusy] = useState(false);
  /* 监听端点：配置 session 段的 绑定地址 / 端口 / Device ID。
     端口先用总览里的数兜底，配置读回来后再补上地址与 Device ID。 */
  const endpoint = useDeviceEndpoint(
    backend,
    configName,
    backend.overview.find((row) => row.id === deviceSessionId)?.port,
  );

  /* 四态判据统一走 deviceStatus：设备页头、侧栏、设备清单必须说同一句话 */
  const status = deviceStatus(session ?? { state: 'CLOSED' });

  const startDevice = () => {
    setBusy(true);
    /* 未启动的库内设备：「启动」= 按配置创建会话（与设备清单的「启动」同一动作） */
    const action = isConfigOnly
      ? backend.createSession(configOnlyName)
      : backend.connectSession(deviceSessionId);
    /* 启动后立即刷新运行区（事件计划/周期运行卡/台账都按新会话取数），
       不等 2s 轮询慢一拍（2026-11-18 用户反馈：运行区状态不即时刷新） */
    void action
      .then(() => backend.refreshAll())
      .finally(() => setBusy(false));
  };
  const stopDevice = () => {
    setBusy(true);
    void backend.closeSession(deviceSessionId)
      .then(() => backend.refreshAll())
      .finally(() => setBusy(false));
  };
  /* 上线/下线只动 GEM 控制状态，不动链路：等价于主机发 S1F17 / S1F15 */
  const setOnline = (online: boolean) => {
    setBusy(true);
    void backend.setDeviceOnline(deviceSessionId, online)
      .then(() => backend.refreshAll())
      .finally(() => setBusy(false));
  };

  /* 进入设备页就把全局会话钉到这台设备：所有面板按 activeSession 取数。
     必须是 layout effect —— 面板的写操作闭包在 hook 创建时就捕获了当时的
     activeSession，用 useEffect 的话会先画出一帧（还指向上一台设备），
     这一帧里用户如果正好点「删除 SVID / 删除报告」，动的就是 A 设备的数据。 */
  const setActiveSession = backend.setActiveSession;
  const activeSession = backend.activeSession;
  useLayoutEffect(() => {
    if (session !== undefined && activeSession !== session.id) {
      setActiveSession(session.id);
    }
  }, [activeSession, setActiveSession, session]);

  /* 配置区各页哪些还有未保存改动：切走之后也要看得出来（页签上点小圆点）。
     必须在下面的空态 early-return 之前调用：数据异步加载时 sessionKnown 会从
     false 变 true，若 hook 落在 return 之后，前后两次渲染的 hook 数量不一致，
     React 抛 #300 并卸载整棵树——"设备不存在"空态反而永远出不来。 */
  const dirtyPages = useDirtyPages(zone === 'config' ? configName : '');

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
  const sessionKnown = session !== undefined || isConfigOnly || backend.sessions.length === 0;
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
    ? DEVICE_CONFIG_PAGES.map((id) => ({
      id, label: DEVICE_CONFIG_PAGE_LABELS[id], note: DEVICE_CONFIG_PAGE_NOTES[id], dirty: dirtyPages.has(id),
    }))
    : DEVICE_RUNTIME_PAGES.map((id) => ({ id, label: DEVICE_RUNTIME_PAGE_LABELS[id], note: DEVICE_RUNTIME_PAGE_NOTES[id] }));
  /* 标题行不展示了，设备名仍要能被读屏与自动化认出来：挂在页面根节点的 aria-label 上 */
  const deviceLabel = (session?.device ?? (configOnlyName !== '' ? configOnlyName : sessionId)).replace(/\.json$/i, '');

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
          <DeviceOverviewPanel {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'connection' && (
          <ConnectionPanel key={sessionId} {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'structure' && (
          <StructurePanel key={sessionId} {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'svids' && (
          <SvidConfigPanel key={sessionId} {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'context' && (
          <ContextConfigPanel key={sessionId} {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'events' && (
          <CeidReportConfigPanel key={sessionId} {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'cycle' && (
          <CyclePanel key={sessionId} {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'plans' && (
          <PlanConfigPanel key={sessionId} {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'document' && (
          <ConfigPanel key={sessionId} {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'constants' && (
          <ConstantsConfigPanel key={sessionId} {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}
        {zone === 'config' && page === 'revisions' && (
          <DeviceRevisionsPanel {...baseProps} sessionId={deviceSessionId} configName={configName} />
        )}

        {/* 未启动的设备照样有运行区这几页：页面骨架不随状态变，
            只是运行数据要等设备启动后才有——所以放在页面内容区里说清楚，
            而不是把整个运行区换成一个"设备未启动"的独立页面。 */}
        {zone === 'runtime' && isConfigOnly && (
          <div className="empty-zone" role="status">
            <strong>运行区 · {pages.find((item) => item.id === page)?.label ?? '运行'}</strong>
            <div>这台设备还没有启动，运行数据要等它启动后才产生；配置区的定义随时可改。</div>
            {/* 启动按钮只在页头留一个：两处并排的「启动设备」容易点错 */}
            <div className="hint">点右上角「启动设备」即可。</div>
          </div>
        )}
        {zone === 'runtime' && !isConfigOnly && page === 'plans' && (
          <EventsPanel key={sessionId} {...baseProps} />
        )}

        {zone === 'runtime' && !isConfigOnly && page === 'cycle' && (
          <CycleRuntimePanel key={sessionId} {...baseProps} />
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

/** 一份配置里与会话无关的静态事实（连接 / 结构 / 周期）。 */
interface ConfigFacts {
  mode: string;
  listen: string;
  deviceId: string;
  units: number;
  svids: number;
  ceids: number;
  reports: number;
  cycle: string;
  cycleConfigured: boolean;
  plans: number;
}

/** 逐单元累计某个数组字段的长度（svids / ceids / reports 各有一份）。 */
function countPerUnit(units: readonly JsonObject[], key: string): number {
  return units.reduce((sum, unit) => {
    const list = unit[key];
    return sum + (Array.isArray(list) ? list.length : 0);
  }, 0);
}

function summarizeConfig(root: JsonObject): ConfigFacts {
  const session = isJsonObject(root.session) ? root.session : null;
  const equipment = isJsonObject(root.equipment) ? root.equipment : null;
  const units = Array.isArray(equipment?.units) ? equipment.units.filter(isJsonObject) : [];
  const globalSvids = Array.isArray(equipment?.globalSvids) ? equipment.globalSvids.length : 0;
  const cycle = isJsonObject(root.cycle) ? root.cycle : null;
  const durationMs = cycle === null ? 0 : (numberField(cycle, 'durationMs') ?? 0);
  const cycleConfigured = cycle !== null && durationMs > 0;
  const cycleEvents = cycle !== null && Array.isArray(cycle.events) ? cycle.events.length : 0;
  const events = isJsonObject(root.events) ? root.events : null;
  const plans = events !== null && Array.isArray(events.plans) ? events.plans.length : 0;
  const passive = session === null ? undefined : session.passive;
  const host = typeof session?.bindHost === 'string' && session.bindHost !== '' ? session.bindHost : '—';
  const port = numberField(session ?? {}, 'port') ?? 0;
  const deviceId = numberField(session ?? {}, 'deviceId') ?? 0;
  return {
    mode: passive === undefined ? '—' : (passive === true ? '被动' : '主动'),
    listen: `${host}:${port > 0 ? port : '—'}`,
    deviceId: deviceId > 0 ? String(deviceId) : '—',
    units: units.length,
    svids: countPerUnit(units, 'svids') + globalSvids,
    ceids: countPerUnit(units, 'ceids'),
    reports: countPerUnit(units, 'reports'),
    cycle: cycleConfigured
      ? `片长 ${(durationMs / 1000).toFixed(1)} s · ${numberField(cycle, 'count') ?? 0} 片 · ${cycleEvents} 条片内事件`
      : '未配置',
    cycleConfigured,
    plans,
  };
}

/** 读配置原文算静态事实；读不到就返回 null（面板显示「—」，不猜）。 */
function useConfigFacts(backend: BackendController, configName: string): ConfigFacts | null {
  const [facts, setFacts] = useState<ConfigFacts | null>(null);
  /* backend 每两秒换一个对象；只把读盘函数放进 ref，避免每次轮询都重读文件 */
  const readRef = useRef(backend.readConfig);
  readRef.current = backend.readConfig;
  useEffect(() => {
    if (configName === '' || typeof readRef.current !== 'function') {
      setFacts(null);
      return;
    }
    let cancelled = false;
    const load = async () => {
      try {
        const snapshot = await readRef.current(configName);
        if (cancelled || snapshot === null) {
          return;
        }
        const parsed = parseConfigDocument(snapshot.text);
        setFacts(parsed.ok ? summarizeConfig(parsed.config.root) : null);
      } catch {
        if (!cancelled) {
          setFacts(null);
        }
      }
    };
    void load();
    /* 配置区保存后这里要跟上，和周期运行卡同一节奏 */
    const timer = window.setInterval(() => void load(), 5_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [configName]);
  return facts;
}

function agoText(at: number): string {
  const seconds = Math.max(0, Math.round((Date.now() - at) / 1000));
  if (seconds < 5) {
    return '刚刚';
  }
  if (seconds < 60) {
    return `${seconds} 秒前`;
  }
  if (seconds < 3_600) {
    return `${Math.floor(seconds / 60)} 分钟前`;
  }
  if (seconds < 86_400) {
    return `${Math.floor(seconds / 3_600)} 小时前`;
  }
  return new Date(at).toLocaleString();
}

/** 配置区·概览：四张摘要卡（静态事实读配置）+ 身份信息表（运行时另标注）。 */
function DeviceOverviewPanel({
  sessionId,
  configName,
  backend,
}: PanelBaseProps & { sessionId: string; configName: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const config = backend.configList.find((entry) => entry.name === configName);
  const facts = useConfigFacts(backend, configName);
  /* 未启动的库内设备没有会话行：设备名退回配置文件名（去 .json） */
  const deviceLabel = summary?.device ?? configName.replace(/\.json$/i, '');
  const runtimeStatus = deviceStatus(summary ?? { state: 'CLOSED' });
  /* 最近事件 / 报警是运行时数据：停止态拿不到就明写「—」，不拿配置冒充 */
  const lastEventAt = backend.overview.find((row) => row.id === sessionId)?.lastEventAt ?? 0;
  const lastEvent = lastEventAt > 0 ? agoText(lastEventAt) : '—';
  const activeAlarms = summary?.counters.activeAlarms ?? 0;
  const alarmsSent = summary?.counters.alarmsSent ?? 0;
  return (
    <Panel id="panel-overview" title="概览" headless>
      <div className="overview-grid">
        <div className="overview-card">
          <h3>连接 <span className="badge tone-muted">配置</span></h3>
          <span className="overview-value">{facts === null ? '—' : `${facts.mode} ${facts.listen}`}</span>
          <ul>
            <li>模式：{facts?.mode ?? '—'}（被动 = 等主机连入）</li>
            <li>监听：{facts?.listen ?? '—'}</li>
            <li>Device ID：{facts?.deviceId ?? '—'}</li>
            <li><b>运行时：</b>{DEVICE_STATUS_LABEL[runtimeStatus]}（来自会话{summary === undefined ? '，当前未启动' : ''}）</li>
          </ul>
        </div>
        <div className="overview-card">
          <h3>结构 <span className="badge tone-muted">配置</span></h3>
          <span className="overview-value">{facts === null ? '—' : `${facts.units} 单元`}</span>
          <ul>
            <li>{facts?.svids ?? '—'} SVID · {facts?.ceids ?? '—'} CEID · {facts?.reports ?? '—'} RPT</li>
            <li>取自配置的 equipment 段：设备没启动也是这个数</li>
          </ul>
        </div>
        <div className="overview-card">
          <h3>周期 <span className="badge tone-muted">配置</span></h3>
          <span className="overview-value" style={{ color: facts?.cycleConfigured === true ? 'var(--ok)' : undefined }}>
            {facts === null ? '—' : (facts.cycleConfigured ? '已配置' : '未配置')}
          </span>
          <ul>
            <li>{facts?.cycle ?? '—'}</li>
            <li>事件计划：{facts?.plans ?? '—'} 条（草稿）</li>
          </ul>
        </div>
        <div className="overview-card">
          <h3>最近活动 <span className="badge tone-warn">运行时</span></h3>
          <span className="overview-value">
            {summary === undefined ? '—' : (activeAlarms > 0 ? `${activeAlarms} 条活动报警` : lastEvent)}
          </span>
          <ul>
            <li>最近事件：{lastEvent}（工作台聚合）</li>
            <li>报警：活动 {activeAlarms} · 已发 {alarmsSent}</li>
            <li>设备停止后没有运行时数据，显示「—」是正常的</li>
          </ul>
        </div>
      </div>

      <h3 className="sub-title">身份信息</h3>
      <table className="effects-table">
        <tbody>
          <tr><th>会话 id</th><td className="mono">{summary?.id ?? '—（未启动）'}</td></tr>
          <tr><th>设备名</th><td>{deviceLabel}</td></tr>
          <tr><th>配置文件</th><td className="mono">{configName || '—'}</td></tr>
          <tr><th>模板</th><td>{config?.template ?? '—'}</td></tr>
          <tr><th>大小 / 修改时间</th><td className="mono">
            {config ? `${config.sizeBytes} B` : '—'}{config ? ` · ${new Date(config.modifiedMs).toLocaleString()}` : ''}
          </td></tr>
          <tr>
            <th>自动连接</th>
            <td>
              <span className="badge tone-muted">后端未提供</span>
              {' '}设备侧后端没有自动连接开关；连接参数在「连接」页修改，保存后需重启设备。
            </td>
          </tr>
        </tbody>
      </table>
      <p className="hint">
        连接 / 结构 / 周期三张卡读的是配置原文与配置目录，与设备是否启动无关；标「运行时」的卡才读当前会话。
      </p>
    </Panel>
  );
}

interface ConfigRevision {
  revision: number;
  sha256: string;
  createdAt: number;
  createdBy: string;
  note: string;
}

/**
 * 配置区·版本历史：元数据新→旧，点版本展开变更摘要，可对比当前草稿。
 *
 * <p>后端只返回 revision / sha256 / 时间 / 操作者 / 备注，<b>不返回历史原文、
 * 也没有回滚端点</b>。所以这里不假装有逐版本 diff 或一键回滚：相邻版本用校验值
 * 判断是否逐字节一致，真正能逐行比对的是「草稿 vs 当前已保存版本」。</p>
 */
function DeviceRevisionsPanel({
  sessionId,
  configName,
  backend,
  notifier,
}: PanelBaseProps & { sessionId: string; configName?: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const name = summary?.config ?? configName ?? '';
  const [revisions, setRevisions] = useState<ConfigRevision[]>([]);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState<number | null>(null);
  const [showDraftDiff, setShowDraftDiff] = useState(false);
  const draft = useDraftSnapshot(name);

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

  /* 草稿 vs 磁盘基线：唯一拿得到双方原文的逐行 diff（草稿编译不过时为空）。 */
  const draftDiff = useMemo(() => {
    if (draft.status !== 'ready' || draft.text === draft.baselineText) {
      return [];
    }
    return foldUnchanged(diffLines(draft.baselineText, draft.text), 2);
  }, [draft.status, draft.text, draft.baselineText]);
  const draftChanged = draft.status === 'ready' && draft.text !== draft.baselineText;

  return (
    <section className="panel-section">
      <div className="section-head">
        <h2>配置版本历史</h2>
        <div className="preview-controls">
          <button
            type="button"
            disabled={draft.status !== 'ready'}
            onClick={() => setShowDraftDiff((previous) => !previous)}
            title="逐行比较当前草稿与磁盘上已保存的版本（含其它配置页的未保存改动）"
          >
            {showDraftDiff ? '收起草稿对比' : '对比当前草稿'}
          </button>
        </div>
      </div>
      {error !== '' && <p className="inline-note danger">{error}</p>}
      {showDraftDiff && (
        <div className="inline-note">
          <p className="hint">草稿（正在编辑）vs 当前已保存版本（磁盘基线）：标 + 是新增、标 − 是删掉。</p>
          {draft.status !== 'ready' ? (
            <p className="hint">草稿尚未载入；到任一配置页打开后再看。</p>
          ) : !draftChanged ? (
            <p className="hint">草稿与已保存版本一致，没有差异。</p>
          ) : (
            <div className="mono" style={{ maxHeight: 240, overflowY: 'auto' }}>
              {draftDiff.map((line, index) => (
                <div
                  key={index}
                  style={{
                    color: line.op === 'added' ? 'var(--ok)'
                      : line.op === 'removed' ? 'var(--danger)' : undefined,
                  }}
                >
                  {line.op === 'skip'
                    ? `··· ${line.count} 行未改动`
                    : `${line.op === 'added' ? '+' : line.op === 'removed' ? '-' : ' '} ${line.text}`}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
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
            {revisions.map((revision, index) => {
              const older = revisions[index + 1];
              return (
                <Fragment key={revision.revision}>
                  <tr>
                    <td className="mono">
                      <button
                        type="button"
                        className="link"
                        aria-expanded={expanded === revision.revision}
                        onClick={() => setExpanded(expanded === revision.revision ? null : revision.revision)}
                        title="展开变更摘要与可比对的信息"
                      >
                        v{revision.revision}
                      </button>
                    </td>
                    <td className="mono">{new Date(revision.createdAt).toLocaleString()}</td>
                    <td>{revision.createdBy}</td>
                    <td>{revision.note || '—'}</td>
                    <td className="mono hint-inline">{revision.sha256.slice(0, 12)}…</td>
                  </tr>
                  {expanded === revision.revision && (
                    <tr>
                      <td colSpan={5}>
                        <div className="inline-note">
                          <p><b>变更摘要：</b>{revision.note || '（这一版没有填备注）'}</p>
                          <p>
                            <b>相邻版本对比：</b>
                            {older === undefined
                              ? '最早版本，没有更早记录可比。'
                              : older.sha256 === revision.sha256
                                ? `与 v${older.revision} 校验值相同：内容逐字节一致（可能只改了备注）。`
                                : `与 v${older.revision} 校验值不同：内容有变化。后端只返回版本元数据，不返回历史原文，因此无法逐行 diff。`}
                          </p>
                          <p>
                            <b>回滚限制：</b>后端没有提供版本回滚或历史原文端点。要恢复旧内容，
                            需从部署侧导出该版本原文，在「配置原文」页粘贴后保存。
                          </p>
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      )}
      <p className="hint">版本历史来自后端 config_revision 快照（新→旧）；点版本号看变更摘要，用「对比当前草稿」看逐行差异。</p>
    </section>
  );
}

/** 运行区·实时状态已下线：三态徽标、单元数与累计上报都收进实例头。 */
