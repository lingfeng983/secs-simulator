/**
 * 命令面板（Ctrl+K）的匹配逻辑。
 *
 * <p>纯函数，不碰 DOM：匹配规则是这个面板好不好用的全部关键，
 * 必须能单独测。</p>
 */

/** 命令面板里的一条命令。 */
export interface CommandItem {
  /** 稳定 id，React key 用。 */
  id: string;
  /** 主标题。 */
  label: string;
  /** 分组名（"跳转""操作""数据"…）。 */
  group: string;
  /** 右侧提示，比如快捷键或所属会话。 */
  hint?: string;
  /** 额外关键词，搜得到但不会显示。 */
  keywords?: string[];
  /** 选中后执行。 */
  run: () => void;
}

/**
 * 给一条命令打分。
 *
 * <p>分数越高越靠前。返回 -1 表示不匹配。</p>
 *
 * <p>打分顺序刻意这样排：完全相同 > 前缀 > 词首命中 > 包含 > 关键词命中。
 * 用户打 "cur" 期望先看到"曲线预览"，而不是正文里恰好含 "cur" 的条目。</p>
 */
export function scoreCommand(item: CommandItem, query: string): number {
  const needle = query.trim().toLowerCase();
  if (needle === '') {
    return 1;
  }

  const label = item.label.toLowerCase();
  if (label === needle) {
    return 100;
  }
  if (label.startsWith(needle)) {
    return 80;
  }
  /* 词首：把标签按空格切开，"曲线预览" 能被 "预览" 命中 */
  const words = label.split(/[\s·/]+/);
  if (words.some((word) => word.startsWith(needle))) {
    return 60;
  }
  if (label.includes(needle)) {
    return 40;
  }
  if (item.keywords?.some((keyword) => keyword.toLowerCase().includes(needle))) {
    return 20;
  }
  if (item.group.toLowerCase().includes(needle)) {
    return 10;
  }
  return -1;
}

/**
 * 按查询过滤并排序命令。
 *
 * <p>上限 {@link MAX_COMMANDS} 是刻意的：命令面板是"敲两下就选"的地方，
 * 列出上百条反而没法用。</p>
 */
export const MAX_COMMANDS = 40;

export function matchCommands(items: readonly CommandItem[], query: string): CommandItem[] {
  const scored: Array<{ item: CommandItem; score: number; index: number }> = [];
  items.forEach((item, index) => {
    const score = scoreCommand(item, query);
    if (score >= 0) {
      scored.push({ item, score, index });
    }
  });
  scored.sort((left, right) => right.score - left.score || left.index - right.index);
  return scored.slice(0, MAX_COMMANDS).map((entry) => entry.item);
}

/**
 * 把命令按分组整理，面板按组显示。
 *
 * <p>保持传入顺序：匹配结果已经是排好序的，
 * 分组时不能把组内顺序打乱。</p>
 */
export function groupCommands(items: readonly CommandItem[]): Array<{ group: string; items: CommandItem[] }> {
  const groups: Array<{ group: string; items: CommandItem[] }> = [];
  const index = new Map<string, number>();
  for (const item of items) {
    const existing = index.get(item.group);
    if (existing === undefined) {
      index.set(item.group, groups.length);
      groups.push({ group: item.group, items: [item] });
    } else {
      groups[existing].items.push(item);
    }
  }
  return groups;
}
