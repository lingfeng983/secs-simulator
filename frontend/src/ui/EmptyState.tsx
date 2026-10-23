/** 空状态：没有数据时给一句有用的话，而不是一张空表。 */
export interface EmptyStateProps {
  text: string;
  /** 次要说明，解释为什么空、接下来该做什么。 */
  hint?: string;
  /** 额外的操作区。 */
  action?: React.ReactNode;
}

export function EmptyState({ text, hint, action }: EmptyStateProps): JSX.Element {
  return (
    <div className="empty-state">
      <p className="empty-text">{text}</p>
      {hint !== undefined && <p className="empty-hint">{hint}</p>}
      {action !== undefined && <div className="empty-action">{action}</div>}
    </div>
  );
}
