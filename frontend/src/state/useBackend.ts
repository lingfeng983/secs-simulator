import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type AlarmState,
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
  type TerminalMessage,
} from '../api';
import type { CeidInput, EventPlanInput, ReportInput, SvidInput } from '../api';
import type { Notifier } from './useToasts';
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

/** 事件流日志的一行。 */

/**
 * 控制器返回的东西。
 *
 * <p>把"页面长什么样"和"后端现在什么状态"分开：
 * 面板只管渲染和收集输入，所有轮询与写操作都在这里统一处理。</p>
 */
export interface BackendController {
  health: Health | null;

  sessions: SessionSummary[];
  /** 当前选中的会话；所有面板共用它。 */
  activeSession: string;
  setActiveSession: (id: string) => void;
  /** 当前 CEID；设备目录里点"填入"会改它。 */
  ceid: number;
  setCeid: (ceid: number) => void;

  alarms: AlarmState[];
  terminalMessages: TerminalMessage[];
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
  configList: ConfigEntry[];

  /** 服务端记录的报文总数（可能大于面板里保留的条数）。 */

  /** 事件流日志。 */

  preview: PreviewData | null;
  previewSession: string;

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
  /** 单个状态变量的曲线预览(窗口相对仿真起点)。 */
  previewSvid: (sessionId: string, svid: number, windowMs: number) => Promise<PreviewData | null>;
  sendSml: (sessionId: string, sml: string) => Promise<SentMessageResult | null>;
  downloadPreview: (windowMs: number) => Promise<void>;
  /** 配置草稿的曲线预览（编辑器里改模板直接看效果）。 */
  draftPreview: PreviewData | null;
  previewDraft: (text: string, windowMs: number) => Promise<void>;
  /** 单 SVID 草稿预览(v6,带事件标记)。 */
  previewDraftSvid: (text: string, windowMs: number, svid: number) => Promise<PreviewData | null>;
  /** 对比会话 B 的预览数据；null 表示未开启对比。 */
  comparePreview: PreviewData | null;
  /** 对比会话 B 的 id。 */
  compareSession: string;
  /** 拉取/清除对比预览；空 sessionId 关闭对比。 */
  setComparePreview: (sessionId: string, fromMs: number, toMs: number) => Promise<void>;
  /** 按时间窗刷新曲线（先取一次 uptime，时间窗才是"最近 N 分钟"而不是"开局 N 秒"）。 */
  refreshPreview: (windowMs: number) => Promise<void>;

  /** 主机模拟器连接状态（随会话明细轮询）。 */
  hostSimOpen: boolean;
  hostSimTarget: string;
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
    case 400:
      return `${what}失败（配置不合法或读不到，详见服务端日志）`;
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
  const [alarms, setAlarms] = useState<AlarmState[]>([]);
  const [terminalMessages, setTerminalMessages] = useState<TerminalMessage[]>([]);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [eventPlans, setEventPlans] = useState<EventPlanView[]>([]);
  const [auditEntries, setAuditEntries] = useState<AuditEntryView[]>([]);
  const [configList, setConfigList] = useState<ConfigEntry[]>([]);
  const [logLevel, setLogLevelState] = useState('info');
  const [preview, setPreview] = useState<PreviewData | null>(null);
  const [comparePreview, setComparePreviewData] = useState<PreviewData | null>(null);
  const [draftPreview, setDraftPreview] = useState<PreviewData | null>(null);
  const [compareSession, setCompareSessionId] = useState('');
  const [previewSession, setPreviewSession] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const [hostSimOpen, setHostSimOpen] = useState(false);
  const [overview, setOverview] = useState<OverviewRow[]>([]);
  const [hostSimTarget, setHostSimTarget] = useState('');

  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

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

