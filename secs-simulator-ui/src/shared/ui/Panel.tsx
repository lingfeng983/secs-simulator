import type { ReactNode } from 'react';

/** 面板外壳：标题、说明、操作区、内容。 */
export interface PanelProps {
  title: string;
  /** 一句话说清这个面板能干什么；支持换行（\n 会按行展示）。 */
  description?: ReactNode;
  /** 标题右侧的操作区。 */
  actions?: ReactNode;
  /** 说明下方、标题上方的"指标条"。 */
  stats?: ReactNode;
  children: ReactNode;
  /** 面板 id，便于跳转与测试定位。 */
  id?: string;
  /** 标题前的序号（多段表单里告诉读者"这是第几步"）。 */
  index?: string;
  /** 标题右侧的状态标记（放在动作之前）。 */
  status?: ReactNode;
}

/**
 * 面板外壳。
 *
 * <p>标题 / 说明 / 操作 / 内容四段固定，间距与分隔线由 CSS 决定；
 * 面板自己不再排版，加一个面板不用重新调样式。</p>
 */
export function Panel({ title, description, actions, stats, children, id, index, status }: PanelProps): JSX.Element {
  return (
    <section className="panel" id={id}>
      <div className="panel-head">
        <div className="panel-title-wrap">
          <div className="panel-title-row">
            <h2 className="panel-title">
              {index !== undefined && <span className="panel-index">{index}</span>}
              {title}
            </h2>
            {status}
          </div>
          {description !== undefined && <p className="panel-desc">{description}</p>}
        </div>
        {actions !== undefined && (
          <div className="panel-actions" role="toolbar" aria-label={`${title} 操作`}>
            {actions}
          </div>
        )}
      </div>
      {stats !== undefined && <div className="panel-stats">{stats}</div>}
      <div className="panel-body">{children}</div>
    </section>
  );
}

/** 面板脚注条目：右下角一行的"名称 数值"。 */
export interface FootnoteItem {
  label: string;
  value: ReactNode;
  tone?: 'ok' | 'warn' | 'danger';
}

/**
 * 面板底部的轻量统计。
 *
 * <p>纯计数不再占一排指标卡，视线落在内容上，数字退到角落。</p>
 */
export function PanelFootnote({ items }: { items: readonly FootnoteItem[] }): JSX.Element | null {
  if (items.length === 0) {
    return null;
  }
  return (
    <p className="panel-footnote">
      {items.map((item) => (
        <span key={item.label} className={item.tone === undefined ? undefined : `tone-${item.tone}`}>
          {item.label} {item.value}
        </span>
      ))}
    </p>
  );
}
