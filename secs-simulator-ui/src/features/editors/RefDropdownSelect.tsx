import { useEffect, useRef, useState } from 'react';

/** 引用多选（下拉式）：触发器显示已选摘要，展开后逐项勾选，支持全选。 */
export interface RefChoice {
  /** 写进文档的引用值。 */
  value: string;
  /** 展示名；缺省用 value。 */
  label?: string;
}

export function RefDropdownSelect({ choices, values, onChange, emptyHint, label, align = 'left', placeholder = '未选择' }: {
  choices: ReadonlyArray<RefChoice>;
  values: ReadonlyArray<string>;
  onChange: (next: string[]) => void;
  /** 一个候选都没有时的提示文案。 */
  emptyHint: string;
  /** 这一列叫什么（读屏器与悬浮提示用）。 */
  label: string;
  /** 面板对齐方向：靠页面右缘的列用 right，避免被裁掉。 */
  align?: 'left' | 'right';
  /** 一个都没选时触发器上的占位文案。 */
  placeholder?: string;
}): JSX.Element {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const allRef = useRef<HTMLInputElement>(null);

  const known = new Set(choices.map((choice) => choice.value));
  const missing = values.filter((value) => !known.has(value));
  const selected = choices.filter((choice) => values.includes(choice.value)).length;
  const allChecked = choices.length > 0 && selected === choices.length;
  const partial = selected > 0 && !allChecked;

  /* 半选态只能用 DOM 属性表达；面板每次展开都是新挂载的 input，要重设 */
  useEffect(() => {
    if (allRef.current !== null) {
      allRef.current.indeterminate = partial;
    }
  }, [partial, open]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const onDown = (event: MouseEvent) => {
      if (rootRef.current !== null && !rootRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const toggle = (value: string, checked: boolean) => {
    onChange(checked ? [...values, value] : values.filter((item) => item !== value));
  };

  /* 全选保持已选项的先后，新选的按候选顺序排在后面；取消全选清空整列 */
  const toggleAll = (checked: boolean) => {
    onChange(checked
      ? [...values, ...choices.filter((choice) => !values.includes(choice.value)).map((choice) => choice.value)]
      : []);
  };

  const labelOf = (value: string) => choices.find((choice) => choice.value === value)?.label ?? value;
  const summary = values.map(labelOf).join('、');

  return (
    <div className={`ref-dropdown${align === 'right' ? ' ref-dropdown--right' : ''}`} ref={rootRef}>
      <button
        type="button"
        ref={triggerRef}
        className="ref-dropdown-trigger"
        aria-expanded={open}
        aria-label={label}
        title={summary === '' ? `${label}：${placeholder}` : `${label}：${summary}`}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className={`ref-dropdown-summary${values.length === 0 ? ' is-empty' : ''}`}>
          {values.length === 0 ? placeholder : summary}
        </span>
        {values.length > 0 && <span className="ref-dropdown-count">{values.length}</span>}
      </button>
      {open && (
        <div className="ref-dropdown-panel" role="group" aria-label={label}>
          {choices.length === 0 && missing.length === 0 ? (
            <span className="hint-inline ref-dropdown-empty">{emptyHint}</span>
          ) : (
            <>
              {choices.length > 0 && (
                <label className="ref-dropdown-item ref-dropdown-all">
                  <input
                    ref={allRef}
                    type="checkbox"
                    checked={allChecked}
                    onChange={(event) => toggleAll(event.target.checked)}
                  />
                  <span>全选</span>
                  <span className="ref-dropdown-item-count">{selected}/{choices.length}</span>
                </label>
              )}
              <div className="ref-dropdown-list">
                {choices.map((choice) => (
                  <label className="ref-dropdown-item" key={choice.value}>
                    <input
                      type="checkbox"
                      checked={values.includes(choice.value)}
                      onChange={(event) => toggle(choice.value, event.target.checked)}
                    />
                    <span className="mono">{choice.label ?? choice.value}</span>
                  </label>
                ))}
                {/* 文档里已引用、但候选里没有的项（比如引用刚被删掉）保底展示，避免勾不掉 */}
                {missing.map((value) => (
                  <label
                    className="ref-dropdown-item is-missing"
                    key={`missing-${value}`}
                    title="引用的目标不存在，取消勾选即移除"
                  >
                    <input
                      type="checkbox"
                      checked
                      onChange={(event) => toggle(value, event.target.checked)}
                    />
                    <span className="mono">{value}（缺失）</span>
                  </label>
                ))}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}