import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type ApiClient,
  type SessionBulkParams,
  type SessionBulkResult,
  type AuditEntryView,
  type Catalog,
  type ConfigEntry,
  type ConfigProblems,
  type ConfigSaveResult,
  type ConfigTemplateEntry,
  type ConfigTextSnapshot,
  type EventPlanView,
  type Health,
  type PreviewData,
  type SentMessageResult,
  type SessionSummary,
} from '../api';
import type { CeidInput, EventPlanInput, ReportInput, SvidInput } from '../api';
import { previewWindow } from '../lib/previewWindow';
import type { Notifier } from './useToasts';
import { createRequestSeq } from './requestSeq';
import { useSessionCommands } from './useSessionCommands';
import { useConfigCommands } from './useConfigCommands';
import { useDataCommands } from './useDataCommands';
import type { OverviewRow, BurstRequest, BurstResult, CollectorConfigInput, CollectorState } from '../api';

/** 面板里保留的报文条数上限。 */
export const MAX_MESSAGES = 200;

/** 各类资源的轮询间隔（毫秒）。 */
const INTERVAL = {
  /** 健康与会话：变化快，盯得紧。 */
  session: 2000,
  /** 报警 / 终端消息 / 目录 / 事件：2 秒。 */
  sessionDetail: 2000,
  /** 审计：低频。 */
  audit: 5000,
  /** 配置目录：低频。 */
  config: 5000,
} as const;

/** 事件流日志保留的条数。 */
const MAX_LOG = 400;

/**
 * 控制器返回的东西。
 *
 * <p>把"页面长什么样"和"后端现在什么状态"分开：
 * 面板只管渲染和收集输入，所有轮询与写操作都在这里统一处理。</p>
 */
export interface BackendController {
  health: Health | null;
  /** API 客户端:主机模拟页直接用它建多主机实例。 */
  client: ApiClient | null;

  sessions: SessionSummary[];
  /** 当前选中的会话；所有面板共用它。 */
  activeSession: string;
  setActiveSession: (id: string) => void;
  /** 当前 CEID；设备目录里点"填入"会改它。 */
  ceid: number;
  setCeid: (ceid: number) => void;

  catalog: Catalog | null;
  eventPlans: EventPlanView[];
  auditEntries: AuditEntryView[];
  /** 「载入更早」翻出来的更旧审计页(轮询不刷新它,随过滤变化清空)。 */
  auditOlder: AuditEntryView[];
  /** 审计服务端过滤(action/actor 精确匹配,空串=全部);轮询也带着它取数。 */
  auditFilter: { action: string; actor: string };
  /** 设置审计过滤并立刻按新过滤取一次。 */
  setAuditFilter: (filter: { action?: string; actor?: string }) => Promise<void>;
  /** 审计「载入更早」:以当前视图最早一条为游标向更早翻一页。 */
  loadAuditOlder: () => Promise<void>;
  /** 最近一次审计拉取失败的原因；空串 = 正常。失败时保留上一份数据。 */
  auditError: string;
  configList: ConfigEntry[];

  /** 正在刷新（用于按钮上的等待态）。 */
  refreshing: boolean;

  refreshAll: () => Promise<void>;
  refreshSessions: () => Promise<void>;
  refreshConfigs: () => Promise<void>;

