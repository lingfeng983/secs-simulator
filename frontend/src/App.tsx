import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createClient, readApiBase, saveApiBase } from './api';
import { useBackend } from './state/useBackend';
import { usePreferences, type ThemePreference } from './state/usePreferences';
import { useToasts } from './state/useToasts';
import { useEventStream, streamStateLabel, type NoticeWire, type StreamState } from './state/useEventStream';
import { ToastHost } from './ui/ToastHost';
import { CommandPalette } from './ui/CommandPalette';
import { ShortcutHelp, type ShortcutHint } from './ui/ShortcutHelp';
import { Modal } from './ui/Modal';
import { useHotkeys } from './ui/useHotkeys';
import type { CommandItem } from './lib/command';

import { NAV_ITEMS, routeTitle } from './layout/navigation';
import { Sidebar, type SidebarDevice } from './layout/Sidebar';
import { Topbar } from './layout/Topbar';

import { useRoute, DEVICE_TABS, type Route } from './router';
import { WorkbenchPanel } from './pages/WorkbenchPage';
import { DevicePage } from './pages/DevicePage';
import { SystemPage } from './pages/SystemPage';

/** SSE 类型化通知的保留条数(事件流 feed 用)。 */
const MAX_LIVE_EVENTS = 200;

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
 * 应用外壳（schema v6 两层信息架构）。
 *
 * <p>hash 路由:<code>#/workbench</code>、<code>#/device/{id}/{tab}</code>、
 * <code>#/system/{page}</code>。旧面板全部收编:overview/sessions→工作台,
 * catalog/alarms/events/preview→设备页·监控,config→设备页·配置,
 * send/hostsim→设备页·工具,audit/console→系统页。
 * 数据接线、页面摆位、快捷键——仍只做这三件事。</p>
 */
