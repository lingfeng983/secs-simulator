import { useState } from 'react';

/**
 * 预设下拉:分组列出"更多预设",选中即填入并复位到占位项。
 *
 * <p>预设太多会把面板工具栏挤成一排散按钮——常用的留在按钮上,
 * 其余收进这里。受控为始终回到占位项,选过的内容落在编辑框里。</p>
 */
export function PresetSelect({
  groups,
  onPick,
  disabled = false,
  placeholder = '更多预设…',
  ariaLabel = '更多预设',
}: {
  groups: ReadonlyArray<{
    label: string;
    items: ReadonlyArray<{ label: string; value: string }>;
  }>;
  onPick: (value: string) => void;
  disabled?: boolean;
  placeholder?: string;
  ariaLabel?: string;
}): JSX.Element {
  const [picked, setPicked] = useState('');

  return (
    <select
      value={picked}
      disabled={disabled}
      aria-label={ariaLabel}
      onChange={(event) => {
        const value = event.target.value;
        setPicked('');
        if (value !== '') {
          onPick(value);
        }
      }}
    >
      <option value="">{placeholder}</option>
      {groups.map((group) => (
        <optgroup key={group.label} label={group.label}>
          {group.items.map((item) => (
            <option key={item.label} value={item.value}>
              {item.label}
            </option>
          ))}
        </optgroup>
      ))}
    </select>
  );
}
