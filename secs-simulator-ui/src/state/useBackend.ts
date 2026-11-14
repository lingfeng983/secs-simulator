import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  type ApiClient,
  type AuditEntryView,
  type BurstRequest,
  type BurstResult,
  type Catalog,
  type CeidInput,
  type CollectorConfigInput,
  type CollectorState,
  type ConfigEntry,
  type ConfigProblems,
  type ConfigSaveResult,
  type ConfigTemplateEntry,
  type ConfigTextSnapshot,
  type EventPlanInput,
  type EventPlanView,
  type Health,
  type HostSimTarget,
  type OverviewRow,
  type PreviewData,
  type ReportInput,
  type SentMessageResult,
  type SessionBulkParams,
  type SessionBulkResult,
  type SessionSummary,
  type SvidInput,
} from '../api';
import type { Notifier } from './useToasts';
import { createRequestSeq } from './requestSeq';
import { useSessionCommands } from './useSessionCommands';
import { useConfigCommands } from './useConfigCommands';
import { useDataCommands } from './useDataCommands';
import { useAuditFeed } from './useAuditFeed';
import { useDeviceEdits } from './useDeviceEdits';
import { useDeviceOps } from './useDeviceOps';
import { useServerOps } from './useServerOps';
import { useLogLevel } from './useLogLevel';

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

  /** 新建会话；返回 HTTP 状态码（201 = 建成，0 = 未连后端或请求异常）。 */
  createSession: (config: string) => Promise<number>;
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
  /** 重新建链：断开态的设备对象复用，模型/钉值/计划启用态原样恢复。 */
  connectSession: (deviceId: string) => Promise<void>;
  /** 手动上线 / 下线（GEM 里等价于主机发 S1F17 / S1F15）；未建链时提示先启动。 */
  setDeviceOnline: (deviceId: string, online: boolean) => Promise<void>;
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
  openHostSim: (sessionId: string, target?: HostSimTarget) => Promise<void>;
  closeHostSim: (sessionId: string) => Promise<void>;
  /** 以主机角色发送 SML；返回发送结果。 */
  sendHostSml: (sessionId: string, sml: string) => Promise<SentMessageResult | null>;

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
 * 后端控制器。
 *
 * <p>这里只剩"装配"：轮询调度、当前会话选择，以及把各域 hook 拼成
 * {@link BackendController}。具体操作按域拆在 state/ 下的 useXxx hook 里
 * （会话、配置、审计、运行时编辑、设备操作、服务端任务、日志级别），
 * 本文件不再承载业务细节。</p>
 *
 * <p>轮询收成<b>一个</b>调度器：每个资源按自己的节奏到点才刷新，
 * 页面切到后台就整体暂停，回到前台立刻补一次。原来五个定时器各打各的请求，
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
  const [configList, setConfigList] = useState<ConfigEntry[]>([]);
  const [refreshing, setRefreshing] = useState(false);

  /** 当前活跃会话的同步镜像：异步响应回来时判断"是不是已经切走了"。 */
  const activeSessionRef = useRef(activeSession);
  activeSessionRef.current = activeSession;

  /* 轮询序号：请求可能叠 4 期（8s 超时 × 2s 节奏），过期响应既不能覆盖
     新快照，也不能把旧的失败写回 backendError（新一次已成功）。 */
  const sessionsSeqRef = useRef(createRequestSeq());

  /* ---------- 读取 ---------- */

  const refreshSessions = useCallback(async () => {
    if (client === null) {
      return;
    }
    const token = sessionsSeqRef.current.begin();
    const [healthResult, sessionsResult] = await Promise.allSettled([client.health(), client.sessions()]);
    if (!sessionsSeqRef.current.isCurrent(token)) {
      return;
    }
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

  /* ---------- 各域 hook ---------- */

  const audit = useAuditFeed(client, notifier);
  const edits = useDeviceEdits({ client, notifier, activeSession, refreshSessionDetail });
  const ops = useDeviceOps({ client, notifier, activeSession, refreshSessionDetail });
  const serverOps = useServerOps(client);
  const logLevel = useLogLevel(client, notifier);

  const refreshAll = useCallback(async () => {
    if (client === null) {
      return;
    }
    setRefreshing(true);
    try {
      await Promise.all([
        refreshSessions(),
        refreshSessionDetail(activeSession),
        audit.refresh(),
        refreshConfigs(),
        logLevel.refresh(),
        serverOps.refreshOverview(),
      ]);
    } finally {
      setRefreshing(false);
    }
  }, [client, activeSession, refreshSessions, refreshSessionDetail, audit.refresh, refreshConfigs, logLevel.refresh, serverOps.refreshOverview]);

  /* 单一调度器：按各自节奏到点刷新 */
  useEffect(() => {
    if (client === null) {
      setHealth(null);
      setSessions([]);
      setCatalog(null);
      setEventPlans([]);
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
        void audit.refresh();
      }
      if (now >= due.config) {
        due.config = now + INTERVAL.config;
        void refreshConfigs();
      }
      if (now >= due.overview) {
        due.overview = now + INTERVAL.audit;
        void serverOps.refreshOverview();
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
  }, [client, activeSession, refreshSessions, refreshSessionDetail, audit.refresh, refreshConfigs, serverOps.refreshOverview]);

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

  /* 切会话先把明细清掉：不清的话，在新会话的目录回来之前，页面上挂着的
     是上一台设备的 CEID/报告/SVID 与计划列表——标题已经换了、数据没换。
     而且新会话的拉取一旦失败（或一直没回来），这份旧数据会**永久**留着。
     用 layout effect 是为了赶在浏览器绘制之前清掉，不让用户看到错帧。
     这里不能只判 `activeSession === ''`：断开/删除设备时 activeSession 未必
     先变空，两个非空会话之间直接切换才是最常走的那条路。 */
  useLayoutEffect(() => {
    setCatalog(null);
    setEventPlans([]);
  }, [activeSession]);

  const sessionCommands = useSessionCommands({ client, notifier, refreshSessions, refreshSessionDetail });

  /**
   * 保存配置后让相关会话生效：对每个运行中会话调一次重载。
   *
   * <p>sessions JSON 不带配置来源，界面无法精确匹配"哪些会话用这份文件"；
   * 重载对每个会话都按<b>它自己的来源</b>重读——用的是同一份磁盘目录，
   * 保存过的改动自然被带上；演示会话回 not-reloadable，被安静跳过。
   * 连接标识没变的会话走热生效不断连，变化了才重建。</p>
   */
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;
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
      let failed = 0;
      for (const session of targets) {
        try {
          const status = await client.reloadSession(session.id);
          if (status === 200) {
            hot += 1;
          } else {
            failed += 1;
          }
        } catch {
          /* 单个会话重载失败不阻断其余，但要计数——全部失败还报"已生效"，
             用户会以为改动上线了，实际设备还在跑旧配置 */
          failed += 1;
        }
      }
      if (failed > 0) {
        notifierRef.current.warn(
          `配置已保存，但 ${failed} 个会话未能生效`,
          `${hot} 个已重载（连接标识未变的走热生效）；没生效的仍在用旧配置，回工作台点「重载」重试`,
        );
      } else if (hot > 0) {
        notifierRef.current.info(`配置已生效：${hot} 个会话已重载（连接标识未变的走热生效，主机不断连）`);
      }
      await refreshSessions();
    },
    [client, sessions, refreshSessions],
  );

  const configCommands = useConfigCommands({ client, notifier, refreshConfigs, reloadSessionsUsing });
  const dataCommands = useDataCommands({ client, notifier, setHealth });

  return useMemo<BackendController>(
    () => ({
      ...sessionCommands,
      ...configCommands,
      ...dataCommands,
      ...edits,
      ...ops,
      client,
      health,
      sessions,
      activeSession,
      setActiveSession,
      ceid,
      setCeid,
      catalog,
      eventPlans,
      auditEntries: audit.entries,
      auditOlder: audit.older,
      auditFilter: audit.filter,
      setAuditFilter: audit.setFilter,
      auditError: audit.error,
      loadAuditOlder: audit.loadOlder,
      configList,
      refreshing,
      refreshAll,
      refreshSessions,
      refreshConfigs,
      logLevel: logLevel.level,
      backendError,
      setLogLevel: logLevel.set,
      overview: serverOps.overview,
      refreshOverview: serverOps.refreshOverview,
      burst: serverOps.burst,
      collectorState: serverOps.collectorState,
      saveCollector: serverOps.saveCollector,
      collectorCommand: serverOps.collectorCommand,
    }),
    [
      sessionCommands,
      configCommands,
      dataCommands,
      edits,
      ops,
      client,
      health,
      sessions,
      activeSession,
      ceid,
      catalog,
      eventPlans,
      audit.entries,
      audit.older,
      audit.filter,
      audit.setFilter,
      audit.error,
      audit.loadOlder,
      configList,
      refreshing,
      refreshAll,
      refreshSessions,
      refreshConfigs,
      logLevel.level,
      logLevel.set,
      backendError,
      serverOps.overview,
      serverOps.refreshOverview,
      serverOps.burst,
      serverOps.collectorState,
      serverOps.saveCollector,
      serverOps.collectorCommand,
    ],
  );
}