export function App(): JSX.Element {
  const [connOpen, setConnOpen] = useState(false);
  const [draftBase, setDraftBase] = useState('');
  const [streamState, setStreamState] = useState<StreamState>('idle');
  const [streamDetail, setStreamDetail] = useState('未连接');
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [liveEvents, setLiveEvents] = useState<NoticeWire[]>([]);

  const notifier = useToasts();
  const { preferences, update, resolvedTheme } = usePreferences();
  /* 后端地址改变时整页重载，这里只需按当前地址建一次客户端 */
  const client = useMemo(() => createClient(), []);
  const backend = useBackend(client, notifier);

  const [route, navigate] = useRoute(preferences.activeTab);
  const { appendLog, pushMessages } = backend;

  /* 事件流：状态、提示与报文都转发给控制器;类型化通知进 liveEvents 缓冲 */
  useEventStream(
    client,
    preferences.pauseStream,
    {
      onFlush: useCallback(
        (messages: import('./api').WireMessage[]) => {
          pushMessages(messages);
        },
        [pushMessages],
      ),
      onState: useCallback((state: StreamState, detail: string) => {
        setStreamState(state);
        setStreamDetail(detail);
      }, []),
      onNotice: useCallback(
        (text: string) => {
          appendLog(text);
        },
        [appendLog],
      ),
      onReconnected: useCallback(() => {
        void backend.backfillAfterReconnect();
      }, [backend]),
      onNoticeEvent: useCallback(
        (notice: NoticeWire) => {
          setLiveEvents((previous) => {
            const next = [...previous, notice];
            return next.length > MAX_LIVE_EVENTS ? next.slice(next.length - MAX_LIVE_EVENTS) : next;
          });
          if (notice.type === 'session') {
            /* 设备状态迁移:工作台卡片与侧栏状态点要跟上 */
            void backend.refreshSessions();
          }
        },
        [backend],
      ),
    },
  );

  /** 兼容旧面板 id 的导航:面板与命令面板都只会说"去 config / 去 sessions"这类话。 */
  const goTo = useCallback(
    (id: string) => {
      if (id.startsWith('device-tab:')) {
        const [, sessionId, tab] = id.split(':');
        if (sessionId !== undefined && DEVICE_TABS.includes(tab as never)) {
          navigate({ name: 'device', sessionId, tab: tab as 'monitor' | 'config' | 'tools' });
        }
        return;
      }
      if (id.startsWith('device:')) {
        navigate({ name: 'device', sessionId: id.slice('device:'.length), tab: 'monitor' });
        return;
      }
      switch (id) {
        case 'workbench':
        case 'overview':
        case 'sessions':
          navigate({ name: 'workbench' });
          return;
        case 'audit':
          navigate({ name: 'system', page: 'audit' });
          return;
        case 'console':
          navigate({ name: 'system', page: 'console' });
          return;
        case 'config': {
          const sessionId = backend.activeSession;
          if (sessionId !== '') {
            navigate({ name: 'device', sessionId, tab: 'config' });
          } else {
            navigate({ name: 'workbench' });
          }
          return;
        }
        case 'send':
        case 'hostsim': {
          const sessionId = backend.activeSession;
          if (sessionId !== '') {
            navigate({ name: 'device', sessionId, tab: 'tools' });
          } else {
            navigate({ name: 'workbench' });
          }
          return;
        }
        default: {
          /* preview/alarms/events/catalog/messages → 设备页·监控 */
          const sessionId = backend.activeSession;
          if (sessionId !== '') {
            navigate({ name: 'device', sessionId, tab: 'monitor' });
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

  const routeKey = route.name === 'device' ? `device:${route.sessionId}:${route.tab}` : route.name === 'system' ? `system:${route.page}` : 'workbench';

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
      for (const tab of DEVICE_TABS) {
        if (tab === 'monitor') {
          continue;
        }
        items.push({
          id: `device-tab:${session.id}:${tab}`,
          label: `${session.id} · ${tab === 'config' ? '配置' : '工具'}`,
          group: '设备',
          keywords: [session.id, tab],
          run: () => goTo(`device-tab:${session.id}:${tab}`),
        });
      }
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
        id: 'action:history',
        label: '载入报文历史',
        group: '操作',
        run: () => void backend.loadHistory(),
      },
      {
        id: 'action:export',
        label: '导出报文 JSONL',
        group: '操作',
        run: () => void backend.exportMessages(),
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
        label: `上报 CEID ${ceid.ceid}${ceid.slot ? ` · ${ceid.slot}` : ''}`,
        group: '设备目录',
        hint: ceid.owner,
        keywords: ['ceid', String(ceid.ceid), ceid.slot ?? ''],
        run: () => {
          backend.setCeid(ceid.ceid);
          goTo('workbench');
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
    true,
  );

  /* ---------- 布局 ---------- */

  const title = routeTitle(route);
  const streamLabel = streamStateLabel(streamState, streamDetail);

  const badgeFor = (id: string): number | null => {
    if (id === 'console') {
      return backend.messages.length > 0 ? null : null;
    }
    return null;
  };

  const sidebarDevices: SidebarDevice[] = backend.sessions.map((session) => ({
    id: session.id,
    label: `${session.device}`,
    online: session.controlState === 'ONLINE',
  }));

  const panelProps = {
    backend,
    notifier,
    preferences,
    updatePreferences: update,
    goTo,
    streamState,
    streamLabel,
    liveEvents,
  };

  return (
    <div className={`app${preferences.sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
      <Sidebar
        route={route}
        devices={sidebarDevices}
        collapsed={preferences.sidebarCollapsed}
        badgeFor={badgeFor}
        onNavigate={navigate}
        onToggleCollapse={() => update({ sidebarCollapsed: !preferences.sidebarCollapsed })}
        version={backend.health?.version ?? '-'}
        mode={backend.health?.mode ?? '-'}
        streamState={streamState}
        streamLabel={streamLabel}
      />

      <div className="main-col">
        <Topbar
          title={title}
          streamState={streamState}
          streamLabel={streamLabel}
          sessions={backend.sessions}
          activeSession={activeSessionOf(route, backend.activeSession)}
          onSessionChange={(id) => {
            backend.setActiveSession(id);
            navigate({ name: 'device', sessionId: id, tab: 'monitor' });
          }}
          refreshing={backend.refreshing}
          onRefresh={() => void backend.refreshAll()}
          onOpenPalette={() => setPaletteOpen(true)}
          onOpenConnection={() => {
            setDraftBase(readApiBase());
            setConnOpen(true);
          }}
          logLevel={backend.logLevel}
          onLogLevel={(level) => void backend.setLogLevel(level)}
          resolvedTheme={resolvedTheme}
          onToggleTheme={() => update({ theme: resolvedTheme === 'dark' ? 'light' : 'dark' })}
          density={preferences.density}
          onToggleDensity={() => update({ density: preferences.density === 'compact' ? 'comfortable' : 'compact' })}
          onShowHelp={() => setHelpOpen(true)}
        />

        {backend.backendError !== '' && (
          <div className="global-error-banner" role="alert">
            <span>后端不可达：{backend.backendError}</span>
            <button type="button" onClick={() => void backend.refreshAll()} title="立即重试">
              重试
            </button>
            <button
              type="button"
              onClick={() => {
                setDraftBase(readApiBase());
                setConnOpen(true);
              }}
            >
              修改地址
            </button>
          </div>
        )}

        <main className="content">
          {route.name === 'workbench' && <WorkbenchPanel {...panelProps} />}
          {route.name === 'device' && (
            <DevicePage {...panelProps} sessionId={route.sessionId} tab={route.tab} />
          )}
          {route.name === 'system' && <SystemPage {...panelProps} page={route.page} />}
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
        <div className="form-row">
          <label className="field">
            后端地址
            <input
              value={draftBase}
              onChange={(event) => setDraftBase(event.target.value)}
              placeholder="留空 = 本机（http://127.0.0.1:端口）"
              aria-label="后端地址"
            />
          </label>
        </div>
        <p className="hint">
          默认同源访问；要把这个界面指向另一台机器上的运行时后端，
          填它的地址（如 <code>http://192.168.1.10:8080</code>），保存后页面重载。
        </p>
      </Modal>

      <ShortcutHelp open={helpOpen} onClose={() => setHelpOpen(false)} hints={SHORTCUTS} />
    </div>
  );
}

/** 设备页钉住该会话;其他页面用全局选择。 */
function activeSessionOf(route: Route, fallback: string): string {
  return route.name === 'device' ? route.sessionId : fallback;
}

/** 聚焦当前页面里第一个搜索框。 */
function focusSearch(): void {
  const input = document.querySelector<HTMLInputElement>('.content input[type="search"]');
  input?.focus();
  input?.select();
}
