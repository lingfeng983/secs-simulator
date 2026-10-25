import { useMemo, useState } from 'react';
import type { EventEffectInput, EventPlanInput, EventPlanView } from '../api';
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

/**
 * 事件编排面板。
 *
 * <p>从「事件设置」页已定义的事件里选 CEID 做计划:<b>新增 / 编辑 / 删除</b>
 * (含效果)、启停、手动触发。这些改的是<b>运行时状态</b>,不写回配置;
 * 重载会话后按配置恢复。</p>
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

  const ceidChoices = catalog?.ceids ?? [];
  const svidChoices = catalog?.svids ?? [];

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



  const svidHint = useMemo(
    () => new Map(svidChoices.map((choice) => [choice.svid, `${choice.owner}.${choice.slot}`])),
    [svidChoices],
  );


  return (
    <Panel
      id="panel-events"
      title="事件编排"
      description="引用「事件设置」里已定义的 CEID 做计划:增删改、启停、手动触发。运行时改动不写回配置。"
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

      <PanelFootnote
        items={[
          { label: '计划数', value: eventPlans.length },
          { label: '启用中', value: enabled, tone: enabled > 0 ? 'ok' : undefined },
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
    </Panel>
  );
}
