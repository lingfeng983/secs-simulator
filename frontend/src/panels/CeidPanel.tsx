import { useMemo, useState } from 'react';
import type { CeidInput, ReportInput } from '../api';
import { Panel, PanelFootnote } from '../ui/Panel';
import { ConfirmModal } from '../ui/ConfirmModal';
import { Modal } from '../ui/Modal';
import { DataTable, type Column } from '../ui/DataTable';
import type { PanelBaseProps } from './types';

const EMPTY_CEID: CeidInput = { id: 0, slot: '', reports: [] };
const EMPTY_REPORT: ReportInput = { id: 0, slot: '', svids: [] };

/** CEID 表格的行(来自目录)。 */
interface CeidRow {
  ceid: number;
  slot: string;
  owner: string;
  reports: number[];
  source: string;
}

/** 报告表格的行。 */
interface ReportRow {
  rptid: number;
  slot: string;
  svids: number[];
  source: string;
}

/**
 * 事件设置面板:采集事件(CEID)的定义与报告(RPTID)绑定。
 *
 * <p>这里定义"有哪些事件、每个事件带哪些报告";「事件编排」页从这些
 * 已设置的事件里选 CEID 做计划。压测上报也在这里——它测的就是某个
 * 事件定义。运行时改动不写回配置,重载会话后失效。</p>
 */
