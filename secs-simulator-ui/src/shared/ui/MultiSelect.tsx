import { useEffect, useRef, useState } from 'react';

/** 下拉多选里的一项：name 是明细与摘要都用的人话，meta 是编号（等宽显示）。 */
export interface MultiSelectOption<T> {
  value: T;
  name: string;
  meta?: string;
}

export interface MultiSelectProps<T extends string | number> {
  /** 按钮前缀，例如「参数」。 */
  label: string;
  options: readonly MultiSelectOption<T>[];
  value: readonly T[];
  onChange: (next: T[]) => void;
  /** 摘要里最多直接写几个名字，超出的折成「等 N 个」。 */
  summaryLimit?: number;
  /** 列表为空时的提示。 */
  emptyText?: string;
  /** 无障碍名：同一页可能有多个下拉时用来区分。 */
  ariaLabel?: string;
}

/**
 * 下拉多选（勾选式）。
 *
 * <p>用自定义浮层而不是 {@code <select multiple>}：原生多选在浏览器里是一块
 * 常开的列表框，占地方且看不出"已经选了几个"。这里平时只占一个按钮，
 * 展开才列选项——参数很多时页面也不会被撑成一片勾选框。</p>
 *
 * <p>浮层贴着按钮向左展开，点外面或按 Esc 收起；选择结果由调用方持有。</p>
 */
export function MultiSelect<T extends string | number>({
  label,
  options,
  value,
  onChange,
  summaryLimit = 3,
  emptyText = '没有可选项。',
  ariaLabel,
}: MultiSelectProps<T>): JSX.Element {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const onPointerDown = (event: MouseEvent) => {
      const root = rootRef.current;
      if (root !== null && event.target instanceof Node && !root.contains(event.target)) {
        setOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const chosen = options.filter((option) => value.includes(option.value));
  const names = chosen.map((option) => option.name);
  const summary = names.length === 0
    ? '未选'
    : names.length <= summaryLimit
      ? names.join('、')
      : `${names.slice(0, summaryLimit).join('、')} 等 ${names.length} 个`;

  const toggle = (option: MultiSelectOption<T>) => {
    const next = value.includes(option.value)
      ? value.filter((item) => item !== option.value)
      : [...value, option.value];
    onChange(next);
  };

  return (
    <div className="multi-select" ref={rootRef}>
      <button
        type="button"
        className="multi-select-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel ?? `${label}多选`}
        title={chosen.length === 0 ? `选择要展示的${label}` : `已选：${names.join('、')}`}
        onClick={() => setOpen((previous) => !previous)}
      >
        <span className="multi-select-label">{label}：</span>
        <span className="multi-select-summary">{summary}</span>
        <span className="multi-select-caret" aria-hidden="true">▾</span>
      </button>

      {open && (
        <div className="multi-select-menu" role="listbox" aria-multiselectable="true">
          {options.length === 0 ? (
            <p className="hint multi-select-empty">{emptyText}</p>
          ) : (
            <div className="multi-select-options">
              {options.map((option) => {
                const checked = value.includes(option.value);
                return (
                  <label key={String(option.value)} className="check-inline multi-select-option">
                    <input type="checkbox" checked={checked} onChange={() => toggle(option)} />
                    {option.meta !== undefined && <span className="mono">{option.meta}</span>}
                    <span>{option.name}</span>
                  </label>
                );
              })}
            </div>
          )}
          <div className="multi-select-actions">
            <button
              type="button"
              disabled={options.length === 0}
              onClick={() => onChange(options.map((option) => option.value))}
            >
              全选
            </button>
            <button type="button" disabled={value.length === 0} onClick={() => onChange([])}>
              清空
            </button>
            <span className="spacer" />
            <span className="hint-inline">已选 {chosen.length} / {options.length}</span>
          </div>
        </div>
      )}
    </div>
  );
}
