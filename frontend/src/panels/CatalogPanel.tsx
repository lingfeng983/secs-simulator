import { useMemo, useState } from 'react';
import type { CatalogCeid, CatalogReport, CatalogSvid } from '../api';
import { Panel, PanelFootnote } from '../ui/Panel';
import { DataTable, type Column } from '../ui/DataTable';
import type { PanelBaseProps } from './types';

type CatalogTab = 'ceids' | 'reports' | 'svids';

const TABS: ReadonlyArray<{ id: CatalogTab; label: string }> = [
  { id: 'ceids', label: '采集事件（CEID）' },
  { id: 'reports', label: '报告（RPTID）' },
  { id: 'svids', label: '状态变量（SVID）' },
];

/**
 * 设备目录面板。
 *
 * <p>CEID / RPTID / SVID 的号段按单元独立，不同会话之间不能照搬，
 * 所以目录必须跟着选中的会话换。</p>
 *
 * <p>三类条目用一个分段切换而不是三个大表：
 * 表格一多就要滚很久，而这三类里通常只有一类是当下要看的。</p>
 */
export function CatalogPanel({ backend }: PanelBaseProps): JSX.Element {
  const [tab, setTab] = useState<CatalogTab>('ceids');
  const { catalog, activeSession, setCeid } = backend;

  const ceids = catalog?.ceids ?? [];
  const reports = catalog?.reports ?? [];
  const svids = catalog?.svids ?? [];

  const hostOverrides = useMemo(() => {
    const hostCeids = ceids.filter((entry) => entry.source === 'host').length;
    const hostReports = reports.filter((entry) => entry.source === 'host').length;
    return hostCeids + hostReports;
  }, [ceids, reports]);

  const ceidColumns: Column<CatalogCeid>[] = [
    { key: 'ceid', header: 'CEID', align: 'right', sortValue: (row) => row.ceid, render: (row) => row.ceid },
    { key: 'owner', header: '归属', sortValue: (row) => row.owner, render: (row) => row.owner || '-' },
    { key: 'slot', header: '名称', sortValue: (row) => row.slot, render: (row) => row.slot || '-' },
    {
      key: 'reports',
      header: '携带报告',
      sortValue: (row) => row.reports.join(','),
      render: (row) => <span className="mono">{row.reports.join(', ') || '-'}</span>,
    },
    {
      key: 'source',
      header: '来源',
      sortValue: (row) => row.source ?? 'local',
      render: (row) => (
        <span className={`badge badge-${row.source === 'host' ? 'accent' : 'muted'}`}>
          {row.source === 'host' ? '主机' : '本地'}
        </span>
      ),
    },
    {
      key: 'enabled',
      header: '状态',
      sortValue: (row) => (row.enabled === false ? 0 : 1),
      render: (row) =>
        row.enabled === false ? (
          <span className="badge badge-muted">已禁用</span>
        ) : (
          <span className="badge badge-ok">启用</span>
        ),
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <button
          type="button"
          onClick={() => setCeid(row.ceid)}
          title='填入会话面板的 CEID 框'
        >
          填入
        </button>
      ),
    },
  ];

  const reportColumns: Column<CatalogReport>[] = [
    { key: 'rptid', header: 'RPTID', align: 'right', sortValue: (row) => row.rptid, render: (row) => row.rptid },
    { key: 'owner', header: '归属', sortValue: (row) => row.owner, render: (row) => row.owner || '-' },
    { key: 'slot', header: '名称', sortValue: (row) => row.slot, render: (row) => row.slot || '-' },
    {
      key: 'svids',
      header: '变量',
      sortValue: (row) => row.svids.join(','),
      render: (row) => <span className="mono">{row.svids.join(', ') || '-'}</span>,
    },
    {
      key: 'source',
      header: '来源',
      sortValue: (row) => row.source ?? 'local',
      render: (row) => (
        <span className={`badge badge-${row.source === 'host' ? 'accent' : 'muted'}`}>
          {row.source === 'host' ? '主机' : '本地'}
        </span>
      ),
    },
  ];

  const svidColumns: Column<CatalogSvid>[] = [
    { key: 'svid', header: 'SVID', align: 'right', sortValue: (row) => row.svid, render: (row) => row.svid },
    { key: 'owner', header: '归属', sortValue: (row) => row.owner, render: (row) => row.owner },
    { key: 'slot', header: '名称', sortValue: (row) => row.slot, render: (row) => row.slot },
    { key: 'type', header: '类型', sortValue: (row) => row.type, render: (row) => <span className="mono">{row.type}</span> },
    { key: 'unit', header: '单位', sortValue: (row) => row.unit, render: (row) => row.unit || '-' },
  ];

  return (
    <Panel
      id="panel-catalog"
      title="设备目录"
      description="把「绝对 ID → 语义」的映射摊开：CEID、RPTID、SVID 各自的归属、名称与来源。"
    >
      {catalog !== null && (catalog.hostReports! > 0 || catalog.hostLinks! > 0 || catalog.hostAllDisabled) && (
        <p className="hint">
          主机已下发 {catalog.hostReports} 条报告定义、{catalog.hostLinks} 条事件链接
          {catalog.hostAllDisabled ? '，并把全部事件上报关掉了（S2F37）' : ''}；
          下面标了「主机」的条目就是主机覆盖过的配置（ADR-0006）。
        </p>
      )}

      <div className="segmented" role="tablist">
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={tab === item.id}
            className={`segment${tab === item.id ? ' active' : ''}`}
            onClick={() => setTab(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>

      {tab === 'ceids' && (
        <DataTable
        tableKey="catalog"
          columns={ceidColumns}
          rows={ceids}
          rowKey={(row) => String(row.ceid)}
          searchPlaceholder="搜索 CEID / 名称 / 归属"
          exportName="catalog-ceids"
          emptyText={catalog === null ? '该会话没有提供目录' : '该会话没有定义采集事件'}
          exportColumns={[
            { header: 'CEID', value: (row) => row.ceid },
            { header: '归属', value: (row) => row.owner },
            { header: '名称', value: (row) => row.slot },
            { header: '携带报告', value: (row) => row.reports.join(' ') },
            { header: '来源', value: (row) => (row.source === 'host' ? '主机' : '本地') },
          ]}
        />
      )}

      {tab === 'reports' && (
        <DataTable
        tableKey="catalog-2"
          columns={reportColumns}
          rows={reports}
          rowKey={(row) => String(row.rptid)}
          searchPlaceholder="搜索 RPTID / 名称 / 归属"
          exportName="catalog-reports"
          emptyText={catalog === null ? '该会话没有提供目录' : '该会话没有定义报告'}
          exportColumns={[
            { header: 'RPTID', value: (row) => row.rptid },
            { header: '归属', value: (row) => row.owner },
            { header: '名称', value: (row) => row.slot },
            { header: '变量', value: (row) => row.svids.join(' ') },
            { header: '来源', value: (row) => (row.source === 'host' ? '主机' : '本地') },
          ]}
        />
      )}

      {tab === 'svids' && (
        <DataTable
        tableKey="catalog-3"
          columns={svidColumns}
          rows={svids}
          rowKey={(row) => String(row.svid)}
          searchPlaceholder="搜索 SVID / 名称 / 单位"
          exportName="catalog-svids"
          emptyText={catalog === null ? '该会话没有提供目录' : '该会话没有定义状态变量'}
          exportColumns={[
            { header: 'SVID', value: (row) => row.svid },
            { header: '归属', value: (row) => row.owner },
            { header: '名称', value: (row) => row.slot },
            { header: '类型', value: (row) => row.type },
            { header: '单位', value: (row) => row.unit },
          ]}
        />
      )}

      <p className="hint">CEID / RPTID / SVID 的号段按单元独立，不同会话之间不能照搬。</p>
          <PanelFootnote
        items={[
          { label: 'CEID', value: ceids.length },
          { label: 'RPTID', value: reports.length },
          { label: 'SVID', value: svids.length },
          { label: '主机覆盖', value: hostOverrides, tone: hostOverrides > 0 ? 'warn' : undefined },
        ]}
      />
    </Panel>
  );
}