  createSession: (config: string) => Promise<void>;
  /** 从一份设备配置批量开多台设备；返回逐台结果。 */
  bulkCreateSessions: (params: SessionBulkParams) => Promise<SessionBulkResult | null>;
  /** 对多台设备并发触发上报；onProgress 报告进度。 */
  batchTrigger: (
    ids: ReadonlySet<string>,
    ceid: number,
    onProgress?: (done: number, total: number) => void,
  ) => Promise<{ ok: number; fail: number }>;
  /** 对多台设备并发重载。 */
  batchReload: (
    ids: ReadonlySet<string>,
    onProgress?: (done: number, total: number) => void,
  ) => Promise<{ ok: number; fail: number }>;
  /** 对多台设备并发关闭。 */
  batchClose: (
    ids: ReadonlySet<string>,
    onProgress?: (done: number, total: number) => void,
  ) => Promise<{ ok: number; fail: number }>;
  /** 当前后端日志级别。 */
  logLevel: string;
  /** 后端不可达的错误文案；空串 = 正常。 */
  backendError: string;
  /** 切换后端日志级别；返回是否成功。 */
  setLogLevel: (level: string) => Promise<boolean>;
  /** 新建一份配置；templateId 缺省是空骨架。返回 HTTP 状态码。 */
  createConfig: (name: string, templateId?: string) => Promise<number>;
  /** 内置设备模板目录（新建配置的选型列表）。 */
  configTemplates: () => Promise<ConfigTemplateEntry[]>;
  /** 读配置原文(带 modifiedMs 快照);读不到返回 null。 */
  readConfig: (name: string) => Promise<ConfigTextSnapshot | null>;
  /** 只校验不落盘。 */
  validateConfig: (name: string, text: string) => Promise<ConfigProblems | null>;
  /** 校验通过后落盘;expectedModifiedMs 非空时 If-Match 并发保护,被改过回 409。 */
  saveConfig: (name: string, text: string, expectedModifiedMs?: number | null) => Promise<ConfigSaveResult | null>;
  closeSession: (sessionId: string) => Promise<void>;
  reloadSession: (sessionId: string) => Promise<void>;
  trigger: (sessionId: string, ceid: number) => Promise<void>;
  setAlarm: (sessionId: string, alid: number, set: boolean, text: string, category: number) => Promise<void>;
  toggleEvent: (plan: EventPlanView) => Promise<void>;
  /* ---------- 运行时编辑(设备页·事件/报告/状态变量;不写回配置,重载后失效) ---------- */
  addEventPlan: (plan: EventPlanInput) => Promise<boolean>;
  updateEventPlan: (planId: string, plan: EventPlanInput) => Promise<boolean>;
  deleteEventPlan: (planId: string) => Promise<boolean>;
  addReport: (report: ReportInput) => Promise<boolean>;
  /** 运行时新增采集事件(事件设置页)。 */
  addCeid: (ceid: CeidInput) => Promise<boolean>;
  updateCeid: (ceid: number, def: CeidInput) => Promise<boolean>;
  deleteCeid: (ceid: number) => Promise<boolean>;
  updateReport: (rptid: number, report: ReportInput) => Promise<boolean>;
  deleteReport: (rptid: number) => Promise<boolean>;
  addSvid: (def: SvidInput) => Promise<boolean>;
  updateSvid: (svid: number, def: SvidInput) => Promise<boolean>;
  deleteSvid: (svid: number) => Promise<boolean>;
  setSvidValue: (svid: number, value: number) => Promise<boolean>;
  clearSvidValue: (svid: number) => Promise<boolean>;
  /** 单个状态变量的曲线预览（窗口右端点取进程 uptime，即"最近 N 毫秒"）。 */
  previewSvid: (sessionId: string, svid: number, windowMs: number) => Promise<PreviewData | null>;
  /** 只回放某一条编排计划的预览（事件及其效果）。 */
  previewPlan: (sessionId: string, planId: string, windowMs: number) => Promise<PreviewData | null>;
  /** 拉整台设备的曲线(全部 SVID + 事件标记),用于"事件串起来"的预览。 */
  previewAll: (sessionId: string, windowMs: number) => Promise<PreviewData | null>;
  /** 手动发送一条 SML；返回发送结果。 */
  sendSml: (sessionId: string, sml: string) => Promise<SentMessageResult | null>;
  openHostSim: (sessionId: string, target?: import('../api').HostSimTarget) => Promise<void>;
  closeHostSim: (sessionId: string) => Promise<void>;
  /** 以主机角色发送 SML；返回发送结果。 */
  sendHostSml: (sessionId: string, sml: string) => Promise<import('../api').SentMessageResult | null>;

  /** 工作台聚合(v6):每台设备的速率/计数一览。 */
  overview: OverviewRow[];
  refreshOverview: () => Promise<void>;
  /** 服务端压测(v6)。 */
  burst: (sessionId: string, request: BurstRequest) => Promise<BurstResult>;
  /** 主机模拟 S1F3 采集器状态与数据(v6)。 */
  collectorState: (sessionId: string) => Promise<CollectorState | null>;
  /** 下发采集器配置;返回错误文案或 null。 */
  saveCollector: (sessionId: string, config: CollectorConfigInput) => Promise<string | null>;
  /** 手动启停采集器。 */
  collectorCommand: (sessionId: string, start: boolean) => Promise<string | null>;
}

/**
 * 会话生命周期操作的状态码文案。
 *
 * <p>服务端已经在日志里记了原因，这里只负责把状态码说成人话：
 * 400 分成"配置有问题"和"名字不合法"两种可能，光一个 400 谁也看不出该改哪。</p>
 */
