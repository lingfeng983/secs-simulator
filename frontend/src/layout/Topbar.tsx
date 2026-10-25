import type { SessionSummary } from '../api';
import { Icon } from '../ui/Icon';
import type { StreamState } from '../state/useEventStream';
import type { NavGroupDef, NavItemDef } from './navigation';

export interface TopbarProps {
  /** 当前面板与分组（面包屑 + 标题）。 */
  item: NavItemDef;
  group: NavGroupDef | undefined;
  streamState: StreamState;
  streamLabel: string;
  sessions: readonly SessionSummary[];
  activeSession: string;
  onSessionChange: (id: string) => void;
  refreshing: boolean;
  onRefresh: () => void;
  onOpenPalette: () => void;
  onOpenConnection: () => void;
  logLevel: string;
  onLogLevel: (level: string) => void;
  resolvedTheme: 'light' | 'dark';
  onToggleTheme: () => void;
  density: 'comfortable' | 'compact';
  onToggleDensity: () => void;
  onShowHelp: () => void;
}

/**
 * 顶栏。
 *
 * <p>左边告诉用户「现在在哪」（分组 / 面板 + 一句话说明），
 * 右边是全局动作：会话选择、刷新、命令面板、连接、日志级别、
 * 主题、密度、快捷键。图标按钮统一 32px 见方，悬停出提示。</p>
 */
export function Topbar({
  item,
  group,
  streamState,
  streamLabel,
  sessions,
  activeSession,
  onSessionChange,
  refreshing,
  onRefresh,
  onOpenPalette,
  onOpenConnection,
  logLevel,
  onLogLevel,
  resolvedTheme,
  onToggleTheme,
  density,
  onToggleDensity,
  onShowHelp,
}: TopbarProps): JSX.Element {
  const tone =
    streamState === 'connected' ? 'ok' : streamState === 'reconnecting' ? 'warn' : streamState === 'idle' ? 'muted' : 'danger';

  return (
    <header className="topbar">
      <div className="page-title">
        <span className="page-crumb">{group?.label ?? ''}</span>
        <h1 className="page-name">
          <Icon name={item.icon} size={18} />
          {item.label}
        </h1>
        <p className="page-desc">{item.description}</p>
      </div>

      <div className="topbar-status" title={`事件流：${streamLabel}`}>
        <span className={`conn-dot ${tone}`} />
        <span className="topbar-status-text">{streamLabel}</span>
      </div>

      <div className="topbar-actions">
        <div className="session-picker">
          <label htmlFor="active-session">会话</label>
          <select
            id="active-session"
            value={activeSession}
            onChange={(event) => onSessionChange(event.target.value)}
            disabled={sessions.length === 0}
          >
            {sessions.length === 0 && <option value="">（无会话）</option>}
            {sessions.map((session) => (
              <option key={session.id} value={session.id}>
                {session.id}
              </option>
            ))}
          </select>
        </div>

        <button
          type="button"
          className="icon-btn text-btn"
          onClick={onRefresh}
          disabled={refreshing}
          title="立即刷新所有数据（R）"
        >
          <Icon name="refresh" size={15} className={refreshing ? 'spin' : undefined} />
          {refreshing ? '刷新中' : '刷新'}
        </button>

        <button type="button" className="icon-btn text-btn palette-btn" onClick={onOpenPalette} title="命令面板（Ctrl+K）">
          <Icon name="search" size={15} />
          <span>命令</span>
          <kbd>Ctrl K</kbd>
        </button>

        <span className="topbar-divider" aria-hidden="true" />

        <button type="button" className="icon-btn" onClick={onOpenConnection} title="设置后端地址（远程运行时后端）" aria-label="连接设置">
          <Icon name="link" size={16} />
        </button>

        <label className="loglevel-picker" title="后端日志级别（排查时切到 debug）">
          <span>日志</span>
          <select value={logLevel} onChange={(event) => onLogLevel(event.target.value)}>
            <option value="error">error</option>
            <option value="info">info</option>
            <option value="debug">debug</option>
          </select>
        </label>

        <button
          type="button"
          className="icon-btn"
          onClick={onToggleTheme}
          title={resolvedTheme === 'dark' ? '切换到浅色' : '切换到深色'}
          aria-label="切换主题"
        >
          <Icon name={resolvedTheme === 'dark' ? 'sun' : 'moon'} size={16} />
        </button>

        <button
          type="button"
          className="icon-btn"
          onClick={onToggleDensity}
          title={density === 'compact' ? '切换为宽松密度' : '切换为紧凑密度'}
          aria-label="切换密度"
          data-active={density === 'compact' ? 'true' : undefined}
        >
          <Icon name="density" size={16} />
        </button>

        <button type="button" className="icon-btn" onClick={onShowHelp} title="快捷键（?）" aria-label="快捷键帮助">
          <Icon name="help" size={16} />
        </button>
      </div>
    </header>
  );
}
