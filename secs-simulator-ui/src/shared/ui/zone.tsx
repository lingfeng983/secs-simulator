import type { ReactNode } from 'react';

/**
 * 设备/主机详情共用的「两段式」骨架（设计 §3.4.1 同构对照表）：
 * 实例头 + 配置区/运行区切换条 + 子页签。两个域共用版式与交互语言，
 * 数据与路由完全独立（同构但不相交）。
 */

/** 实例头：徽标 + 关键指标 + 动作按钮。 */
export interface InstanceHeadProps {
  title: string;
  /** 徽标（已连接/已断开/…）。 */
  badge: ReactNode;
  /** 徽标下的补充行（目标地址 / 配置版本等）。 */
  subtitle?: ReactNode;
  /** 四格指标。 */
  metrics?: ReadonlyArray<{ label: string; value: ReactNode }>;
  /** 右侧动作按钮组。 */
  actions?: ReactNode;
  /** 配置区 / 运行区切换：并入实例头这一行，不再单独占一行。 */
  zoneSwitch?: ReactNode;
}

export function InstanceHead({ title, badge, subtitle, metrics, actions, zoneSwitch }: InstanceHeadProps): JSX.Element {
  return (
    <div className="instance-head">
      <div className="instance-head-main">
        <h1 className="instance-title">
          {title}
          {badge}
        </h1>
        {subtitle !== undefined && <div className="instance-subtitle">{subtitle}</div>}
      </div>
      {metrics !== undefined && metrics.length > 0 && (
        <div className="instance-metrics">
          {metrics.map((metric) => (
            <div className="instance-metric" key={metric.label}>
              <div className="instance-metric-label">{metric.label}</div>
              <div className="instance-metric-value">{metric.value}</div>
            </div>
          ))}
        </div>
      )}
      {(zoneSwitch !== undefined || actions !== undefined) && (
        <div className="instance-actions">
          {zoneSwitch}
          {actions}
        </div>
      )}
    </div>
  );
}

/**
 * 紧凑的配置区 / 运行区切换：放在实例头的状态/动作行里，一行排完。
 */
export function ZoneSwitch({
  zone,
  onChange,
  configNote,
  runtimeNote,
}: {
  zone: 'config' | 'runtime';
  onChange: (zone: 'config' | 'runtime') => void;
  configNote?: string;
  runtimeNote?: string;
}): JSX.Element {
  return (
    <div className="zone-switch" role="tablist" aria-label="配置区 / 运行区">
      <button
        type="button"
        role="tab"
        aria-selected={zone === 'config'}
        className={`zone-switch-btn config${zone === 'config' ? ' active' : ''}`}
        onClick={() => onChange('config')}
        title={configNote ?? '修改实例定义'}
      >
        ▣ 配置区
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={zone === 'runtime'}
        className={`zone-switch-btn runtime${zone === 'runtime' ? ' active' : ''}`}
        onClick={() => onChange('runtime')}
        title={runtimeNote ?? '查看与操作当前运行'}
      >
        ◉ 运行区
      </button>
    </div>
  );
}

/** 两段式内部的子页签。 */
export function SubTabs({
  pages,
  active,
  onSelect,
}: {
  pages: ReadonlyArray<{ id: string; label: string }>;
  active: string;
  onSelect: (page: string) => void;
}): JSX.Element {
  return (
    <div className="subtabs" role="tablist">
      {pages.map((page) => (
        <button
          key={page.id}
          type="button"
          role="tab"
          aria-selected={active === page.id}
          className={`subtab${active === page.id ? ' active' : ''}`}
          onClick={() => onSelect(page.id)}
        >
          {page.label}
        </button>
      ))}
    </div>
  );
}
