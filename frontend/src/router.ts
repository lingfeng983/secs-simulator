import { useCallback, useEffect, useState } from 'react';

/**
 * 轻量 hash 路由（零依赖,约 80 行）。
 *
 * <p>拆分后的信息架构——每个页面只做一件事：</p>
 * <pre>
 *   #/workbench                 工作台(默认):KPI + 设备卡墙
 *   #/events                    实时事件流(全局)
 *   #/messages                  报文流(全局)
 *   #/sessions                  会话管理
 *   #/device/{id}/{page}        设备子页(见 DEVICE_PAGES,一页一能力)
 *   #/system/{page}             系统 · audit | console
 * </pre>
 *
 * <p>用 hash 而不是 history API:界面由后端静态托管,深链刷新不需要服务端
 * 回退规则。旧版把面板 id 存在 preferences.activeTab 里,迁移:首次进入
 * 无 hash 时按旧值映射一次。</p>
 */

/** 设备子页:一个能力一页。 */
export type DevicePage =
  | 'monitor'    // 曲线预览
  | 'events'     // 事件编排 + 本设备实时事件
  | 'alarms'     // 报警与终端
  | 'catalog'    // 设备目录
  | 'config'     // 配置编辑
  | 'send'       // SML 发送
  | 'burst'      // 压测
  | 'hostsim'    // 主机模拟
  | 'collector'; // S1F3 采集

export const DEVICE_PAGES: readonly DevicePage[] = [
  'monitor', 'events', 'alarms', 'catalog', 'config', 'send', 'burst', 'hostsim', 'collector',
];

/** 设备子页的中文名（设备页子导航与命令面板用）。 */
export const DEVICE_PAGE_LABELS: Record<DevicePage, string> = {
  monitor: '曲线',
  events: '事件',
  alarms: '报警',
  catalog: '目录',
  config: '配置',
  send: '发送',
  burst: '压测',
  hostsim: '主机模拟',
  collector: '采集',
};

/** 全局单功能页(不属于任何设备)。 */
export type ViewPage = 'events' | 'messages' | 'sessions';
export const VIEW_PAGES: readonly ViewPage[] = ['events', 'messages', 'sessions'];

export type SystemPage = 'audit' | 'console';

export type Route =
  | { name: 'workbench' }
  | { name: 'view'; page: ViewPage }
  | { name: 'device'; sessionId: string; tab: DevicePage }
  | { name: 'system'; page: SystemPage };

/** 解析当前 hash;空/非法回落工作台。 */
export function parseHash(hash: string, legacyTab?: string): Route {
  const text = hash.replace(/^#\/?/, '');
  if (text === '') {
    return legacyRoute(legacyTab);
  }
  const parts = text.split('/').filter((piece) => piece !== '');
  if (parts[0] === 'device' && parts[1] !== undefined && parts[1] !== '') {
    const tab = DEVICE_PAGES.includes(parts[2] as DevicePage) ? (parts[2] as DevicePage) : 'monitor';
    return { name: 'device', sessionId: decodeURIComponent(parts[1]), tab };
  }
  if (parts[0] === 'system') {
    const page: SystemPage = parts[1] === 'console' ? 'console' : 'audit';
    return { name: 'system', page };
  }
  if (VIEW_PAGES.includes(parts[0] as ViewPage) && parts.length === 1) {
    return { name: 'view', page: parts[0] as ViewPage };
  }
  if (parts[0] === 'workbench') {
    return { name: 'workbench' };
  }
  return legacyRoute(legacyTab);
}

/** 旧版面板 id → 新路由（一次性迁移,未知值回工作台）。 */
function legacyRoute(tab: string | undefined): Route {
  switch (tab) {
    case 'audit':
      return { name: 'system', page: 'audit' };
    case 'console':
      return { name: 'system', page: 'console' };
    case 'messages':
      return { name: 'view', page: 'messages' };
    case 'sessions':
      return { name: 'view', page: 'sessions' };
    default:
      return { name: 'workbench' };
  }
}

/** 路由 → hash。 */
export function toHash(route: Route): string {
  switch (route.name) {
    case 'workbench':
      return '#/workbench';
    case 'view':
      return `#/${route.page}`;
    case 'device':
      return `#/device/${encodeURIComponent(route.sessionId)}/${route.tab}`;
    case 'system':
      return `#/system/${route.page}`;
  }
}

export function sameRoute(left: Route, right: Route): boolean {
  return toHash(left) === toHash(right);
}

/** 当前路由 + 导航函数;hashchange 驱动,浏览器前进后退可用。 */
export function useRoute(legacyTab?: string): [Route, (route: Route) => void] {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash, legacyTab));

  useEffect(() => {
    const onHashChange = () => {
      setRoute(parseHash(window.location.hash, legacyTab));
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
    // legacyTab 只在挂载时作为迁移输入,变化不应重挂
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const navigate = useCallback((next: Route) => {
    const hash = toHash(next);
    if (window.location.hash === hash) {
      setRoute(next);
      return;
    }
    window.location.hash = hash;
  }, []);

  return [route, navigate];
}
