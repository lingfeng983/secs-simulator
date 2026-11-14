/** 引用多选框：从列表直接勾选，不再手写逗号分隔文本。 */
export interface RefChoice {
  /** 写进文档的引用值。 */
  value: string;
  /** 展示名；缺省用 value。 */
  label?: string;
}

export function RefMultiSelect({ choices, values, onChange, emptyHint }: {
  choices: ReadonlyArray<RefChoice>;
  values: ReadonlyArray<string>;
  onChange: (next: string[]) => void;
  /** 列表为空时的提示文案。 */
  emptyHint: string;
}): JSX.Element {
  const known = new Set(choices.map((choice) => choice.value));
  const missing = values.filter((value) => !known.has(value));
  const toggle = (value: string, checked: boolean) => {
    onChange(checked ? [...values, value] : values.filter((item) => item !== value));
  };
  return (
    <div className="ref-multiselect" role="group" aria-label="引用选择">
      {choices.length === 0 ? (
        <span className="hint-inline">{emptyHint}</span>
      ) : (
        choices.map((choice) => (
          <label className="check-inline" key={choice.value}>
            <input
              type="checkbox"
              checked={values.includes(choice.value)}
              onChange={(event) => toggle(choice.value, event.target.checked)}
            />
            <span className="mono">{choice.label ?? choice.value}</span>
          </label>
        ))
      )}
      {/* 文档里已引用、但列表里没有的项（比如引用刚被删掉）保底展示，避免勾不掉 */}
      {missing.map((value) => (
        <label className="check-inline" key={`missing-${value}`} title="引用的目标不存在，取消勾选即从文档移除">
          <input
            type="checkbox"
            checked
            onChange={(event) => toggle(value, event.target.checked)}
          />
          <span className="mono hint-inline">{value}（缺失）</span>
        </label>
      ))}
    </div>
  );
}
