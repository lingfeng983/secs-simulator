import { useEffect, useMemo, useRef, useState } from 'react';
import { groupCommands, matchCommands, type CommandItem } from '../lib/command';
import { Overlay } from './Overlay';

export interface CommandPaletteProps {
  open: boolean;
  items: readonly CommandItem[];
  onClose: () => void;
  /** 打开时预置的查询词（例如按了某个快捷键直接搜某类）。 */
  initialQuery?: string;
  placeholder?: string;
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
  const grouped = useMemo(() => groupCommands(matched), [matched]);

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

  const runAt = (index: number) => {
    const item = matched[index];
    if (item === undefined) {
      return;
    }
    onClose();
    item.run();
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive((previous) => (matched.length === 0 ? 0 : (previous + 1) % matched.length));
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((previous) => (matched.length === 0 ? 0 : (previous - 1 + matched.length) % matched.length));
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      runAt(active);
    }
  };

  /* 分组后要算出"第几条"的全局下标，键盘上下键才对得上 */
  let flatIndex = -1;

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
          {matched.length === 0 && <p className="palette-empty">没有匹配的命令</p>}
          {grouped.map((group) => (
            <div key={group.group} className="palette-group">
              <div className="palette-group-title">{group.group}</div>
              {group.items.map((item) => {
                flatIndex += 1;
                const index = flatIndex;
                return (
                  <button
                    key={item.id}
                    type="button"
                    className={`command-item${index === active ? ' active' : ''}`}
                    onMouseEnter={() => setActive(index)}
                    onClick={() => runAt(index)}
                  >
                    <span className="command-label">{item.label}</span>
                    {item.hint !== undefined && <span className="command-hint">{item.hint}</span>}
                  </button>
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
