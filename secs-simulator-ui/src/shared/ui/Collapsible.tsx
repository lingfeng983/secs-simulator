import { useState, type ReactNode } from 'react';
import { HintTitle } from './FieldLabel';

/**
 * 可折叠区块：标题行点击开合，默认收起（设备段的子区多数时候不用看）。
 *
 * <p>配置表单太长的问题靠它解决：会话/设备/事件/模板四段各自折叠，
 * 打开哪段改哪段。整段说明挂在标题上悬浮展示（hint），不默认占版面。</p>
 */
export function Collapsible({
  title,
  hint,
  defaultOpen = false,
  badge,
  children,
}: {
  title: string;
  /** 区块级说明：悬浮在标题上时展示。 */
  hint?: string;
  defaultOpen?: boolean;
  /** 标题右侧的轻量标注（如数量）。 */
  badge?: string;
  children: ReactNode;
}): JSX.Element {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className={`collapsible${open ? ' open' : ''}`}>
      <button type="button" className="collapsible-head" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className="collapsible-mark" aria-hidden="true">
          {open ? '▾' : '▸'}
        </span>
        <span className="collapsible-title">
          {hint === undefined ? title : <HintTitle text={hint} focusable={false}>{title}</HintTitle>}
        </span>
        {badge !== undefined && <span className="collapsible-badge">{badge}</span>}
      </button>
      {open && <div className="collapsible-body">{children}</div>}
    </div>
  );
}
