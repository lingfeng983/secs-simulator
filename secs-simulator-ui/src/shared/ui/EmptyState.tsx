/**
 * 空状态：图标 + 一句原因 + 一个可选的 CTA。
 *
 * <p>不再只丢一张空表或一句孤零零的"暂无数据"：图标先让人认出这是空态，
 * text 说清为什么空，hint 补一句下一步，action 给一个出口。
 * 既有调用方只传 text（可选 hint/action）即可，无需改动。</p>
 */
export interface EmptyStateProps {
  /** 一句原因（必填），如"暂无数据""没有匹配的结果"。 */
  text: string;
  /** 次要说明，解释为什么空、接下来该做什么。 */
  hint?: string;
  /** 一个 CTA（按钮或链接）；不给就只展示图标 + 说明。 */
  action?: React.ReactNode;
  /** 自定义图标；不给用统一的空盘图标。 */
  icon?: React.ReactNode;
}

export function EmptyState({ text, hint, action, icon }: EmptyStateProps): JSX.Element {
  return (
    <div className="empty-state" role="status">
      <span className="empty-icon" aria-hidden="true">
        {icon ?? (
          <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
            <path d="M4.2 5.5h15.6l2.2 7.6v4.4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4.4Z" />
            <path d="M2.2 13.1h5l1.5 2.8h6.6l1.5-2.8h5" />
          </svg>
        )}
      </span>
      <p className="empty-text">{text}</p>
      {hint !== undefined && <p className="empty-hint">{hint}</p>}
      {action !== undefined && <div className="empty-action">{action}</div>}
    </div>
  );
}
