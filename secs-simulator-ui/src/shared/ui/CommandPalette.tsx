import { useEffect, useMemo, useRef, useState } from 'react';
import { matchCommands, type CommandItem } from '../lib/command';
import { Overlay } from './Overlay';

export interface CommandPaletteProps {
  open: boolean;
  items: readonly CommandItem[];
  onClose: () => void;
  /** 打开时预置的查询词（例如按了某个快捷键直接搜某类）。 */
  initialQuery?: string;
  placeholder?: string;
}

/** 一行结果：一条主命令，外加同一台设备收进来的两个分支入口。 */
export interface PaletteRow {
  key: string;
  group: string;
  primary: CommandItem;
  branches: Array<{ label: string; item: CommandItem }>;
}

const DEVICE_PRIMARY = /^device:([^:]+)$/;
const DEVICE_BRANCH = /^device-page:([^:]+):(config|runtime)$/;

/**
 * 把「同一台设备三行」压成一行 + 两个分支按钮。
 *
 * <p>App 为每台设备生成「打开设备 / 配置区 / 运行区」三条命令，过去在面板里
 * 占三行、文案还高度重复。这里按 id 归拢：主行保留「打开设备」，两个区变成
 * 行内小按钮；键盘上下键走合并后的行，点分支按各自的目标跳转。纯函数，可单测。</p>
 */
export function buildPaletteRows(matched: readonly CommandItem[]): PaletteRow[] {
  /* 主命令可能排在分支后面（匹配按分数排序），先把有主命令的设备号记下来：
     这类分支统一跳过单列，等轮到主命令时再挂上去。 */
  const primaryIds = new Set<string>();
  for (const item of matched) {
    const found = DEVICE_PRIMARY.exec(item.id);
    if (found !== null) {
      primaryIds.add(found[1]);
    }
  }

  const branches = new Map<string, Map<'config' | 'runtime', CommandItem>>();
  for (const item of matched) {
    const found = DEVICE_BRANCH.exec(item.id);
    if (found !== null) {
      const bucket = branches.get(found[1]) ?? new Map<'config' | 'runtime', CommandItem>();
      bucket.set(found[2] === 'config' ? 'config' : 'runtime', item);
      branches.set(found[1], bucket);
    }
  }

  const rows: PaletteRow[] = [];
  for (const item of matched) {
    const branch = DEVICE_BRANCH.exec(item.id);
    if (branch !== null && primaryIds.has(branch[1])) {
      continue;
    }
    const device = DEVICE_PRIMARY.exec(item.id);
    const bucket = device === null ? undefined : branches.get(device[1]);
    if (bucket !== undefined) {
      const merged: PaletteRow['branches'] = [];
      for (const [zone, label] of [['config', '配置区'], ['runtime', '运行区']] as const) {
        const branch = bucket.get(zone);
        if (branch !== undefined) {
          merged.push({ label, item: branch });
        }
      }
      rows.push({ key: item.id, group: item.group, primary: item, branches: merged });
      continue;
    }
    rows.push({ key: item.id, group: item.group, primary: item, branches: [] });
  }
  return rows;
}

/**
 * 命令面板（Ctrl+K）。
 *
 * <p>它解决的是"面板变多之后的迷路问题"：
 * 十个面板、几十个动作，靠点菜单找会越来越慢。
 * 一个输入框就能跳到任意面板或动作。</p>
 */
export function CommandPalette({
  open,
  items,
  onClose,
  initialQuery = '',
  placeholder = '搜索面板、动作、CEID、SVID…',
}: CommandPaletteProps): JSX.Element | null {
  const [query, setQuery] = useState(initialQuery);
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  const matched = useMemo(() => matchCommands(items, query), [items, query]);
  const rows = useMemo(() => buildPaletteRows(matched), [matched]);
  /* 分组只管显示；active 用扁平下标，上下键才不会在组间跳号 */
  const groups = useMemo(() => {
    const result: Array<{ group: string; rows: PaletteRow[]; start: number }> = [];
    rows.forEach((row, index) => {
      const last = result[result.length - 1];
      if (last !== undefined && last.group === row.group) {
        last.rows.push(row);
      } else {
        result.push({ group: row.group, rows: [row], start: index });
      }
    });
    return result;
  }, [rows]);

  /* 打开时清空并聚焦：上一次搜的东西不该留着 */
  useEffect(() => {
    if (!open) {
      return;
    }
    setQuery(initialQuery);
    setActive(0);
    window.setTimeout(() => inputRef.current?.focus(), 0);
  }, [open, initialQuery]);

  /* 查询变了以后选中项要回到第一条，否则会"跳"到不相干的条目 */
  useEffect(() => {
    setActive(0);
  }, [query]);

  /* 选中项滚动进视野 */
  useEffect(() => {
    const list = listRef.current;
    if (list === null) {
      return;
    }
    const element = list.querySelector('.command-item.active');
    if (element instanceof HTMLElement) {
      element.scrollIntoView({ block: 'nearest' });
    }
  }, [active]);

  if (!open) {
    return null;
  }

  const runItem = (item: CommandItem) => {
    onClose();
    item.run();
  };

  const runAt = (index: number) => {
    const row = rows[index];
    if (row !== undefined) {
      runItem(row.primary);
    }
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive((previous) => (rows.length === 0 ? 0 : (previous + 1) % rows.length));
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((previous) => (rows.length === 0 ? 0 : (previous - 1 + rows.length) % rows.length));
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      runAt(active);
    }
  };

  return (
    <Overlay onClose={onClose}>
      <div className="palette" role="dialog" aria-modal="true" aria-label="命令面板">
        <input
          ref={inputRef}
          className="palette-input"
          type="text"
          value={query}
          placeholder={placeholder}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={onKeyDown}
          aria-label="命令面板"
        />
        <div className="palette-list" ref={listRef}>
          {rows.length === 0 && <p className="palette-empty">没有匹配的命令</p>}
          {groups.map((group) => (
            <div key={group.group} className="palette-group">
              <div className="palette-group-title">{group.group}</div>
              {group.rows.map((row, offset) => {
                const index = group.start + offset;
                return (
                  <div
                    key={row.key}
                    className={`command-item${index === active ? ' active' : ''}`}
                    onMouseEnter={() => setActive(index)}
                  >
                    <button type="button" className="command-main" onClick={() => runItem(row.primary)}>
                      <span className="command-type">{row.group}</span>
                      <span className="command-label">{row.primary.label}</span>
                      {row.primary.hint !== undefined && <span className="command-hint">{row.primary.hint}</span>}
                    </button>
                    {row.branches.map((branch) => (
                      <button
                        key={branch.label}
                        type="button"
                        className="command-branch"
                        title={`${row.primary.label} · ${branch.label}`}
                        onClick={() => runItem(branch.item)}
                      >
                        {branch.label}
                      </button>
                    ))}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
        <div className="palette-foot">
          <span>↑↓ 选择</span>
          <span>Enter 执行</span>
          <span>Esc 关闭</span>
        </div>
      </div>
    </Overlay>
  );
}
