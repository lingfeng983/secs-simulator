import { useCallback, useEffect, useState } from 'react';

/**
 * 轻量 hash 路由（零依赖）。
 *
 * <p>3.0 两段式信息架构（设计 §3.1）：</p>
 * <pre>
 *   #/workbench                          工作台（设备清单入口）
 *   #/device/{id}/{zone}/{page}          设备详情：配置区 / 运行区
 *   #/hosts                              主机列表页
 *   #/hosts/{hostId}/{zone}/{page}       主机详情：配置区 / 运行区
 *   #/host-templates                     独立工具 · 主机模板
 *   #/templates                          独立工具 · 设备模板
 *   #/system/audit                       独立工具 · 审计
 * </pre>
 *
 * <p>用 hash 而不是 history API：界面由后端静态托管，深链刷新不需要服务端
 * 回退规则。2.x 的旧 hash（扁平子页 / hostsim 五页）在这里一次性映射到新家。</p>
 */

/** 设备配置区子页：描述「这台设备是什么」（保存后需重载）。 */
export type DeviceConfigPage =
  | 'overview'    // 概览：名称/模板/自动连接
  | 'connection'  // 连接：session 段
  | 'structure'   // 结构：单元/SVID/曲线/组合/实例
  | 'document'    // 配置原文
  | 'constants'   // 设备常量（ECV）
  | 'revisions';  // 版本历史

/** 设备运行区子页：当前运行（仅本次有效）。 */
export type DeviceRuntimePage =
  | 'plans'     // 事件计划与执行：计划 CRUD + 启停 + 本设备实时事件
  | 'catalog'   // 事件与报告：CEID/RPTID 运行时增删改 + 手动触发
  | 'svids'     // 状态变量：曲线预览 + 值设定
  | 'manual';   // 手动操作：SML 发送 + 报警置位/清除

export type DeviceZone = 'config' | 'runtime';
export type DevicePage = DeviceConfigPage | DeviceRuntimePage;

export const DEVICE_CONFIG_PAGES: readonly DeviceConfigPage[] = [
  'overview', 'connection', 'structure', 'document', 'constants', 'revisions',
];
/* 运行区只留"要动手"的页：实时状态并入实例头（停止/启动/链接三态 + 计数），
   报文监控统一收进右侧抽屉，因此不再单独列 status / messages 两个子页。 */
export const DEVICE_RUNTIME_PAGES: readonly DeviceRuntimePage[] = [
  'plans', 'catalog', 'svids', 'manual',
];

/** 主机配置区子页（连接参数并入概览，不再单列一页）。 */
export type HostConfigPage =
  | 'overview'   // 概览：名称/目标/设置版本/自动重连
  | 'presets'    // 报文模板：16 条预设 + 说明
  | 'trace'      // Trace 设置：S2F23 表单
  | 'collect';   // 采集设置：启停 CEID + SVID

/** 主机运行区子页。 */
export type HostRuntimePage =
  | 'status'   // 实时状态
  | 'messages' // 消息发送（只发本主机）
  | 'trace'    // Trace 采集
  | 'collect'; // 数据采集

export type HostZone = 'config' | 'runtime';
export type HostPage = HostConfigPage | HostRuntimePage;

/* 连接页已删除：目标地址/端口/Device ID 在概览里就能改，不再单独占一个页签。 */
export const HOST_CONFIG_PAGES: readonly HostConfigPage[] = [
  'overview', 'presets', 'trace', 'collect',
];
/* 收发记录并入右侧抽屉，手动操作页签删除；连接/断开/使设置生效在实例头。 */
export const HOST_RUNTIME_PAGES: readonly HostRuntimePage[] = [
  'status', 'messages', 'trace', 'collect',
];

export const DEVICE_CONFIG_PAGE_LABELS: Record<DeviceConfigPage, string> = {
  overview: '概览',
  connection: '连接',
  structure: '结构',
  document: '配置原文',
  constants: '设备常量',
  revisions: '版本历史',
};
export const DEVICE_RUNTIME_PAGE_LABELS: Record<DeviceRuntimePage, string> = {
  plans: '事件计划',
  catalog: '事件与报告',
  svids: '状态变量',
  manual: '手动操作',
};
export const HOST_CONFIG_PAGE_LABELS: Record<HostConfigPage, string> = {
  overview: '概览',
  presets: '报文模板',
  trace: 'Trace 设置',
  collect: '采集设置',
};
export const HOST_RUNTIME_PAGE_LABELS: Record<HostRuntimePage, string> = {
  status: '实时状态',
  messages: '消息发送',
  trace: 'Trace 采集',
  collect: '数据采集',
};

/** 全局单功能页已精简掉；旧 hash 一律回落工作台。 */
export type SystemPage = 'audit';

