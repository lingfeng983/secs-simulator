import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { createClient, readApiBase, saveApiBase, type WireMessage } from '../api';
import { inDesktopShell, tauriInvoke, type ShellConnection } from '../shared/lib/tauri';
import { mergeMessages } from '../shared/lib/messageFilter';
import { useBackend } from '../state/useBackend';
import { usePreferences, type ThemePreference } from '../state/usePreferences';
import { usePersistentState } from '../state/usePersistentState';
import { useInputDraftCache } from '../state/useInputDraftCache';
import { useToasts } from '../state/useToasts';
import { useEventStream, type NoticeWire } from '../state/useEventStream';
import { ToastHost } from '../shared/ui/ToastHost';
import { CommandPalette } from '../shared/ui/CommandPalette';
import { ShortcutHelp, type ShortcutHint } from '../shared/ui/ShortcutHelp';
import { Modal } from '../shared/ui/Modal';
import { FieldLabel } from '../shared/ui/FieldLabel';
import { useHotkeys } from '../shared/ui/useHotkeys';
import type { CommandItem } from '../shared/lib/command';

import { NAV_ITEMS, routeTitle } from './layout/navigation';
import { Sidebar, type SidebarDevice } from './layout/Sidebar';
import { Titlebar } from './layout/Titlebar';

import { parseHash, toHash, useRoute, type DevicePage, type DeviceZone, type HostPage, type HostZone } from './router';
import { useHostsV2, hostPhaseLabel, hostPhaseTone } from '../state/useHostsV2';
import { useRegions } from '../state/useRegions';
import { TabBar, type TabCloseMode } from './layout/TabBar';
import { GeneralSettingsModal } from './layout/GeneralSettingsModal';
import { nextTabAfterClose, canonicalizeDeviceTabs, deviceTabLabel, tabIdForRoute, upsertTab, type AppTab } from './layout/tabs';
import { WorkbenchPage } from '../features/pages/WorkbenchPage';
import { DevicePage as DevicePageView } from '../features/pages/DevicePage';
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
import { SvidTemplatePage } from '../features/pages/SvidTemplatePage';
import { MessageMonitorDrawer, type MonitorScope, type MonitorTarget } from '../features/panels/MessageMonitorDrawer';

/** SSE 类型化通知的保留条数(事件流 feed 用)。 */
const MAX_LIVE_EVENTS = 200;

/** 界面保留的报文条数（消息监视面板用；与后端内存上限 5000 不同，界面只留最近的）。 */
const MAX_LIVE_MESSAGES = 5_000;

/**
 * 「一页只有一张表」的主页面：设备清单 / 主机清单 / 主机模板 / 设备模板 /
 * 参数模板 / 审计。这些页面的表格要吃满页面剩余高度，内容溢出后才滚动，
 * 而不是刚过 62vh 就在页面中间开一条滚动条。
 */
const SINGLE_TABLE_PAGES: ReadonlySet<string> = new Set([
  'workbench', 'hosts', 'host-templates', 'templates', 'svid-templates', 'system',
]);

/** 2.x/3.0 早期的消息子页 hash：页面已下线，命中时自动拉开右侧监控抽屉。 */
const LEGACY_MONITOR_HASH = /^#\/(?:device\/[^/]+\/(?:runtime\/)?messages|hosts\/[^/]+\/runtime\/monitor)(?:[?#]|$)/;

