import type { Route } from '../router';

/** 一个打开过的页面标签。hash 记的是该标签最后一次停留的子页。 */
export interface AppTab {
  /** 标签身份：一台设备 / 一台主机 / 一个独立页面各占一个标签。 */
  id: string;
  label: string;
  hash: string;
}

/**
 * 标签的类别：顶栏靠颜色一眼分开"设备 / 主机 / 其他"。
 *
 * <p>设备清单与设备详情算设备，主机清单与主机详情算主机，模板与审计算其他。</p>
 */
export type TabKind = 'device' | 'host' | 'other';

export function tabKindOf(tabId: string): TabKind {
  if (tabId === 'workbench' || tabId.startsWith('device:')) {
    return 'device';
  }
  if (tabId === 'hosts' || tabId.startsWith('host:')) {
    return 'host';
  }
  return 'other';
}

/** 最多同时保留多少个标签，避免长时间使用后顶栏挤成一条。 */
export const MAX_TABS = 12;

/** 判设备身份只需要这几个字段：会话 id、用的是哪份配置、设备叫什么。 */
interface SessionKeyLike {
  id: string;
  config?: string;
  device?: string;
}

/** 判配置身份只需要文件名。 */
interface ConfigKeyLike {
  name: string;
}

/**
 * 一个设备的稳定身份 = 它用的<b>配置文件名</b>。
 *
 * <p>设备在地址里可能以三种写法出现：配置文件全名（{@code ETCH01.json}）、
 * 去掉扩展名的写法（{@code ETCH01}）、以及启动后的会话 id（ULID / 旧版 stem）。
 * 这三种写法指的是同一台设备，页签必须落在同一个标签上，否则一台设备会开出
 * 两三个页签、各停在不同的子页（2026-11-18 用户反馈）。</p>
 *
 * <p>用配置名而不是会话 id 当身份还有一个好处：设备停止再启动会换一个会话 id，
 * 页签不会因此变成"另一台设备"。找不到配置（或这条会话没有 config）时退回会话 id。</p>
 */
export function deviceTabKey(
  routeKey: string,
  sessions: readonly SessionKeyLike[],
  configs: readonly ConfigKeyLike[],
): string {
  if (routeKey === '') {
    return routeKey;
  }
  /* 地址里就是会话 id */
  const byId = sessions.find((session) => session.id === routeKey);
  if (byId !== undefined) {
    return (byId.config ?? '') !== '' ? (byId.config as string) : byId.id;
  }
  /* 地址里是配置文件名（带不带 .json 都算） */
  const wanted = routeKey.toLowerCase().endsWith('.json')
    ? [routeKey]
    : [routeKey, `${routeKey}.json`];
  const byConfig = sessions.find((session) => wanted.includes(session.config ?? ''));
  if (byConfig !== undefined && (byConfig.config ?? '') !== '') {
    return byConfig.config as string;
  }
  const config = configs.find((entry) => wanted.includes(entry.name));
  return config === undefined ? routeKey : config.name;
}

/**
 * 页签上的设备名：设备已启动就用会话里的设备名，没启动就用配置名去掉 .json。
 *
 * <p>同一个标签在设备启动前后必须是同一个名字，否则看起来像换了一台设备。</p>
 */
export function deviceTabLabel(
  routeKey: string,
  sessions: readonly SessionKeyLike[],
  configs: readonly ConfigKeyLike[],
): string {
  const key = deviceTabKey(routeKey, sessions, configs);
  const session = sessions.find((item) => item.id === key || (item.config ?? '') === key);
  const name = session?.device ?? '';
  return name !== '' ? name : key.replace(/\.json$/i, '');
}

/** 页签身份：设备走 {@link deviceTabKey}，其余仍按对象/页面。 */export function tabIdForRoute(
  route: Route,
  sessions: readonly SessionKeyLike[] = [],
  configs: readonly ConfigKeyLike[] = [],
): string {
  switch (route.name) {
    case 'device':
      return `device:${deviceTabKey(route.sessionId, sessions, configs)}`;
    case 'host':
      return `host:${route.hostId}`;
    case 'system':
      return `system:${route.page}`;
    default:
      return route.name;
  }
}

/**
 * 把已经存下来的页签归一：旧数据里可能有同一台设备的两个标签
 * （配置文件名一份、会话 id 一份），合并成一个。
 *
 * <p>合并时保留第一次出现的位置；内容取最后一次停留（用户最近去过的子页）。
 * 当前正在看的那一个标签直接以路由为准，免得合并之后点它跳到别的页。</p>
 *
 * <p>没有需要改的地方就返回原数组：这个函数接在轮询数据后面，
 * 每次会话刷新都跑，返回新数组会让顶栏跟着重渲染、也会反复写 localStorage。</p>
 */
export function canonicalizeDeviceTabs(
  tabs: readonly AppTab[],
  sessions: readonly SessionKeyLike[],
  configs: readonly ConfigKeyLike[],
  activeId?: string,
  activeHash?: string,
): AppTab[] {
  let changed = false;
  const out: AppTab[] = [];
  const at = new Map<string, number>();
  for (const tab of tabs) {
    const id = tab.id.startsWith('device:')
      ? `device:${deviceTabKey(tab.id.slice('device:'.length), sessions, configs)}`
      : tab.id;
    if (id !== tab.id) {
      changed = true;
    }
    const hash = id === activeId && activeHash !== undefined ? activeHash : tab.hash;
    const index = at.get(id);
    if (index === undefined) {
      at.set(id, out.length);
      out.push(hash === tab.hash && id === tab.id ? tab : { ...tab, id, hash });
      continue;
    }
    changed = true;
    const kept = out[index];
    /* 当前标签的停留位置以路由为准：后面这个旧标签不能把它拽回别的子页 */
    if (kept.id === activeId) {
      continue;
    }
    out[index] = { ...kept, hash, label: tab.label };
  }
  return changed ? out : (tabs as AppTab[]);
}

/*
 * 标签按"对象"而不是按子页划分：设备在两个区、十几个子页之间来回切，
 * 只更新同一个标签的 hash，切回时能回到刚才那一页，又不会开出一排标签。
 * 设备那一份身份要按配置归一，见 {@link deviceTabKey}。
 */

/** 新页面追加标签、老页面更新停留位置；超出上限时淘汰最久未用的非当前标签。 */
export function upsertTab(tabs: readonly AppTab[], tab: AppTab, limit = MAX_TABS): AppTab[] {
  const existing = tabs.find((item) => item.id === tab.id);
  if (existing !== undefined && existing.hash === tab.hash && existing.label === tab.label) {
    return tabs as AppTab[];
  }
  const next = existing === undefined
    ? [...tabs, tab]
    : tabs.map((item) => (
      item.id === tab.id ? { ...item, hash: tab.hash, label: tab.label } : item
    ));
  let trimmed = next;
  while (trimmed.length > limit) {
    const retire = trimmed.find((item) => item.id !== tab.id && item.id !== 'workbench');
    if (retire === undefined) {
      break;
    }
    trimmed = trimmed.filter((item) => item !== retire);
  }
  return trimmed;
}

/** 关掉一个标签后，优先切到它右边的邻居，其次是左边，最后回工作台。 */
export function nextTabAfterClose(tabs: readonly AppTab[], closedId: string): AppTab | null {
  const index = tabs.findIndex((item) => item.id === closedId);
  if (index < 0) {
    return tabs[0] ?? null;
  }
  const rest = tabs.filter((item) => item.id !== closedId);
  if (rest.length === 0) {
    return null;
  }
  return rest[Math.min(index, rest.length - 1)];
}
