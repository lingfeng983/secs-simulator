import type { ReactNode } from 'react';

/** 面板外壳：标题、悬浮说明、操作区、内容。 */
export interface PanelProps {
  title: string;
  /** 一句话说清这个面板能干什么；支持换行（\n 会按行展示）。悬浮标题时弹出，不常驻。 */
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
  /**
   * 不渲染标题行（标题只留在无障碍树里当 aria-label）。
   *
   * <p>详情页的子页用：页签已经说明"这是哪一页"，面板不再重复标题与说明，
   * 内容直接开始。此时 status / actions 不会丢——它们落到内容下方的一条
   * 操作行里，状态在左、按钮右对齐。</p>
   */
  headless?: boolean;
}

/**
 * 面板外壳。
 *
 * <p>标题 / 操作 / 内容三段固定，说明悬浮标题时弹出；间距与分隔线由 CSS 决定；
 * 面板自己不再排版，加一个面板不用重新调样式。</p>
 */
export function Panel({
  title,
  description,
  actions,
  stats,
  children,
  id,
  index,
  status,
  headless = false,
}: PanelProps): JSX.Element {
  /* 无标题的面板把状态与动作放到内容下方：按钮下移、右对齐 */
  const footed = headless && (status !== undefined || actions !== undefined);
  return (
    <section
      className={`panel${footed ? ' panel--footed' : ''}`}
      id={id}
      aria-label={headless ? title : undefined}
    >
      {!headless && (
        <div className="panel-head">
          <div className="panel-title-wrap" tabIndex={description !== undefined ? 0 : undefined}>
            <div className="panel-title-row">
              <h2 className="panel-title">
                {index !== undefined && <span className="panel-index">{index}</span>}
                {title}
              </h2>
              {status}
            </div>
            {description !== undefined && (
              <div className="panel-tip" role="tooltip">{description}</div>
            )}
          </div>
          {actions !== undefined && (
            <div className="panel-actions" role="toolbar" aria-label={`${title} 操作`}>
              {actions}
            </div>
          )}
        </div>
      )}
      {stats !== undefined && <div className="panel-stats">{stats}</div>}
      <div className="panel-body">{children}</div>
      {footed && (
        <div className="panel-foot">
          {status !== undefined && <span className="panel-foot-status">{status}</span>}
          <span className="spacer" aria-hidden="true" />
          {actions !== undefined && (
            <div className="panel-actions" role="toolbar" aria-label={`${title} 操作`}>
              {actions}
            </div>
          )}
        </div>
      )}
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
