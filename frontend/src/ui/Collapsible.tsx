import { useState, type ReactNode } from 'react';

/**
 * 可折叠区块：标题行点击开合，默认收起（设备段的子区多数时候不用看）。
 *
 * <p>配置表单太长的问题靠它解决：会话/设备/事件/模板四段各自折叠，
 * 打开哪段改哪段。</p>
 */
export function Collapsible({
  title,
  defaultOpen = false,
  badge,
  children,
}: {
  title: string;
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
        <span className="collapsible-title">{title}</span>
        {badge !== undefined && <span className="collapsible-badge">{badge}</span>}
      </button>
      {open && <div className="collapsible-body">{children}</div>}
    </div>
  );
}
