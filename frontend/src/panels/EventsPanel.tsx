import { useMemo, useState } from 'react';
import type { EventEffectInput, EventPlanInput, EventPlanView, ReportInput } from '../api';
import { Panel, PanelFootnote } from '../ui/Panel';
import { ConfirmModal } from '../ui/ConfirmModal';
import { Modal } from '../ui/Modal';
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

/** 触发类型选项(与后端 Trigger 的四种实现一一对应)。 */
const TRIGGER_TYPES = [
  ['periodic', '周期'],
  ['at', '一次性'],
  ['onCondition', '条件'],
  ['afterEvent', '事件链'],
] as const;

const OPS = ['set', 'add', 'shift', 'pulse', 'resume'] as const;

const EMPTY_PLAN: EventPlanInput = {
  id: '',
  ceid: 2001,
  triggerType: 'periodic',
  periodMs: 1000,
  jitterMs: 0,
  atMs: 0,
  expression: '',
  debounceMs: 0,
  sourcePlan: '',
  delayMs: 0,
  effects: [],
};

const EMPTY_REPORT: ReportInput = { id: 0, slot: '', svids: [] };

/** 报告表格的行(来自目录)。 */
interface ReportRow {
  rptid: number;
  slot: string;
  svids: number[];
}

/**
 * 事件编排面板。
 *
 * <p>运行时编辑:<b>新增 / 编辑 / 删除</b>计划(含效果)、启停、按计划手动触发、
 * 对某个 CEID 压测上报;报告(RPTID)归属于事件,也在这里增删改。
 * 这些改的是<b>运行时状态</b>,不写回配置;重载会话后按配置恢复。</p>
 */
