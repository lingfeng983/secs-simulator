import { useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { Panel } from '../../shared/ui/Panel';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { Modal } from '../../shared/ui/Modal';
import { FieldLabel } from '../../shared/ui/FieldLabel';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import type { HostsV2State, SaveHostTemplateRequest } from '../../state/useHostsV2';
import type { HostTemplate } from '../../state/hostTemplates';
import { templatePlanCount } from '../../state/hostTemplates';
import { parseIdList, planId, type CollectPlan, type TracePlan } from '../../state/hostPlans';

/**
 * 独立工具 · 主机模板：一组可复用的 Trace / S1F3 采集方案。
 *
 * <p>目标地址、端口、Device ID 是「某一台主机实例」的设置，不是模板内容，
 * 所以新建/编辑模板的弹框里没有它们。模板里加的是方案；套用模板时把方案
 * 复制进新主机的配置区。内置两份默认模板只读，编辑后另存为自定义模板。</p>
 */
export function HostTemplatePage({
  hosts,
  notifier,
}: PanelBaseProps & { hosts: HostsV2State }): JSX.Element {
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<HostTemplate | null>(null);
  /** 套用模板建主机：模板选方案，实例的目标信息在这一个弹框里填。 */
  const [createHostFrom, setCreateHostFrom] = useState<HostTemplate | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  /** 按模板新建一台主机：方案复制进新主机的方案库。 */
  const createFromTemplate = (
    template: HostTemplate,
    target: { name: string; host: string; port: number; deviceId: number; connect: boolean },
  ) => {
    setBusy(true);
    setError('');
    hosts.create({ ...target, template })
      .then(() => {
        setCreateHostFrom(null);
        notifier.success(
          `已按模板「${template.name}」新建主机`,
          `复制了 ${templatePlanCount(template)} 份方案，可在主机配置区直接使用或修改`,
        );
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  const columns: Column<HostTemplate>[] = [
    {
      key: 'name',
      header: '模板名',
      sortValue: (row) => row.name,
      render: (row) => (
        <>
          {row.name}
          {row.builtin && <span className="badge tone-muted" style={{ marginLeft: 6 }}>内置</span>}
        </>
      ),
    },
    {
      key: 'trace',
      header: 'Trace 方案',
      sortValue: (row) => row.tracePlans.length,
      render: (row) => (row.tracePlans.length === 0
        ? <span className="hint-inline">—</span>
        : <span>{row.tracePlans.length} 份 · {row.tracePlans.map((plan) => plan.name).join('、')}</span>),
    },
    {
      key: 'collect',
      header: '采集方案',
      sortValue: (row) => row.collectPlans.length,
      render: (row) => (row.collectPlans.length === 0
        ? <span className="hint-inline">—</span>
        : <span>{row.collectPlans.length} 份 · {row.collectPlans.map((plan) => plan.name).join('、')}</span>),
    },
    { key: 'note', header: '说明', sortValue: (row) => row.note },
    {
      key: 'actions',
      header: '操作',
      width: '220px',
      render: (row) => (
        <div className="row-actions">
          <button
            type="button"
            disabled={busy}
            onClick={() => setCreateHostFrom(row)}
            title="新建一台主机，并把模板里的方案复制进它的配置区"
          >
            套用新建
          </button>
          <button type="button" onClick={() => setEditing(row)}>编辑</button>
          {!row.builtin && (
            <button type="button" className="danger" onClick={() => setDeleteId(row.id)}>删除</button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="panel-stack">
      <Panel id="panel-host-templates" title="主机模板" headless>
        {error !== '' && <p className="inline-note danger">{error}</p>}
        <DataTable
          tableKey="host-templates"
          columns={columns}
          rows={hosts.templates}
          rowKey={(row) => row.id}
          searchPlaceholder="搜索模板名 / 方案 / 说明"
          emptyText="还没有主机模板。点「＋ 新建模板」把常用方案存成模板。"
          /* 新增按钮靠右：左边是搜索，右边是"动手做什么" */
          toolbarEnd={
            <button type="button" className="primary" onClick={() => setCreateOpen(true)}>
              ＋ 新建模板
            </button>
          }
        />
      </Panel>

      <HostTemplateForm
        key={editing?.id ?? (createOpen ? 'new' : 'closed')}
        open={createOpen || editing !== null}
        editing={editing}
        busy={busy}
        error={error}
        onClose={() => {
          setCreateOpen(false);
          setEditing(null);
          setError('');
        }}
        onSubmit={async (request) => {
          setBusy(true);
          setError('');
          try {
            await hosts.saveTemplate(request);
            setCreateOpen(false);
            setEditing(null);
            notifier.success('已保存主机模板', `Trace ${request.tracePlans.length} 份 · 采集 ${request.collectPlans.length} 份`);
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      />

      <CreateHostFromTemplateModal
        key={createHostFrom?.id ?? 'none'}
        template={createHostFrom}
        busy={busy}
        error={error}
        onClose={() => { setCreateHostFrom(null); setError(''); }}
        onCreate={(target) => {
          if (createHostFrom !== null) {
            createFromTemplate(createHostFrom, target);
          }
        }}
      />

      <ConfirmModal
        open={deleteId !== null}
        title="删除模板"
        danger
        confirmLabel="删除"
        message={deleteId === null ? '' : `确定删除模板 ${deleteId}？已套用过的主机方案不受影响。`}
        onClose={() => setDeleteId(null)}
        onConfirm={() => {
          if (deleteId !== null) {
            void hosts.deleteTemplate(deleteId);
          }
          setDeleteId(null);
        }}
      />
    </div>
  );
}

/* ---------- 新建 / 编辑模板弹框 ---------- */

interface TracePlanFields {
  name: string;
  trid: string;
  dsper: string;
  totsmp: string;
  repgsz: string;
  svids: string;
}

interface CollectPlanFields {
  name: string;
  startCeid: string;
  stopCeid: string;
  intervalMs: string;
  svids: string;
}

/** 弹框内正在新增/修改的那一份方案；id 非空 = 改已有方案。 */
type PlanDraft =
  | { kind: 'trace'; id: string | null; fields: TracePlanFields }
  | { kind: 'collect'; id: string | null; fields: CollectPlanFields };

const EMPTY_TRACE: TracePlanFields = {
  name: '', trid: '1', dsper: '1000', totsmp: '100', repgsz: '1', svids: '1001, 1003',
};
const EMPTY_COLLECT: CollectPlanFields = {
  name: '', startCeid: '2001', stopCeid: '2002', intervalMs: '1000', svids: '1001, 1003',
};

function traceFields(plan: TracePlan): TracePlanFields {
  return {
    name: plan.name,
    trid: String(plan.trid),
    dsper: String(plan.dsper),
    totsmp: String(plan.totsmp),
    repgsz: String(plan.repgsz),
    svids: plan.svids,
  };
}

function collectFields(plan: CollectPlan): CollectPlanFields {
  return {
    name: plan.name,
    startCeid: plan.startCeid,
    stopCeid: plan.stopCeid,
    intervalMs: plan.intervalMs,
    svids: plan.svids,
  };
}

/** 新建/编辑主机模板：只维护方案包，不出现任何实例目标信息。 */
function HostTemplateForm({
  open,
  editing,
  busy,
  error,
  onClose,
  onSubmit,
}: {
  open: boolean;
  editing: HostTemplate | null;
  busy: boolean;
  error: string;
  onClose: () => void;
  onSubmit: (request: SaveHostTemplateRequest) => void;
}): JSX.Element {
  const [name, setName] = useState(editing === null ? '' : editing.builtin ? `${editing.name} 副本` : editing.name);
  const [note, setNote] = useState(editing?.note ?? '');
  const [tracePlans, setTracePlans] = useState<TracePlan[]>(editing?.tracePlans ?? []);
  const [collectPlans, setCollectPlans] = useState<CollectPlan[]>(editing?.collectPlans ?? []);
  const [draft, setDraft] = useState<PlanDraft | null>(null);
  const [problems, setProblems] = useState<string[]>([]);

  /** 方案名在两类方案里都有；分开收窄后再写，避免联合类型丢掉字段关联。 */
  const updateDraftName = (value: string) => {
    setDraft((previous) => {
      if (previous === null) {
        return null;
      }
      return previous.kind === 'trace'
        ? { ...previous, fields: { ...previous.fields, name: value } }
        : { ...previous, fields: { ...previous.fields, name: value } };
    });
  };

  /** 提交一份方案到当前模板的方案列表。 */
  const commitPlan = () => {
    if (draft === null) {
      return;
    }
    const errors: string[] = [];
    if (draft.kind === 'trace') {
      const f = draft.fields;
      if (!(Number(f.trid) >= 1)) { errors.push('TRID 需为正整数'); }
      if (!(Number(f.dsper) >= 1000)) { errors.push('采样周期需 ≥ 1000ms（DSPER 按整秒下发）'); }
      if (!(Number(f.totsmp) >= 0)) { errors.push('总样本数不能为负'); }
      if (!(Number(f.repgsz) >= 1)) { errors.push('每条样本数需 ≥ 1'); }
      if (parseIdList(f.svids).length === 0) { errors.push('至少填一个 SVID'); }
      if (errors.length > 0) { setProblems(errors); return; }
      const plan: TracePlan = {
        id: draft.id ?? planId(),
        name: f.name.trim() || `TRID ${f.trid} · ${f.dsper}ms`,
        trid: Number(f.trid),
        dsper: Number(f.dsper),
        totsmp: Number(f.totsmp),
        repgsz: Number(f.repgsz),
        svids: f.svids,
        createdAt: Date.now(),
      };
      setTracePlans((previous) => (
        draft.id === null
          ? [...previous, plan]
          : previous.map((item) => (item.id === draft.id ? plan : item))
      ));
    } else {
      const f = draft.fields;
      if (!(Number(f.startCeid) > 0)) { errors.push('启动 CEID 需为正整数'); }
      if (!(Number(f.stopCeid) > 0)) { errors.push('停止 CEID 需为正整数'); }
      if (!(Number(f.intervalMs) >= 100)) { errors.push('采集周期需 ≥ 100ms'); }
      if (parseIdList(f.svids).length === 0) { errors.push('至少填一个 SVID'); }
      if (errors.length > 0) { setProblems(errors); return; }
      const plan: CollectPlan = {
        id: draft.id ?? planId(),
        name: f.name.trim() || `CEID ${f.startCeid} · ${f.intervalMs}ms`,
        startCeid: f.startCeid,
        stopCeid: f.stopCeid,
        intervalMs: f.intervalMs,
        svids: f.svids,
        createdAt: Date.now(),
      };
      setCollectPlans((previous) => (
        draft.id === null
          ? [...previous, plan]
          : previous.map((item) => (item.id === draft.id ? plan : item))
      ));
    }
    setDraft(null);
    setProblems([]);
  };

  const valid = name.trim() !== '' && tracePlans.length + collectPlans.length > 0;

  return (
    <Modal
      open={open}
      title={editing === null ? '新建主机模板' : `编辑模板 · ${editing.name}`}
      hint={`模板里只存 Trace 与 S1F3 采集方案。目标地址、端口、Device ID 是每台主机自己的设置，新建主机时填写。${editing?.builtin === true ? ' 内置模板只读，保存会生成一份自定义副本。' : ''}方案先加入模板，点弹框底部的「保存模板」才会写入。`}
      onClose={onClose}
      width="min(60rem, 100%)"
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button
            type="button"
            className="primary"
            disabled={busy || !valid || draft !== null}
            onClick={() => onSubmit({
              /* 内置模板不能覆盖：不带 id，服务端会新建一份自定义模板 */
              id: editing?.builtin === true ? undefined : editing?.id,
              name: name.trim(),
              note: note.trim() === '' ? undefined : note.trim(),
              tracePlans,
              collectPlans,
            })}
          >
            {editing?.builtin === true ? '另存为模板' : '保存模板'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <label className="field">
          模板名
          <input value={name} onChange={(event) => setName(event.target.value)} placeholder="例如 产线 B 采集" />
        </label>
        <label className="field">
          说明
          <input value={note} onChange={(event) => setNote(event.target.value)} placeholder="可选" />
        </label>
      </div>
      {error !== '' && <p className="inline-note danger">{error}</p>}
      {problems.length > 0 && (
        <ul className="problems">{problems.map((problem, index) => <li key={index}>{problem}</li>)}</ul>
      )}
      {!valid && tracePlans.length + collectPlans.length === 0 && (
        <p className="problems">至少添加一份 Trace 方案或采集方案才能保存模板。</p>
      )}

      <section className="panel-section">
        <div className="section-head">
          <h2>Trace 方案（S2F23 → S6F1）</h2>
          <button type="button" onClick={() => { setDraft({ kind: 'trace', id: null, fields: { ...EMPTY_TRACE } }); setProblems([]); }}>
            ＋ 新增 Trace 方案
          </button>
        </div>
        {tracePlans.length === 0 ? (
          <p className="hint">还没有 Trace 方案。点「新增 Trace 方案」定义采样周期、样本数与变量。</p>
        ) : (
          <table className="effects-table">
            <thead>
              <tr><th>方案</th><th>TRID</th><th>周期</th><th>样本数</th><th>SVID</th><th>操作</th></tr>
            </thead>
            <tbody>
              {tracePlans.map((plan) => (
                <tr key={plan.id}>
                  <td>{plan.name}</td>
                  <td className="mono">{plan.trid}</td>
                  <td className="mono">{plan.dsper}ms</td>
                  <td className="mono">{plan.totsmp}</td>
                  <td className="mono hint-inline">{plan.svids}</td>
                  <td>
                    <div className="row-actions">
                      <button type="button" onClick={() => { setDraft({ kind: 'trace', id: plan.id, fields: traceFields(plan) }); setProblems([]); }}>编辑</button>
                      <button type="button" className="danger" onClick={() => setTracePlans((previous) => previous.filter((item) => item.id !== plan.id))}>删除</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="panel-section">
        <div className="section-head">
          <h2>采集方案（S1F3 轮询）</h2>
          <button type="button" onClick={() => { setDraft({ kind: 'collect', id: null, fields: { ...EMPTY_COLLECT } }); setProblems([]); }}>
            ＋ 新增采集方案
          </button>
        </div>
        {collectPlans.length === 0 ? (
          <p className="hint">还没有采集方案。点「新增采集方案」定义启停 CEID、周期与变量。</p>
        ) : (
          <table className="effects-table">
            <thead>
              <tr><th>方案</th><th>启动 / 停止 CEID</th><th>周期</th><th>SVID</th><th>操作</th></tr>
            </thead>
            <tbody>
              {collectPlans.map((plan) => (
                <tr key={plan.id}>
                  <td>{plan.name}</td>
                  <td className="mono">{plan.startCeid} / {plan.stopCeid}</td>
                  <td className="mono">{plan.intervalMs}ms</td>
                  <td className="mono hint-inline">{plan.svids}</td>
                  <td>
                    <div className="row-actions">
                      <button type="button" onClick={() => { setDraft({ kind: 'collect', id: plan.id, fields: collectFields(plan) }); setProblems([]); }}>编辑</button>
                      <button type="button" className="danger" onClick={() => setCollectPlans((previous) => previous.filter((item) => item.id !== plan.id))}>删除</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {draft !== null && (
        <section className="panel-section template-card">
          <div className="section-head">
            <h3>{draft.id === null ? '新增' : '编辑'}{draft.kind === 'trace' ? ' Trace 方案' : ' 采集方案'}</h3>
          </div>
          <div className="form-row">
            <label className="field field-wide">
              方案名称（留空自动生成）
              <input
                value={draft.fields.name}
                onChange={(event) => updateDraftName(event.target.value)}
                placeholder={draft.kind === 'trace' ? '如：温度曲线 1s' : '如：温度+压力 1s'}
              />
            </label>
          </div>
          {draft.kind === 'trace' ? (
            <>
              <div className="form-row">
                <label className="field">TRID
                  <input type="number" min={1} value={draft.fields.trid}
                    onChange={(event) => setDraft({ ...draft, fields: { ...draft.fields, trid: event.target.value } })} />
                </label>
                <label className="field">采样周期 ms
                  <input type="number" min={1000} step={1000} value={draft.fields.dsper}
                    onChange={(event) => setDraft({ ...draft, fields: { ...draft.fields, dsper: event.target.value } })} />
                </label>
                <label className="field">总样本数
                  <input type="number" min={0} value={draft.fields.totsmp}
                    onChange={(event) => setDraft({ ...draft, fields: { ...draft.fields, totsmp: event.target.value } })} />
                </label>
                <label className="field">每条样本数
                  <input type="number" min={1} value={draft.fields.repgsz}
                    onChange={(event) => setDraft({ ...draft, fields: { ...draft.fields, repgsz: event.target.value } })} />
                </label>
              </div>
              <label className="field field-wide">
                采集变量（逗号/空格分隔）
                <input value={draft.fields.svids}
                  onChange={(event) => setDraft({ ...draft, fields: { ...draft.fields, svids: event.target.value } })} />
              </label>
            </>
          ) : (
            <>
              <div className="form-row">
                <label className="field">启动 CEID
                  <input value={draft.fields.startCeid}
                    onChange={(event) => setDraft({ ...draft, fields: { ...draft.fields, startCeid: event.target.value } })} />
                </label>
                <label className="field">停止 CEID
                  <input value={draft.fields.stopCeid}
                    onChange={(event) => setDraft({ ...draft, fields: { ...draft.fields, stopCeid: event.target.value } })} />
                </label>
                <label className="field">周期 ms
                  <input value={draft.fields.intervalMs}
                    onChange={(event) => setDraft({ ...draft, fields: { ...draft.fields, intervalMs: event.target.value } })} />
                </label>
              </div>
              <label className="field field-wide">
                采集变量（逗号/空格分隔）
                <input value={draft.fields.svids}
                  onChange={(event) => setDraft({ ...draft, fields: { ...draft.fields, svids: event.target.value } })} />
              </label>
            </>
          )}
          <div className="editor-foot">
            <button type="button" onClick={() => { setDraft(null); setProblems([]); }}>取消</button>
            <button type="button" className="primary" onClick={commitPlan}>加入模板</button>
          </div>
        </section>
      )}
    </Modal>
  );
}

/**
 * 「套用新建」弹框：目标地址 / 端口 / Device ID 是主机实例自己的设置，
 * 只在真正建主机时问一次；模板里的方案作为只读摘要展示。
 */
function CreateHostFromTemplateModal({
  template,
  busy,
  error,
  onClose,
  onCreate,
}: {
  template: HostTemplate | null;
  busy: boolean;
  error: string;
  onClose: () => void;
  onCreate: (target: { name: string; host: string; port: number; deviceId: number; connect: boolean }) => void;
}): JSX.Element {
  const [name, setName] = useState(template?.name ?? 'host');
  const [host, setHost] = useState('127.0.0.1');
  const [port, setPort] = useState('5000');
  const [deviceId, setDeviceId] = useState('0');
  /* 「立即连接」开关：默认开，与主机清单页的新建弹窗同语义 */
  const [connectNow, setConnectNow] = useState(true);
  const valid = host.trim() !== '' && Number(port) >= 1 && Number(port) <= 65535;

  return (
    <Modal
      open={template !== null}
      title={template === null ? '' : `按模板「${template.name}」新建主机`}
      hint={`将复制 ${template === null ? 0 : template.tracePlans.length} 份 Trace 方案、${template === null ? 0 : template.collectPlans.length} 份采集方案到这台主机。下面是这台主机自己的目标信息，不属于模板。`}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button
            type="button"
            className="primary"
            disabled={busy || !valid}
            onClick={() => onCreate({
              name: name.trim() === '' ? 'host' : name.trim(),
              host: host.trim(),
              port: Number(port),
              deviceId: Number(deviceId) || 0,
              connect: connectNow,
            })}
          >
            创建主机
          </button>
        </>
      }
    >
      {error !== '' && <p className="inline-note danger">{error}</p>}
      <div className="form-row">
        <label className="field">
          名称
          <input value={name} onChange={(event) => setName(event.target.value)} />
        </label>
        <label className="field">
          目标地址
          <input value={host} onChange={(event) => setHost(event.target.value)} />
        </label>
      </div>
      <div className="form-row">
        <label className="field">
          端口
          <input type="number" min={1} max={65535} value={port} onChange={(event) => setPort(event.target.value)} />
        </label>
        <label className="field">
          Device ID
          <input type="number" min={0} value={deviceId} onChange={(event) => setDeviceId(event.target.value)} />
        </label>
      </div>
      <div className="form-row">
        <label className="field modal-field">
          <FieldLabel
            text="立即连接"
            help="创建后马上连接目标；不勾选则只建实例，之后在主机清单或详情页点「连接」。"
          />
          <span className="check-inline">
            <input
              type="checkbox"
              checked={connectNow}
              aria-label="立即连接"
              onChange={(event) => setConnectNow(event.target.checked)}
            />
            创建后立即连接
          </span>
        </label>
      </div>
    </Modal>
  );
}
