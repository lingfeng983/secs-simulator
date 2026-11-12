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

/** 侧栏「设备清单」动态组里的一台设备。 */
export interface SidebarDevice {
  id: string;
  label: string;
  /** OFFLINE / COMMUNICATING / ONLINE（E5 简化模型）。 */
  state: string;
  /** 创建时使用的模板 id；空 = 未分组。 */
  template?: string;
  /** 配置文件，用于 tooltip。 */
  config?: string;
}

/** 侧栏「主机清单」动态组里的一台主机（v2 独立实例）。 */
export type SidebarHost = HostV2;

/** 状态字与色调：主机连上但还没 SELECT 时是"连接中"，不能算离线。 */
function stateLabel(state: string): string {
  if (state === 'ONLINE') {
    return '在线';
  }
  if (state === 'COMMUNICATING') {
    return '通讯中';
  }
  return '离线';
}

function stateTone(state: string): 'ok' | 'warn' | 'muted' {
  if (state === 'ONLINE') {
    return 'ok';
  }
  if (state === 'COMMUNICATING') {
    return 'warn';
  }
  return 'muted';
}

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

  /* ===== 设备树 ===== */
  const onlineCount = devices.filter((device) => device.state !== 'OFFLINE').length;
  const [deviceFilter, setDeviceFilter] = useState('');
  const [groupByTemplate, setGroupByTemplate] = useState(false);
  const [collapsedGroups, setCollapsedGroups] = useState<ReadonlySet<string>>(new Set());
  const [expandedDevices, setExpandedDevices] = useState<ReadonlySet<string>>(new Set());
  const query = deviceFilter.trim().toLowerCase();
  const visibleDevices = query === ''
    ? devices
    : devices.filter((device) => (
      device.label.toLowerCase().includes(query)
      || device.id.toLowerCase().includes(query)
      || (device.template ?? '').toLowerCase().includes(query)
      || (device.config ?? '').toLowerCase().includes(query)
    ));

  const templateGroups = new Map<string, SidebarDevice[]>();
  for (const device of visibleDevices) {
    const key = device.template === undefined || device.template === '' ? '未分组模板' : device.template;
    const list = templateGroups.get(key) ?? [];
    list.push(device);
    templateGroups.set(key, list);
  }

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
    const tone = stateTone(device.state);
    const expanded = expandedDevices.has(device.id);
    return (
      <div className="nav-device" key={device.id}>
        <div className="nav-device-row">
          <button
            type="button"
            className={`nav-item${active ? ' active' : ''}`}
            onClick={() => onNavigate({ name: 'device', sessionId: device.id, zone: 'runtime', page: 'catalog' })}
            aria-current={active ? 'page' : undefined}
            title={`${device.label}（${stateLabel(device.state)}）${device.template === undefined || device.template === '' ? '' : `\n模板：${device.template}`}`}
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
            {!collapsed && <span className={`nav-state tone-${tone}`}>{stateLabel(device.state)}</span>}
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
      {!collapsed && (
        <div className="nav-device-tools">
          <input
            type="search"
            className="nav-filter"
            placeholder="筛选设备/模板"
            value={deviceFilter}
            onChange={(event) => setDeviceFilter(event.target.value)}
            aria-label="筛选设备"
          />
          <button
            type="button"
            className={`nav-mode${groupByTemplate ? ' active' : ''}`}
            onClick={() => setGroupByTemplate((previous) => !previous)}
            title={groupByTemplate ? '切换为平铺设备列表' : '按设备模板聚合为三级菜单'}
          >
            {groupByTemplate ? '平铺' : '分组'}
          </button>
        </div>
      )}
      {!collapsed && <div className="nav-group-title nav-group-subtitle">设备 · {onlineCount}/{devices.length}</div>}
      {visibleDevices.length === 0 && !collapsed && (
        <p className="nav-empty">{devices.length === 0 ? '暂无设备' : '没有匹配的设备'}</p>
      )}
      {!groupByTemplate && visibleDevices.map((device) => renderDevice(device, false))}
      {groupByTemplate && collapsed && visibleDevices.map((device) => renderDevice(device, false))}
      {groupByTemplate && !collapsed && [...templateGroups.entries()].map(([template, groupDevices]) => {
        const folded = collapsedGroups.has(template);
        return (
          <div className="nav-template-group" key={template}>
            <button
              type="button"
              className={`nav-template-head${folded ? ' folded' : ''}`}
              onClick={() => toggleSet(setCollapsedGroups, template)}
              aria-expanded={!folded}
              title={`模板 ${template}（${groupDevices.length} 台）`}
            >
              <span className="nav-caret">{folded ? '▸' : '▾'}</span>
              <span className="nav-template-name">{template}</span>
              <span className="nav-template-count">{groupDevices.length}</span>
            </button>
            {!folded && groupDevices.map((device) => renderDevice(device, true))}
          </div>
        );
      })}
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
          const tone = host.phase === 'CONNECTED' ? 'ok' : host.phase === 'FAILED' ? 'danger' : 'muted';
          const phase = host.phase === 'CONNECTED' ? '已连接' : host.phase === 'CONNECTING' ? '连接中'
            : host.phase === 'FAILED' ? '连接失败' : '已断开';
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
