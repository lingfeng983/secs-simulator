import type { ReactNode } from 'react';

/**
 * 表单字段标题：一行一个配置项时用它做左列标题。
 *
 * <p>描述性的说明默认不展示，悬浮在标题上时以气泡展示
 * （原生 title 作兜底，CSS 气泡即时可见、可换行）。</p>
 *
 * <p>{@code required} 在标题后加一个红色星号：必填项要写在字段上，
 * 而不是等用户点「确定」时弹一条"不能为空"的横幅。</p>
 */
export function FieldLabel({ text, help, required = false }: { text: string; help?: string; required?: boolean }): JSX.Element {
  const body = (
    <>
      {text}
      {required && <span className="field-required" aria-hidden="true">*</span>}
    </>
  );
  if (help === undefined || help === '') {
    return <span className="modal-field-label">{body}</span>;
  }
  return (
    <span className="modal-field-label has-help" title={help} tabIndex={0}>
      {body}
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
