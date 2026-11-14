import type { IconName } from '../../shared/ui/Icon';
import type { Route } from '../router';

/**
 * 导航结构（单一事实来源，3.0 侧栏三组 · 第三轮修订 11）：
 *
 * <ol>
 *   <li>设备清单——工作台入口 + 动态设备树（Sidebar 渲染在组内）；</li>
 *   <li>主机清单——主机模拟列表入口 + 动态主机实例树；</li>
 *   <li>独立工具——主机模板 / 设备模板 / 审计。</li>
 * </ol>
 */

export interface NavItemDef {
  /** 面板 id（命令面板/快捷键引用）。 */
  id: string;
  /** 菜单上的短名称。 */
  label: string;
  /** 顶栏单行路径里的短说明（不再单独占一行）。 */
  description: string;
  icon: IconName;
  /** 该入口对应的路由。 */
  route: Route;
}

export interface NavGroupDef {
  id: string;
  label: string;
  /** 该组挂哪种动态实例树（Sidebar 在组内渲染）。 */
  dynamic?: 'devices' | 'hosts';
  items: readonly NavItemDef[];
}

export const NAV_GROUPS: readonly NavGroupDef[] = [
  {
    id: 'devices',
    label: '设备',
    dynamic: 'devices',
    items: [
      {
        id: 'workbench',
        label: '设备清单',
        description: '总览与会话管理',
        icon: 'overview',
        route: { name: 'workbench' },
      },
    ],
  },
  {
    id: 'hosts',
    label: '主机清单',
    dynamic: 'hosts',
    items: [
      {
        id: 'hosts',
        label: '主机模拟',
        description: '多主机实例管理',
        icon: 'hostsim',
        route: { name: 'hosts' },
      },
    ],
  },
  {
    id: 'tools',
    label: '独立工具',
    items: [
      {
        id: 'host-templates',
        label: '主机模板',
        description: '目标地址 / 端口 / Device ID 预设',
        icon: 'hostsim',
        route: { name: 'host-templates' },
      },
      {
        id: 'templates',
        label: '设备模板',
        description: '设备模板增删改查',
        icon: 'config',
        route: { name: 'templates' },
      },
      {
        id: 'audit',
        label: '审计',
        description: '操作留痕',
        icon: 'audit',
        route: { name: 'system', page: 'audit' },
      },
    ],
  },
];

/** 所有静态入口的平铺清单。 */
export const NAV_ITEMS: readonly NavItemDef[] = NAV_GROUPS.flatMap((group) => group.items);

/** 路由 → 顶栏标题（设备/主机详情页在 Sidebar 里动态列出,标题在 App 单独拼）。 */
export function routeTitle(route: Route): { group: string; label: string; description: string } {
  for (const group of NAV_GROUPS) {
    for (const item of group.items) {
      if (
        (route.name === 'workbench' && item.id === 'workbench')
        || (route.name === 'hosts' && item.id === 'hosts')
        || (route.name === 'host-templates' && item.id === 'host-templates')
        || (route.name === 'templates' && item.id === 'templates')
        || (route.name === 'system' && item.id === route.page)
      ) {
        /* group 保留在数据结构里供命令面板/测试用，顶栏不再展示它 */
        return { group: '', label: item.label, description: item.description };
      }
    }
  }
  return { group: '', label: '', description: '' };
}
