import { Icon } from '../../shared/ui/Icon';

export interface TopbarProps {
  /** 顶栏标题（路由派生:分组/标题/描述）。group 只用于内部语义，不展示。 */
  title: { group: string; label: string; description: string };
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
  /** 右侧实时消息监控抽屉是否展开。 */
  monitorOpen: boolean;
  /** 展开/隐藏消息监控。 */
  onToggleMonitor: () => void;
}

/**
 * 顶栏。
 *
 * <p>左侧是一行紧凑路径（当前对象 › 当前子页），不再用三行展示
 * 「分组 / 标题 / 说明」，也不再重复「设备清单 / 主机清单」这类分组名；
 * 右侧是全局动作，其中包括右侧消息监控抽屉的开关。</p>
 */
export function Topbar({
  title,
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
  monitorOpen,
  onToggleMonitor,
}: TopbarProps): JSX.Element {
  return (
    <header className="topbar">
      <div className="page-title">
        <div className="page-path" title={`${title.label}${title.description === '' ? '' : ` · ${title.description}`}`}>
          {title.label !== '' && <span className="page-name">{title.label}</span>}
          {title.description !== '' && (
            <>
              <span className="page-sep" aria-hidden="true">›</span>
              <span className="page-desc">{title.description}</span>
            </>
          )}
        </div>
      </div>

      <div className="topbar-actions">
        <button
          type="button"
          className={`icon-btn text-btn monitor-toggle${monitorOpen ? ' active' : ''}`}
          onClick={onToggleMonitor}
          aria-pressed={monitorOpen}
          title={monitorOpen ? '隐藏实时消息监控' : '展开实时消息监控（接收 / 发送按颜色区分）'}
        >
          <Icon name="messages" size={15} />
          <span className="monitor-label">消息监控</span>
        </button>

        <button
          type="button"
          className="icon-btn text-btn"
          onClick={onRefresh}
          disabled={refreshing}
          title="立即刷新所有数据（R）"
        >
          <Icon name="refresh" size={15} className={refreshing ? 'spin' : undefined} />
          <span className="refresh-label">{refreshing ? '刷新中' : '刷新'}</span>
        </button>

        <button type="button" className="icon-btn text-btn palette-btn" onClick={onOpenPalette} title="命令面板（Ctrl+K）">
          <Icon name="search" size={15} />
          <span className="palette-label">命令</span>
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
