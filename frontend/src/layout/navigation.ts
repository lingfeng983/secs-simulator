import type { IconName } from '../ui/Icon';
import type { Route } from '../router';

/**
 * 导航结构（单一事实来源）。
 *
 * <p>v6 两层信息架构:静态入口只有「工作台」与「系统」两组;
 * 「设备」组是动态的（来自会话列表）,由 Sidebar 直接渲染。
 * 设备页内部的 监控/配置/工具 Tab 也不进这份表——它们是设备页的状态。</p>
 */

export interface NavItemDef {
  /** 面板 id。 */
  id: string;
  /** 菜单上的短名称。 */
  label: string;
  /** 顶栏标题下的一句话说明。 */
  description: string;
  icon: IconName;
}

export interface NavGroupDef {
  id: string;
  label: string;
  items: readonly NavItemDef[];
}

export const NAV_GROUPS: readonly NavGroupDef[] = [
  {
    id: 'workbench',
    label: '工作台',
    items: [
      {
        id: 'workbench',
        label: '工作台',
        description: '所有设备的总监控:卡片墙、实时事件流、报文与会话管理。',
        icon: 'overview',
      },
    ],
  },
  {
    id: 'system',
    label: '系统',
    items: [
      { id: 'audit', label: '审计', description: '谁在什么时候动了什么：操作留痕。', icon: 'audit' },
      { id: 'console', label: '事件流日志', description: 'SSE 事件通道的运行日志与连接状态。', icon: 'console' },
    ],
  },
];

/** 所有静态入口的平铺清单。 */
export const NAV_ITEMS: readonly NavItemDef[] = NAV_GROUPS.flatMap((group) => group.items);

/** 路由 → 顶栏标题（设备页在 Sidebar 里动态列出,标题在 App 单独拼）。 */
export function routeTitle(route: Route): { group: string; label: string; description: string } {
  if (route.name === 'workbench') {
    const item = NAV_ITEMS.find((entry) => entry.id === 'workbench');
    return { group: '工作台', label: item?.label ?? '工作台', description: item?.description ?? '' };
  }
  if (route.name === 'system') {
    const id = route.page === 'audit' ? 'audit' : 'console';
    const item = NAV_ITEMS.find((entry) => entry.id === id);
    return { group: '系统', label: item?.label ?? id, description: item?.description ?? '' };
  }
  return {
    group: '设备',
    label: route.sessionId,
    description: route.tab === 'monitor'
      ? '该设备的曲线、事件、报警与目录。'
      : route.tab === 'config'
        ? '该设备的配置:分段编辑、每 SVID 值来源与曲线预览。'
        : '该设备的工具:发送、压测、主机模拟与 S1F3 采集。',
  };
}
