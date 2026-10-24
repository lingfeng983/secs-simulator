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
  type EventPlanView,
  type Health,
  type PreviewData,
  type SentMessageResult,
  type SessionSummary,
  type TerminalMessage,
} from '../api';
import { mergeMessages, type MessageEntry } from '../messageHistory';
import type { Notifier } from './useToasts';

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

/** 预览曲线默认桶数。 */
const PREVIEW_BUCKETS = 120;

/** 事件流日志保留的条数。 */
const MAX_LOG = 400;

/** 事件流日志的一行。 */
export interface LogLine {
  at: string;
  text: string;
}

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
  configList: ConfigEntry[];

  messages: MessageEntry[];
  /** 服务端记录的报文总数（可能大于面板里保留的条数）。 */
  messageTotal: number;

  /** 事件流日志。 */
  log: LogLine[];
  appendLog: (text: string) => void;
  clearLog: () => void;

  preview: PreviewData | null;
  previewSession: string;

  /** 上次成功刷新的时间戳。 */
  lastRefresh: number;
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
  /** 新建一份配置骨架；返回 HTTP 状态码。 */
  createConfig: (name: string) => Promise<number>;
  /** 读配置原文；读不到返回 null。 */
  readConfig: (name: string) => Promise<string | null>;
  /** 只校验不落盘。 */
  validateConfig: (name: string, text: string) => Promise<ConfigProblems | null>;
  /** 校验通过后落盘。 */
  saveConfig: (name: string, text: string) => Promise<ConfigSaveResult | null>;
  closeSession: (sessionId: string) => Promise<void>;
  reloadSession: (sessionId: string) => Promise<void>;
  trigger: (sessionId: string, ceid: number) => Promise<void>;
  setAlarm: (sessionId: string, alid: number, set: boolean, text: string, category: number) => Promise<void>;
  toggleEvent: (plan: EventPlanView) => Promise<void>;
  sendSml: (sessionId: string, sml: string) => Promise<SentMessageResult | null>;
  loadHistory: () => Promise<void>;
  exportMessages: () => Promise<void>;
  downloadPreview: (windowMs: number) => Promise<void>;
  fetchPreview: (sessionId: string, fromMs: number, toMs: number) => Promise<void>;
  /** 按时间窗刷新曲线（先取一次 uptime，时间窗才是"最近 N 分钟"而不是"开局 N 秒"）。 */
  refreshPreview: (windowMs: number) => Promise<void>;
  pushMessages: (messages: import('../api').WireMessage[]) => void;
  clearMessages: () => void;
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
  const [messages, setMessages] = useState<MessageEntry[]>([]);
  const [messageTotal, setMessageTotal] = useState(0);
  const [preview, setPreview] = useState<PreviewData | null>(null);
  const [previewSession, setPreviewSession] = useState('');
  const [lastRefresh, setLastRefresh] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const [log, setLog] = useState<LogLine[]>([]);

  const nextMessageKey = useRef(0);
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

  /** 拉某个会话的报警 / 终端消息 / 目录 / 事件计划。 */  const refreshSessionDetail = useCallback(
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
    },
    [client],
  );

  const refreshAudit = useCallback(async () => {
    if (client === null) {
      setAuditEntries([]);
      return;
    }
    try {
      setAuditEntries(await client.audit(200));
    } catch {
      setAuditEntries([]);
    }
  }, [client]);

  /**
   * 统一刷新入口。
   *
   * <p>切面板、回到前台、点"全部刷新"都走这里。</p>
   */
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
      ]);
      setLastRefresh(Date.now());
    } finally {
      setRefreshing(false);
    }
  }, [client, activeSession, refreshSessions, refreshSessionDetail, refreshAudit, refreshConfigs, refreshLogLevel]);

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
  }, [client, activeSession, refreshSessions, refreshSessionDetail, refreshAudit, refreshConfigs]);

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

  /* ---------- 写操作 ---------- */

  const createSession = useCallback(
    async (config: string) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.createSession(config.trim());
        const text = lifecycleMessage(status, '新建会话');
        if (status === 201) {
          notifierRef.current.success(text);
        } else {
          notifierRef.current.error(text);
        }
        await refreshSessions();
      } catch (error) {
        notifierRef.current.error('新建会话失败', (error as Error).message);
      }
    },
    [client, refreshSessions],
  );

  /** 从一份设备配置批量开多台设备；逐台独立，失败的台数不影响其余。 */
  const bulkCreateSessions = useCallback(
    async (params: SessionBulkParams) => {
      if (client === null) {
        return null;
      }
      try {
        const result = await client.bulkSessions(params);
        const failedNote = result.failed.length > 0 ? `，${result.failed.length} 台失败` : '';
        notifierRef.current.success(`批量新建完成：${result.created.length} 台${failedNote}`);
        await refreshSessions();
        return result;
      } catch (error) {
        notifierRef.current.error('批量新建失败', (error as Error).message);
        await refreshSessions();
        return null;
      }
    },
    [client, refreshSessions],
  );

  /**
   * 逐台执行的通用骨架：5 路并发 + 进度回调。
   *
   * <p>串行要 100 台 × 500ms = 50 秒；并发上限收在 5 是因为后端命令
   * 仍要占会话线程，开太高会把自己的事件心跳挤掉。</p>
   */
  const batchRun = useCallback(
    async (
      ids: ReadonlySet<string>,
      verb: string,
      run: (id: string) => Promise<number>,
      tone: 'success' | 'info',
      onProgress?: (done: number, total: number) => void,
    ) => {
      const total = ids.size;
      let ok = 0;
      let fail = 0;
      let done = 0;
      let next = 0;
      const list = [...ids];

      const worker = async (): Promise<void> => {
        for (;;) {
          const index = next;
          if (index >= total) {
            return;
          }
          next += 1;
          try {
            const status = await run(list[index]);
            if (status >= 200 && status < 300) {
              ok += 1;
            } else {
              fail += 1;
            }
          } catch {
            fail += 1;
          }
          done += 1;
          onProgress?.(done, total);
        }
      };

      await Promise.all(Array.from({ length: Math.min(5, total) }, () => worker()));
      notifierRef.current[tone](`批量${verb}完成：成功 ${ok}，失败 ${fail}`);
      await refreshSessions();
      return { ok, fail };
    },
    [refreshSessions],
  );

  const batchTrigger = useCallback(
    (ids: ReadonlySet<string>, ceid: number, onProgress?: (done: number, total: number) => void) =>
      batchRun(ids, '上报', (id) => client === null ? Promise.resolve(0) : client.trigger(id, ceid), 'success', onProgress),
    [batchRun, client],
  );

  const batchReload = useCallback(
    (ids: ReadonlySet<string>, onProgress?: (done: number, total: number) => void) =>
      batchRun(ids, '重载', (id) => client === null ? Promise.resolve(0) : client.reloadSession(id), 'info', onProgress),
    [batchRun, client],
  );

  const batchClose = useCallback(
    (ids: ReadonlySet<string>, onProgress?: (done: number, total: number) => void) =>
      batchRun(ids, '关闭', (id) => client === null ? Promise.resolve(0) : client.closeSession(id), 'info', onProgress),
    [batchRun, client],
  );

  const closeSession = useCallback(
    async (sessionId: string) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.closeSession(sessionId);
        const text = lifecycleMessage(status, `关闭会话 ${sessionId}`);
        if (status === 200 || status === 204) {
          notifierRef.current.success(text);
        } else {
          notifierRef.current.error(text);
        }
        await refreshSessions();
      } catch (error) {
        notifierRef.current.error('关闭会话失败', (error as Error).message);
      }
    },
    [client, refreshSessions],
  );

  const reloadSession = useCallback(
    async (sessionId: string) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.reloadSession(sessionId);
        const text = lifecycleMessage(status, `重载会话 ${sessionId}`);
        if (status === 200) {
          notifierRef.current.success(text);
        } else {
          notifierRef.current.error(text);
        }
        await refreshSessions();
        await refreshSessionDetail(sessionId);
      } catch (error) {
        notifierRef.current.error('重载会话失败', (error as Error).message);
      }
    },
    [client, refreshSessions, refreshSessionDetail],
  );

  const trigger = useCallback(
    async (sessionId: string, ceid: number) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.trigger(sessionId, ceid);
        const text = `触发 ${sessionId} CEID ${ceid} -> HTTP ${status}`;
        if (status >= 200 && status < 300) {
          notifierRef.current.success(text);
        } else {
          notifierRef.current.error(text);
        }
      } catch (error) {
        notifierRef.current.error('触发失败', (error as Error).message);
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

  const loadHistory = useCallback(async () => {
    if (client === null) {
      return;
    }
    try {
      const page = await client.messages('', '', 1000);
      setMessages((previous) => {
        const merged = mergeMessages(previous, page.messages, nextMessageKey.current, MAX_MESSAGES);
        nextMessageKey.current = merged.nextKey;
        return merged.entries;
      });
      setMessageTotal(page.total);
      notifierRef.current.success(
        page.truncated
          ? `已载入最近 ${page.messages.length} 条历史（共 ${page.total} 条）`
          : `已载入历史 ${page.messages.length} 条`,
      );
    } catch (error) {
      notifierRef.current.error('载入历史失败', (error as Error).message);
    }
  }, [client]);

  const exportMessages = useCallback(async () => {
    if (client === null) {
      return;
    }
    try {
      const text = await client.messagesJsonl('', '', 5000);
      if (text.trim() === '') {
        notifierRef.current.info('没有可导出的报文');
        return;
      }
      const blob = new Blob([text], { type: 'application/x-ndjson;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `messages-${new Date().toISOString().replace(/[:.]/g, '-')}.jsonl`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
      notifierRef.current.success(`已导出报文历史 ${text.trim().split('\n').length} 行`);
    } catch (error) {
      notifierRef.current.error('导出报文失败', (error as Error).message);
    }
  }, [client]);

  const fetchPreview = useCallback(
    async (sessionId: string, fromMs: number, toMs: number) => {
      if (client === null) {
        return;
      }
      try {
        const data = await client.preview(sessionId, fromMs, toMs, PREVIEW_BUCKETS);
        setPreview(data);
        setPreviewSession(sessionId);
      } catch (error) {
        notifierRef.current.error('预览失败', (error as Error).message);
      }
    },
    [client],
  );

  const downloadPreview = useCallback(
    async (windowMs: number) => {
      if (client === null || activeSession === '') {
        return;
      }
      try {
        const fresh = await client.health();
        setHealth(fresh);
        const to = Math.max(1, fresh.uptimeMs ?? 0);
        const from = Math.max(0, to - windowMs);
        const blob = await client.previewBinary(activeSession, from, to, PREVIEW_BUCKETS);
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `preview-${activeSession}-${Date.now()}.bin`;
        document.body.appendChild(link);
        link.click();
        link.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 0);
        notifierRef.current.success(`已下载 ${activeSession} 的二进制曲线（${blob.size} 字节）`);
      } catch (error) {
        notifierRef.current.error('下载二进制曲线失败', (error as Error).message);
      }
    },
    [client, activeSession],
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
        await fetchPreview(activeSession, from, to);
      } catch (error) {
        notifierRef.current.error('预览失败', (error as Error).message);
      }
    },
    [client, activeSession, fetchPreview],
  );

  /* ---------- 配置目录 ---------- */

  const createConfig = useCallback(
    async (name: string): Promise<number> => {
      if (client === null) {
        return 0;
      }
      try {
        const status = await client.createConfig(name);
        if (status === 201) {
          notifierRef.current.success(`已新建 ${name}，改完点「保存」才会落盘`);
        } else if (status === 409) {
          notifierRef.current.warn('已经有同名的配置了，直接从上面选它来改');
        } else {
          notifierRef.current.error(`新建失败（HTTP ${status}）`);
        }
        await refreshConfigs();
        return status;
      } catch (error) {
        notifierRef.current.error('新建配置失败', (error as Error).message);
        return 0;
      }
    },
    [client, refreshConfigs],
  );

  const readConfig = useCallback(
    async (name: string): Promise<string | null> => {
      if (client === null) {
        return null;
      }
      try {
        return await client.configText(name);
      } catch (error) {
        notifierRef.current.error(`打开配置 ${name} 失败`, (error as Error).message);
        return null;
      }
    },
    [client],
  );

  const validateConfig = useCallback(
    async (name: string, text: string): Promise<ConfigProblems | null> => {
      if (client === null) {
        return null;
      }
      try {
        return await client.validateConfig(name, text);
      } catch (error) {
        notifierRef.current.error('校验失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  const saveConfig = useCallback(
    async (name: string, text: string): Promise<ConfigSaveResult | null> => {
      if (client === null) {
        return null;
      }
      try {
        const result = await client.saveConfig(name, text);
        if (result.status === 200) {
          notifierRef.current.success(`已保存 ${name}（已在运行的会话需要重载才生效）`);
        } else if (result.status === 422) {
          notifierRef.current.error('保存被拒：配置有问题，未落盘');
        } else {
          notifierRef.current.error(`保存失败（HTTP ${result.status}）`);
        }
        return result;
      } catch (error) {
        notifierRef.current.error('保存失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  /* ---------- 报文 ---------- */

  const pushMessages = useCallback((incoming: import('../api').WireMessage[]) => {
    /* key 在 setState 之外分配：setState 的更新函数在开发模式下会被调用两次，
       在里面自增会让 key 跳号，React 的 key 就不再唯一了。 */
    const entries = incoming.map((message) => {
      const key = nextMessageKey.current;
      nextMessageKey.current += 1;
      return { key, msg: message };
    });
    setMessages((previous) => {
      const next = [...previous, ...entries];
      return next.length > MAX_MESSAGES ? next.slice(next.length - MAX_MESSAGES) : next;
    });
  }, []);

  const clearMessages = useCallback(() => {
    setMessages([]);
  }, []);

  /** 往事件流日志追加一行。 */
  const appendLog = useCallback((text: string) => {
    setLog((previous) => {
      const next = [...previous, { at: new Date().toLocaleTimeString(), text }];
      return next.length > MAX_LOG ? next.slice(next.length - MAX_LOG) : next;
    });
  }, []);

  const clearLog = useCallback(() => {
    setLog([]);
  }, []);

  return useMemo<BackendController>(
    () => ({
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
      configList,
      messages,
      messageTotal,
      log,
      appendLog,
      clearLog,
      preview,
      previewSession,
      lastRefresh,
      refreshing,
      refreshAll,
      refreshSessions,
      refreshConfigs,
      createSession,
      bulkCreateSessions,
      batchTrigger,
      batchReload,
      batchClose,
      logLevel,
      backendError,
      setLogLevel,
      createConfig,
      readConfig,
      validateConfig,
      saveConfig,
      closeSession,
      reloadSession,
      trigger,
      setAlarm,
      toggleEvent,
      sendSml,
      loadHistory,
      exportMessages,
      downloadPreview,
      fetchPreview,
      refreshPreview,
      pushMessages,
      clearMessages,
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
      configList,
      messages,
      messageTotal,
      log,
      appendLog,
      clearLog,
      preview,
      previewSession,
      lastRefresh,
      refreshing,
      refreshAll,
      refreshSessions,
      refreshConfigs,
      createSession,
      bulkCreateSessions,
      batchTrigger,
      batchReload,
      batchClose,
      logLevel,
      backendError,
      setLogLevel,
      createConfig,
      readConfig,
      validateConfig,
      saveConfig,
      closeSession,
      reloadSession,
      trigger,
      setAlarm,
      toggleEvent,
      sendSml,
      loadHistory,
      exportMessages,
      downloadPreview,
      fetchPreview,
      refreshPreview,
      pushMessages,
      clearMessages,
    ],
  );
}
