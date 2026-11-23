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
  | 'overview'    // 概览：身份信息 + 连接/结构/周期摘要（静态事实读配置，运行状态读会话）
  | 'connection'  // 连接：session 段
  | 'structure'   // 结构：单元/模板/组合 整体模型
  | 'svids'       // 变量配置：SVID 定义（写入配置草稿）
  | 'context'     // 晶圆属性：LOTID/RCPID/PPID/WAFERID（写入配置草稿）
  | 'events'      // 事件与报告：CEID/RPT 定义（写入配置草稿）
  | 'cycle'       // 工艺周期：节拍参数 + 片内事件 + 运行卡（2026-11-16 拆分）
  | 'plans'       // 事件计划：events.plans 定义（写入配置草稿）
  | 'document'    // 配置原文
  | 'constants'   // 设备常量（ECV）
  | 'revisions';  // 版本历史

/** 设备运行区子页：当前运行（仅本次有效）。
 *  定义类编辑已全部上收配置区，运行区只留"要动手"的页。 */
export type DeviceRuntimePage =
  | 'plans'      // 事件执行：计划启停 + 本设备实时事件
  | 'cycle'      // 工艺周期：运行态启停 + 片内事件台账（2026-11-17 运行区补齐）
  | 'messages'   // 消息发送：手动 SML + 压测发送（2026-11-20 由「手动与压测」拆出）
  | 'alarms';    // 报警风暴：活动报警 + 置位/清除 + 风暴（2026-11-20 单独成页）

export type DeviceZone = 'config' | 'runtime';
export type DevicePage = DeviceConfigPage | DeviceRuntimePage;

export const DEVICE_CONFIG_PAGES: readonly DeviceConfigPage[] = [
  'overview', 'connection', 'structure', 'constants', 'context', 'svids', 'events', 'cycle', 'plans', 'document', 'revisions',
];
/* 运行区只留"要动手"的页：启停/发送/压测/报警。实时状态在实例头，
   报文监控统一收进右侧抽屉，不再单独列 status / messages 两个子页。 */
export const DEVICE_RUNTIME_PAGES: readonly DeviceRuntimePage[] = [
  'plans', 'cycle', 'messages', 'alarms',
];

/** 旧运行区子页名 → 新页（2026-11-20「手动与压测」拆成两页后的一次性迁移）。 */
const LEGACY_RUNTIME_PAGE: Record<string, DeviceRuntimePage> = {
  manual: 'messages',
  send: 'messages',
};

/** 主机配置区子页（连接参数并入概览，不再单列一页）。 */
export type HostConfigPage =
  | 'overview'   // 概览：名称/目标/设置版本/自动重连
  | 'presets'    // 报文模板：16 条预设 + 说明
  | 'trace'      // Trace 设置：S2F23 方案库
  | 'collect';   // 采集设置：S1F3 配置库

/** 主机运行区子页：实时状态页已删（状态在实例头）。 */
export type HostRuntimePage =
  | 'messages' // 消息发送（只发本主机）
  | 'trace'    // Trace 采集
  | 'collect'; // 数据采集

export type HostZone = 'config' | 'runtime';
export type HostPage = HostConfigPage | HostRuntimePage;

/* 连接页已删除：目标地址/端口/Device ID 在概览里就能改，不再单独占一个页签。 */
export const HOST_CONFIG_PAGES: readonly HostConfigPage[] = [
  'overview', 'presets', 'trace', 'collect',
];
/* 收发记录并入右侧抽屉；连接/断开/使设置生效在实例头；实时状态并入实例头。 */
export const HOST_RUNTIME_PAGES: readonly HostRuntimePage[] = [
  'messages', 'trace', 'collect',
];

export const DEVICE_CONFIG_PAGE_LABELS: Record<DeviceConfigPage, string> = {
  overview: '概览',
  connection: '链接配置',
  structure: '结构配置',
  svids: '变量配置',
  context: '晶圆属性',
  events: '事件与报告',
  cycle: '工艺周期',
  plans: '事件计划',
  document: '全量配置',
  constants: '设备常量',
  revisions: '版本历史',
};
export const DEVICE_RUNTIME_PAGE_LABELS: Record<DeviceRuntimePage, string> = {
  plans: '事件执行',
  cycle: '工艺周期',
  messages: '消息发送',
  alarms: '报警风暴',
};
export const HOST_CONFIG_PAGE_LABELS: Record<HostConfigPage, string> = {
  overview: '概览',
  presets: '报文模板',
  trace: 'Trace 设置',
  collect: '采集设置',
};
export const HOST_RUNTIME_PAGE_LABELS: Record<HostRuntimePage, string> = {
  messages: '消息发送',
  trace: 'Trace 采集',
  collect: '数据采集',
};

