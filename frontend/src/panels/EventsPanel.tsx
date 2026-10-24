import type { EventPlanView } from '../api';
import { Panel, PanelFootnote } from '../ui/Panel';
import { DataTable, type Column } from '../ui/DataTable';
import type { PanelBaseProps } from './types';

/** 事件编排的触发规则摘要。 */
export function triggerLabel(plan: EventPlanView): string {
  switch (plan.triggerType) {
    case 'at':
      return `一次性 @ ${plan.atMs ?? 0} ms`;
    case 'periodic':
      return `每 ${plan.periodMs ?? 0} ms${plan.jitterMs ? ` ± ${plan.jitterMs} ms` : ''}`;
    case 'onCondition':
      return `${plan.expression ?? ''}（连续 ${plan.debounceMs ?? 0} ms）`;
    case 'afterEvent':
      return `${plan.sourcePlan ?? '?'} 之后 ${plan.delayMs ?? 0} ms`;
    default:
      return plan.triggerType;
  }
}

/**
 * 事件编排面板。
 *
 * <p>这里改的是<b>运行时状态</b>，不写回配置；重载会话会恢复配置里的启用状态。
 * 这一点必须在界面上说清楚，否则用户会以为改配置没生效。</p>
 */
export function EventsPanel({ backend }: PanelBaseProps): JSX.Element {
  const { eventPlans, activeSession } = backend;
  const enabled = eventPlans.filter((plan) => plan.enabled).length;

  const columns: Column<EventPlanView>[] = [
    { key: 'id', header: '计划', sortValue: (row) => row.id, render: (row) => <span className="mono">{row.id}</span> },
    { key: 'ceid', header: 'CEID', align: 'right', sortValue: (row) => row.ceid, render: (row) => row.ceid },
    { key: 'trigger', header: '触发规则', sortValue: (row) => triggerLabel(row), render: (row) => triggerLabel(row) },
    {
      key: 'enabled',
      header: '状态',
      sortValue: (row) => (row.enabled ? 1 : 0),
      render: (row) => (
        <span className={`badge badge-${row.enabled ? 'ok' : 'muted'}`}>{row.enabled ? '启用' : '禁用'}</span>
      ),
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <button
          type="button"
          onClick={() => void backend.toggleEvent(row)}
          disabled={activeSession === ''}
          title={row.enabled ? '禁用后不再触发；已排队的延迟事件会清掉' : '重新启用；禁用期间的事件不补发'}
        >
          {row.enabled ? '禁用' : '启用'}
        </button>
      ),
    },
  ];

  return (
    <Panel
      id="panel-events"
      title="事件编排"
      description="周期、条件或延迟触发的事件计划。启停只影响当前运行实例。"
    >
      <DataTable
        tableKey="events"
        columns={columns}
        rows={eventPlans}
        rowKey={(row) => row.id}
        searchPlaceholder="搜索计划 / CEID / 触发规则"
        exportName="event-plans"
        emptyText={activeSession === '' ? '暂无会话' : '该会话没有事件编排计划'}
        exportColumns={[
          { header: '计划', value: (row) => row.id },
          { header: 'CEID', value: (row) => row.ceid },
          { header: '触发规则', value: (row) => triggerLabel(row) },
          { header: '启用', value: (row) => (row.enabled ? '是' : '否') },
        ]}
      />
      <p className="hint">这里改的是运行时状态，不写回配置；重载会话会恢复配置里的启用状态。</p>
          <PanelFootnote
        items={[
          { label: '计划数', value: eventPlans.length },
          { label: '启用中', value: enabled, tone: enabled > 0 ? 'ok' : undefined },
        ]}
      />
    </Panel>
  );
}
