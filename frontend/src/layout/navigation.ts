import type { IconName } from '../ui/Icon';
import type { Route } from '../router';

/**
 * 导航结构（单一事实来源）。
 *
 * <p>拆分后的信息架构:静态入口每个只代表一个页面、一个核心能力;
 * 「设备」组是动态的（来自会话列表）,由 Sidebar 渲染在上下两组之间。
 * 设备内部的子页也不进这份表——它们是设备页的状态。</p>
 */

export interface NavItemDef {
  /** 面板 id（命令面板/快捷键引用）。 */
  id: string;
  /** 菜单上的短名称。 */
  label: string;
  /** 顶栏标题下的一句话说明。 */
  description: string;
  icon: IconName;
  /** 该入口对应的路由。 */
  route: Route;
}

export interface NavGroupDef {
  id: string;
  label: string;
  /** true = 渲染在「设备」动态组下方（管理类入口）。 */
  belowDevices?: boolean;
  items: readonly NavItemDef[];
}

export const NAV_GROUPS: readonly NavGroupDef[] = [
  {
    id: 'workbench',
    label: '总览',
    items: [
      {
        id: 'workbench',
        label: '工作台',
        description: '所有设备的总览:KPI 与设备卡墙,点卡片进设备页。',
        icon: 'overview',
        route: { name: 'workbench' },
      },
    ],
  },
  {
    id: 'live',
    label: '实时',
    items: [
      {
        id: 'events',
        label: '事件流',
        description: '全部设备的实时事件(S6F11),按设备/CEID 过滤。',
        icon: 'events',
        route: { name: 'view', page: 'events' },
      },
      {
        id: 'messages',
        label: '报文',
        description: 'HSMS 报文流:搜索、查看 SML、导出 JSONL。',
        icon: 'messages',
        route: { name: 'view', page: 'messages' },
      },
    ],
  },
  {
    id: 'manage',
    label: '管理',
    belowDevices: true,
    items: [
      {
        id: 'sessions',
        label: '会话管理',
        description: '开设备/关设备、批量操作、手动上报 CEID。',
        icon: 'sessions',
        route: { name: 'view', page: 'sessions' },
      },
      {
        id: 'audit',
        label: '审计',
        description: '谁在什么时候动了什么：操作留痕。',
        icon: 'audit',
        route: { name: 'system', page: 'audit' },
      },
      {
        id: 'console',
        label: '事件流日志',
        description: 'SSE 事件通道的运行日志与连接状态。',
        icon: 'console',
        route: { name: 'system', page: 'console' },
      },
    ],
  },
];

/** 所有静态入口的平铺清单。 */
export const NAV_ITEMS: readonly NavItemDef[] = NAV_GROUPS.flatMap((group) => group.items);

/** 路由 → 顶栏标题（设备页在 Sidebar 里动态列出,标题在 App 单独拼）。 */
export function routeTitle(route: Route): { group: string; label: string; description: string } {
  for (const group of NAV_GROUPS) {
    for (const item of group.items) {
      if (
        (route.name === 'workbench' && item.id === 'workbench')
        || (route.name === 'view' && item.id === route.page)
        || (route.name === 'system' && item.id === route.page)
      ) {
        return { group: group.label, label: item.label, description: item.description };
      }
    }
  }
  return { group: '', label: '', description: '' };
}
