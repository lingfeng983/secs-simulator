import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createClient, readApiBase, saveApiBase } from './api';
import { useBackend } from './state/useBackend';
import { usePreferences, type ThemePreference } from './state/usePreferences';
import { useToasts } from './state/useToasts';
import { useEventStream, streamStateLabel, type StreamState } from './state/useEventStream';
import { ToastHost } from './ui/ToastHost';
import { CommandPalette } from './ui/CommandPalette';
import { ShortcutHelp, type ShortcutHint } from './ui/ShortcutHelp';
import { Modal } from './ui/Modal';
import { useHotkeys } from './ui/useHotkeys';
import type { CommandItem } from './lib/command';

import { SessionsPanel } from './panels/SessionsPanel';
import { AlarmsPanel } from './panels/AlarmsPanel';
import { PreviewPanel } from './panels/PreviewPanel';
import { CatalogPanel } from './panels/CatalogPanel';
import { EventsPanel } from './panels/EventsPanel';
import { SendPanel } from './panels/SendPanel';
import { MessagesPanel } from './panels/MessagesPanel';
import { ConfigPanel } from './panels/ConfigPanel';
import { AuditPanel } from './panels/AuditPanel';
import { ConsolePanel } from './panels/ConsolePanel';

/** 一个面板的入口。 */
interface TabDef {
  id: string;
  label: string;
  group: string;
  /** 侧边栏上的小图标。 */
  icon: string;
}

/**
 * 面板清单。
 *
 * <p>分组是给人看的：设备 / 数据 / 工具三块，
 * 面板多起来以后还能靠分组找到位置。</p>
 */
const TABS: readonly TabDef[] = [
  { id: 'sessions', label: '会话', group: '设备', icon: '▦' },
  { id: 'alarms', label: '报警', group: '设备', icon: '⚠' },
  { id: 'events', label: '事件编排', group: '设备', icon: '↻' },
  { id: 'preview', label: '曲线预览', group: '数据', icon: '∿' },
  { id: 'catalog', label: '设备目录', group: '数据', icon: '☰' },
  { id: 'messages', label: '报文', group: '数据', icon: '⇄' },
  { id: 'send', label: '手动发送', group: '工具', icon: '➤' },
  { id: 'config', label: '配置编辑器', group: '工具', icon: '⚙' },
  { id: 'audit', label: '审计', group: '工具', icon: '☑' },
  { id: 'console', label: '事件流', group: '工具', icon: '▷' },
];

/** 快捷键说明表。 */
const SHORTCUTS: readonly ShortcutHint[] = [
  { keys: 'Ctrl+K', description: '打开命令面板：跳面板、找 CEID、执行动作' },
  { keys: '1..9', description: '直接跳到第 N 个面板（0 是第 10 个）' },
  { keys: 'R', description: '立即刷新所有数据' },
  { keys: '/', description: '聚焦当前面板的搜索框' },
  { keys: '?', description: '打开这张快捷键表' },
  { keys: 'Esc', description: '关闭浮层' },
];

/**
 * 应用外壳。
 *
 * <p>这里只做三件事：把数据接起来、把面板摆好、把快捷键接上。
 * 具体逻辑都在 {@link useBackend} 与各个面板里，
 * 加一个面板只要在这里登记一行。</p>
 */
