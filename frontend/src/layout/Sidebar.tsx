import { Icon } from '../ui/Icon';
import type { StreamState } from '../state/useEventStream';
import { NAV_GROUPS } from './navigation';

export interface SidebarProps {
  /** 当前面板 id。 */
  activeId: string;
  /** 折叠为纯图标栏。 */
  collapsed: boolean;
  /** 徽标计数（报警 / 报文）；返回 null 表示不显示。 */
  badgeFor: (id: string) => number | null;
  onNavigate: (id: string) => void;
  onToggleCollapse: () => void;
  /** 后端版本号（未知给 '-'）。 */
  version: string;
  /** 后端运行形态（integrated / runtime …）。 */
  mode: string;
  /** 事件流状态，用于侧栏底部的状态点。 */
  streamState: StreamState;
  streamLabel: string;
}

/**
 * 左侧导航栏。
 *
 * <p>品牌区在顶部，中间是按「总览 / 监控 / 设备 / 数据 / 系统」
 * 分组的菜单，底部是折叠开关与连接状态。折叠后只剩图标，
 * 悬停靠 title 提示，给内容区让出横向空间。</p>
 */
export function Sidebar({
  activeId,
  collapsed,
  badgeFor,
  onNavigate,
  onToggleCollapse,
  version,
  mode,
  streamState,
  streamLabel,
}: SidebarProps): JSX.Element {
  const tone =
    streamState === 'connected' ? 'ok' : streamState === 'reconnecting' ? 'warn' : streamState === 'idle' ? 'muted' : 'danger';

  return (
    <aside className={`sidenav${collapsed ? ' collapsed' : ''}`} aria-label="面板导航">
      <div className="brand">
        <span className="brand-mark" aria-hidden="true">
          {/* 芯片 logo：方形本体 + 四边引脚 */}
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round">
            <rect x="6" y="6" width="12" height="12" rx="2.5" />
            <path d="M9 6V3M12 6V3M15 6V3M9 21v-3M12 21v-3M15 21v-3M6 9H3M6 12H3M6 15H3M21 9h-3M21 12h-3M21 15h-3" />
          </svg>
        </span>
        {!collapsed && (
          <span className="brand-text">
            <span className="brand-name">SECS Simulator</span>
            <span className="brand-sub">v{version} · {mode}</span>
          </span>
        )}
      </div>

      <nav className="sidenav-nav">
        {NAV_GROUPS.map((group) => (
          <div className="nav-group" key={group.id}>
            {!collapsed && <div className="nav-group-title">{group.label}</div>}
            {group.items.map((item) => {
              const badge = badgeFor(item.id);
              const active = activeId === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  className={`nav-item${active ? ' active' : ''}`}
                  onClick={() => onNavigate(item.id)}
                  aria-current={active ? 'page' : undefined}
                  title={collapsed ? `${item.label}（${group.label}）` : undefined}
                >
                  <span className="nav-icon" aria-hidden="true">
                    <Icon name={item.icon} size={17} />
                  </span>
                  {!collapsed && <span className="nav-label">{item.label}</span>}
                  {!collapsed && badge !== null && badge > 0 && (
                    <span className={`nav-badge${item.id === 'alarms' ? ' alert' : ''}`}>{badge}</span>
                  )}
                  {collapsed && badge !== null && badge > 0 && (
                    <span className={`nav-dot${item.id === 'alarms' ? ' alert' : ''}`} aria-hidden="true" />
                  )}
                </button>
              );
            })}
          </div>
        ))}
      </nav>

      <div className="sidenav-foot">
        <div className={`sidenav-status${collapsed ? ' icon-only' : ''}`} title={`事件流：${streamLabel}`}>
          <span className={`conn-dot ${tone}`} />
          {!collapsed && <span className="sidenav-status-text">{streamLabel}</span>}
        </div>
        <button
          type="button"
          className="icon-btn sidenav-toggle"
          onClick={onToggleCollapse}
          title={collapsed ? '展开导航栏' : '收起导航栏'}
          aria-label={collapsed ? '展开导航栏' : '收起导航栏'}
          aria-expanded={!collapsed}
        >
          <Icon name={collapsed ? 'expand' : 'collapse'} size={16} />
        </button>
      </div>
    </aside>
  );
}
