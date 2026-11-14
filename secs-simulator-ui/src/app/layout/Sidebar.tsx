import { Icon } from '../../shared/ui/Icon';
import type { Route } from '../router';
import {
  DEVICE_CONFIG_PAGES,
  DEVICE_CONFIG_PAGE_LABELS,
  DEVICE_RUNTIME_PAGES,
  DEVICE_RUNTIME_PAGE_LABELS,
  HOST_CONFIG_PAGES,
  HOST_CONFIG_PAGE_LABELS,
  HOST_RUNTIME_PAGES,
  HOST_RUNTIME_PAGE_LABELS,
  sameRoute,
} from '../router';
import { NAV_GROUPS } from './navigation';
import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from 'react';
import {
  SIDEBAR_DEFAULT_W,
  SIDEBAR_MAX_W,
  SIDEBAR_MIN_W,
  clampSidebarWidth,
} from '../../state/usePreferences';
import type { HostV2 } from '../../api';
import {
  DEVICE_STATUS_LABEL,
  DEVICE_STATUS_TONE,
  deviceStatus,
  deviceStatusHint,
} from '../../shared/lib/deviceStatus';
import { hostPhaseLabel, hostPhaseTone } from '../../state/useHostsV2';

/** 侧栏「设备清单」动态组里的一台设备。 */
export interface SidebarDevice {
  id: string;
  label: string;
  /** 会话状态：CREATED / OPENING / OPEN / CLOSING / CLOSED。 */
  state: string;
  /** 控制状态：OFFLINE / COMMUNICATING / ONLINE。 */
  controlState?: string;
  /** 创建时使用的模板 id，只用于悬浮说明，不再参与分组。 */
  template?: string;
  /** 配置文件，用于 tooltip。 */
  config?: string;
}

/** 侧栏「主机清单」动态组里的一台主机（v2 独立实例）。 */
export type SidebarHost = HostV2;

export interface SidebarProps {
  /** 当前路由。 */
  route: Route;
  /** 动态设备列表(来自会话)。 */
  devices: readonly SidebarDevice[];
  /** 动态主机实例列表(v2)。 */
  hostInstances?: readonly SidebarHost[];
  /** 点主机实例 → 进详情。 */
  onNavigateHost?: (hostId: string) => void;
  /** 折叠为纯图标栏。 */
  collapsed: boolean;
  /** 展开时的宽度（px）。 */
  width: number;
  /** 拖动或键盘调整宽度（已按区间收口）。 */
  onResize: (width: number) => void;
  onNavigate: (route: Route) => void;
  onToggleCollapse: () => void;
}

/**
 * 左侧导航栏（3.0 三组 · 第三轮修订 11）：
 * 设备清单 / 主机清单 / 独立工具。设备与主机都是动态树,
 * 展开后可直达两段式子页。
 */