export type Route =
  | { name: 'workbench' }
  | { name: 'templates' }
  | { name: 'device'; sessionId: string; zone: DeviceZone; page: DevicePage }
  | { name: 'hosts' }
  | { name: 'host'; hostId: string; zone: HostZone; page: HostPage }
  | { name: 'host-templates' }
  | { name: 'system'; page: SystemPage };

/** 旧设备扁平子页 → 两段式新家（一次性迁移映射）。 */
const LEGACY_DEVICE_PAGE: Record<string, [DeviceZone, DevicePage]> = {
  ceids: ['runtime', 'catalog'],
  reports: ['runtime', 'catalog'],
  events: ['runtime', 'plans'],
  alarms: ['runtime', 'manual'],
  svids: ['runtime', 'svids'],
  /* 报文监控已并入右侧抽屉：旧消息深链回落到运行区首页，由 App 顺手拉开抽屉。 */
  messages: ['runtime', 'plans'],
  send: ['runtime', 'manual'],
  connection: ['config', 'connection'],
  structure: ['config', 'structure'],
  config: ['config', 'document'],
};

function isDeviceZone(value: string | undefined): value is DeviceZone {
  return value === 'config' || value === 'runtime';
}

function isHostZone(value: string | undefined): value is HostZone {
  return value === 'config' || value === 'runtime';
}

/** 解析当前 hash；空/非法回落工作台。 */
export function parseHash(hash: string, legacyTab?: string): Route {
  const text = hash.replace(/^#\/?/, '');
  if (text === '') {
    return legacyRoute(legacyTab);
  }
  const parts = text.split('/').filter((piece) => piece !== '');
  try {
    if (parts[0] === 'device' && parts[1] !== undefined && parts[1] !== '') {
      const sessionId = decodeURIComponent(parts[1]);
      if (isDeviceZone(parts[2]) && parts[3] !== undefined) {
        /* 子页名要按该区的合法清单收口：手输/旧书签里的
           `#/device/line1/run/whatever` 以前会原样塞进路由，DevicePage 一个
           分支都匹配不上，页面就只剩页签、下面是空的（看起来像坏了）。
           注意只在"给了子页名"时收口：`#/device/line1/config` 这种 2.x 旧深链
           要继续走下面的 LEGACY_DEVICE_PAGE 映射。 */
        const zone = parts[2];
        const known = zone === 'config' ? DEVICE_CONFIG_PAGES : DEVICE_RUNTIME_PAGES;
        const page = known.find((id) => id === parts[3]) ?? known[0];
        return { name: 'device', sessionId, zone, page };
      }
      /* 2.x 旧深链：#/device/{id}/{page} → 一次性映射 */
      const legacy = parts[2] ?? 'ceids';
      const mapped = LEGACY_DEVICE_PAGE[legacy] ?? ['runtime', 'catalog'];
      return { name: 'device', sessionId, zone: mapped[0], page: mapped[1] };
    }
    if (parts[0] === 'hostsim') {
      /* 2.x：#/hostsim/{page} → 主机列表页（实例树里再进详情） */
      return { name: 'hosts' };
    }
    if (parts[0] === 'hosts' && parts[1] === undefined) {
      return { name: 'hosts' };
    }
    if (parts[0] === 'hosts' && parts[1] !== undefined && parts[1] !== '') {
      const hostId = decodeURIComponent(parts[1]);
      if (isHostZone(parts[2])) {
        const zone = parts[2];
        const known = zone === 'config' ? HOST_CONFIG_PAGES : HOST_RUNTIME_PAGES;
        const page = known.find((id) => id === parts[3]) ?? known[0];
        return { name: 'host', hostId, zone, page };
      }
      return { name: 'host', hostId, zone: 'config', page: 'overview' };
    }
    if (parts[0] === 'host-templates') {
      return { name: 'host-templates' };
    }
    if (parts[0] === 'system') {
      return { name: 'system', page: 'audit' };
    }
    if (parts[0] === 'workbench') {
      return { name: 'workbench' };
    }
    if (parts[0] === 'templates') {
      return { name: 'templates' };
    }
  } catch {
    /* 地址里是坏的 %-转义：回工作台，别让解析异常把整页打白 */
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
    case 'templates':
      return '#/templates';
    case 'device':
      return `#/device/${encodeURIComponent(route.sessionId)}/${route.zone}/${route.page}`;
    case 'hosts':
      return '#/hosts';
    case 'host':
      return `#/hosts/${encodeURIComponent(route.hostId)}/${route.zone}/${route.page}`;
    case 'host-templates':
      return '#/host-templates';
    case 'system':
      return `#/system/${route.page}`;
  }
}

export function sameRoute(left: Route, right: Route): boolean {
  return toHash(left) === toHash(right);
}

/** 当前路由 + 导航函数；hashchange 驱动,浏览器前进后退可用。 */
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