export function lifecycleMessage(status: number, what: string): string {
  switch (status) {
    case 200:
    case 201:
      return `${what}成功`;
    case 400:
      return `${what}失败（配置不合法/读不到，或端口被占；详见服务端日志）`;
    case 404:
      return `${what}失败：会话不存在`;
    case 409:
      return `${what}失败：标识已被占用，或该会话没有配置来源`;
    default:
      return `${what} -> HTTP ${status}`;
  }
}

/**
 * 后端控制器。
 *
 * <p>把原来散在 App 里的一堆 setInterval 收成<b>一个</b>：
 * 每个资源按自己的节奏到点才刷新，页面切到后台就整体暂停，
 * 回到前台立刻补一次。原来五个定时器各打各的请求，
 * 互相错开还容易撞在一起，界面上看着"一顿一顿的"。</p>
 */
export function useBackend(client: ApiClient | null, notifier: Notifier): BackendController {
  const [health, setHealth] = useState<Health | null>(null);
  /** 后端不可达时的最后错误；空串 = 正常。全局横幅靠它。 */
  const [backendError, setBackendError] = useState('');
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [activeSession, setActiveSession] = useState('');
  const [ceid, setCeid] = useState(2001);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [eventPlans, setEventPlans] = useState<EventPlanView[]>([]);
  const [auditEntries, setAuditEntries] = useState<AuditEntryView[]>([]);
  const [configList, setConfigList] = useState<ConfigEntry[]>([]);
  const [logLevel, setLogLevelState] = useState('info');
  const [refreshing, setRefreshing] = useState(false);
  const [overview, setOverview] = useState<OverviewRow[]>([]);

  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  /** 当前活跃会话的同步镜像：异步响应回来时判断"是不是已经切走了"。 */
  const activeSessionRef = useRef(activeSession);
  activeSessionRef.current = activeSession;

  /* ---------- 读取 ---------- */

  const refreshSessions = useCallback(async () => {
    if (client === null) {
      return;
    }
    const [healthResult, sessionsResult] = await Promise.allSettled([client.health(), client.sessions()]);
    if (healthResult.status === 'fulfilled') {
      setHealth(healthResult.value);
      setBackendError('');
    } else {
      setBackendError((healthResult.reason as Error)?.message ?? '后端不可达');
    }
    if (sessionsResult.status === 'fulfilled') {
      setSessions(sessionsResult.value);
    }
  }, [client]);

  const refreshConfigs = useCallback(async () => {
    if (client === null) {
      return;
    }
    try {
      setConfigList(await client.configs());
    } catch {
      /* 读不到目录先不打扰用户，下一轮再试 */
    }
  }, [client]);

  /* 报警 / 终端消息 / 主机模拟开关的本地快照已删除：
     它们没有独立触发点（状态由异步 ACK 决定），主机模拟也已迁到独立页，
     原来每 2 秒拉一次纯属白跑。报警是否真的到达主机去「消息」页看 S5F1/S5F2。 */

  /** 拉某个会话的目录 / 事件计划。 */
  const refreshSessionDetail = useCallback(
    async (sessionId: string) => {
      if (client === null || sessionId === '') {
        return;
      }
      const [catalogResult, eventsResult] = await Promise.allSettled([
        client.catalog(sessionId),
        client.events(sessionId),
      ]);
      /* 响应回来时可能已经切到别的设备：过期结果不能写进当前面板，
         否则"结构/事件"页会短暂挂着上一台设备的数据 */
      if (activeSessionRef.current !== sessionId) {
        return;
      }
      if (catalogResult.status === 'fulfilled') {
        setCatalog(catalogResult.value);
      }
      if (eventsResult.status === 'fulfilled') {
        setEventPlans(eventsResult.value);
      }
    },
    [client],
  );

  /** 审计的服务端过滤(action/actor 精确匹配);每次轮询都带着它取数。 */
  const [auditFilter, setAuditFilterState] = useState<{ action: string; actor: string }>({ action: '', actor: '' });
  const auditFilterRef = useRef(auditFilter);
  auditFilterRef.current = auditFilter;
  /** 「载入更早」翻出来的更旧审计页;轮询只刷新最近窗口,不碰这里。 */
  const [auditOlder, setAuditOlder] = useState<AuditEntryView[]>([]);
  /** 审计最近一次失败原因；有数据时保留旧数据，只把错误亮出来。 */
  const [auditError, setAuditError] = useState('');
  /** 审计请求序号：切换过滤与轮询共用，后发先至的旧响应不能覆盖新过滤的结果。 */
  const auditSeqRef = useRef(createRequestSeq());

  const refreshAudit = useCallback(async () => {
    if (client === null) {
      setAuditEntries([]);
      setAuditError('');
      return;
    }
    const token = auditSeqRef.current.begin();
    try {
      const entries = await client.audit(200, auditFilterRef.current);
      if (!auditSeqRef.current.isCurrent(token)) {
        return;
      }
      setAuditEntries(entries);
      setAuditError('');
    } catch (error) {
      if (!auditSeqRef.current.isCurrent(token)) {
        return;
      }
      /* 保留旧数据：后端短暂抖动时把表格闪空，会让人以为审计记录丢了 */
      setAuditError((error as Error).message);
    }
  }, [client]);

  /** 设置审计过滤并立刻按新过滤取一次(轮询此后也一直带着它);翻页缓存随之清空。 */
  const setAuditFilter = useCallback(async (filter: { action?: string; actor?: string }) => {
    /* 先同步更新 ref：否则紧接着触发的轮询还会带着旧过滤，
       它的响应会因为序号更新而胜出，界面就停在旧过滤结果上 */
    const nextFilter = { ...auditFilterRef.current, ...filter };
    auditFilterRef.current = nextFilter;
    setAuditFilterState(nextFilter);
    setAuditOlder([]);
    if (client === null) {
      return;
    }
    /* 用最新过滤直接取,不等下一次轮询；同时抢到新序号，
       让还在路上的旧过滤响应（以及旧轮询）自动作废 */
    const token = auditSeqRef.current.begin();
    try {
      const entries = await client.audit(200, nextFilter);
      if (!auditSeqRef.current.isCurrent(token)) {
        return;
      }
      setAuditEntries(entries);
      setAuditError('');
    } catch (error) {
      if (!auditSeqRef.current.isCurrent(token)) {
        return;
      }
      setAuditError((error as Error).message);
    }
  }, [client]);

  /** 审计「载入更早」:以当前视图最早一条为游标向更早翻一页,合并进 auditOlder。 */
  const loadAuditOlder = useCallback(async () => {
    if (client === null) {
      return;
    }
    const oldest = Math.min(
      ...[...auditEntries, ...auditOlder].map((entry) => entry.at),
      Number.POSITIVE_INFINITY,
    );
    if (!Number.isFinite(oldest)) {
      return;
    }
    try {
      const page = await client.audit(200, { ...auditFilterRef.current, before: oldest });
      setAuditOlder((previous) => {
        const seen = new Set(previous.map(auditEntryKey));
        const fresh = page.filter((entry) => !seen.has(auditEntryKey(entry)));
        return [...previous, ...fresh].sort((left, right) => right.at - left.at);
      });
    } catch (error) {
      notifierRef.current.error('载入更早的审计失败', (error as Error).message);
    }
  }, [client, auditEntries, auditOlder]);

  /* ---------- 运行时编辑(设备页·事件/报告/状态变量) ---------- */

  /** 编辑通用:调 client、按状态码通知、成功后立刻刷新当前会话明细。 */
  const runEdit = useCallback(
    async (action: string, okText: string, run: () => Promise<number>): Promise<boolean> => {
      if (client === null) {
        return false;
      }
      try {
        const status = await run();
        if (status === 200 || status === 201) {
          notifierRef.current.success(okText);
          await refreshSessionDetail(activeSession);
          return true;
        }
        if (status === 409) {
          notifierRef.current.error(`${action}被拒：标识已存在`);
        } else if (status === 404) {
          notifierRef.current.error(`${action}失败：目标不存在`);
        } else {
          notifierRef.current.error(`${action}失败（HTTP ${status}）`);
        }
        return false;
      } catch (error) {
        notifierRef.current.error(`${action}失败`, (error as Error).message);
        return false;
      }
    },
    [client, activeSession, refreshSessionDetail],
  );

  const addEventPlan = useCallback(
    (plan: EventPlanInput) => runEdit('新增编排', '已新增编排计划', () => client!.addEventPlan(activeSession, plan)),
    [runEdit, activeSession],
  );
  const updateEventPlan = useCallback(
    (planId: string, plan: EventPlanInput) => runEdit('更新编排', '已更新编排计划', () => client!.updateEventPlan(activeSession, planId, plan)),
    [runEdit],
  );
  const deleteEventPlan = useCallback(
    (planId: string) => runEdit('删除编排', '已删除编排计划', () => client!.deleteEventPlan(activeSession, planId)),
    [runEdit],
  );
  const addCeid = useCallback(
    (ceid: CeidInput) => runEdit('新增事件', '已新增采集事件', () => client!.addCeid(activeSession, ceid)),
    [runEdit, activeSession],
  );
  const updateCeid = useCallback(
    (ceid: number, def: CeidInput) => runEdit('更新事件', '已更新采集事件', () => client!.updateCeid(activeSession, ceid, def)),
    [runEdit, activeSession],
  );
  const deleteCeid = useCallback(
    (ceid: number) => runEdit('删除事件', '已删除采集事件', () => client!.deleteCeid(activeSession, ceid)),
    [runEdit, activeSession],
  );

  const addReport = useCallback(
    (report: ReportInput) => runEdit('新增报告', '已新增报告', () => client!.addReport(activeSession, report)),
    [runEdit],
  );
  const updateReport = useCallback(
    (rptid: number, report: ReportInput) => runEdit('更新报告', '已更新报告', () => client!.updateReport(activeSession, rptid, report)),
    [runEdit],
  );
  const deleteReport = useCallback(
    (rptid: number) => runEdit('删除报告', '已删除报告', () => client!.deleteReport(activeSession, rptid)),
    [runEdit],
  );
  const addSvid = useCallback(
    (def: SvidInput) => runEdit('新增状态变量', '已新增状态变量', () => client!.addSvid(activeSession, def)),
    [runEdit],
  );
  const updateSvid = useCallback(
    (svid: number, def: SvidInput) => runEdit('更新状态变量', '已更新状态变量', () => client!.updateSvid(activeSession, svid, def)),
    [runEdit],
  );
  const deleteSvid = useCallback(
    (svid: number) => runEdit('删除状态变量', '已删除状态变量', () => client!.deleteSvid(activeSession, svid)),
    [runEdit],
  );
  const setSvidValue = useCallback(
    (svid: number, value: number) => runEdit('设定值', `已设定 SVID ${svid} 的值`, () => client!.setSvidValue(activeSession, svid, value)),
    [runEdit],
  );
  const clearSvidValue = useCallback(
    (svid: number) => runEdit('取消设定', `已取消 SVID ${svid} 的设定`, () => client!.clearSvidValue(activeSession, svid)),
    [runEdit],
  );

  /** 单 SVID 曲线预览:走预览端点的 svids 过滤。 */
  const previewSvid = useCallback(
    async (sessionId: string, svid: number, windowMs: number): Promise<PreviewData | null> => {
      if (client === null) {
        return null;
      }
      try {
        /* 预览"最近 N 秒"：以进程 uptime 为右端点。
           从 0 开始会把周期 60s 的计划整个漏掉（窗口只到 60s 边界）。 */
        const fresh = await client.health();
        const { from, to } = previewWindow(fresh.uptimeMs, windowMs);
        return await client.preview(sessionId, from, to, 90, [svid], true);
      } catch (error) {
        notifierRef.current.error('曲线预览失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  /** 只回放某一条编排计划，用于「这个事件及其参数的周期预览」。 */
  const previewPlan = useCallback(
    async (sessionId: string, planId: string, windowMs: number): Promise<PreviewData | null> => {
      if (client === null || planId === '') {
        return null;
      }
      try {
        const fresh = await client.health();
        const { from, to } = previewWindow(fresh.uptimeMs, windowMs);
        return await client.preview(sessionId, from, to, 120, undefined, true, planId);
      } catch (error) {
        notifierRef.current.error('事件预览失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  const refreshLogLevel = useCallback(async () => {
    if (client === null) {
      return;
    }
    try {
      setLogLevelState(await client.logLevel());
    } catch {
      /* 老后端没有这个端点：保持 info */
    }
  }, [client]);

  const setLogLevel = useCallback(
    async (level: string) => {
      if (client === null) {
        return false;
      }
      try {
        const changed = await client.setLogLevel(level);
        if (changed) {
          setLogLevelState(level);
          notifierRef.current.info(`日志级别已切换为 ${level}`);
        }
        return changed;
      } catch (error) {
        notifierRef.current.error('切换日志级别失败', (error as Error).message);
        return false;
      }
    },
    [client],
  );

  /** 工作台聚合(v6):速率/计数一览；失败时保留上一份，不让 KPI 闪成 0。 */
  const refreshOverview = useCallback(async () => {
    if (client === null) {
      return;
    }
    try {
      setOverview(await client.overview());
    } catch (error) {
      /* 这里不占全局 backendError（health 轮询会把它清掉），但要留痕迹：
         老后端缺端点或瞬时失败时，保留上一份读数比清空更有用 */
      console.warn('[overview] 拉取失败，保留上一份数据', error);
    }
  }, [client]);

  /**
   * 服务端压测(v6)。
   *
   * <p>服务端把压测放到后台执行，POST 只回 202 受理；这里轮询 GET 进度直到
   * `running === false`，把最终统计返回给调用方。否则界面会把"受理"当成结果，
   * 显示"成功 0 / 失败 0"。</p>
   */
  const burst = useCallback(
    async (sessionId: string, request: BurstRequest): Promise<BurstResult> => {
      if (client === null) {
        throw new Error('未连接后端');
      }

      try {
        await client.burst(sessionId, request);
      } catch (error) {
        /* 409 = 该会话已有一个压测在跑：接着轮询那一轮，而不是报错 */
        if ((error as { status?: number }).status !== 409) {
          throw error;
        }
      }

      /* 服务端预算是 5 分钟；这里给 1 分钟底 + 请求本身的理论时长，最多 6 分钟 */
      const deadline = Date.now() + Math.min(
        6 * 60_000,
        60_000 + request.count * (request.intervalMs ?? 0),
      );

      for (;;) {
        await new Promise((resolve) => setTimeout(resolve, 500));
        const status = await client.burstStatus(sessionId);
        if (status.running !== true) {
          return {
            sent: status.sent ?? 0,
            failed: status.failed ?? 0,
            durationMs: status.durationMs ?? 0,
            latencyP50: status.latencyP50 ?? 0,
            latencyP95: status.latencyP95 ?? 0,
            latencyMax: status.latencyMax ?? 0,
          };
        }
        if (Date.now() > deadline) {
          throw new Error('压测仍未结束（服务端可能仍在执行，可稍后重试或查看服务端日志）');
        }
      }
    },
    [client],
  );

  /** 采集器状态与数据(v6)。 */
  const collectorState = useCallback(
    async (sessionId: string): Promise<CollectorState | null> => {
      if (client === null) {
        return null;
      }
      return client.collectorState(sessionId);
    },
    [client],
  );

  /** 下发采集器配置;返回错误文案或 null。 */
  const saveCollector = useCallback(
    async (sessionId: string, config: CollectorConfigInput): Promise<string | null> => {
      if (client === null) {
        return '未连接后端';
      }
      return client.setCollector(sessionId, config);
    },
    [client],
  );

  /** 手动启停采集器。 */
  const collectorCommand = useCallback(
    async (sessionId: string, start: boolean): Promise<string | null> => {
      if (client === null) {
        return '未连接后端';
      }
      return client.collectorCommand(sessionId, start);
    },
    [client],
  );

  const refreshAll = useCallback(async () => {
    if (client === null) {
      return;
    }
    setRefreshing(true);
    try {
      await Promise.all([
        refreshSessions(),
        refreshSessionDetail(activeSession),
        refreshAudit(),
        refreshConfigs(),
        refreshLogLevel(),
        refreshOverview(),
      ]);
    } finally {
      setRefreshing(false);
    }
  }, [client, activeSession, refreshSessions, refreshSessionDetail, refreshAudit, refreshConfigs, refreshLogLevel, refreshOverview]);

  /* 单一调度器：按各自节奏到点刷新 */
  useEffect(() => {
    if (client === null) {
      setHealth(null);
      setSessions([]);
      setCatalog(null);
      setEventPlans([]);
      setAuditEntries([]);
      setConfigList([]);
      return;
    }

    const due: Record<string, number> = {
      session: 0,
      detail: 0,
      audit: 0,
      config: 0,
      overview: 0,
    };

    const tick = async () => {
      /* 页面在后台时不刷：看不见的界面不值得让后端干活 */
      if (typeof document !== 'undefined' && document.hidden) {
        return;
      }
      const now = Date.now();

      if (now >= due.session) {
        due.session = now + INTERVAL.session;
        void refreshSessions();
      }
      if (now >= due.detail) {
        due.detail = now + INTERVAL.sessionDetail;
        void refreshSessionDetail(activeSession);
      }
      if (now >= due.audit) {
        due.audit = now + INTERVAL.audit;
        void refreshAudit();
      }
      if (now >= due.config) {
        due.config = now + INTERVAL.config;
        void refreshConfigs();
      }
      if (now >= due.overview) {
        due.overview = now + INTERVAL.audit;
        void refreshOverview();
      }
    };

    void tick();
    const timer = window.setInterval(() => void tick(), 1000);

    /* 回到前台立刻补一次，不然界面会停留在旧数据上好几秒 */
    const onVisible = () => {
      if (!document.hidden) {
        due.session = 0;
        due.detail = 0;
        void tick();
      }
    };
    document.addEventListener('visibilitychange', onVisible);

    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [client, activeSession, refreshSessions, refreshSessionDetail, refreshAudit, refreshConfigs, refreshOverview]);

  /* 选中的会话必须真实存在 */
  useEffect(() => {
    if (sessions.length === 0) {
      if (activeSession !== '') {
        setActiveSession('');
      }
      return;
    }
    if (!sessions.some((session) => session.id === activeSession)) {
      setActiveSession(sessions[0].id);
    }
  }, [sessions, activeSession]);

  /* 会话没了就把它的明细清掉，别留着上一个会话的目录糊弄人 */
  useEffect(() => {
    if (activeSession === '') {
      setCatalog(null);
      setEventPlans([]);
    }
  }, [activeSession]);

  /* ---------- 写操作（会话域拆分至 useSessionCommands） ---------- */
  const sessionCommands = useSessionCommands({ client, notifier, refreshSessions, refreshSessionDetail });
  /**
   * 保存配置后让相关会话生效：对每个运行中会话调一次重载。
   *
   * <p>sessions JSON 不带配置来源，界面无法精确匹配"哪些会话用这份文件"；
   * 重载对每个会话都按<b>它自己的来源</b>重读——用的是同一份磁盘目录，
   * 保存过的改动自然被带上；演示会话回 not-reloadable，被安静跳过。
   * 连接标识没变的会话走热生效不断连，变化了才重建。</p>
   */
  const reloadSessionsUsing = useCallback(
    async (configName: string) => {
      if (client === null) {
        return;
      }
      /* 只重载用这份配置的会话。老版本 sessions JSON 没有 config 字段，
         这时退回"全部重载"以保持行为不变。 */
      const matched = configName === ''
        ? []
        : sessions.filter((session) => session.config === configName);
      const targets = matched.length > 0 ? matched : sessions;

      let hot = 0;
      for (const session of targets) {
        try {
          const status = await client.reloadSession(session.id);
          if (status === 200) {
            hot += 1;
          }
        } catch {
          /* 单个会话重载失败不阻断其余 */
        }
      }
      if (hot > 0) {
        notifierRef.current.info(`配置已生效：${hot} 个会话已重载（连接标识未变的走热生效，主机不断连）`);
      }
      await refreshSessions();
    },
    [client, sessions, refreshSessions],
  );

  const configCommands = useConfigCommands({ client, notifier, refreshConfigs, reloadSessionsUsing });
  const dataCommands = useDataCommands({ client, notifier, setHealth });

  const openHostSim = useCallback(
    async (sessionId: string, target?: import('../api').HostSimTarget) => {
      if (client === null) {
        return;
      }
      const status = await client.openHostSim(sessionId, target).catch(() => 0);
      if (status === 201) {
        notifierRef.current.success(target === undefined ? '主机模拟器已连接' : '主机模拟器已连接（远程）');
      } else if (status === 409) {
        notifierRef.current.error('连接失败', '设备已有主机连接，或模拟器已开启');
      } else {
        notifierRef.current.error(`主机模拟器连接失败（HTTP ${status}）`);
      }
      await refreshSessionDetail(sessionId);
    },
    [client, refreshSessionDetail],
  );

  const closeHostSim = useCallback(
    async (sessionId: string) => {
      if (client === null) {
        return;
      }
      await client.closeHostSim(sessionId).catch(() => 0);
      notifierRef.current.info('主机模拟器已断开');
      await refreshSessionDetail(sessionId);
    },
    [client, refreshSessionDetail],
  );

  const sendHostSml = useCallback(
    async (sessionId: string, sml: string): Promise<import('../api').SentMessageResult | null> => {
      if (client === null) {
        return null;
      }
      try {
        const result = await client.sendHostSml(sessionId, sml);
        const summary =
          result.replySml != null
            ? `S${result.stream}F${result.function} -> S${result.replyStream}F${result.replyFunction}`
            : result.timedOut
              ? `S${result.stream}F${result.function} -> 等待回复超时`
              : `S${result.stream}F${result.function} -> 已发送`;
        notifierRef.current.success(summary, `耗时 ${result.elapsedMs} ms`);
        return result;
      } catch (error) {
        notifierRef.current.error('主机模拟发送失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  const setAlarm = useCallback(
    async (sessionId: string, alid: number, set: boolean, text: string, category: number) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.alarm(sessionId, alid, set, text, category);
        const message = `报警${set ? '置位' : '清除'} ${sessionId} ALID ${alid} -> HTTP ${status}`;
        if (status >= 200 && status < 300) {
          notifierRef.current.success(message);
        } else {
          notifierRef.current.error(message);
        }
        await refreshSessionDetail(sessionId);
      } catch (error) {
        notifierRef.current.error('报警失败', (error as Error).message);
      }
    },
    [client, refreshSessionDetail],
  );

  const toggleEvent = useCallback(
    async (plan: EventPlanView) => {
      if (client === null || activeSession === '') {
        return;
      }
      try {
        const status = await client.setEventEnabled(activeSession, plan.id, !plan.enabled);
        if (status === 200) {
          notifierRef.current.success(`事件计划 ${plan.id} 已${plan.enabled ? '禁用' : '启用'}`);
        } else if (status === 404) {
          notifierRef.current.error(`事件计划 ${plan.id} 操作失败：会话或计划不存在`);
        } else {
          notifierRef.current.error(`事件计划 ${plan.id} 操作失败（HTTP ${status}）`);
        }
        await refreshSessionDetail(activeSession);
      } catch (error) {
        notifierRef.current.error('事件计划操作失败', (error as Error).message);
      }
    },
    [client, activeSession, refreshSessionDetail],
  );

  const sendSml = useCallback(
    async (sessionId: string, sml: string): Promise<SentMessageResult | null> => {
      if (client === null) {
        return null;
      }
      try {
        const result = await client.sendSml(sessionId, sml);
        const summary =
          result.replySml != null
            ? `S${result.stream}F${result.function} -> S${result.replyStream}F${result.replyFunction}`
            : result.timedOut
              ? `S${result.stream}F${result.function} -> 等待回复超时`
              : `S${result.stream}F${result.function} -> 已发送`;
        notifierRef.current.success(summary, `耗时 ${result.elapsedMs} ms`);
        return result;
      } catch (error) {
        notifierRef.current.error('手动发送失败', (error as Error).message);
        throw error;
      }
    },
    [client],
  );

  /* ---------- 配置目录 ---------- */

  return useMemo<BackendController>(
    () => ({
      ...sessionCommands,
      ...configCommands,
      ...dataCommands,
      client,
      health,
      sessions,
      activeSession,
      setActiveSession,
      ceid,
      setCeid,
      catalog,
      eventPlans,
      auditEntries,
      auditOlder,
      auditFilter,
      setAuditFilter,
      auditError,
      loadAuditOlder,
      addEventPlan,
      updateEventPlan,
      deleteEventPlan,
      addCeid,
      updateCeid,
      deleteCeid,
      addReport,
      updateReport,
      deleteReport,
      addSvid,
      updateSvid,
      deleteSvid,
      setSvidValue,
      clearSvidValue,
      previewSvid,
      previewPlan,
      previewAll: dataCommands.previewAll,
      configList,
      refreshing,
      refreshAll,
      refreshSessions,
      refreshConfigs,
      logLevel,
      backendError,
      setLogLevel,
      setAlarm,
      toggleEvent,
      sendSml,
      openHostSim,
      closeHostSim,
      sendHostSml,
      overview,
      refreshOverview,
      burst,
      collectorState,
      saveCollector,
      collectorCommand,
    }),
    [
      health,
      sessions,
      activeSession,
      ceid,
      catalog,
      eventPlans,
      auditEntries,
      auditOlder,
      auditFilter,
      setAuditFilter,
      auditError,
      loadAuditOlder,
      addEventPlan,
      updateEventPlan,
      deleteEventPlan,
      addCeid,
      updateCeid,
      deleteCeid,
      addReport,
      updateReport,
      deleteReport,
      addSvid,
      updateSvid,
      deleteSvid,
      setSvidValue,
      clearSvidValue,
      previewSvid,
      previewPlan,
      configList,
      refreshing,
      refreshAll,
      refreshSessions,
      refreshConfigs,
      logLevel,
      backendError,
      setLogLevel,
      setAlarm,
      toggleEvent,
      sendSml,
      openHostSim,
      closeHostSim,
      sendHostSml,
      overview,
      refreshOverview,
      burst,
      collectorState,
      saveCollector,
      collectorCommand,
    ],
  );
}

/** 审计条目的去重键:翻页合并与轮询刷新的联合视图用。 */
function auditEntryKey(entry: AuditEntryView): string {
  return `${entry.at}-${entry.actor}-${entry.action}-${entry.target}-${entry.detail}`;
}
