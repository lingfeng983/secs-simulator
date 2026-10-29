import { useMemo, useState } from 'react';
import type { AuditEntryView } from '../api';
import { Panel, PanelFootnote } from '../ui/Panel';
import { DataTable, type Column } from '../ui/DataTable';
import { formatDateTime } from '../lib/format';
import type { PanelBaseProps } from './types';
import { saveHint } from '../lib/saveFile';

/**
 * 审计面板。
 *
 * <p>只有管理员能看：记录里有账号和操作目标。非管理员看到的不是
 * "空列表"，而是一句说明——否则用户会以为这个功能坏了。
 * 动作/操作者是<b>服务端</b>过滤(精确匹配),轮询也带着它取数;
 * 表格内建搜索是本地包含式过滤,两者互补。</p>
 */
export function AuditPanel({ backend, notifier }: PanelBaseProps): JSX.Element {
  const { auditEntries, auditOlder, auditFilter, setAuditFilter, auditError, loadAuditOlder, health } = backend;
  const [actionInput, setActionInput] = useState(auditFilter.action);
  const [actorInput, setActorInput] = useState(auditFilter.actor);
  const [imported, setImported] = useState<AuditEntryView[] | null>(null);

  /** 轮询只刷新最近窗口;翻出来的旧页在这里合并去重,按时间倒序。 */
  const allEntries = useMemo(() => {
    const seen = new Set(auditEntries.map((entry) => `${entry.at}-${entry.actor}-${entry.action}-${entry.target}-${entry.detail}`));
    const fresh = auditOlder.filter((entry) => !seen.has(`${entry.at}-${entry.actor}-${entry.action}-${entry.target}-${entry.detail}`));
    return [...auditEntries, ...fresh].sort((left, right) => right.at - left.at);
  }, [auditEntries, auditOlder]);

  /** 非管理员:后端对审计回 403,界面明说而不是给一个空表。 */
  const notAdmin = health?.role === 'viewer' || health?.role === 'operator';

  const columns: Column<AuditEntryView>[] = [
    {
      key: 'at',
      header: '时间',
      width: '170px',
      sortValue: (row) => row.at,
      render: (row) => <span className="mono nowrap">{formatDateTime(row.at)}</span>,
    },
    { key: 'actor', header: '操作者', width: '110px', sortValue: (row) => row.actor },
    {
      key: 'role',
      header: '角色',
      width: '80px',
      sortValue: (row) => row.role,
      render: (row) => <span className="badge badge-muted">{row.role}</span>,
    },
    { key: 'action', header: '动作', width: '140px', sortValue: (row) => row.action, render: (row) => <span className="mono">{row.action}</span> },
    { key: 'target', header: '目标', width: '170px', sortValue: (row) => row.target, render: (row) => <span className="mono">{row.target}</span> },
    {
      key: 'detail',
      header: '细节',
      className: 'audit-detail-cell',
      sortValue: (row) => row.detail,
      render: (row) => <span className="mono audit-detail">{row.detail}</span>,
    },
  ];

  const applyFilter = () => {
    void setAuditFilter({ action: actionInput.trim(), actor: actorInput.trim() });
  };

  const clearFilter = () => {
    setActionInput('');
    setActorInput('');
    void setAuditFilter({ action: '', actor: '' });
  };

  const filterActive = auditFilter.action !== '' || auditFilter.actor !== '';
  const rows = imported ?? allEntries;

  return (
    <Panel
      id="panel-audit"
      title="审计"
      description="谁在什么时候动了什么。完整历史在服务端日志文件里。"
    >
      {/* 过滤条:精确匹配动作/操作者,和表格内的模糊搜索互补 */}
      {!notAdmin && (
        <div className="preview-controls">
          <input
            type="search"
            className="table-search"
            placeholder="动作精确匹配,如 config-save"
            value={actionInput}
            onChange={(event) => setActionInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                applyFilter();
              }
            }}
            aria-label="按动作过滤"
          />
          <input
            type="search"
            className="table-search"
            placeholder="操作者精确匹配"
            value={actorInput}
            onChange={(event) => setActorInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                applyFilter();
              }
            }}
            aria-label="按操作者过滤"
          />
          <button type="button" className="primary" onClick={applyFilter}>
            过滤
          </button>
          <button type="button" onClick={clearFilter} disabled={!filterActive}>
            清除过滤
          </button>
          <span className="spacer" />
          <button
            type="button"
            onClick={() => void loadAuditOlder()}
            disabled={allEntries.length === 0}
            title="以当前最早一条为游标,向服务端要更早的一页审计"
          >
            载入更早
          </button>
        </div>
      )}

      {notAdmin ? (
        <p className="hint">审计只对管理员开放;当前账号角色是「{health?.role}」。找管理员查操作留痕。</p>
      ) : (
        <>
          {auditError !== '' && (
            <p className="inline-note">审计刷新失败：{auditError}（表格保留上一次成功的数据）</p>
          )}
          <DataTable
            tableKey="audit"
            columns={columns}
            rows={rows}
            rowKey={(row) => `${row.at}-${row.actor}-${row.action}`}
            searchPlaceholder="搜索操作者 / 动作 / 目标"
            exportName="audit"
        onExported={(result) => { const hint = saveHint(result); if (hint !== '') { notifier.success('已导出', hint); } }}
            onImport={(importedRows) => {
              const entries: AuditEntryView[] = importedRows.flatMap((row) => {
                const at = Date.parse(row['时间'] ?? '');
                if (!Number.isFinite(at)) {
                  return [];
                }
                return [{
                  at,
                  actor: row['操作者'] ?? '',
                  role: row['角色'] ?? '',
                  action: row['动作'] ?? '',
                  target: row['目标'] ?? '',
                  detail: row['细节'] ?? '',
                }];
              });
              setImported(entries);
            }}
            emptyText={filterActive ? '当前过滤条件下没有审计记录' : '暂无审计记录'}
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
          <p className="hint">
            最近 200 条操作记录{filterActive ? '（按动作/操作者过滤后）' : ''}，「载入更早」继续往前翻；
            完整历史在 <code>--work-dir/logs/audit.jsonl</code>（重启后仍保留最近 1000 条）。
            导入 CSV 可把导出的审计放在本页只读查看，不会写回服务端。
          </p>
          {imported !== null && (
            <button type="button" onClick={() => setImported(null)}>退出导入视图</button>
          )}
          <PanelFootnote
            items={[
              { label: '当前视图', value: allEntries.length },
              { label: '过滤', value: filterActive ? `${auditFilter.action || '*'} · ${auditFilter.actor || '*'}` : '无', tone: filterActive ? 'warn' : undefined },
            ]}
          />
        </>
      )}
    </Panel>
  );
}
