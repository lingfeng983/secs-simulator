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

/**
 * 标签按"对象"而不是按子页划分：设备在两个区、十几个子页之间来回切，
 * 只更新同一个标签的 hash，切回时能回到刚才那一页，又不会开出一排标签。
 */
export function tabIdForRoute(route: Route): string {
  switch (route.name) {
    case 'device':
      return `device:${route.sessionId}`;
    case 'host':
      return `host:${route.hostId}`;
    case 'system':
      return `system:${route.page}`;
    default:
      return route.name;
  }
}

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
