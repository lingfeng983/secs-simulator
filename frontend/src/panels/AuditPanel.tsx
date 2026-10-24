import type { AuditEntryView } from '../api';
import { Panel, PanelFootnote } from '../ui/Panel';
import { DataTable, type Column } from '../ui/DataTable';
import { formatDateTime } from '../lib/format';
import type { PanelBaseProps } from './types';

/**
 * 审计面板。
 *
 * <p>只有管理员能看：记录里有账号和操作目标。
 * 非管理员看到的不是"空列表"，而是一句说明——
 * 否则用户会以为这个功能坏了。</p>
 */
export function AuditPanel({ backend }: PanelBaseProps): JSX.Element {
  const { auditEntries } = backend;

  const columns: Column<AuditEntryView>[] = [
    {
      key: 'at',
      header: '时间',
      sortValue: (row) => row.at,
      render: (row) => <span className="mono nowrap">{formatDateTime(row.at)}</span>,
    },
    { key: 'actor', header: '操作者', sortValue: (row) => row.actor },
    {
      key: 'role',
      header: '角色',
      sortValue: (row) => row.role,
      render: (row) => <span className="badge badge-muted">{row.role}</span>,
    },
    { key: 'action', header: '动作', sortValue: (row) => row.action, render: (row) => <span className="mono">{row.action}</span> },
    { key: 'target', header: '目标', sortValue: (row) => row.target, render: (row) => <span className="mono">{row.target}</span> },
    {
      key: 'detail',
      header: '细节',
      className: 'audit-detail',
      sortValue: (row) => row.detail,
      render: (row) => <span className="mono audit-detail">{row.detail}</span>,
    },
  ];

  return (
    <Panel
      id="panel-audit"
      title="审计"
      description="谁在什么时候动了什么。完整历史在服务端日志文件里。"
    >
      <DataTable
        columns={columns}
        rows={auditEntries}
        rowKey={(row) => `${row.at}-${row.actor}-${row.action}`}
        searchPlaceholder="搜索操作者 / 动作 / 目标"
        exportName="audit"
        emptyText="暂无审计记录"
        defaultSort={{ key: 'at', direction: 'desc' }}
        exportColumns={[
          { header: '时间', value: (row) => new Date(row.at).toISOString() },
          { header: '操作者', value: (row) => row.actor },
          { header: '角色', value: (row) => row.role },
          { header: '动作', value: (row) => row.action },
          { header: '目标', value: (row) => row.target },
          { header: '细节', value: (row) => row.detail },
        ]}
      />
      <p className="hint">最近 200 条操作记录；完整历史在 <code>--work-dir/logs/audit.jsonl</code>。</p>
          <PanelFootnote items={[{ label: '最近记录', value: auditEntries.length }]} />
    </Panel>
  );
}