/** 常驻标签：任何时候都留着，保证关完标签后还有地方可去。 */
const WORKBENCH_TAB: AppTab = { id: 'workbench', label: '设备清单', hash: '#/workbench' };

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
  const [settingsOpen, setSettingsOpen] = useState(false);
  /** 打开过的页面标签；工作台标签常驻，其余可关闭。 */
  const [tabs, setTabs] = usePersistentState<AppTab[]>('secs.ui.tabs', [WORKBENCH_TAB]);
  const [draftBase, setDraftBase] = useState('');
  const [shellConn, setShellConn] = useState<ShellConnection | null>(null);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  /** 右侧实时消息监控：展开状态与宽度都记住，收起后不占版面。 */
  const [monitorOpen, setMonitorOpen] = usePersistentState<boolean>('secs.ui.monitor.open', false);
  const [monitorWidth, setMonitorWidth] = usePersistentState<number>('secs.ui.monitor.width', 620);
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
  /* 稳定引用：SSE 回调的依赖数组里不能放每次渲染都新建的 hostV2 对象 */
  const applyHostPhase = hostV2.applyPhase;
  const regions = useRegions();

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
        switch (notice.type) {
          case 'session':
            /* 设备控制状态迁移（ONLINE/OFFLINE）：工作台表格与侧栏状态点要跟上 */
            void backend.refreshSessions();
            break;
          case 'device-link':
            /* 设备链路相位（STARTING/LISTENING/CONNECTED/DISCONNECTED/FAILED）：
               会话列表里的 state 就是它，重拉一次即同步。
               payload 带的是 ULID 形式的 deviceId，与列表行对不上，不做增量合并。 */
            void backend.refreshSessions();
            break;
          case 'host-link':
            /* 主机链路相位：payload 的 phase 与列表字段同值域，就地改这一台，
               不重拉整个列表（自动重连会反复切相位，重拉会闪）。 */
            applyHostPhase(
              typeof notice.data.hostId === 'string' ? notice.data.hostId : '',
              notice.data.phase,
            );
            break;
          case 'config-changed':
            /* 配置保存/新建/删除：设备清单与配置目录跟着变（新配置是"未启动的设备"） */
            void backend.refreshConfigs();
            if (notice.data.action === 'delete') {
              /* 删配置时后端会先停掉用它的会话：设备清单也要跟上 */
              void backend.refreshSessions();
            }
            break;
          default:
            break;
        }
      },
      [backend, applyHostPhase],
    ),
  });

  /** 兼容旧面板 id 的导航:面板与命令面板都只会说"去 config / 去 sessions"这类话。 */
  const goTo = useCallback(
    (id: string) => {
      if (id.startsWith('device-tab:')) {
        /* 旧面板 id:映射进两段式（定义类页签迁到配置区，其余旧 id 归运行区首页） */
        const [, sessionId, tab] = id.split(':');
        const mapped: Record<string, [DeviceZone, DevicePage]> = {
          svids: ['config', 'svids'],
        };
        const hit = mapped[tab] ?? ['runtime', 'plans'];
        navigate({ name: 'device', sessionId, zone: hit[0], page: hit[1] });
        return;
      }
      if (id.startsWith('device-zone:')) {
        const [, sessionId, zone] = id.split(':');
        navigate({ name: 'device', sessionId, zone: zone as DeviceZone, page: zone === 'config' ? 'overview' : 'plans' });
        return;
      }
      if (id.startsWith('device-page:')) {
        const [, sessionId, zone, page] = id.split(':');
        navigate({ name: 'device', sessionId, zone: zone as DeviceZone, page: page as DevicePage });
        return;
      }
      if (id.startsWith('host-open:')) {
        /* 从左侧菜单/列表进主机，默认落在运行区；配置区由实例头上的切换按钮进入。 */
        navigate({ name: 'host', hostId: id.slice('host-open:'.length), zone: 'runtime', page: 'messages' });
        return;
      }
      if (id.startsWith('host-zone:')) {
        const [, hostId, zone] = id.split(':');
        navigate({ name: 'host', hostId, zone: zone as HostZone, page: zone === 'config' ? 'overview' : 'messages' });
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
      if (id === 'svid-templates') {
        navigate({ name: 'svid-templates' });
        return;
      }
      if (id.startsWith('device:')) {
        navigate({ name: 'device', sessionId: id.slice('device:'.length), zone: 'runtime', page: 'plans' });
        return;
      }
      if (id.startsWith('device-open:')) {
        /* 「打开设备」只有一个落点：设备本身，默认落在运行区（要动手的页在前面）。
           设备启没启动不影响打开哪一页——运行数据要等启动后才有，但页面骨架不分家。 */
        navigate({ name: 'device', sessionId: id.slice('device-open:'.length), zone: 'runtime', page: 'plans' });
        return;
      }
      switch (id) {
        case 'workbench':
        case 'sessions':
          navigate({ name: 'workbench' });
          return;
        case 'audit':
          navigate({ name: 'system', page: 'audit' });
          return;
        case 'templates':
          navigate({ name: 'templates' });
          return;
        default: {
          /* 设备能力 id → 当前设备对应子页（命令面板只传 catalog） */
          const sessionId = backend.activeSession;
          if (sessionId !== '') {
            const mapped: Record<string, [DeviceZone, DevicePage]> = {
              catalog: ['config', 'events'],
            };
            const hit = mapped[id] ?? ['runtime', 'plans'];
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
            : route.name === 'templates' || route.name === 'svid-templates' ? 'templates' : 'workbench';

  useEffect(() => {
    void refreshRef.current();
  }, [routeKey, client]);

  /* 页面范围内缓存所有表单输入：切页签/切页面回来后草稿还在。 */
  useInputDraftCache(routeKey);

  /* 旧消息深链会被路由收口到运行区首页；顺手把抽屉拉开，
     否则用户从旧书签/收藏夹进来会以为"消息页面怎么没了"。 */
  useEffect(() => {
    if (LEGACY_MONITOR_HASH.test(window.location.hash)) {
      setMonitorOpen(true);
    }
  }, [routeKey, setMonitorOpen]);

  const monitorScope: MonitorScope = route.name === 'device'
    ? { kind: 'device', id: route.sessionId }
    : route.name === 'host'
      ? { kind: 'host', id: route.hostId }
      : { kind: 'none', id: '' };

  /* 抽屉里一次只看一台设备或一台主机：把可选项一并给它，切换不跳页。
     区域一起带上——抽屉先按区域收窄，再在区域里选对象。 */
  const monitorTargets: MonitorTarget[] = [
    ...backend.sessions.map((session) => ({
      kind: 'device' as const,
      id: session.id,
      label: session.device === '' ? session.id : session.device,
      region: regions.deviceRegion(session.config ?? session.id),
    })),
    ...hostV2.hosts.map((host) => ({
      kind: 'host' as const,
      id: host.id,
      label: host.name,
      region: regions.hostRegion(host.id),
    })),
  ];

  /* 抽屉宽度：用户拖出来的值再按「通用设置」里的上下界夹一次 */
  const monitorW = Math.max(preferences.monitorMinW, Math.min(preferences.monitorMaxW, monitorWidth));

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
        run: () => goTo(`device-open:${session.id}`),
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
        run: () => goTo(`device-zone:${session.id}:runtime`),
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

  /* 页签标题用设备名；没启动的设备路由 key 是配置文件名，去掉 .json 再显示。
     身份与名字都按配置归一：同一台设备无论地址里写的是文件名还是会话 id，
     都是同一个页签、同一个名字（2026-11-18 用户反馈"一台设备会开两个页签"）。 */
  const deviceName = route.name === 'device'
    ? deviceTabLabel(route.sessionId, backend.sessions, backend.configList)
    : '';
  const devicePageDescription = route.name === 'device'
    ? (route.zone === 'config' ? DEVICE_CONFIG_PAGE_LABELS : DEVICE_RUNTIME_PAGE_LABELS)[route.page as never]
    : '';
  /* 顶栏只显示一行紧凑路径：当前对象 › 区域 / 子页。
     分组名（设备清单 / 主机清单）与长说明都不再占位置。 */
  const zoneLabel = (zone: string): string => (zone === 'config' ? '配置区' : '运行区');
  const hostTitle = route.name === 'host'
    ? (() => {
      const host = hostV2.find(route.hostId);
      const label = host === null ? route.hostId : `${host.name} · ${host.id}`;
      const page = route.zone === 'config'
        ? HOST_CONFIG_PAGE_LABELS[route.page as never]
        : HOST_RUNTIME_PAGE_LABELS[route.page as never];
      return { group: '', label, description: `${zoneLabel(route.zone)} / ${page}` };
    })()
    : null;
  const title = route.name === 'device'
    ? {
      group: '',
      label: deviceName,
      description: `${zoneLabel(route.zone)} / ${devicePageDescription}`,
    }
    : route.name === 'host' && hostTitle !== null
      ? hostTitle
      : routeTitle(route);

  /* ---------- 顶部页面标签 ----------
     一个设备 / 一台主机 / 一个独立页各占一个标签；在子页之间切换只更新该标签
     最后停留的地址，切回来还落在刚才那一页，又不会把顶栏开成一长排。 */
  const currentTabId = tabIdForRoute(route, backend.sessions, backend.configList);
  const currentHash = toHash(route);
  const currentTabLabel = route.name === 'device'
    ? (deviceName === '' ? '设备' : deviceName)
    : route.name === 'host'
      ? (hostV2.find(route.hostId)?.name ?? '主机')
      : (title.label === '' ? '设备清单' : title.label);
  useEffect(() => {
    setTabs((previous) => upsertTab(previous, {
      id: currentTabId, label: currentTabLabel, hash: currentHash,
    }));
  }, [currentTabId, currentTabLabel, currentHash, setTabs]);

  /* 以前存下来的页签可能带着两种身份（配置文件全名 / 会话 id），或者是设备重启前
     的旧会话 id：按当前会话表归一一次，同一台设备只留一个标签。
     归一不需要改动时返回原数组，轮询刷新不会造成无谓的重渲染。 */
  useEffect(() => {
    setTabs((previous) => canonicalizeDeviceTabs(
      previous, backend.sessions, backend.configList, currentTabId, currentHash,
    ));
  }, [backend.sessions, backend.configList, currentTabId, currentHash, setTabs]);

  const selectTab = (tab: AppTab) => navigate(parseHash(tab.hash));
  const closeTab = (tab: AppTab) => {
    const fallback = nextTabAfterClose(tabs, tab.id);
    const rest = tabs.filter((item) => item.id !== tab.id);
    setTabs(rest.length > 0 ? rest : [WORKBENCH_TAB]);
    if (tab.id === currentTabId) {
      navigate(parseHash(fallback?.hash ?? '#/workbench'));
    }
  };

  /**
   * 右键菜单的批量关闭。
   *
   * <p>工作台标签任何模式下都留着——它是唯一保证"总有地方可去"的锚点。
   * 关掉的如果是当前页，落到保留下来的最后一个标签上。</p>
   */
  const closeManyTabs = (target: AppTab, mode: TabCloseMode) => {
    const at = tabs.findIndex((item) => item.id === target.id);
    let next: AppTab[] = tabs;
    switch (mode) {
      case 'self':
        next = tabs.filter((item) => item.id !== target.id);
        break;
      case 'others':
        next = tabs.filter((item) => item.id === target.id || item.id === 'workbench');
        break;
      case 'left':
        next = tabs.filter((item, index) => index >= at || item.id === 'workbench');
        break;
      case 'right':
        next = tabs.filter((item, index) => index <= at || item.id === 'workbench');
        break;
      case 'all':
        next = tabs.filter((item) => item.id === 'workbench');
        break;
      default:
        break;
    }
    if (next.length === 0) {
      next = [WORKBENCH_TAB];
    }
    setTabs(next);
    if (!next.some((item) => item.id === currentTabId)) {
      const landing = next.find((item) => item.id === target.id) ?? next[next.length - 1];
      navigate(parseHash(landing.hash));
    }
  };

  const sidebarDevices: SidebarDevice[] = [
    ...backend.sessions.map((session) => ({
      id: session.id,
      label: `${session.device}`,
      state: session.state,
      controlState: session.controlState,
      template: backend.configList.find((entry) => entry.name === session.config)?.template ?? '',
      config: session.config,
      region: regions.deviceRegion(session.config ?? session.id),
    })),
    /* 数据库里还没启动的设备也进树：和已启动的设备长得一样，点「启动」才创建会话 */
    ...backend.configList
      .filter((entry) => !backend.sessions.some((session) => session.config === entry.name))
      .map((entry) => ({
        id: entry.name,
        label: entry.name.replace(/\.json$/i, ''),
        state: 'CLOSED',
        template: entry.template ?? '',
        config: entry.name,
        region: regions.deviceRegion(entry.name),
      })),
  ];

  const panelProps = {
    backend,
    notifier,
    preferences,
    updatePreferences: update,
    goTo,
    liveEvents,
    messages: liveMessages,
    regions,
  };

  return (
    <div
      className={`app${preferences.sidebarCollapsed ? ' sidebar-collapsed' : ''}${monitorOpen ? ' monitor-open' : ''}`}
      /* 宽度用 CSS 变量驱动 grid 布局：拖动时只改这一个变量，不重排 DOM。
         监控抽屉再宽也不超过窗口的 46%：窄窗口下不把内容区压成一条缝。 */
      style={{
        '--sidenav-w': `${preferences.sidebarWidth}px`,
        '--monitor-w': `min(${monitorW}px, 46vw)`,
      } as CSSProperties}
    >
      {/* 顶部信息栏横跨整个窗口：左栏、内容、右侧监控都从它下面开始 */}
      <Titlebar
        refreshing={backend.refreshing}
        onRefresh={() => void backend.refreshAll()}
        onOpenPalette={() => setPaletteOpen(true)}
        onOpenConnection={openConnection}
        onOpenSettings={() => setSettingsOpen(true)}
        resolvedTheme={resolvedTheme}
        onToggleTheme={() => update({ theme: resolvedTheme === 'dark' ? 'light' : 'dark' })}
        onShowHelp={() => setHelpOpen(true)}
        monitorOpen={monitorOpen}
        toggleMonitor={() => setMonitorOpen((previous) => !previous)}
      />

      <div className="app-body">
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
          regions={regions}
          minWidth={preferences.sidebarMinW}
          maxWidth={preferences.sidebarMaxW}
        />

        <div className="main-col">
          <TabBar
            tabs={tabs}
            activeId={currentTabId}
            onSelect={selectTab}
            onClose={closeTab}
            onCloseMany={closeManyTabs}
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

          <main className={`content${SINGLE_TABLE_PAGES.has(route.name) ? ' content--table-page' : ''}`}>
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
        {route.name === 'svid-templates' && <SvidTemplatePage {...panelProps} />}
          </main>
        </div>
      </div>

      <MessageMonitorDrawer
        open={monitorOpen}
        onClose={() => setMonitorOpen(false)}
        messages={liveMessages}
        scope={monitorScope}
        targets={monitorTargets}
        regions={regions}
        windowMin={preferences.monitorWindowMin}
        width={monitorW}
        minWidth={preferences.monitorMinW}
        maxWidth={preferences.monitorMaxW}
        onWidthChange={setMonitorWidth}
      />

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
        <div className="modal-form">
          <label className="field modal-field">
            <FieldLabel
              text="后端地址"
              help={inDesktopShell
                ? '填远程服务端地址（如 http://192.168.1.10:8080）切换到远程，本机后端会停止；留空切回本机。远程端直接访问，不需要任何令牌或账号。'
                : '默认同源访问；要把这个界面指向另一台机器上的运行时后端，填它的地址（如 http://192.168.1.10:8080），保存后页面重载。'}
            />
            <input
              value={draftBase}
              onChange={(event) => setDraftBase(event.target.value)}
              placeholder={inDesktopShell ? '留空 = 本机后端' : '留空 = 本机（http://127.0.0.1:端口）'}
              aria-label="后端地址"
            />
          </label>
        </div>
      </Modal>

      <ShortcutHelp open={helpOpen} onClose={() => setHelpOpen(false)} hints={SHORTCUTS} />

      <GeneralSettingsModal
        open={settingsOpen}
        preferences={preferences}
        onChange={update}
        onClose={() => setSettingsOpen(false)}
        logLevel={backend.logLevel}
        onLogLevel={(level) => void backend.setLogLevel(level)}
      />
    </div>
  );
}

/** 聚焦当前页面里第一个搜索框。 */
function focusSearch(): void {
  const input = document.querySelector<HTMLInputElement>('.content input[type="search"]');
  input?.focus();
  input?.select();
}
