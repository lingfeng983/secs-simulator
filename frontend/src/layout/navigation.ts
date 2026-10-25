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
        description: '所有设备的总览与会话管理:KPI、设备卡墙、开设备/重载/上报。',
        icon: 'overview',
        route: { name: 'workbench' },
      },
    ],
  },
  {
    id: 'manage',
    label: '管理',
    belowDevices: true,
    items: [
      {
        id: 'audit',
        label: '审计',
        description: '谁在什么时候动了什么：操作留痕。',
        icon: 'audit',
        route: { name: 'system', page: 'audit' },
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
        || (route.name === 'system' && item.id === route.page)
      ) {
        return { group: group.label, label: item.label, description: item.description };
      }
    }
  }
  return { group: '', label: '', description: '' };
}
