import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { createClient, readApiBase, saveApiBase, type WireMessage } from '../api';
import { inDesktopShell, tauriInvoke, type ShellConnection } from '../shared/lib/tauri';
import { mergeMessages } from '../shared/lib/messageFilter';
import { useBackend } from '../state/useBackend';
import { usePreferences, type ThemePreference } from '../state/usePreferences';
import { useToasts } from '../state/useToasts';
import { useEventStream, type NoticeWire } from '../state/useEventStream';
import { ToastHost } from '../shared/ui/ToastHost';
import { CommandPalette } from '../shared/ui/CommandPalette';
import { ShortcutHelp, type ShortcutHint } from '../shared/ui/ShortcutHelp';
import { Modal } from '../shared/ui/Modal';
import { useHotkeys } from '../shared/ui/useHotkeys';
import type { CommandItem } from '../shared/lib/command';

import { NAV_ITEMS, routeTitle } from './layout/navigation';
import { Sidebar, type SidebarDevice } from './layout/Sidebar';
import { Topbar } from './layout/Topbar';

import { useRoute, type DevicePage, type DeviceZone, type HostPage, type HostZone } from './router';
import { useHostsV2, hostPhaseLabel, hostPhaseTone } from '../state/useHostsV2';
import { setHostDetailClient } from '../features/panels/hostsim/HostDetailPanels';
import { WorkbenchPage } from '../features/pages/WorkbenchPage';
import { DevicePage as DevicePageView, DEVICE_PAGE_DESCRIPTIONS } from '../features/pages/DevicePage';
import {
  DEVICE_CONFIG_PAGES,
  DEVICE_CONFIG_PAGE_LABELS,
  DEVICE_RUNTIME_PAGES,
  DEVICE_RUNTIME_PAGE_LABELS,
  HOST_CONFIG_PAGE_LABELS,
  HOST_RUNTIME_PAGE_LABELS,
} from './router';
import { HostListPage } from '../features/pages/HostListPage';
import { HostDetailPage } from '../features/pages/HostDetailPage';
import { HostTemplatePage } from '../features/pages/HostTemplatePage';
import { SystemPage } from '../features/pages/SystemPage';
import { TemplatePage } from '../features/pages/TemplatePage';

/** SSE 类型化通知的保留条数(事件流 feed 用)。 */
const MAX_LIVE_EVENTS = 200;

/** 界面保留的报文条数（消息监视面板用；与后端内存上限 5000 不同，界面只留最近的）。 */
const MAX_LIVE_MESSAGES = 5_000;

/** 快捷键说明表。 */
const SHORTCUTS: readonly ShortcutHint[] = [
  { keys: 'Ctrl+K', description: '打开命令面板：跳页面、找 CEID、执行动作' },
  { keys: '1..9 / 0', description: '跳到导航入口' },
  { keys: 'R', description: '立即刷新所有数据' },
  { keys: '/', description: '聚焦当前页面的搜索框' },
  { keys: '[', description: '收起 / 展开导航栏' },
  { keys: '?', description: '打开这张快捷键表' },
  { keys: 'Esc', description: '关闭浮层' },
];

/**
 * 应用外壳（精简后的信息架构）。
 *
 * <p>hash 路由:<code>#/workbench</code>(总览+会话管理)、
 * <code>#/device/{id}/{page}</code>、<code>#/system/audit</code>。
 * 事件流/报文/事件流日志等全局页已精简;设备页一页一能力
 * (曲线/事件/报警/目录/配置/发送/压测/主机模拟/采集)。</p>
 */
