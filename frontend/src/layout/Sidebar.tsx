import { Icon } from '../ui/Icon';
import type { Route } from '../router';
import { sameRoute } from '../router';
import { NAV_GROUPS } from './navigation';

/** 侧栏「设备」动态组里的一台设备。 */
export interface SidebarDevice {
  id: string;
  label: string;
  online: boolean;
}

export interface SidebarProps {
  /** 当前路由。 */
  route: Route;
  /** 动态设备列表(来自会话)。 */
  devices: readonly SidebarDevice[];
  /** 折叠为纯图标栏。 */
  collapsed: boolean;
  onNavigate: (route: Route) => void;
  onToggleCollapse: () => void;
  /** 后端版本号（未知给 '-'）。 */
  version: string;
  /** 后端运行形态（integrated / runtime …）。 */
  mode: string;
}

/**
 * 左侧导航栏。
 *
 * <p>每个入口对应一个单功能页面。分组按「设备」动态组分上下两段:
 * 上段是总览与实时数据,下段是管理入口(会话/审计/日志)。
 * 折叠后只剩图标,悬停靠 title 提示。</p>
 */
export function Sidebar({
  route,
  devices,
  collapsed,
  onNavigate,
  onToggleCollapse,
  version,
  mode,
}: SidebarProps): JSX.Element {
  const groupsAbove = NAV_GROUPS.filter((group) => !group.belowDevices);
  const groupsBelow = NAV_GROUPS.filter((group) => group.belowDevices);

  const renderGroup = (group: (typeof NAV_GROUPS)[number]) => (
    <div className="nav-group" key={group.id}>
      {!collapsed && <div className="nav-group-title">{group.label}</div>}
      {group.items.map((item) => {
        const active = sameRoute(item.route, route);
        return (
          <button
            key={item.id}
            type="button"
            className={`nav-item${active ? ' active' : ''}`}
            onClick={() => onNavigate(item.route)}
            aria-current={active ? 'page' : undefined}
            title={collapsed ? `${item.label}（${group.label}）` : undefined}
          >
            <span className="nav-icon" aria-hidden="true">
              <Icon name={item.icon} size={17} />
            </span>
            {!collapsed && <span className="nav-label">{item.label}</span>}
          </button>
        );
      })}
    </div>
  );

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
        {groupsAbove.map(renderGroup)}

        {/* 设备组(动态):点击进设备子页 */}
        <div className="nav-group">
          {!collapsed && <div className="nav-group-title">设备（{devices.length}）</div>}
          {devices.length === 0 && !collapsed && <p className="nav-empty">暂无设备</p>}
          {devices.map((device) => {
            const active = route.name === 'device' && route.sessionId === device.id;
            return (
              <button
                key={device.id}
                type="button"
                className={`nav-item${active ? ' active' : ''}`}
                onClick={() => onNavigate({ name: 'device', sessionId: device.id, tab: 'events' })}
                aria-current={active ? 'page' : undefined}
                title={collapsed ? `${device.label}（${device.online ? '在线' : '离线'}）` : undefined}
              >
                <span className="nav-icon" aria-hidden="true">
                  <span className={`conn-dot ${device.online ? 'ok' : 'muted'}`} />
                </span>
                {!collapsed && <span className="nav-label">{device.label}</span>}
              </button>
            );
          })}
        </div>

        {groupsBelow.map(renderGroup)}
      </nav>

      <div className="sidenav-foot">
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
