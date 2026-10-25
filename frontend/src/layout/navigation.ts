import type { IconName } from '../ui/Icon';

/**
 * 导航结构（单一事实来源）。
 *
 * <p>侧边栏、顶栏标题、命令面板、数字快捷键都从这份表派生，
 * 加一个面板只需要在这里登记一行，不会出现
 * 「菜单里有、快捷键里没有」这种各说各话的情况。</p>
 */

export interface NavItemDef {
  /** 面板 id，同时是路由状态与 `#panel-<id>` 的锚。 */
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
    id: 'overview',
    label: '总览',
    items: [
      { id: 'overview', label: '工作台', description: '全局状态一览：会话、报警、报文与快捷操作。', icon: 'overview' },
    ],
  },
  {
    id: 'monitor',
    label: '监控',
    items: [
      { id: 'sessions', label: '会话', description: '设备实例的状态、计数与生命周期操作。', icon: 'sessions' },
      { id: 'messages', label: '报文', description: '实时收发的 SECS-II 报文流，支持过滤与导出。', icon: 'messages' },
      { id: 'console', label: '事件流', description: 'SSE 事件通道的运行日志与连接状态。', icon: 'console' },
    ],
  },
  {
    id: 'equipment',
    label: '设备',
    items: [
      { id: 'catalog', label: '设备目录', description: '当前会话的 CEID / RPTID / SVID 清册。', icon: 'catalog' },
      { id: 'alarms', label: '报警', description: 'S5 报警的置位、清除与终端消息。', icon: 'alarms' },
      { id: 'events', label: '事件编排', description: 'S6F11 事件计划的启停与触发节奏。', icon: 'events' },
      { id: 'hostsim', label: '主机模拟', description: '以主机角色连上设备，发 SML 收回复。', icon: 'hostsim' },
    ],
  },
  {
    id: 'data',
    label: '数据',
    items: [
      { id: 'preview', label: '曲线预览', description: '按时间窗回放 SVID 的 min/max 包络与均值线。', icon: 'preview' },
      { id: 'audit', label: '审计', description: '谁在什么时候动了什么：操作留痕。', icon: 'audit' },
    ],
  },
  {
    id: 'system',
    label: '系统',
    items: [
      { id: 'send', label: '手动发送', description: '直接向设备发一条 SML，看回复与耗时。', icon: 'send' },
      { id: 'config', label: '配置编辑器', description: '设备配置的表单 / JSON 双视图编辑与校验。', icon: 'config' },
    ],
  },
];

/** 所有面板的平铺清单（导航顺序）。 */
export const NAV_ITEMS: readonly NavItemDef[] = NAV_GROUPS.flatMap((group) => group.items);

/** 默认落地面板：总览工作台。 */
export const DEFAULT_TAB = 'overview';

/** 按 id 找面板定义；找不到返回 undefined。 */
export function findNavItem(id: string): NavItemDef | undefined {
  return NAV_ITEMS.find((item) => item.id === id);
}

/** 面板所属分组名（面包屑用）。 */
export function groupOf(id: string): NavGroupDef | undefined {
  return NAV_GROUPS.find((group) => group.items.some((item) => item.id === id));
}