  /** 拉某个会话的报警 / 终端消息 / 目录 / 事件计划。 */
  const refreshSessionDetail = useCallback(
    async (sessionId: string) => {
      if (client === null || sessionId === '') {
        return;
      }
      const [alarmsResult, terminalResult, catalogResult, eventsResult] = await Promise.allSettled([
        client.alarms(sessionId),
        client.terminalMessages(sessionId),
        client.catalog(sessionId),
        client.events(sessionId),
      ]);
      if (alarmsResult.status === 'fulfilled') {
        setAlarms(alarmsResult.value);
      }
      if (terminalResult.status === 'fulfilled') {
        setTerminalMessages(terminalResult.value);
      }
      if (catalogResult.status === 'fulfilled') {
        setCatalog(catalogResult.value);
      }
      if (eventsResult.status === 'fulfilled') {
        setEventPlans(eventsResult.value);
      }
      try {
        const sim = await client.hostSimState(sessionId);
        setHostSimOpen(sim?.open ?? false);
        setHostSimTarget(sim?.target ?? '');
      } catch {
        /* 老后端没有主机模拟端点 */
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

  const refreshAudit = useCallback(async () => {
    if (client === null) {
      setAuditEntries([]);
      return;
    }
    try {
      setAuditEntries(await client.audit(200, auditFilterRef.current));
    } catch {
      setAuditEntries([]);
    }
  }, [client]);

  /** 设置审计过滤并立刻按新过滤取一次(轮询此后也一直带着它);翻页缓存随之清空。 */
  const setAuditFilter = useCallback(async (filter: { action?: string; actor?: string }) => {
    setAuditFilterState((previous) => ({ ...previous, ...filter }));
    setAuditOlder([]);
    /* 用最新过滤直接取,不等下一次轮询 */
    if (client !== null) {
      try {
        setAuditEntries(await client.audit(200, { ...auditFilterRef.current, ...filter }));
      } catch {
        /* 取失败就留给下一轮轮询 */
      }
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


  /**
   * 统一刷新入口。
   *
   * <p>切面板、回到前台、点"全部刷新"都走这里。</p>
   */
  /** 配置草稿预览：模拟时钟 0..windowMs，从种子确定性重放。 */
  const previewDraft = useCallback(
    async (text: string, windowMs: number) => {
      if (client === null) {
        return;
      }
      try {
        setDraftPreview(await client.configPreview(text, 0, windowMs, 120));
      } catch (error) {
        notifierRef.current.error('草稿预览失败', (error as Error).message);
        setDraftPreview(null);
      }
    },
    [client],
  );

  /** 单 SVID 草稿预览(v6):带事件标记,给每 SVID 值来源编辑器用。 */
  const previewDraftSvid = useCallback(
    async (text: string, windowMs: number, svid: number): Promise<PreviewData | null> => {
      if (client === null) {
        return null;
      }
      try {
        return await client.configPreview(text, 0, windowMs, 90, [svid], true);
      } catch {
        return null;
      }
    },
    [client],
  );

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
        return await client.preview(sessionId, 0, windowMs, 90, [svid], true);
      } catch (error) {
        notifierRef.current.error('曲线预览失败', (error as Error).message);
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

  /** 工作台聚合(v6):速率/计数一览;老后端没有该端点时回空数组。 */
  const refreshOverview = useCallback(async () => {
    if (client === null) {
      return;
    }
    const rows = await client.overview();
    setOverview(rows);
  }, [client]);

  /** 服务端压测(v6)。 */
  const burst = useCallback(
    async (sessionId: string, request: BurstRequest): Promise<BurstResult> => {
      if (client === null) {
        throw new Error('未连接后端');
      }
      return client.burst(sessionId, request);
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
      setAlarms([]);
      setTerminalMessages([]);
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

  /* 会话没了就把它的明细清掉，别留着上一个会话的报警糊弄人 */
  useEffect(() => {
    if (activeSession === '') {
      setAlarms([]);
      setTerminalMessages([]);
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
    async (_configName: string) => {
      if (client === null) {
        return;
      }
      let hot = 0;
      for (const session of sessions) {
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
  const dataCommands = useDataCommands({
    client,
    notifier,
    setComparePreviewData,
    setCompareSessionId,
    setPreview,
    setPreviewSession,
    setHealth,
    activeSession,
  });











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







  /**
   * 按时间窗刷新曲线。
   *
   * <p>时间窗要相对"现在"而不是相对 0：后端引擎时钟从进程启动起算，
   * 所以每次都要先取一次 uptime 再算 from/to。</p>
   */
  const refreshPreview = useCallback(
    async (windowMs: number) => {
      if (client === null || activeSession === '') {
        return;
      }
      try {
        const fresh = await client.health();
        setHealth(fresh);
        const to = Math.max(1, fresh.uptimeMs ?? 0);
        const from = Math.max(0, to - windowMs);
        await dataCommands.fetchPreview(activeSession, from, to);
      } catch (error) {
        notifierRef.current.error('预览失败', (error as Error).message);
      }
    },
    [client, activeSession, dataCommands],
  );

  /* ---------- 配置目录 ---------- */






  return useMemo<BackendController>(
    () => ({
      ...sessionCommands,
      ...configCommands,
      ...dataCommands,
      health,
      sessions,
      activeSession,
      setActiveSession,
      ceid,
      setCeid,
      alarms,
      terminalMessages,
      catalog,
      eventPlans,
      auditEntries,
      auditOlder,
      auditFilter,
      setAuditFilter,
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
      configList,
      preview,
      previewSession,
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
      comparePreview,
      draftPreview,
      previewDraft,
      previewDraftSvid,
      compareSession,
      refreshPreview,
      hostSimOpen,
      hostSimTarget,
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
      alarms,
      terminalMessages,
      catalog,
      eventPlans,
      auditEntries,
      auditOlder,
      auditFilter,
      setAuditFilter,
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
      configList,
      preview,
      previewSession,
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
      comparePreview,
      draftPreview,
      previewDraft,
      previewDraftSvid,
      compareSession,
      refreshPreview,
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