/** 子页签悬浮说明（SubTabs note）：一句话说清这页管什么。 */
export const DEVICE_CONFIG_PAGE_NOTES: Record<DeviceConfigPage, string> = {
  overview: '设备身份与模板选择；保存后需重启生效。',
  connection: 'HSMS 链接参数（session 段）：地址、端口、模式。',
  structure: '设备全局与单元结构；设备全局点「保存」生效，单元增删立即保存。',
  svids: '状态变量（SVID）定义与各自的值来源：新建/编辑/删除确认后立即保存。',
  context: '晶圆属性（LOTID/RCPID/PPID/WAFERID）：随片变化，报告里按 ctx.名 引用。',
  events: '事件（CEID）与报告（RPT）定义：报告可引用 SVID / 设备常量 / 晶圆属性。',
  cycle: '工艺周期：一片 wafer 的节拍与片内事件；值随周期启停，运行卡随设备启动。',
  plans: '事件计划：周期外的异常路径（触发规则），启动/停止在运行区「事件执行」。',
  document: '全量配置 JSON 与版本 diff。',
  constants: '设备常量（ECV）定义：新建/编辑/删除确认后立即保存。',
  revisions: '配置版本历史与回看。',
};
export const DEVICE_RUNTIME_PAGE_NOTES: Record<DeviceRuntimePage, string> = {
  plans: '计划启停等运行态设置；改动仅本次运行有效。',
  cycle: '工艺周期运行态：启停、当前片号/相位/进度与片内事件的发送记录。',
  messages: '手动 SML 发送与压测发送（服务端循环）；本页不保留收发历史，是否到达看右侧消息监控。',
  alarms: '报警置位/清除与报警风暴；风暴只压主机侧处理能力，历史 S5F1/S5F2 看右侧消息监控。',
};
export const HOST_CONFIG_PAGE_NOTES: Record<HostConfigPage, string> = {
  overview: '主机身份与目标；保存后需「使设置生效」。',
  presets: '主机侧 16 条报文预设与说明。',
  trace: 'S2F23 采样方案库：只管理方案与参数，发送在运行区。',
  collect: 'S1F3 采集配置库：只管理配置方案，下发与采集在运行区。',
};
export const HOST_RUNTIME_PAGE_NOTES: Record<HostRuntimePage, string> = {
  messages: '向本主机发送报文。',
  trace: '下发 S2F23 采样并查看 S6F1 参数趋势。',
  collect: '下发采集配置、启停采集并查看数据曲线。',
};

/** 全局单功能页已精简掉；旧 hash 一律回落工作台。 */
export type SystemPage = 'audit';

export type Route =
  | { name: 'workbench' }
  | { name: 'templates' }
  | { name: 'svid-templates' }
  | { name: 'device'; sessionId: string; zone: DeviceZone; page: DevicePage }
  | { name: 'hosts' }
  | { name: 'host'; hostId: string; zone: HostZone; page: HostPage }
  | { name: 'host-templates' }
  | { name: 'system'; page: SystemPage };

/** 旧设备扁平子页 → 两段式新家（一次性迁移映射）。
 *  定义类编辑已上收配置区：旧运行区 catalog/svids 深链迁到配置区对应页。 */
const LEGACY_DEVICE_PAGE: Record<string, [DeviceZone, DevicePage]> = {
  ceids: ['config', 'events'],
  reports: ['config', 'events'],
  events: ['config', 'plans'],
  alarms: ['runtime', 'alarms'],
  svids: ['config', 'svids'],
  /* 报文监控已并入右侧抽屉：旧消息深链回落到运行区首页，由 App 顺手拉开抽屉。 */
  messages: ['runtime', 'plans'],
  send: ['runtime', 'messages'],
  manual: ['runtime', 'messages'],
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
        /* 旧运行区子页名（manual）按别名迁到新页，其余非法值收口到该区首页 */
        const page = known.find((id) => id === parts[3])
          ?? (zone === 'runtime' ? LEGACY_RUNTIME_PAGE[parts[3]] : undefined)
          ?? known[0];
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
    if (parts[0] === 'svid-templates') {
      return { name: 'svid-templates' };
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
    case 'svid-templates':
      return '#/svid-templates';
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
