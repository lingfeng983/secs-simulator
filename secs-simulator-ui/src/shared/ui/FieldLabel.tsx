import type { ReactNode } from 'react';

/**
 * 表单字段标题：一行一个配置项时用它做左列标题。
 *
 * <p>描述性的说明默认不展示，悬浮在标题上时以气泡展示
 * （原生 title 作兜底，CSS 气泡即时可见、可换行）。</p>
 */
export function FieldLabel({ text, help }: { text: string; help?: string }): JSX.Element {
  if (help === undefined || help === '') {
    return <span className="modal-field-label">{text}</span>;
  }
  return (
    <span className="modal-field-label has-help" title={help} tabIndex={0}>
      {text}
      <span className="field-tip" role="tooltip" aria-hidden="true">{help}</span>
    </span>
  );
}

/**
 * 段落级标题的悬浮说明：包住 Modal 标题、小节 h3 这类整段说明的归属标题。
 *
 * <p>与 FieldLabel 同一套气泡（.field-tip），但说明在标题下方弹出——
 * 这些标题通常顶着弹框头或面板头，向上弹会被裁掉。标题本身已可聚焦
 * （如 Collapsible 的按钮头）时传 focusable={false}，避免套嵌套焦点。</p>
 */
export function HintTitle({ text, children, focusable = true }: { text: string; children: ReactNode; focusable?: boolean }): JSX.Element {
  return (
    <span className="hint-title has-help" title={text} tabIndex={focusable ? 0 : undefined}>
      {children}
      <span className="field-tip" role="tooltip" aria-hidden="true">{text}</span>
    </span>
  );
}
