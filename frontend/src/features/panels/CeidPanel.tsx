import { useMemo, useState } from 'react';
import type { CeidInput, ReportInput } from '../../api';
import { Panel, PanelFootnote } from '../../shared/ui/Panel';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { Modal } from '../../shared/ui/Modal';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import type { PanelBaseProps } from './types';
import { saveHint } from '../../shared/lib/saveFile';

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
export function CeidPanel({ backend, notifier, mode = 'events' }: PanelBaseProps & { mode?: 'events' | 'reports' | 'both' }): JSX.Element {
  const { catalog, activeSession } = backend;
  /** both = 一页两表（3.0 的「事件与报告」）：默认停在事件，报告一键切过去。 */
  const [pane, setPane] = useState<'events' | 'reports'>('events');
  const showEvents = mode === 'both' ? pane === 'events' : mode === 'events';
  const showReports = mode === 'both' ? pane === 'reports' : mode === 'reports';

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

  const openReportEditor = (row?: ReportRow) => {
    if (row === undefined) {
      setEditingReport(null);
      setReportDraft({ ...EMPTY_REPORT });
    } else {
      setEditingReport(row.rptid);
      setReportDraft({ id: row.rptid, slot: row.slot, svids: [...row.svids] });
    }
    setReportEditorOpen(true);
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
    { key: 'ceid', header: 'CEID', sortValue: (row) => row.ceid, render: (row) => <span className="mono">{row.ceid}</span> },
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
    { key: 'rptid', header: 'RPTID', sortValue: (row) => row.rptid, render: (row) => <span className="mono">{row.rptid}</span> },
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
            onClick={() => openReportEditor(row)}
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
      id={showEvents ? 'panel-ceids' : 'panel-reports'}
      title={mode === 'both' ? '事件与报告' : showEvents ? '事件' : '报告'}
      description={
        mode === 'both'
          ? '事件的增删改查与它携带的报告;压测上报按某个事件循环上报。运行时改动不写回配置,重载后失效。'
          : showEvents
            ? '定义采集事件(CEID)及它携带的报告;「事件编排」页从这里选事件做计划。'
            : '定义报告(RPTID)包含哪些 SVID;事件通过 RPTID 携带变量。'
      }
      actions={
        <button
          type="button"
          className="primary"
          disabled={activeSession === ''}
          onClick={() => (showEvents ? openCeidEditor(undefined) : openReportEditor(undefined))}
          title={showEvents ? '新增一个采集事件' : '新增一个报告'}
        >
          {showEvents ? '新增事件' : '新增报告'}
        </button>
      }
    >
      {mode === 'both' && (
        <div className="segmented" role="tablist" aria-label="事件 / 报告">
          <button
            type="button"
            role="tab"
            aria-selected={pane === 'events'}
            className={pane === 'events' ? 'active' : ''}
            onClick={() => setPane('events')}
          >
            事件（{ceidRows.length}）
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={pane === 'reports'}
            className={pane === 'reports' ? 'active' : ''}
            onClick={() => setPane('reports')}
          >
            报告（{reportRows.length}）
          </button>
        </div>
      )}
      {showEvents && (
        <>
      <DataTable
        tableKey="ceids"
        columns={ceidColumns}
        rows={ceidRows}
        rowKey={(row) => String(row.ceid)}
        searchPlaceholder="搜索 CEID / 事件名 / RPTID"
        exportName="ceids"
        onExported={(result) => { const hint = saveHint(result); if (hint !== '') { notifier.success('已导出', hint); } }}
        onImport={async (rows) => {
          let done = 0;
          for (const row of rows) {
            const id = Number.parseInt(row.CEID ?? '', 10);
            if (!Number.isFinite(id) || id < 1) {
              continue;
            }
            const input = {
              id,
              slot: (row['事件名'] ?? '').trim() || `CEID_${id}`,
              reports: (row['绑定 RPTID'] ?? '').split(/[,\s]+/).map((piece) => Number.parseInt(piece, 10)).filter((value) => Number.isFinite(value) && value > 0),
            };
            const exists = ceidRows.some((item) => item.ceid === id);
            const ok = exists ? await backend.updateCeid(id, input) : await backend.addCeid(input);
            if (ok) {
              done += 1;
            }
          }
          notifier.success(`已导入 ${done} 个事件`);
        }}
        emptyText={activeSession === '' ? '先在会话表里选一个会话' : '该设备还没有采集事件——点「新增事件」创建'}
        exportColumns={[
          { header: 'CEID', value: (row) => row.ceid },
          { header: '事件名', value: (row) => row.slot },
          { header: '归属', value: (row) => row.owner },
          { header: '绑定 RPTID', value: (row) => row.reports.join(', ') },
          { header: '来源', value: (row) => row.source },
        ]}
      />

      </>
      )}

      {showReports && (
        <>
      <div className="section-head" style={{ marginTop: '0.75rem' }}>
        <h3 className="sub-title">报告（RPTID）—— 事件携带的变量组</h3>
      </div>
      <DataTable
        tableKey="ceid-reports"
        columns={reportColumns}
        rows={reportRows}
        rowKey={(row) => String(row.rptid)}
        searchPlaceholder="搜索 RPTID / 报告名 / 变量"
        exportName="ceid-reports"
          onExported={(result) => { const hint = saveHint(result); if (hint !== '') { notifier.success('已导出', hint); } }}
        onImport={async (rows) => {
          let done = 0;
          for (const row of rows) {
            const id = Number.parseInt(row.RPTID ?? '', 10);
            if (!Number.isFinite(id) || id < 1) {
              continue;
            }
            const input = {
              id,
              slot: (row['报告名'] ?? '').trim() || `RPT_${id}`,
              svids: (row['变量'] ?? '').split(/[,\s]+/).map((piece) => Number.parseInt(piece, 10)).filter((value) => Number.isFinite(value) && value > 0),
            };
            const exists = reportRows.some((item) => item.rptid === id);
            const ok = exists ? await backend.updateReport(id, input) : await backend.addReport(input);
            if (ok) {
              done += 1;
            }
          }
          notifier.success(`已导入 ${done} 个报告`);
        }}
        emptyText="该设备还没有报告——事件要带数据就必须先定义报告"
        exportColumns={[
          { header: 'RPTID', value: (row) => row.rptid },
          { header: '报告名', value: (row) => row.slot },
          { header: '变量', value: (row) => row.svids.join(', ') },
        ]}
      />
        </>
      )}

      {showEvents && (
        <>
      <h3 className="sub-title">压测上报（对某个事件定义循环上报）</h3>
      <div className="toolbar">
        <label className="field">
          CEID
          <select
            value={burstCeid}
            onChange={(event) => setBurstCeid(event.target.value)}
          >
            <option value="">选择事件…</option>
            {ceidRows.map((row) => (
              <option key={row.ceid} value={row.ceid}>{row.ceid} · {row.slot}</option>
            ))}
          </select>
        </label>
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
        </>
      )}

      <PanelFootnote
        items={showEvents
          ? [
            { label: '事件数', value: ceidRows.length },
            { label: '主机定义', value: ceidRows.filter((row) => row.source === 'host').length, tone: ceidRows.some((row) => row.source === 'host') ? 'info' as never : undefined },
          ]
          : [{ label: '报告数', value: reportRows.length }]}
      />

      {/* 事件编辑弹窗 */}
      <Modal
        open={showEvents && ceidEditorOpen}
        title={editingCeid === null ? '新增采集事件' : `编辑事件 CEID ${editingCeid}`}
        onClose={() => setCeidEditorOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setCeidEditorOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={busy} onClick={() => void saveCeid()}>保存</button>
          </>
        }
      >
        <div className="form-stack">
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
          <div className="field-block">
            <span className="template-label">绑定 RPTID（从已有报告点选）</span>
            {reportRows.length === 0 ? (
              <p className="hint">还没有报告，先到「报告」页创建。</p>
            ) : (
              <div className="check-grid">
                {reportRows.map((row) => (
                  <label key={row.rptid} className="check-inline">
                    <input
                      type="checkbox"
                      checked={ceidDraft.reports.includes(row.rptid)}
                      onChange={(event) => {
                        const next = event.target.checked
                          ? [...ceidDraft.reports, row.rptid]
                          : ceidDraft.reports.filter((id) => id !== row.rptid);
                        setCeidDraft({ ...ceidDraft, reports: next });
                      }}
                    />
                    <span className="mono">{row.rptid}</span>
                    <span className="hint-inline">{row.slot}</span>
                  </label>
                ))}
              </div>
            )}
          </div>
        </div>
      </Modal>

      {/* 报告编辑弹窗 */}
      <Modal
        open={showReports && reportEditorOpen}
        title={editingReport === null ? '新增报告' : `编辑报告 RPTID ${editingReport}`}
        onClose={() => setReportEditorOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setReportEditorOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={busy} onClick={() => void saveReport()}>保存</button>
          </>
        }
      >
        <div className="form-stack">
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
          <div className="field-block">
            <span className="template-label">变量列表（从状态变量目录点选）</span>
            {(catalog?.svids ?? []).length === 0 ? (
              <p className="hint">状态变量目录为空。</p>
            ) : (
              <div className="check-grid">
                {(catalog?.svids ?? []).map((item) => (
                  <label key={item.svid} className="check-inline">
                    <input
                      type="checkbox"
                      checked={reportDraft.svids.includes(item.svid)}
                      onChange={(event) => {
                        const next = event.target.checked
                          ? [...reportDraft.svids, item.svid]
                          : reportDraft.svids.filter((id) => id !== item.svid);
                        setReportDraft({ ...reportDraft, svids: next });
                      }}
                    />
                    <span className="mono">{item.svid}</span>
                    <span className="hint-inline">{item.slot}</span>
                  </label>
                ))}
              </div>
            )}
          </div>
        </div>
      </Modal>

      {/* 删除确认 */}
      <ConfirmModal
        open={showEvents && deleteCeidTarget !== null}
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
        open={showReports && deleteReportTarget !== null}
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