export function CeidPanel({ backend, notifier }: PanelBaseProps): JSX.Element {
  const { catalog, activeSession } = backend;

  const [ceidEditorOpen, setCeidEditorOpen] = useState(false);
  const [editingCeid, setEditingCeid] = useState<number | null>(null);
  const [ceidDraft, setCeidDraft] = useState<CeidInput>(EMPTY_CEID);
  const [busy, setBusy] = useState(false);
  const [deleteCeidTarget, setDeleteCeidTarget] = useState<number | null>(null);

  const [reportEditorOpen, setReportEditorOpen] = useState(false);
  const [editingReport, setEditingReport] = useState<number | null>(null);
  const [reportDraft, setReportDraft] = useState<ReportInput>(EMPTY_REPORT);
  const [deleteReportTarget, setDeleteReportTarget] = useState<number | null>(null);

  /* 压测上报 */
  const [burstCeid, setBurstCeid] = useState('');
  const [burstCount, setBurstCount] = useState('100');
  const [burstInterval, setBurstInterval] = useState('0');
  const [burstRunning, setBurstRunning] = useState(false);

  const ceidRows: CeidRow[] = useMemo(
    () => (catalog?.ceids ?? []).map((ceid) => ({
      ceid: ceid.ceid,
      slot: ceid.slot,
      owner: ceid.owner,
      reports: [...(ceid.reports ?? [])],
      source: ceid.source ?? 'local',
    })),
    [catalog],
  );
  const reportRows: ReportRow[] = useMemo(
    () => (catalog?.reports ?? []).map((report) => ({
      rptid: report.rptid,
      slot: report.slot,
      svids: [...report.svids],
      source: report.source ?? 'local',
    })),
    [catalog],
  );

  const openCeidEditor = (row?: CeidRow) => {
    if (row === undefined) {
      setEditingCeid(null);
      setCeidDraft({ ...EMPTY_CEID });
    } else {
      setEditingCeid(row.ceid);
      setCeidDraft({ id: row.ceid, slot: row.slot, reports: [...row.reports] });
    }
    setCeidEditorOpen(true);
  };

  const saveCeid = async () => {
    if (!Number.isFinite(ceidDraft.id) || ceidDraft.id < 1) {
      notifier.error('CEID 必须是正整数');
      return;
    }
    if (ceidDraft.slot.trim() === '') {
      notifier.error('事件名不能为空');
      return;
    }
    setBusy(true);
    try {
      const ok = editingCeid === null
        ? await backend.addCeid({ ...ceidDraft, slot: ceidDraft.slot.trim() })
        : await backend.updateCeid(editingCeid, { ...ceidDraft, slot: ceidDraft.slot.trim() });
      if (ok) {
        setCeidEditorOpen(false);
      }
    } finally {
      setBusy(false);
    }
  };

  const saveReport = async () => {
    if (!Number.isFinite(reportDraft.id) || reportDraft.id < 1) {
      notifier.error('RPTID 必须是正整数');
      return;
    }
    if (reportDraft.slot.trim() === '') {
      notifier.error('报告名不能为空');
      return;
    }
    setBusy(true);
    try {
      const ok = editingReport === null
        ? await backend.addReport({ ...reportDraft, slot: reportDraft.slot.trim() })
        : await backend.updateReport(editingReport, { ...reportDraft, slot: reportDraft.slot.trim() });
      if (ok) {
        setReportEditorOpen(false);
      }
    } finally {
      setBusy(false);
    }
  };

  const runBurst = async () => {
    const ceid = Number.parseInt(burstCeid, 10);
    const count = Number.parseInt(burstCount, 10);
    if (!Number.isFinite(ceid) || ceid < 1) {
      notifier.error('CEID 必须是正整数');
      return;
    }
    if (!Number.isFinite(count) || count < 1 || count > 5000) {
      notifier.error('次数要在 1..5000 之间');
      return;
    }
    setBurstRunning(true);
    try {
      const stats = await backend.burst(activeSession, {
        kind: 'ceid',
        ceid,
        count,
        intervalMs: Number.parseInt(burstInterval, 10) || 0,
      });
      notifier.info(`压测完成:成功 ${stats.sent} / 失败 ${stats.failed}`);
    } catch (error) {
      notifier.error(`压测失败: ${(error as Error).message}`);
    } finally {
      setBurstRunning(false);
    }
  };

  const ceidColumns: Column<CeidRow>[] = [
    { key: 'ceid', header: 'CEID', align: 'right', sortValue: (row) => row.ceid, render: (row) => <span className="mono">{row.ceid}</span> },
    { key: 'slot', header: '事件名', sortValue: (row) => row.slot, render: (row) => <span className="mono">{row.slot}</span> },
    { key: 'owner', header: '归属', sortValue: (row) => row.owner, render: (row) => row.owner },
    { key: 'reports', header: '绑定 RPTID', sortValue: (row) => row.reports.length, render: (row) => <span className="mono">{row.reports.join(', ')}</span> },
    {
      key: 'source',
      header: '来源',
      sortValue: (row) => row.source,
      render: (row) => (row.source === 'host' ? <span className="badge tone-info">主机</span> : <span className="hint-inline">本地</span>),
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          <button type="button" onClick={() => openCeidEditor(row)} title="编辑事件名与绑定的报告">编辑</button>
          <button type="button" className="danger" onClick={() => setDeleteCeidTarget(row.ceid)} title="删除事件">删除</button>
        </div>
      ),
    },
  ];

  const reportColumns: Column<ReportRow>[] = [
    { key: 'rptid', header: 'RPTID', align: 'right', sortValue: (row) => row.rptid, render: (row) => <span className="mono">{row.rptid}</span> },
    { key: 'slot', header: '报告名', sortValue: (row) => row.slot, render: (row) => row.slot },
    { key: 'svids', header: '变量', sortValue: (row) => row.svids.length, render: (row) => <span className="mono">{row.svids.join(', ')}</span> },
    {
      key: 'source',
      header: '来源',
      sortValue: (row) => row.source,
      render: (row) => (row.source === 'host' ? <span className="badge tone-info">主机</span> : <span className="hint-inline">本地</span>),
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          <button
            type="button"
            onClick={() => {
              setEditingReport(row.rptid);
              setReportDraft({ id: row.rptid, slot: row.slot, svids: [...row.svids] });
              setReportEditorOpen(true);
            }}
          >
            编辑
          </button>
          <button type="button" className="danger" onClick={() => setDeleteReportTarget(row.rptid)}>删除</button>
        </div>
      ),
    },
  ];

  return (
    <Panel
      id="panel-ceids"
      title="事件设置"
      description="定义采集事件(CEID)与它绑定的报告(RPTID);「事件编排」页从这里选事件做计划。运行时改动不写回配置。"
      actions={
        <button
          type="button"
          className="primary"
          disabled={activeSession === ''}
          onClick={() => openCeidEditor(undefined)}
          title="新增一个采集事件"
        >
          新增事件
        </button>
      }
    >
      <DataTable
        tableKey="ceids"
        columns={ceidColumns}
        rows={ceidRows}
        rowKey={(row) => String(row.ceid)}
        searchPlaceholder="搜索 CEID / 事件名 / RPTID"
        exportName="ceids"
        emptyText={activeSession === '' ? '先在会话表里选一个会话' : '该设备还没有采集事件——点「新增事件」创建'}
        exportColumns={[
          { header: 'CEID', value: (row) => row.ceid },
          { header: '事件名', value: (row) => row.slot },
          { header: '归属', value: (row) => row.owner },
          { header: '绑定 RPTID', value: (row) => row.reports.join(', ') },
          { header: '来源', value: (row) => row.source },
        ]}
      />

      <div className="section-head" style={{ marginTop: '0.75rem' }}>
        <h3 className="sub-title">报告（RPTID）—— 事件携带的变量组</h3>
        <button
          type="button"
          disabled={activeSession === ''}
          onClick={() => {
            setEditingReport(null);
            setReportDraft({ ...EMPTY_REPORT });
            setReportEditorOpen(true);
          }}
          title="新增一个报告"
        >
          新增报告
        </button>
      </div>
      <DataTable
        tableKey="ceid-reports"
        columns={reportColumns}
        rows={reportRows}
        rowKey={(row) => String(row.rptid)}
        searchPlaceholder="搜索 RPTID / 报告名 / 变量"
        exportName="ceid-reports"
        emptyText="该设备还没有报告——事件要带数据就必须先定义报告"
        exportColumns={[
          { header: 'RPTID', value: (row) => row.rptid },
          { header: '报告名', value: (row) => row.slot },
          { header: '变量', value: (row) => row.svids.join(', ') },
        ]}
      />

      <h3 className="sub-title">压测上报（对某个事件定义循环上报）</h3>
      <div className="toolbar">
        <label className="field">
          CEID
          <input
            value={burstCeid}
            onChange={(event) => setBurstCeid(event.target.value)}
            list="ceids-burst-choices"
            inputMode="numeric"
            placeholder="如 2001"
          />
        </label>
        <datalist id="ceids-burst-choices">
          {ceidRows.map((row) => (
            <option key={row.ceid} value={row.ceid}>{row.slot}</option>
          ))}
        </datalist>
        <label className="field">
          次数
          <input value={burstCount} onChange={(event) => setBurstCount(event.target.value)} inputMode="numeric" />
        </label>
        <label className="field">
          间隔 ms
          <input value={burstInterval} onChange={(event) => setBurstInterval(event.target.value)} inputMode="numeric" title="0 = 全速" />
        </label>
        <button
          type="button"
          className="primary"
          disabled={activeSession === '' || burstRunning}
          onClick={() => void runBurst()}
          title="在服务端循环上报该 CEID(走手动上报路径,不受 events.maxPerSec 限制)"
        >
          {burstRunning ? '压测中…' : '开始压测'}
        </button>
      </div>

      <PanelFootnote
        items={[
          { label: '事件数', value: ceidRows.length },
          { label: '报告数', value: reportRows.length },
          { label: '主机定义', value: ceidRows.filter((row) => row.source === 'host').length, tone: ceidRows.some((row) => row.source === 'host') ? 'info' as never : undefined },
        ]}
      />

      {/* 事件编辑弹窗 */}
      <Modal
        open={ceidEditorOpen}
        title={editingCeid === null ? '新增采集事件' : `编辑事件 CEID ${editingCeid}`}
        onClose={() => setCeidEditorOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setCeidEditorOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={busy} onClick={() => void saveCeid()}>保存</button>
          </>
        }
      >
        <div className="form-row">
          <label className="field">
            CEID
            <input
              type="number"
              min={1}
              value={ceidDraft.id}
              onChange={(event) => setCeidDraft({ ...ceidDraft, id: Number(event.target.value) || 0 })}
              disabled={editingCeid !== null}
            />
          </label>
          <label className="field">
            事件名
            <input
              value={ceidDraft.slot}
              onChange={(event) => setCeidDraft({ ...ceidDraft, slot: event.target.value })}
              placeholder="如 LOT_START"
            />
          </label>
          <label className="field field-wide">
            绑定 RPTID（逗号分隔;事件触发时携带这些报告）
            <input
              value={ceidDraft.reports.join(', ')}
              onChange={(event) => setCeidDraft({
                ...ceidDraft,
                reports: event.target.value.split(/[,\s]+/).map((piece) => Number.parseInt(piece, 10)).filter((value) => Number.isFinite(value) && value > 0),
              })}
              placeholder="如 1, 2"
            />
          </label>
        </div>
      </Modal>

      {/* 报告编辑弹窗 */}
      <Modal
        open={reportEditorOpen}
        title={editingReport === null ? '新增报告' : `编辑报告 RPTID ${editingReport}`}
        onClose={() => setReportEditorOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setReportEditorOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={busy} onClick={() => void saveReport()}>保存</button>
          </>
        }
      >
        <div className="form-row">
          <label className="field">
            RPTID
            <input
              type="number"
              min={1}
              value={reportDraft.id}
              onChange={(event) => setReportDraft({ ...reportDraft, id: Number(event.target.value) || 0 })}
              disabled={editingReport !== null}
            />
          </label>
          <label className="field">
            报告名
            <input
              value={reportDraft.slot}
              onChange={(event) => setReportDraft({ ...reportDraft, slot: event.target.value })}
              placeholder="如 TEMP_REPORT"
            />
          </label>
          <label className="field field-wide">
            变量列表（逗号分隔的 SVID）
            <input
              value={reportDraft.svids.join(', ')}
              onChange={(event) => setReportDraft({
                ...reportDraft,
                svids: event.target.value.split(/[,\s]+/).map((piece) => Number.parseInt(piece, 10)).filter((value) => Number.isFinite(value) && value > 0),
              })}
              placeholder="如 1001, 1003"
            />
          </label>
        </div>
      </Modal>

      {/* 删除确认 */}
      <ConfirmModal
        open={deleteCeidTarget !== null}
        title="删除采集事件"
        danger
        confirmLabel="删除"
        message={deleteCeidTarget === null ? '' : `确定删除 CEID ${deleteCeidTarget}?引用它的编排计划会失效。`}
        onClose={() => setDeleteCeidTarget(null)}
        onConfirm={() => {
          if (deleteCeidTarget !== null) {
            void backend.deleteCeid(deleteCeidTarget);
          }
          setDeleteCeidTarget(null);
        }}
      />
      <ConfirmModal
        open={deleteReportTarget !== null}
        title="删除报告"
        danger
        confirmLabel="删除"
        message={deleteReportTarget === null ? '' : `确定删除 RPTID ${deleteReportTarget}?绑定它的事件将不再携带该报告。`}
        onClose={() => setDeleteReportTarget(null)}
        onConfirm={() => {
          if (deleteReportTarget !== null) {
            void backend.deleteReport(deleteReportTarget);
          }
          setDeleteReportTarget(null);
        }}
      />
    </Panel>
  );
}