export function EventsPanel({ backend, notifier }: PanelBaseProps): JSX.Element {
  const { eventPlans, catalog, activeSession } = backend;
  const enabled = eventPlans.filter((plan) => plan.enabled).length;

  /* 计划编辑 */
  const [planEditorOpen, setPlanEditorOpen] = useState(false);
  const [editingPlanId, setEditingPlanId] = useState<string | null>(null);
  const [planDraft, setPlanDraft] = useState<EventPlanInput>(EMPTY_PLAN);
  const [planBusy, setPlanBusy] = useState(false);
  const [deletePlanId, setDeletePlanId] = useState<string | null>(null);

  /* 压测上报 */
  const [burstCeid, setBurstCeid] = useState('');
  const [burstCount, setBurstCount] = useState('100');
  const [burstInterval, setBurstInterval] = useState('0');
  const [burstRunning, setBurstRunning] = useState(false);

  /* 报告编辑 */
  const [reportEditorOpen, setReportEditorOpen] = useState(false);
  const [editingReportId, setEditingReportId] = useState<number | null>(null);
  const [reportDraft, setReportDraft] = useState<ReportInput>(EMPTY_REPORT);
  const [deleteReportId, setDeleteReportId] = useState<number | null>(null);

  const ceidChoices = catalog?.ceids ?? [];
  const svidChoices = catalog?.svids ?? [];
  const reportRows: ReportRow[] = useMemo(
    () => (catalog?.reports ?? []).map((report) => ({ rptid: report.rptid, slot: report.slot, svids: [...report.svids] })),
    [catalog],
  );

  const columns: Column<EventPlanView>[] = [
    { key: 'id', header: '计划', sortValue: (row) => row.id, render: (row) => <span className="mono">{row.id}</span> },
    { key: 'ceid', header: 'CEID', align: 'right', sortValue: (row) => row.ceid, render: (row) => row.ceid },
    { key: 'trigger', header: '触发规则', sortValue: (row) => triggerLabel(row), render: (row) => triggerLabel(row) },
    { key: 'effects', header: '效果', sortValue: (row) => row.effects?.length ?? 0, render: (row) => (row.effects?.length ?? 0) > 0 ? `${row.effects!.length} 项` : '—' },
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
        <div className="row-actions">
          <button
            type="button"
            onClick={() => void backend.trigger(activeSession, row.ceid)}
            disabled={activeSession === ''}
            title="手动触发一次"
          >
            触发
          </button>
          <button
            type="button"
            onClick={() => void backend.toggleEvent(row)}
            disabled={activeSession === ''}
            title={row.enabled ? '禁用后不再触发;已排队的延迟事件会清掉' : '重新启用;禁用期间的事件不补发'}
          >
            {row.enabled ? '禁用' : '启用'}
          </button>
          <button type="button" onClick={() => openPlanEditor(row)} title="编辑计划(触发规则与效果)">
            编辑
          </button>
          <button type="button" className="danger" onClick={() => setDeletePlanId(row.id)} title="删除计划">
            删除
          </button>
        </div>
      ),
    },
  ];

  const openPlanEditor = (plan?: EventPlanView) => {
    if (plan === undefined) {
      setEditingPlanId(null);
      setPlanDraft({ ...EMPTY_PLAN, id: `plan-${Date.now()}` });
    } else {
      setEditingPlanId(plan.id);
      setPlanDraft({
        id: plan.id,
        ceid: plan.ceid,
        triggerType: (plan.triggerType as EventPlanInput['triggerType']) ?? 'periodic',
        atMs: plan.atMs ?? 0,
        periodMs: plan.periodMs ?? 1000,
        jitterMs: plan.jitterMs ?? 0,
        expression: plan.expression ?? '',
        debounceMs: plan.debounceMs ?? 0,
        sourcePlan: plan.sourcePlan ?? '',
        delayMs: plan.delayMs ?? 0,
        effects: (plan.effects ?? []).map((effect) => ({ ...effect })),
      });
    }
    setPlanEditorOpen(true);
  };

  const savePlan = async () => {
    if (planDraft.id.trim() === '') {
      notifier.error('计划标识不能为空');
      return;
    }
    setPlanBusy(true);
    try {
      const ok = editingPlanId === null
        ? await backend.addEventPlan({ ...planDraft, id: planDraft.id.trim() })
        : await backend.updateEventPlan(editingPlanId, { ...planDraft, id: editingPlanId });
      if (ok) {
        setPlanEditorOpen(false);
      }
    } finally {
      setPlanBusy(false);
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

  const saveReport = async () => {
    if (!Number.isFinite(reportDraft.id) || reportDraft.id < 1) {
      notifier.error('RPTID 必须是正整数');
      return;
    }
    if (reportDraft.slot.trim() === '') {
      notifier.error('报告名不能为空');
      return;
    }
    setPlanBusy(true);
    try {
      const ok = editingReportId === null
        ? await backend.addReport({ ...reportDraft, slot: reportDraft.slot.trim() })
        : await backend.updateReport(editingReportId, { ...reportDraft, slot: reportDraft.slot.trim() });
      if (ok) {
        setReportEditorOpen(false);
      }
    } finally {
      setPlanBusy(false);
    }
  };

  const svidHint = useMemo(
    () => new Map(svidChoices.map((choice) => [choice.svid, `${choice.owner}.${choice.slot}`])),
    [svidChoices],
  );

  const reportColumns: Column<ReportRow>[] = [
    { key: 'rptid', header: 'RPTID', align: 'right', sortValue: (row) => row.rptid, render: (row) => <span className="mono">{row.rptid}</span> },
    { key: 'slot', header: '名称', sortValue: (row) => row.slot, render: (row) => row.slot },
    { key: 'svids', header: '变量', sortValue: (row) => row.svids.length, render: (row) => <span className="mono">{row.svids.join(', ')}</span> },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          <button
            type="button"
            onClick={() => {
              setEditingReportId(row.rptid);
              setReportDraft({ id: row.rptid, slot: row.slot, svids: [...row.svids] });
              setReportEditorOpen(true);
            }}
            title="编辑报告的变量列表"
          >
            编辑
          </button>
          <button type="button" className="danger" onClick={() => setDeleteReportId(row.rptid)} title="删除报告">
            删除
          </button>
        </div>
      ),
    },
  ];

  return (
    <Panel
      id="panel-events"
      title="事件编排"
      description="计划的增删改、启停、手动触发与压测;报告(RPTID)归属于事件,一并管理。运行时改动不写回配置。"
      actions={
        <button
          type="button"
          className="primary"
          disabled={activeSession === ''}
          onClick={() => openPlanEditor(undefined)}
          title="新增一条编排计划"
        >
          新增计划
        </button>
      }
    >
      <DataTable
        tableKey="events"
        columns={columns}
        rows={eventPlans}
        rowKey={(row) => row.id}
        searchPlaceholder="搜索计划 / CEID / 触发规则"
        exportName="events"
        emptyText={activeSession === '' ? '暂无会话' : '该设备还没有编排计划——点「新增计划」创建'}
        exportColumns={[
          { header: '计划', value: (row) => row.id },
          { header: 'CEID', value: (row) => row.ceid },
          { header: '触发规则', value: (row) => triggerLabel(row) },
          { header: '状态', value: (row) => (row.enabled ? '启用' : '禁用') },
        ]}
      />
      <p className="hint">
        这里改的是<b>运行时状态</b>，不写回配置；重载会话会恢复配置里的编排。
      </p>

      {/* 压测上报 */}
      <h3 className="sub-title">压测上报</h3>
      <div className="toolbar">
        <label className="field">
          CEID
          <input
            value={burstCeid}
            onChange={(event) => setBurstCeid(event.target.value)}
            list="events-ceid-choices"
            inputMode="numeric"
            placeholder="如 2001"
          />
        </label>
        <datalist id="events-ceid-choices">
          {ceidChoices.map((choice) => (
            <option key={choice.ceid} value={choice.ceid}>
              {choice.slot}
            </option>
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
          title="在服务端循环上报同一个 CEID(走手动上报路径,不受 events.maxPerSec 限制)"
        >
          {burstRunning ? '压测中…' : '开始压测'}
        </button>
      </div>

      {/* 报告管理 */}
      <div className="section-head" style={{ marginTop: '0.75rem' }}>
        <h3 className="sub-title">报告（RPTID）</h3>
        <button
          type="button"
          disabled={activeSession === ''}
          onClick={() => {
            setEditingReportId(null);
            setReportDraft({ ...EMPTY_REPORT });
            setReportEditorOpen(true);
          }}
          title="新增一个报告"
        >
          新增报告
        </button>
      </div>
      <DataTable
        tableKey="event-reports"
        columns={reportColumns}
        rows={reportRows}
        rowKey={(row) => String(row.rptid)}
        searchPlaceholder="搜索 RPTID / 名称 / 变量"
        exportName="event-reports"
        emptyText="该设备还没有报告定义——点「新增报告」创建"
        exportColumns={[
          { header: 'RPTID', value: (row) => row.rptid },
          { header: '名称', value: (row) => row.slot },
          { header: '变量', value: (row) => row.svids.join(', ') },
        ]}
      />

      <PanelFootnote
        items={[
          { label: '计划数', value: eventPlans.length },
          { label: '启用中', value: enabled, tone: enabled > 0 ? 'ok' : undefined },
          { label: '报告数', value: reportRows.length },
        ]}
      />

      {/* 计划编辑弹窗 */}
      <Modal
        open={planEditorOpen}
        title={editingPlanId === null ? '新增编排计划' : `编辑计划 ${editingPlanId}`}
        onClose={() => setPlanEditorOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setPlanEditorOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={planBusy} onClick={() => void savePlan()}>保存</button>
          </>
        }
      >
        <div className="form-row">
          <label className="field">
            计划标识
            <input
              value={planDraft.id}
              onChange={(event) => setPlanDraft({ ...planDraft, id: event.target.value })}
              disabled={editingPlanId !== null}
              placeholder="如 plan-1"
            />
          </label>
          <label className="field">
            CEID
            <input
              type="number"
              min={1}
              value={planDraft.ceid}
              onChange={(event) => setPlanDraft({ ...planDraft, ceid: Number(event.target.value) || 1 })}
              list="plan-ceid-choices"
            />
          </label>
          <datalist id="plan-ceid-choices">
            {ceidChoices.map((choice) => (
              <option key={choice.ceid} value={choice.ceid}>{choice.slot}</option>
            ))}
          </datalist>
          <label className="field">
            触发类型
            <select
              value={planDraft.triggerType}
              onChange={(event) => setPlanDraft({ ...planDraft, triggerType: event.target.value as EventPlanInput['triggerType'] })}
            >
              {TRIGGER_TYPES.map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>
        </div>
        <div className="form-row">
          {planDraft.triggerType === 'periodic' && (
            <>
              <label className="field">
                周期 ms
                <input
                  type="number"
                  min={1}
                  value={planDraft.periodMs}
                  onChange={(event) => setPlanDraft({ ...planDraft, periodMs: Number(event.target.value) || 1 })}
                />
              </label>
              <label className="field">
                抖动 ms
                <input
                  type="number"
                  min={0}
                  value={planDraft.jitterMs}
                  onChange={(event) => setPlanDraft({ ...planDraft, jitterMs: Number(event.target.value) || 0 })}
                />
              </label>
            </>
          )}
          {planDraft.triggerType === 'at' && (
            <label className="field">
              触发时刻 ms（相对仿真起点）
              <input
                type="number"
                min={0}
                value={planDraft.atMs}
                onChange={(event) => setPlanDraft({ ...planDraft, atMs: Number(event.target.value) || 0 })}
              />
            </label>
          )}
          {planDraft.triggerType === 'onCondition' && (
            <>
              <label className="field field-wide">
                条件表达式（变量支持 t 与 svid.&lt;id&gt;）
                <input
                  value={planDraft.expression}
                  onChange={(event) => setPlanDraft({ ...planDraft, expression: event.target.value })}
                  placeholder='如 svid.1001 > 80'
                />
              </label>
              <label className="field">
                去抖 ms
                <input
                  type="number"
                  min={0}
                  value={planDraft.debounceMs}
                  onChange={(event) => setPlanDraft({ ...planDraft, debounceMs: Number(event.target.value) || 0 })}
                />
              </label>
            </>
          )}
          {planDraft.triggerType === 'afterEvent' && (
            <>
              <label className="field">
                源计划
                <input
                  value={planDraft.sourcePlan}
                  onChange={(event) => setPlanDraft({ ...planDraft, sourcePlan: event.target.value })}
                />
              </label>
              <label className="field">
                延迟 ms
                <input
                  type="number"
                  min={0}
                  value={planDraft.delayMs}
                  onChange={(event) => setPlanDraft({ ...planDraft, delayMs: Number(event.target.value) || 0 })}
                />
              </label>
            </>
          )}
        </div>

        {/* 效果编辑 */}
        <div className="section-head">
          <h4 style={{ margin: 0 }}>触发效果</h4>
          <button
            type="button"
            onClick={() => setPlanDraft({
              ...planDraft,
              effects: [...(planDraft.effects ?? []), { svid: svidChoices[0]?.svid ?? 1001, op: 'set', value: 0 }],
            })}
          >
            + 效果
          </button>
        </div>
        {(planDraft.effects ?? []).length === 0 && <p className="hint">无效果:只上报,不改变量的值。</p>}
        {(planDraft.effects ?? []).map((effect, index) => (
          <div className="toolbar" key={index}>
            <label className="field">
              SVID
              <input
                type="number"
                min={1}
                value={effect.svid}
                onChange={(event) => {
                  const effects = [...planDraft.effects!];
                  effects[index] = { ...effect, svid: Number(event.target.value) || 1 };
                  setPlanDraft({ ...planDraft, effects });
                }}
              />
            </label>
            {svidHint.get(effect.svid) !== undefined && <span className="hint-inline">{svidHint.get(effect.svid)}</span>}
            <label className="field">
              类型
              <select
                value={effect.op}
                onChange={(event) => {
                  const effects = [...planDraft.effects!];
                  effects[index] = { svid: effect.svid, op: event.target.value as EventEffectInput['op'] };
                  setPlanDraft({ ...planDraft, effects });
                }}
              >
                {OPS.map((op) => <option key={op} value={op}>{op}</option>)}
              </select>
            </label>
            {(effect.op === 'set' || effect.op === 'pulse') && (
              <label className="field">
                值
                <input
                  type="number"
                  value={effect.value ?? 0}
                  onChange={(event) => {
                    const effects = [...planDraft.effects!];
                    effects[index] = { ...effect, value: Number(event.target.value) };
                    setPlanDraft({ ...planDraft, effects });
                  }}
                />
              </label>
            )}
            {effect.op === 'pulse' && (
              <label className="field">
                保持 ms
                <input
                  type="number"
                  min={0}
                  value={effect.holdMs ?? 1000}
                  onChange={(event) => {
                    const effects = [...planDraft.effects!];
                    effects[index] = { ...effect, holdMs: Number(event.target.value) || 0 };
                    setPlanDraft({ ...planDraft, effects });
                  }}
                />
              </label>
            )}
            {(effect.op === 'add' || effect.op === 'shift') && (
              <label className="field">
                增量
                <input
                  type="number"
                  value={effect.delta ?? 0}
                  onChange={(event) => {
                    const effects = [...planDraft.effects!];
                    effects[index] = { ...effect, delta: Number(event.target.value) };
                    setPlanDraft({ ...planDraft, effects });
                  }}
                />
              </label>
            )}
            {effect.op === 'set' && (
              <label className="field">
                衰减 ms（0 = 不衰减）
                <input
                  type="number"
                  min={0}
                  value={effect.decayMs ?? 0}
                  onChange={(event) => {
                    const effects = [...planDraft.effects!];
                    effects[index] = { ...effect, decayMs: Number(event.target.value) || undefined };
                    setPlanDraft({ ...planDraft, effects });
                  }}
                />
              </label>
            )}
            <button
              type="button"
              className="danger"
              onClick={() => setPlanDraft({ ...planDraft, effects: planDraft.effects!.filter((_, i) => i !== index) })}
            >
              移除
            </button>
          </div>
        ))}
      </Modal>

      {/* 报告编辑弹窗 */}
      <Modal
        open={reportEditorOpen}
        title={editingReportId === null ? '新增报告' : `编辑报告 RPTID ${editingReportId}`}
        onClose={() => setReportEditorOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setReportEditorOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={planBusy} onClick={() => void saveReport()}>保存</button>
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
              disabled={editingReportId !== null}
            />
          </label>
          <label className="field">
            名称
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
        open={deletePlanId !== null}
        title="删除编排计划"
        danger
        confirmLabel="删除"
        message={deletePlanId === null ? '' : `确定删除计划「${deletePlanId}」?其待触发队列与去抖状态一并清除。`}
        onClose={() => setDeletePlanId(null)}
        onConfirm={() => {
          if (deletePlanId !== null) {
            void backend.deleteEventPlan(deletePlanId);
          }
          setDeletePlanId(null);
        }}
      />
      <ConfirmModal
        open={deleteReportId !== null}
        title="删除报告"
        danger
        confirmLabel="删除"
        message={deleteReportId === null ? '' : `确定删除报告 RPTID ${deleteReportId}?`}
        onClose={() => setDeleteReportId(null)}
        onConfirm={() => {
          if (deleteReportId !== null) {
            void backend.deleteReport(deleteReportId);
          }
          setDeleteReportId(null);
        }}
      />
    </Panel>
  );
}