export function App(): JSX.Element {
  const [connOpen, setConnOpen] = useState(false);
  const [draftBase, setDraftBase] = useState('');
  const [streamState, setStreamState] = useState<StreamState>('idle');
  const [streamDetail, setStreamDetail] = useState('未连接');
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);

  const notifier = useToasts();
  const { preferences, update, resolvedTheme } = usePreferences();
  /* 后端地址改变时整页重载，这里只需按当前地址建一次客户端 */
  const client = useMemo(() => createClient(), []);
  const backend = useBackend(client, notifier);

  const { appendLog, pushMessages, activeSession } = backend;

  /* 事件流：状态、提示与报文都转发给控制器 */
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
    },
  );

  const activeTab = TABS.some((tab) => tab.id === preferences.activeTab) ? preferences.activeTab : TABS[0].id;

  const goTo = useCallback(
    (id: string) => {
      update({ activeTab: id });
    },
    [update],
  );

  /* 切面板时立刻拉一次新数据：不要让用户盯着上一个面板的旧值。
     用 ref 取最新的 refreshAll，避免闭包里钉住旧的 activeSession。 */
  const refreshRef = useRef(backend.refreshAll);
  refreshRef.current = backend.refreshAll;

  useEffect(() => {
    void refreshRef.current();
  }, [activeTab, client]);

  /* ---------- 命令面板 ---------- */

  const commands = useMemo<CommandItem[]>(() => {
    const items: CommandItem[] = [];

    for (const tab of TABS) {
      items.push({
        id: `tab:${tab.id}`,
        label: `前往 ${tab.label}`,
        group: '跳转面板',
        hint: tab.group,
        keywords: [tab.id, tab.label],
        run: () => goTo(tab.id),
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
        id: 'action:shortcuts',
        label: '查看快捷键',
        group: '操作',
        hint: '?',
        run: () => setHelpOpen(true),
      },
    );

    /* 目录里的 CEID / SVID 直接变成命令：不用先切面板再找 */
    for (const ceid of backend.catalog?.ceids ?? []) {
      items.push({
        id: `ceid:${ceid.ceid}`,
        label: `上报 CEID ${ceid.ceid}${ceid.slot ? ` · ${ceid.slot}` : ''}`,
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
  }, [backend, goTo, notifier, preferences.density, preferences.theme, resolvedTheme, update]);

  /* ---------- 快捷键 ---------- */

  useHotkeys(
    [
      { combo: 'mod+k', description: '命令面板', allowInInput: true, handler: () => setPaletteOpen(true) },
      { combo: '?', description: '快捷键表', handler: () => setHelpOpen((previous) => !previous) },
      { combo: 'r', description: '刷新', handler: () => void backend.refreshAll() },
      { combo: '/', description: '聚焦搜索', handler: () => focusSearch() },
      {
        combo: 'escape',
        description: '关闭浮层',
        handler: () => {
          setPaletteOpen(false);
          setHelpOpen(false);
        },
      },
      ...TABS.map((tab, index) => ({
        /* 第十个面板用 0：单字符键位才留得住 */
        combo: index === 9 ? '0' : String(index + 1),
        description: `跳到 ${tab.label}`,
        handler: () => goTo(tab.id),
      })),
    ],
    true,
  );

  /* ---------- 顶栏 ---------- */

  const connTone =
    streamState === 'connected' ? 'ok' : streamState === 'reconnecting' ? 'warn' : streamState === 'idle' ? 'muted' : 'danger';

  const badgeFor = (id: string): number | null => {
    if (id === 'alarms') {
      return backend.alarms.length;
    }
    if (id === 'messages') {
      return backend.messages.length;
    }
    return null;
  };

  const panelProps = {
    backend,
    notifier,
    preferences,
    updatePreferences: update,
  };

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar-brand">
          <h1>SECS Simulator</h1>
          <span className="version">{backend.health?.version ?? '-'}</span>
        </div>

        <dl className="health">
          <dt>形态</dt>
          <dd>{backend.health?.mode ?? '-'}</dd>
          <dt>事件流</dt>
          <dd>
            <span className={`conn-dot ${connTone}`} />
            {streamStateLabel(streamState, streamDetail)}
          </dd>
        </dl>

        <span className="topbar-spacer" />

        <div className="topbar-actions">
          <div className="session-picker">
            <label htmlFor="active-session">会话</label>
            <select
              id="active-session"
              value={activeSession}
              onChange={(event) => backend.setActiveSession(event.target.value)}
              disabled={backend.sessions.length === 0}
            >
              {backend.sessions.length === 0 && <option value="">（无会话）</option>}
              {backend.sessions.map((session) => (
                <option key={session.id} value={session.id}>
                  {session.id}
                </option>
              ))}
            </select>
          </div>

          <button
            type="button"
            onClick={() => void backend.refreshAll()}
            disabled={backend.refreshing}
            title="立即刷新所有数据（R）"
          >
            {backend.refreshing ? '刷新中…' : '刷新'}
          </button>
          <button type="button" onClick={() => setPaletteOpen(true)} title="命令面板（Ctrl+K）">
            ⌘ 命令
          </button>
          <button
            type="button"
            onClick={() => {
              setDraftBase(readApiBase());
              setConnOpen(true);
            }}
            title="设置后端地址（远程运行时后端）"
          >
            连接
          </button>
          <button
            type="button"
            onClick={() => update({ theme: resolvedTheme === 'dark' ? 'light' : 'dark' })}
            title="切换浅色 / 深色"
            aria-label="切换主题"
          >
            {resolvedTheme === 'dark' ? '☀' : '☾'}
          </button>
          <button
            type="button"
            onClick={() => update({ density: preferences.density === 'compact' ? 'comfortable' : 'compact' })}
            title="切换表格密度"
          >
            {preferences.density === 'compact' ? '≡ 紧凑' : '≡ 宽松'}
          </button>
          <button type="button" onClick={() => setHelpOpen(true)} title="快捷键（?）">
            ?
          </button>
        </div>
      </header>

      <div className="shell">
        <nav className="sidenav" aria-label="面板导航">
          {(['设备', '数据', '工具'] as const).map((group) => (
            <div key={group}>
              <div className="nav-group-title">{group}</div>
              {TABS.filter((tab) => tab.group === group).map((tab) => {
                const badge = badgeFor(tab.id);
                return (
                  <button
                    key={tab.id}
                    type="button"
                    className={`nav-item${activeTab === tab.id ? ' active' : ''}`}
                    onClick={() => goTo(tab.id)}
                    aria-current={activeTab === tab.id ? 'page' : undefined}
                  >
                    <span className="nav-icon" aria-hidden="true">
                      {tab.icon}
                    </span>
                    <span>{tab.label}</span>
                    {badge !== null && badge > 0 && (
                      <span className={`nav-badge${tab.id === 'alarms' ? ' alert' : ''}`}>{badge}</span>
                    )}
                  </button>
                );
              })}
            </div>
          ))}
        </nav>

        <main className="content">
          {activeTab === 'sessions' && <SessionsPanel {...panelProps} />}
          {activeTab === 'alarms' && <AlarmsPanel {...panelProps} />}
          {activeTab === 'events' && <EventsPanel {...panelProps} />}
          {activeTab === 'preview' && <PreviewPanel {...panelProps} />}
          {activeTab === 'catalog' && <CatalogPanel {...panelProps} />}
          {activeTab === 'messages' && <MessagesPanel {...panelProps} />}
          {activeTab === 'send' && <SendPanel {...panelProps} />}
          {activeTab === 'config' && <ConfigPanel {...panelProps} />}
          {activeTab === 'audit' && <AuditPanel {...panelProps} />}
          {activeTab === 'console' && <ConsolePanel {...panelProps} />}
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

/** 聚焦当前面板里第一个搜索框。 */
function focusSearch(): void {
  const input = document.querySelector<HTMLInputElement>('.content input[type="search"]');
  input?.focus();
  input?.select();
}