export function Sidebar({
  route,
  devices,
  hostInstances = [],
  onNavigateHost,
  collapsed,
  width,
  onResize,
  onNavigate,
  onToggleCollapse,
}: SidebarProps): JSX.Element {
  /* ===== 宽度拖动 =====
     指针按下后把移动/抬起交给 handle 自己（pointer capture），
     这样鼠标划过内容区也不会丢事件；双击恢复默认宽度。 */
  const dragRef = useRef<{ startX: number; startWidth: number } | null>(null);
  const frameRef = useRef<number | null>(null);
  const pendingWidthRef = useRef<number | null>(null);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    document.body.classList.toggle('sidenav-resizing', dragging);
    return () => document.body.classList.remove('sidenav-resizing');
  }, [dragging]);

  useEffect(() => () => {
    if (frameRef.current !== null) {
      window.cancelAnimationFrame(frameRef.current);
    }
  }, []);

  const scheduleResize = (next: number) => {
    pendingWidthRef.current = next;
    if (frameRef.current !== null) {
      return;
    }
    frameRef.current = window.requestAnimationFrame(() => {
      frameRef.current = null;
      const width = pendingWidthRef.current;
      pendingWidthRef.current = null;
      if (width !== null) {
        onResize(width);
      }
    });
  };

  const beginResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (collapsed) {
      return;
    }
    event.preventDefault();
    dragRef.current = { startX: event.clientX, startWidth: width };
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragging(true);
  };

  const moveResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (drag === null) {
      return;
    }
    scheduleResize(clampSidebarWidth(drag.startWidth + (event.clientX - drag.startX)));
  };

  const endResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (dragRef.current === null) {
      return;
    }
    if (frameRef.current !== null) {
      window.cancelAnimationFrame(frameRef.current);
      frameRef.current = null;
    }
    const pending = pendingWidthRef.current;
    pendingWidthRef.current = null;
    if (pending !== null) {
      onResize(pending);
    }

    dragRef.current = null;
    setDragging(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  };

  const resizeByKey = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      onResize(clampSidebarWidth(width - 16));
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      onResize(clampSidebarWidth(width + 16));
    } else if (event.key === 'Home') {
      event.preventDefault();
      onResize(SIDEBAR_MIN_W);
    } else if (event.key === 'End') {
      event.preventDefault();
      onResize(SIDEBAR_MAX_W);
    }
  };

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
              <Icon name={item.icon} size={16} />
            </span>
            {!collapsed && <span className="nav-label">{item.label}</span>}
          </button>
        );
      })}
      {/* 动态实例树挂在本组内 */}
      {group.dynamic === 'devices' && renderDeviceTree()}
      {group.dynamic === 'hosts' && renderHostTree()}
    </div>
  );

  /* ===== 设备树 =====
     设备清单只保留"平铺设备 + 可展开子页"：筛选框、按模板分组、分组折叠
     这些入口与逻辑已下线，避免用户在一张只有几台设备的清单里还要先选模式。 */
  /* 计数用「在线」：标题里的 n/m 是"有几台真的能上报事件"。
     只连上但没上线的不算——严格 GEM 下它们一条 S6F11 也发不出去。 */
  const onlineCount = devices.filter((device) => deviceStatus(device) === 'online').length;
  const [expandedDevices, setExpandedDevices] = useState<ReadonlySet<string>>(new Set());

  const toggleSet = (
    setter: (updater: (previous: ReadonlySet<string>) => ReadonlySet<string>) => void,
    key: string,
  ) => {
    setter((previous) => {
      const next = new Set(previous);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  const renderDevice = (device: SidebarDevice, withPages: boolean) => {
    const active = route.name === 'device' && route.sessionId === device.id;
    const status = deviceStatus(device);
    const tone = DEVICE_STATUS_TONE[status];
    const label = DEVICE_STATUS_LABEL[status];
    const expanded = expandedDevices.has(device.id);
    return (
      <div className="nav-device" key={device.id}>
        <div className="nav-device-row">
          <button
            type="button"
            className={`nav-item${active ? ' active' : ''}`}
            onClick={() => onNavigate({ name: 'device', sessionId: device.id, zone: 'runtime', page: DEVICE_RUNTIME_PAGES[0] })}
            aria-current={active ? 'page' : undefined}
            title={`${device.label}（${label}）${deviceStatusHint(status)}${device.template === undefined || device.template === '' ? '' : `\n模板：${device.template}`}`}
          >
            {collapsed ? (
              <span className={`nav-monogram ${tone}`} aria-hidden="true">
                {device.label.slice(0, 2).toUpperCase()}
              </span>
            ) : (
              <span className="nav-icon" aria-hidden="true">
                <span className={`conn-dot ${tone}`} />
              </span>
            )}
            {!collapsed && <span className="nav-label">{device.label}</span>}
            {!collapsed && <span className={`nav-state tone-${tone}`}>{label}</span>}
          </button>
          {withPages && !collapsed && (
            <button
              type="button"
              className={`nav-caret${expanded ? ' open' : ''}`}
              aria-label={`${expanded ? '收起' : '展开'} ${device.label} 的子页`}
              aria-expanded={expanded}
              onClick={() => toggleSet(setExpandedDevices, device.id)}
            >
              ▸
            </button>
          )}
        </div>
        {withPages && !collapsed && expanded && (
          <div className="nav-subpages">
            {[
              ...DEVICE_CONFIG_PAGES.map((id) => ({ id, label: DEVICE_CONFIG_PAGE_LABELS[id], zone: 'config' as const })),
              ...DEVICE_RUNTIME_PAGES.map((id) => ({ id, label: DEVICE_RUNTIME_PAGE_LABELS[id], zone: 'runtime' as const })),
            ].map((entry) => {
              const selected = active && route.name === 'device' && route.zone === entry.zone && route.page === entry.id;
              return (
                <button
                  key={`${entry.zone}:${entry.id}`}
                  type="button"
                  className={`nav-subpage${selected ? ' active' : ''}`}
                  onClick={() => onNavigate({
                    name: 'device', sessionId: device.id, zone: entry.zone, page: entry.id,
                  })}
                >
                  {entry.zone === 'runtime' ? '◉ ' : '▣ '}{entry.label}
                </button>
              );
            })}
          </div>
        )}
      </div>
    );
  };

  const renderDeviceTree = () => (
    <>
      {!collapsed && <div className="nav-group-title nav-group-subtitle">设备 · {onlineCount}/{devices.length}</div>}
      {devices.length === 0 && !collapsed && <p className="nav-empty">暂无设备</p>}
      {devices.map((device) => renderDevice(device, !collapsed))}
    </>
  );

  /* ===== 主机树 ===== */
  const [expandedHosts, setExpandedHosts] = useState<ReadonlySet<string>>(new Set());

  const renderHostTree = () => {
    const connected = hostInstances.filter((host) => host.phase === 'CONNECTED').length;
    if (collapsed) {
      return hostInstances.map((host) => {
        const active = route.name === 'host' && route.hostId === host.id;
        return (
          <button
            key={host.id}
            type="button"
            className={`nav-item${active ? ' active' : ''}`}
            onClick={() => onNavigateHost?.(host.id)}
            title={host.name}
          >
            <span className={`nav-monogram ${host.phase === 'CONNECTED' ? 'ok' : 'muted'}`} aria-hidden="true">
              {host.name.slice(0, 2).toUpperCase()}
            </span>
          </button>
        );
      });
    }
    return (
      <>
        <div className="nav-group-title nav-group-subtitle">主机 · {connected}/{hostInstances.length}</div>
        {hostInstances.length === 0 && <p className="nav-empty">暂无主机</p>}
        {hostInstances.map((host) => {
          const active = route.name === 'host' && route.hostId === host.id;
          const expanded = expandedHosts.has(host.id);
          const tone = hostPhaseTone(host.phase);
          const phase = hostPhaseLabel(host.phase);
          return (
            <div className="nav-device" key={host.id}>
              <div className="nav-device-row">
                <button
                  type="button"
                  className={`nav-item${active ? ' active' : ''}`}
                  onClick={() => onNavigateHost?.(host.id)}
                  aria-current={active ? 'page' : undefined}
                  title={`${host.name}（${phase}）\n${host.target}`}
                >
                  <span className="nav-icon" aria-hidden="true">
                    <span className={`conn-dot ${tone}`} />
                  </span>
                  {!collapsed && <span className="nav-label">{host.name}</span>}
                  {!collapsed && <span className={`nav-state tone-${tone}`}>{phase}</span>}
                </button>
                {!collapsed && (
                  <button
                    type="button"
                    className={`nav-caret${expanded ? ' open' : ''}`}
                    aria-label={`${expanded ? '收起' : '展开'} ${host.name} 的子页`}
                    aria-expanded={expanded}
                    onClick={() => toggleSet(setExpandedHosts, host.id)}
                  >
                    ▸
                  </button>
                )}
              </div>
              {!collapsed && expanded && (
                <div className="nav-subpages">
                  {[
                    ...HOST_CONFIG_PAGES.map((id) => ({ id, label: HOST_CONFIG_PAGE_LABELS[id], zone: 'config' as const })),
                    ...HOST_RUNTIME_PAGES.map((id) => ({ id, label: HOST_RUNTIME_PAGE_LABELS[id], zone: 'runtime' as const })),
                  ].map((entry) => {
                    const selected = active && route.name === 'host' && route.zone === entry.zone && route.page === entry.id;
                    return (
                      <button
                        key={`${entry.zone}:${entry.id}`}
                        type="button"
                        className={`nav-subpage${selected ? ' active' : ''}`}
                        onClick={() => onNavigate({
                          name: 'host', hostId: host.id, zone: entry.zone, page: entry.id,
                        })}
                      >
                        {entry.zone === 'runtime' ? '◉ ' : '▣ '}{entry.label}
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </>
    );
  };

  return (
    <aside className={`sidenav${collapsed ? ' collapsed' : ''}`} aria-label="面板导航">
      {!collapsed && (
        <div
          className={`sidenav-resizer${dragging ? ' dragging' : ''}`}
          role="separator"
          aria-orientation="vertical"
          aria-label="拖动调整导航宽度（双击恢复默认）"
          aria-valuemin={SIDEBAR_MIN_W}
          aria-valuemax={SIDEBAR_MAX_W}
          aria-valuenow={Math.round(width)}
          tabIndex={0}
          title="拖动调整宽度，双击恢复默认"
          onPointerDown={beginResize}
          onPointerMove={moveResize}
          onPointerUp={endResize}
          onPointerCancel={endResize}
          onDoubleClick={() => onResize(SIDEBAR_DEFAULT_W)}
          onKeyDown={resizeByKey}
        />
      )}
      <nav className="sidenav-nav">
        {NAV_GROUPS.map(renderGroup)}
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
          <Icon name={collapsed ? 'expand' : 'collapse'} size={15} />
        </button>
      </div>
    </aside>
  );
}
