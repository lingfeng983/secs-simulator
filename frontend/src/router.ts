import { useCallback, useEffect, useState } from 'react';

/**
 * 轻量 hash 路由（零依赖,约 80 行）。
 *
 * <p>精简后的信息架构——每个页面只做一件事：</p>
 * <pre>
 *   #/workbench                 工作台(默认):KPI + 设备卡墙 + 会话管理
 *   #/device/{id}/{page}        设备子页(见 DEVICE_PAGES,一页一能力)
 *   #/system/audit              系统 · 审计
 * </pre>
 *
 * <p>用 hash 而不是 history API:界面由后端静态托管,深链刷新不需要服务端
 * 回退规则。旧版把面板 id 存在 preferences.activeTab 里,迁移:首次进入
 * 无 hash 时按旧值映射一次。</p>
 */

/** 设备子页:一个能力一页。曲线预览并入「状态变量」的逐变量预览;压测拆并到发送/报警/事件;采集并入主机模拟。 */
export type DevicePage =
  | 'events'     // 事件编排(计划 CRUD + 报告管理 + 触发/压测) + 本设备实时事件
  | 'alarms'     // 报警与终端 + 报警风暴
  | 'svids'      // 状态变量:增删改查 + 曲线预览 + 值设定
  | 'config'     // 配置编辑
  | 'send'       // SML 发送 + 自定义消息压测
  | 'hostsim';   // 主机模拟:FDC Trace + S1F3 采集

export const DEVICE_PAGES: readonly DevicePage[] = [
  'events', 'alarms', 'svids', 'config', 'send', 'hostsim',
];

/** 设备子页的中文名（设备页子导航与命令面板用）。 */
export const DEVICE_PAGE_LABELS: Record<DevicePage, string> = {
  events: '事件',
  alarms: '报警',
  svids: '状态变量',
  config: '配置',
  send: '发送',
  hostsim: '主机模拟',
};

/** 全局单功能页已精简掉(events/messages/sessions 并入工作台,console 并入审计);旧 hash 一律回落工作台。 */
export type SystemPage = 'audit';

export type Route =
  | { name: 'workbench' }
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
    const tab = DEVICE_PAGES.includes(parts[2] as DevicePage) ? (parts[2] as DevicePage) : 'events';
    return { name: 'device', sessionId: decodeURIComponent(parts[1]), tab };
  }
  if (parts[0] === 'system') {
    return { name: 'system', page: 'audit' };
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
