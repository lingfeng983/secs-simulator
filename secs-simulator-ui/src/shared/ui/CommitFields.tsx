import { useEffect, useState } from 'react';

interface TextFieldProps {
  value: string;
  allowBlank?: boolean;
  wide?: boolean;
  /** 占位提示；透传给 input。 */
  placeholder?: string;
  /** 悬停提示（透传给 input）。 */
  title?: string;
  onCommit: (next: string) => void;
}

/** 失焦/回车才提交的文本框：避免每敲一个字就重写整份配置。 */
export function TextField({ value, allowBlank = false, wide = false, placeholder, title, onCommit }: TextFieldProps): JSX.Element {
  const [draft, setDraft] = useState(value);

  useEffect(() => {
    setDraft(value);
  }, [value]);

  const commit = () => {
    if (!allowBlank && draft.trim() === '') {
      setDraft(value);
      return;
    }
    if (draft !== value) {
      onCommit(draft);
    }
  };

  return (
    <input
      className={wide ? 'template-input-wide' : undefined}
      placeholder={placeholder}
      title={title}
      value={draft}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.currentTarget.blur();
        }
      }}
    />
  );
}

interface NumberFieldProps {
  value: number | undefined;
  integer?: boolean;
  step?: number;
  list?: string;
  /** 悬停提示（透传给 input）。 */
  title?: string;
  /** 给了就允许清空：清空后调用它（可选项删键），不给则清空恢复原值。 */
  onClear?: () => void;
  onCommit: (next: number) => void;
}

/** 失焦/回车才提交的数字框；非法输入恢复原值，避免把 NaN 写进 JSON。 */
export function NumberField({ value, integer = false, step, list, title, onClear, onCommit }: NumberFieldProps): JSX.Element {
  const external = value === undefined ? '' : String(value);
  const [draft, setDraft] = useState(external);

  useEffect(() => {
    setDraft(external);
  }, [external]);

  const commit = () => {
    if (draft.trim() === '' && onClear !== undefined) {
      if (value !== undefined) {
        onClear();
      }
      return;
    }
    const parsed = Number(draft);
    if (draft.trim() === '' || !Number.isFinite(parsed) || (integer && !Number.isInteger(parsed))) {
      setDraft(external);
      return;
    }
    if (parsed !== value) {
      onCommit(parsed);
    }
  };

  return (
    <input
      type="number"
      list={list}
      title={title}
      step={step ?? (integer ? 1 : 'any')}
      value={draft}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.currentTarget.blur();
        }
      }}
    />
  );
}