export function App(): JSX.Element {
  const [connOpen, setConnOpen] = useState(false);
  const [draftBase, setDraftBase] = useState('');
  const [shellConn, setShellConn] = useState<ShellConnection | null>(null);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [liveEvents, setLiveEvents] = useState<NoticeWire[]>([]);
  /** 实时报文缓冲：SSE 增量 + 历史补拉，消息监视面板消费。 */
  const [liveMessages, setLiveMessages] = useState<WireMessage[]>([]);
  /** SSE 报文先攒 150 ms 再合并：高峰期逐条 setState 会让整页每秒重渲染几十次。 */
  const pendingMessages = useRef<WireMessage[]>([]);
  const messageFlushTimer = useRef<number | null>(null);

  const notifier = useToasts();
  const { preferences, update, resolvedTheme } = usePreferences();
  /* 后端地址改变时整页重载，这里只需按当前地址建一次客户端 */
  const client = useMemo(() => createClient(), []);
  const backend = useBackend(client, notifier);

  const [route, navigate] = useRoute(preferences.activeTab);
  const hostV2 = useHostsV2(client);
  setHostDetailClient(client);

  /** 打开「连接设置」：桌面壳内显示当前模式并预填远程地址；浏览器环境维持 localStorage 编辑。 */
  const openConnection = useCallback(() => {
    if (inDesktopShell) {
      setShellConn(null);
      setDraftBase('');
      void tauriInvoke<ShellConnection>('connection_info')
        .then((info) => {
          setShellConn(info);
          setDraftBase(info.mode === 'remote' ? info.url : '');
        })
        .catch(() => setShellConn(null));
    } else {
      setDraftBase(readApiBase());
    }
    setConnOpen(true);
  }, [client]);

  /** 桌面壳内切换后端：local 或远程地址；成功后整页重载（boot 重新取地址）。 */
  const applyShellConnection = useCallback(
    async (target: string) => {
      try {
        await tauriInvoke('switch_backend', { target });
        window.location.reload();
      } catch (error) {
        notifier.error('切换后端失败', String(error));
      }
    },
    [notifier],
  );

  /** 补拉最近报文历史：页面刷新/SSE 重连后，消息监视面板不至于从空白开始。 */
  const reloadMessageHistory = useCallback(() => {
    void client
      .messages('', '', 5_000)
      .then((page) => {
        setLiveMessages((previous) => mergeMessages(previous, page.messages, MAX_LIVE_MESSAGES));
      })
      .catch(() => {
        /* 后端不支持/不可达时保持现有缓冲，下一轮 SSE 会继续补 */
      });
  }, [client]);

  useEffect(() => {
    reloadMessageHistory();
  }, [reloadMessageHistory]);

  /** SSE 报文批量入缓冲：150 ms 合并一次，避免高吞吐时整页跟着每条报文重渲染。 */
  const enqueueMessages = useCallback((batch: WireMessage[]) => {
    pendingMessages.current.push(...batch);
    /* 极端情况下（后台标签页）也不让待合并队列无限长 */
    if (pendingMessages.current.length > 4000) {
      pendingMessages.current = pendingMessages.current.slice(-4000);
    }
    if (messageFlushTimer.current !== null) {
      return;
    }
    messageFlushTimer.current = window.setTimeout(() => {
      messageFlushTimer.current = null;
      const pending = pendingMessages.current;
      pendingMessages.current = [];
      setLiveMessages((previous) => mergeMessages(previous, pending, MAX_LIVE_MESSAGES));
    }, 150);
  }, []);

  useEffect(() => () => {
    if (messageFlushTimer.current !== null) {
      window.clearTimeout(messageFlushTimer.current);
      messageFlushTimer.current = null;
    }
  }, []);

  /* 事件流:报文进 liveMessages（消息监视面板），类型化通知进 liveEvents。 */
  useEventStream(client, preferences.pauseStream, {
    onFlush: (batch) => {
      enqueueMessages(batch);
    },
    onReconnected: reloadMessageHistory,
    onNoticeEvent: useCallback(
      (notice: NoticeWire) => {
        setLiveEvents((previous) => {
          const next = [...previous, notice];
          return next.length > MAX_LIVE_EVENTS ? next.slice(next.length - MAX_LIVE_EVENTS) : next;
        });
        if (notice.type === 'session') {
          /* 设备状态迁移:工作台表格与侧栏状态点要跟上 */
          void backend.refreshSessions();
        }
      },
      [backend],
    ),
  });

  /** 兼容旧面板 id 的导航:面板与命令面板都只会说"去 config / 去 sessions"这类话。 */
  const goTo = useCallback(
    (id: string) => {
      if (id.startsWith('device-tab:')) {
        /* 旧面板 id:映射进两段式 */
        const [, sessionId, tab] = id.split(':');
        const mapped: Record<string, [DeviceZone, DevicePage]> = {
          ceids: ['runtime', 'catalog'],
          reports: ['runtime', 'catalog'],
          events: ['runtime', 'plans'],
          alarms: ['runtime', 'manual'],
          svids: ['runtime', 'svids'],
          messages: ['runtime', 'messages'],
          send: ['runtime', 'manual'],
          connection: ['config', 'connection'],
          structure: ['config', 'structure'],
          config: ['config', 'document'],
        };
        const hit = mapped[tab] ?? ['runtime', 'catalog'];
        navigate({ name: 'device', sessionId, zone: hit[0], page: hit[1] });
        return;
      }
      if (id.startsWith('device-zone:')) {
        const [, sessionId, zone] = id.split(':');
        navigate({ name: 'device', sessionId, zone: zone as DeviceZone, page: zone === 'config' ? 'overview' : 'status' });
        return;
      }
      if (id.startsWith('device-page:')) {
        const [, sessionId, zone, page] = id.split(':');
        navigate({ name: 'device', sessionId, zone: zone as DeviceZone, page: page as DevicePage });
        return;
      }
      if (id.startsWith('host-open:')) {
        navigate({ name: 'host', hostId: id.slice('host-open:'.length), zone: 'config', page: 'overview' });
        return;
      }
      if (id.startsWith('host-zone:')) {
        const [, hostId, zone] = id.split(':');
        navigate({ name: 'host', hostId, zone: zone as HostZone, page: zone === 'config' ? 'overview' : 'status' });
        return;
      }
      if (id.startsWith('host-page:')) {
        const [, hostId, zone, page] = id.split(':');
        navigate({ name: 'host', hostId, zone: zone as HostZone, page: page as HostPage });
        return;
      }
      if (id === 'hosts') {
        navigate({ name: 'hosts' });
        return;
      }
      if (id === 'host-templates') {
        navigate({ name: 'host-templates' });
        return;
      }
      if (id.startsWith('device:')) {
        navigate({ name: 'device', sessionId: id.slice('device:'.length), zone: 'runtime', page: 'catalog' });
        return;
      }
      if (id.startsWith('device-open:')) {
        navigate({ name: 'device', sessionId: id.slice('device-open:'.length), zone: 'config', page: 'overview' });
        return;
      }
      if (id.startsWith('hostsim-page:')) {
        /* 旧主机模拟子页 → 主机列表页(详情从实例树进) */
        navigate({ name: 'hosts' });
        return;
      }
      switch (id) {
        case 'workbench':
        case 'overview':
        case 'events':
        case 'messages':
        case 'sessions':
          navigate({ name: 'workbench' });
          return;
        case 'audit':
        case 'console':
          navigate({ name: 'system', page: 'audit' });
          return;
        case 'templates':
          navigate({ name: 'templates' });
          return;
        case 'hostsim':
          navigate({ name: 'hosts' });
          return;
        case 'host-templates':
          navigate({ name: 'host-templates' });
          return;
        default: {
          /* 设备能力 id → 当前设备对应子页;旧 id 归到新家(preview/catalog→状态变量,burst/collector→就近) */
          const sessionId = backend.activeSession;
          if (sessionId !== '') {
            const mapped: Record<string, [DeviceZone, DevicePage]> = {
              preview: ['runtime', 'svids'],
              catalog: ['runtime', 'catalog'],
              burst: ['runtime', 'manual'],
              collector: ['config', 'overview'],
            };
            const hit = mapped[id] ?? ['runtime', 'catalog'];
            navigate({ name: 'device', sessionId, zone: hit[0], page: hit[1] });
          } else {
            navigate({ name: 'workbench' });
          }
        }
      }
    },
    [backend.activeSession, navigate],
  );

  /* 切页面时立刻拉一次新数据：不要让用户盯着上一页的旧值。
     用 ref 取最新的 refreshAll，避免闭包里钉住旧的 activeSession。 */
  const refreshRef = useRef(backend.refreshAll);
  refreshRef.current = backend.refreshAll;

  const routeKey = route.name === 'device'
    ? `device:${route.sessionId}:${route.zone}:${route.page}`
    : route.name === 'host'
      ? `host:${route.hostId}:${route.zone}:${route.page}`
      : route.name === 'hosts' ? 'hosts'
        : route.name === 'host-templates' ? 'host-templates'
          : route.name === 'system' ? `system:${route.page}`
            : route.name === 'templates' ? 'templates' : 'workbench';

  useEffect(() => {
    void refreshRef.current();
  }, [routeKey, client]);

  /* ---------- 命令面板 ---------- */

  const commands = useMemo<CommandItem[]>(() => {
    const items: CommandItem[] = [];

    for (const tab of NAV_ITEMS) {
      items.push({
        id: `tab:${tab.id}`,
        label: `前往 ${tab.label}`,
        group: '跳转',
        keywords: [tab.id, tab.label],
        run: () => goTo(tab.id),
      });
    }

    for (const session of backend.sessions) {
      items.push({
        id: `device:${session.id}`,
        label: `打开设备 ${session.device}（${session.id}）`,
        group: '设备',
        hint: session.controlState ?? session.state,
        keywords: ['device', session.id, session.device],
        run: () => goTo(`device:${session.id}`),
      });
      items.push({
        id: `device-page:${session.id}:config`,
        label: `${session.id} · 配置区`,
        group: '设备',
        keywords: [session.id, 'config'],
        run: () => goTo(`device-open:${session.id}`),
      });
      items.push({
        id: `device-page:${session.id}:runtime`,
        label: `${session.id} · 运行区`,
        group: '设备',
        keywords: [session.id, 'runtime'],
        run: () => goTo(`device-tab:${session.id}:svids`),
      });
    }

    for (const host of hostV2.hosts) {
      items.push({
        id: `host:${host.id}`,
        label: `打开主机 ${host.name}（${host.id}）`,
        group: '主机',
        hint: hostPhaseLabel(host.phase),
        keywords: ['host', host.id, host.name],
        run: () => goTo(`host-open:${host.id}`),
      });
    }

    items.push(
      {
        id: 'action:refresh',
        label: '刷新全部数据',
        group: '操作',
        hint: 'R',
        run: () => void backend.refreshAll(),
      },
      {
        id: 'action:theme',
        label: '切换主题（浅色 / 深色 / 跟随系统）',
        group: '操作',
        hint: resolvedTheme === 'dark' ? '当前深色' : '当前浅色',
        run: () => {
          const order: ThemePreference[] = ['light', 'dark', 'system'];
          const next = order[(order.indexOf(preferences.theme) + 1) % order.length];
          update({ theme: next });
        },
      },
      {
        id: 'action:density',
        label: '切换表格密度（紧凑 / 宽松）',
        group: '操作',
        hint: preferences.density === 'compact' ? '当前紧凑' : '当前宽松',
        run: () => {
          update({ density: preferences.density === 'compact' ? 'comfortable' : 'compact' });
        },
      },
      {
        id: 'action:sidebar',
        label: '收起 / 展开导航栏',
        group: '操作',
        hint: '[',
        run: () => update({ sidebarCollapsed: !preferences.sidebarCollapsed }),
      },
      {
        id: 'action:shortcuts',
        label: '查看快捷键',
        group: '操作',
        hint: '?',
        run: () => setHelpOpen(true),
      },
    );

    /* 目录里的 CEID / SVID 直接变成命令：不用先找设备再翻目录 */
    for (const ceid of backend.catalog?.ceids ?? []) {
      items.push({
        id: `ceid:${ceid.ceid}`,
        /* 这个动作只是把 CEID 填进输入框，不触发上报；文案必须说清，免得误操作 */
        label: `填入 CEID ${ceid.ceid}${ceid.slot ? ` · ${ceid.slot}` : ''}`,
        group: '设备目录',
        hint: ceid.owner,
        keywords: ['ceid', String(ceid.ceid), ceid.slot ?? ''],
        run: () => {
          backend.setCeid(ceid.ceid);
          goTo('sessions');
          notifier.info(`已把 CEID 填成 ${ceid.ceid}`);
        },
      });
    }

    for (const svid of backend.catalog?.svids ?? []) {
      items.push({
        id: `svid:${svid.svid}`,
        label: `查看 SVID ${svid.svid}${svid.slot ? ` · ${svid.slot}` : ''}`,
        group: '设备目录',
        hint: svid.unit,
        keywords: ['svid', String(svid.svid), svid.slot ?? ''],
        run: () => goTo('catalog'),
      });
    }

    return items;
  }, [backend, goTo, notifier, preferences.density, preferences.sidebarCollapsed, preferences.theme, resolvedTheme, update]);

  /* ---------- 快捷键 ---------- */

  useHotkeys(
    [
      { combo: 'mod+k', description: '命令面板', allowInInput: true, handler: () => setPaletteOpen(true) },
      { combo: '?', description: '快捷键表', handler: () => setHelpOpen((previous) => !previous) },
      { combo: 'r', description: '刷新', handler: () => void backend.refreshAll() },
      { combo: '/', description: '聚焦搜索', handler: () => focusSearch() },
      { combo: '[', description: '收起 / 展开导航', handler: () => update({ sidebarCollapsed: !preferences.sidebarCollapsed }) },
      {
        combo: 'escape',
        description: '关闭浮层',
        handler: () => {
          setPaletteOpen(false);
          setHelpOpen(false);
        },
      },
      ...NAV_ITEMS.slice(0, 10).map((item, index) => ({
        combo: index === 9 ? '0' : String(index + 1),
        description: `跳到 ${item.label}`,
        handler: () => goTo(item.id),
      })),
    ],
    /* 浮层打开时只放行 Esc：否则在对话框里按 1..9 会在背后跳页、r 会触发全量刷新 */
    !paletteOpen && !helpOpen && !connOpen,
  );

  /* ---------- 布局 ---------- */

  const deviceName = route.name === 'device'
    ? backend.sessions.find((session) => session.id === route.sessionId)?.device ?? route.sessionId
    : '';
  const devicePageDescription = route.name === 'device'
    ? (route.zone === 'config' ? DEVICE_CONFIG_PAGE_LABELS : DEVICE_RUNTIME_PAGE_LABELS)[route.page as never]
    : '';
  const hostTitle = route.name === 'host'
    ? (() => {
      const host = hostV2.find(route.hostId);
      const label = host === null ? route.hostId : `${host.name} · ${host.id}`;
      const description = route.zone === 'config'
        ? HOST_CONFIG_PAGE_LABELS[route.page as never]
        : HOST_RUNTIME_PAGE_LABELS[route.page as never];
      return { group: '主机清单', label, description };
    })()
    : null;
  const title = route.name === 'device'
    ? { group: '设备清单', label: deviceName, description: devicePageDescription }
    : route.name === 'host' && hostTitle !== null
      ? hostTitle
      : routeTitle(route);

  const sidebarDevices: SidebarDevice[] = backend.sessions.map((session) => ({
    id: session.id,
    label: `${session.device}`,
    state: session.controlState ?? 'OFFLINE',
    template: backend.configList.find((entry) => entry.name === session.config)?.template ?? '',
    config: session.config,
  }));

  const panelProps = {
    backend,
    notifier,
    preferences,
    updatePreferences: update,
    goTo,
    liveEvents,
    messages: liveMessages,
  };

  return (
    <div
      className={`app${preferences.sidebarCollapsed ? ' sidebar-collapsed' : ''}`}
      /* 宽度用 CSS 变量驱动 grid 布局：拖动时只改这一个变量，不重排 DOM */
      style={{ '--sidenav-w': `${preferences.sidebarWidth}px` } as CSSProperties}
    >
      <Sidebar
        route={route}
        devices={sidebarDevices}
        hostInstances={hostV2.hosts}
        onNavigateHost={(hostId) => goTo(`host-open:${hostId}`)}
        collapsed={preferences.sidebarCollapsed}
        width={preferences.sidebarWidth}
        onResize={(sidebarWidth) => update({ sidebarWidth })}
        onNavigate={navigate}
        onToggleCollapse={() => update({ sidebarCollapsed: !preferences.sidebarCollapsed })}
      />

      <div className="main-col">
        <Topbar
          title={title}
          refreshing={backend.refreshing}
          onRefresh={() => void backend.refreshAll()}
          onOpenPalette={() => setPaletteOpen(true)}
          onOpenConnection={openConnection}
          logLevel={backend.logLevel}
          onLogLevel={(level) => void backend.setLogLevel(level)}
          resolvedTheme={resolvedTheme}
          onToggleTheme={() => update({ theme: resolvedTheme === 'dark' ? 'light' : 'dark' })}
          density={preferences.density}
          onToggleDensity={() => update({ density: preferences.density === 'compact' ? 'comfortable' : 'compact' })}
          onShowHelp={() => setHelpOpen(true)}
        />

        {backend.backendError !== '' && (
          <div className="alert-bar" role="alert">
            <span>后端不可达：{backend.backendError}</span>
            <button type="button" onClick={() => void backend.refreshAll()} title="立即重试">
              重试
            </button>
            <button
              type="button"
              onClick={openConnection}
            >
              修改地址
            </button>
          </div>
        )}

        <main className="content">
          {route.name === 'workbench' && <WorkbenchPage {...panelProps} />}
          {route.name === 'device' && (
            <DevicePageView {...panelProps} sessionId={route.sessionId} zone={route.zone} page={route.page} />
          )}
          {route.name === 'hosts' && <HostListPage {...panelProps} hosts={hostV2} />}
          {route.name === 'host' && (
            <HostDetailPage {...panelProps} hosts={hostV2} hostId={route.hostId} zone={route.zone} page={route.page} />
          )}
          {route.name === 'host-templates' && (
            <HostTemplatePage {...panelProps} hosts={hostV2} />
          )}
          {route.name === 'system' && <SystemPage {...panelProps} page={route.page} />}
      {route.name === 'templates' && <TemplatePage {...panelProps} />}
        </main>
      </div>

      <ToastHost toasts={notifier.toasts} onDismiss={notifier.dismiss} onDismissAll={notifier.dismissAll} />

      <CommandPalette open={paletteOpen} items={commands} onClose={() => setPaletteOpen(false)} />

      <Modal
        open={connOpen}
        title="连接设置"
        onClose={() => setConnOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setConnOpen(false)}>
              取消
            </button>
            <button
              type="button"
              className="primary"
              onClick={() => {
                if (inDesktopShell) {
                  /* 桌面壳：留空切回本机后端，填地址切远程（壳会停掉本机进程） */
                  void applyShellConnection(draftBase.trim() === '' ? 'local' : draftBase.trim());
                  return;
                }
                const previous = readApiBase();
                saveApiBase(draftBase);
                if (previous !== readApiBase()) {
                  window.location.reload();
                  return;
                }
                setConnOpen(false);
                notifier.info('后端地址未变化');
              }}
            >
              保存并重载
            </button>
          </>
        }
      >
        {inDesktopShell && (
          <p className="hint">
            当前：
            {shellConn === null
              ? '未知'
              : shellConn.mode === 'local'
                ? `本机后端（${shellConn.url}）`
                : `远程服务端（${shellConn.url}）`}
          </p>
        )}
        <div className="form-row">
          <label className="field">
            后端地址
            <input
              value={draftBase}
              onChange={(event) => setDraftBase(event.target.value)}
              placeholder={inDesktopShell ? '留空 = 本机后端' : '留空 = 本机（http://127.0.0.1:端口）'}
              aria-label="后端地址"
            />
          </label>
        </div>
        <p className="hint">
          {inDesktopShell ? (
            <>
              填远程服务端地址（如 <code>http://192.168.1.10:8080</code>）切换到远程，
              本机后端会停止；留空切回本机。远程端直接访问，不需要任何令牌或账号。
            </>
          ) : (
            <>
              默认同源访问；要把这个界面指向另一台机器上的运行时后端，
              填它的地址（如 <code>http://192.168.1.10:8080</code>），保存后页面重载。
            </>
          )}
        </p>
      </Modal>

      <ShortcutHelp open={helpOpen} onClose={() => setHelpOpen(false)} hints={SHORTCUTS} />
    </div>
  );
}

/** 聚焦当前页面里第一个搜索框。 */
function focusSearch(): void {
  const input = document.querySelector<HTMLInputElement>('.content input[type="search"]');
  input?.focus();
  input?.select();
}
