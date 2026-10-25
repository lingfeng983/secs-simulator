import { useCallback, useEffect, useState } from 'react';

/**
 * 轻量 hash 路由（零依赖,约 60 行）。
 *
 * <p>两层信息架构：</p>
 * <pre>
 *   #/workbench                 工作台(默认)
 *   #/device/{id}/{tab}         设备页 · monitor | config | tools
 *   #/system/{page}             系统页 · audit | console
 * </pre>
 *
 * <p>用 hash 而不是 history API:界面由后端静态托管,深链刷新不需要服务端
 * 回退规则。旧版把面板 id 存在 preferences.activeTab 里,迁移:首次进入
 * 无 hash 时按旧值映射一次。</p>
 */

export type DeviceTab = 'monitor' | 'config' | 'tools';
export const DEVICE_TABS: readonly DeviceTab[] = ['monitor', 'config', 'tools'];

export type SystemPage = 'audit' | 'console';

export type Route =
  | { name: 'workbench' }
  | { name: 'device'; sessionId: string; tab: DeviceTab }
  | { name: 'system'; page: SystemPage };

/** 解析当前 hash;空/非法回落工作台。 */
export function parseHash(hash: string, legacyTab?: string): Route {
  const text = hash.replace(/^#\/?/, '');
  if (text === '') {
    return legacyRoute(legacyTab);
  }
  const parts = text.split('/').filter((piece) => piece !== '');
  if (parts[0] === 'device' && parts[1] !== undefined && parts[1] !== '') {
    const tab = DEVICE_TABS.includes(parts[2] as DeviceTab) ? (parts[2] as DeviceTab) : 'monitor';
    return { name: 'device', sessionId: decodeURIComponent(parts[1]), tab };
  }
  if (parts[0] === 'system') {
    const page: SystemPage = parts[1] === 'console' ? 'console' : 'audit';
    return { name: 'system', page };
  }
  if (parts[0] === 'system' || parts[0] === 'workbench') {
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
    default:
      return { name: 'workbench' };
  }
}

/** 路由 → hash。 */
export function toHash(route: Route): string {
  switch (route.name) {
    case 'workbench':
      return '#/workbench';
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
