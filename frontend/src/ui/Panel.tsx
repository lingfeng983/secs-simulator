import type { ReactNode } from 'react';

/** 面板外壳：标题、说明、操作区、内容。 */
export interface PanelProps {
  title: string;
  /** 一句话说清这个面板能干什么。 */
  description?: string;
  /** 标题右侧的操作区。 */
  actions?: ReactNode;
  /** 说明下方、标题上方的"指标条"。 */
  stats?: ReactNode;
  children: ReactNode;
  /** 面板 id，便于跳转与测试定位。 */
  id?: string;
}

/**
 * 面板外壳。
 *
 * <p>统一标题层级、间距和说明位置：
 * 之前每个面板自己排版，加一个面板就要重新调一遍 CSS。</p>
 */
export function Panel({ title, description, actions, stats, children, id }: PanelProps): JSX.Element {
  return (
    <section className="panel" id={id}>
      <div className="panel-head">
        <div className="panel-title-wrap">
          <h2 className="panel-title">{title}</h2>
          {description !== undefined && <p className="panel-desc">{description}</p>}
        </div>
        {actions !== undefined && <div className="panel-actions">{actions}</div>}
      </div>
      {stats !== undefined && <div className="panel-stats">{stats}</div>}
      <div className="panel-body">{children}</div>
    </section>
  );
}

/** 指标卡：一个小块数字 + 说明。 */
export function StatCard({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: ReactNode;
  hint?: string;
  tone?: 'ok' | 'warn' | 'danger';
}): JSX.Element {
  return (
    <div className={`stat-card${tone === undefined ? '' : ` tone-${tone}`}`}>
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
      {hint !== undefined && <span className="stat-hint">{hint}</span>}
    </div>
  );
}
