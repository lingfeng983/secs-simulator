import { useMemo, useState } from 'react';
import type { PreviewData, SvidInput } from '../api';
import { Sparkline } from '../charts/Sparkline';
import { Panel, PanelFootnote } from '../ui/Panel';
import { ConfirmModal } from '../ui/ConfirmModal';
import { Modal } from '../ui/Modal';
import { DataTable, type Column } from '../ui/DataTable';
import type { PanelBaseProps } from './types';

/** 后端支持的 SECS-II 数据类型(与 ValueType 一致)。 */
const TYPES = ['U1', 'U2', 'U4', 'U8', 'I1', 'I2', 'I4', 'I8', 'F4', 'F8', 'ASCII', 'BOOLEAN'] as const;

/** 目录里的一行(SVID 视图)。 */
interface SvidRow {
  svid: number;
  owner: string;
  slot: string;
  type: string;
  unit: string;
  pinned: boolean;
}

const EMPTY_SVID: SvidInput = { id: 0, slot: '', type: 'F4', owner: 'GLOBAL', unit: '' };

/**
 * 状态变量面板(原「设备目录」的 SVID 部分,升级为全生命周期)。
 *
 * <p>增删改查 + 逐变量曲线预览 + 值「设定」:设定会把变量钉在给定值上,
 * 覆盖曲线与事件效果,直到取消设定或重载会话。运行时新增的变量绑定恒 0
 * 曲线,先「设定」一个值即可看到数据。同样不写回配置。</p>
 */
export function SvidPanel({ backend, notifier }: PanelBaseProps): JSX.Element {
  const { catalog, activeSession } = backend;
  const svidChoices = catalog?.svids ?? [];

  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [draft, setDraft] = useState<SvidInput>(EMPTY_SVID);
  const [busy, setBusy] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<number | null>(null);

  /* 值设定 */
  const [pinTarget, setPinTarget] = useState<number | null>(null);
  const [pinValue, setPinValue] = useState('0');

  /* 曲线预览 */
  const [preview, setPreview] = useState<{ svid: number; data: PreviewData } | null>(null);
  const [previewBusy, setPreviewBusy] = useState(false);

  /* 已设定的变量集合:从逐变量预览拉不到全局钉住状态,先按预览/操作记录粗略维护 */
  const [pinnedIds, setPinnedIds] = useState<ReadonlySet<number>>(new Set());

  const rows: SvidRow[] = useMemo(
    () => svidChoices.map((choice) => ({
      svid: choice.svid,
      owner: choice.owner,
      slot: choice.slot,
      type: choice.type,
      unit: choice.unit,
      pinned: pinnedIds.has(choice.svid),
    })),
    [svidChoices, pinnedIds],
  );

  const columns: Column<SvidRow>[] = [
    { key: 'svid', header: 'SVID', align: 'right', sortValue: (row) => row.svid, render: (row) => <span className="mono">{row.svid}</span> },
    { key: 'slot', header: '符号名', sortValue: (row) => row.slot, render: (row) => <span className="mono">{row.slot}</span> },
    { key: 'owner', header: '归属', sortValue: (row) => row.owner, render: (row) => row.owner },
    { key: 'type', header: '类型', sortValue: (row) => row.type, render: (row) => <span className="mono">{row.type}</span> },
    { key: 'unit', header: '单位', sortValue: (row) => row.unit, render: (row) => row.unit },
    {
      key: 'pinned',
      header: '设定',
      sortValue: (row) => (row.pinned ? 1 : 0),
      render: (row) => (row.pinned ? <span className="badge tone-warn">已设定</span> : <span className="hint-inline">—</span>),
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          <button
            type="button"
            disabled={previewBusy}
            onClick={() => void loadPreview(row.svid)}
            title="预览该变量的曲线(最近 1 分钟)"
          >
            预览
          </button>
          <button
            type="button"
            disabled={activeSession === ''}
            onClick={() => {
              setPinTarget(row.svid);
              setPinValue('0');
            }}
            title="把变量钉在给定值上(覆盖曲线与事件效果)"
          >
            设定
          </button>
          {row.pinned && (
            <button type="button" onClick={() => void unpin(row.svid)} title="取消设定,回到曲线值">
              取消设定
            </button>
          )}
          <button type="button" onClick={() => openEditor(row)} title="编辑符号名/类型/单位">
            编辑
          </button>
          <button type="button" className="danger" onClick={() => setDeleteTarget(row.svid)} title="删除变量">
            删除
          </button>
        </div>
      ),
    },
  ];

  const loadPreview = async (svid: number) => {
    setPreviewBusy(true);
    try {
      const data = await backend.previewSvid(activeSession, svid, 60_000);
      if (data !== null) {
        setPreview({ svid, data });
      }
    } finally {
      setPreviewBusy(false);
    }
  };

  const openEditor = (row?: SvidRow) => {
    if (row === undefined) {
      setEditingId(null);
      setDraft({ ...EMPTY_SVID, id: 0 });
    } else {
      setEditingId(row.svid);
      setDraft({ id: row.svid, slot: row.slot, type: row.type, owner: row.owner, unit: row.unit });
    }
    setEditorOpen(true);
  };

  const save = async () => {
    if (!Number.isFinite(draft.id) || draft.id < 1) {
      notifier.error('SVID 必须是正整数');
      return;
    }
    if (draft.slot.trim() === '') {
      notifier.error('符号名不能为空');
      return;
    }
    setBusy(true);
    try {
      const ok = editingId === null
        ? await backend.addSvid({ ...draft, slot: draft.slot.trim() })
        : await backend.updateSvid(draft.id, { ...draft, slot: draft.slot.trim() });
      if (ok) {
        setEditorOpen(false);
      }
    } finally {
      setBusy(false);
    }
  };

  const pin = async () => {
    if (pinTarget === null) {
      return;
    }
    const value = Number.parseFloat(pinValue);
    if (!Number.isFinite(value)) {
      notifier.error('设定值必须是数字');
      return;
    }
    setBusy(true);
    try {
      const ok = await backend.setSvidValue(pinTarget, value);
      if (ok) {
        setPinnedIds((previous) => new Set(previous).add(pinTarget));
        setPinTarget(null);
      }
    } finally {
      setBusy(false);
    }
  };

  const unpin = async (svid: number) => {
    const ok = await backend.clearSvidValue(svid);
    if (ok) {
      setPinnedIds((previous) => {
        const next = new Set(previous);
        next.delete(svid);
        return next;
      });
    }
  };

  const previewSeries = preview?.data.series[0];
  const fromMs = preview?.data.from ?? 0;
  const bucketMs = preview?.data.bucketMs ?? 500;

  return (
    <Panel
      id="panel-svids"
      title="状态变量"
      description="SVID 的增删改查、逐变量曲线预览与值设定。运行时改动不写回配置,重载后失效。"
      actions={
        <button
          type="button"
          className="primary"
          disabled={activeSession === ''}
          onClick={() => openEditor(undefined)}
          title="新增一个状态变量(默认恒 0,设定一个值即可看到数据)"
        >
          新增状态变量
        </button>
      }
    >
      <DataTable
        tableKey="svids"
        columns={columns}
        rows={rows}
        rowKey={(row) => String(row.svid)}
        searchPlaceholder="搜索 SVID / 符号名 / 归属"
        exportName="svids"
        emptyText={activeSession === '' ? '先在会话表里选一个会话' : '该设备还没有状态变量——点「新增状态变量」创建'}
        exportColumns={[
          { header: 'SVID', value: (row) => row.svid },
          { header: '符号名', value: (row) => row.slot },
          { header: '归属', value: (row) => row.owner },
          { header: '类型', value: (row) => row.type },
          { header: '单位', value: (row) => row.unit },
        ]}
      />
      <p className="hint">
        「设定」把变量钉在给定值上(覆盖曲线与事件效果,直到取消设定或重载会话);
        「预览」画该变量最近 1 分钟的曲线。CEID 与报告的清册在「事件」页管理。
      </p>

      {/* 单变量曲线预览 */}
      {preview !== null && previewSeries !== undefined && (
        <div className="collector-chart">
          <div className="collector-chart-head">
            <span className="mono">{preview.svid}</span>
            <span>{previewSeries.owner ?? ''}</span>
            {(previewSeries.unit ?? '') !== '' && <span className="hint-inline">{previewSeries.unit}</span>}
            <button type="button" className="link" onClick={() => setPreview(null)}>收起</button>
          </div>
          <Sparkline
            series={{ svid: preview.svid, owner: previewSeries.owner, unit: previewSeries.unit, min: previewSeries.min, max: previewSeries.max, avg: previewSeries.avg }}
            fromMs={fromMs}
            bucketMs={bucketMs}
            height={96}
          />
        </div>
      )}

      <PanelFootnote
        items={[
          { label: '变量数', value: rows.length },
          { label: '已设定', value: rows.filter((row) => row.pinned).length, tone: rows.some((row) => row.pinned) ? 'warn' : undefined },
        ]}
      />

      {/* 新增/编辑弹窗 */}
      <Modal
        open={editorOpen}
        title={editingId === null ? '新增状态变量' : `编辑状态变量 SVID ${editingId}`}
        onClose={() => setEditorOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setEditorOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={busy} onClick={() => void save()}>保存</button>
          </>
        }
      >
        <div className="form-row">
          <label className="field">
            SVID
            <input
              type="number"
              min={1}
              value={draft.id}
              onChange={(event) => setDraft({ ...draft, id: Number(event.target.value) || 0 })}
              disabled={editingId !== null}
            />
          </label>
          <label className="field">
            符号名
            <input
              value={draft.slot}
              onChange={(event) => setDraft({ ...draft, slot: event.target.value })}
              placeholder="如 TEMP_PV"
            />
          </label>
          <label className="field">
            类型
            <select value={draft.type} onChange={(event) => setDraft({ ...draft, type: event.target.value })}>
              {TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
            </select>
          </label>
          <label className="field">
            归属（GLOBAL = 设备级）
            <input
              value={draft.owner}
              onChange={(event) => setDraft({ ...draft, owner: event.target.value })}
              placeholder="GLOBAL"
            />
          </label>
          <label className="field">
            单位
            <input
              value={draft.unit}
              onChange={(event) => setDraft({ ...draft, unit: event.target.value })}
              placeholder="如 C / Torr"
            />
          </label>
        </div>
      </Modal>

      {/* 设定值弹窗 */}
      <Modal
        open={pinTarget !== null}
        title={pinTarget === null ? '' : `设定 SVID ${pinTarget} 的值`}
        onClose={() => setPinTarget(null)}
        footer={
          <>
            <button type="button" onClick={() => setPinTarget(null)}>取消</button>
            <button type="button" className="primary" disabled={busy} onClick={() => void pin()}>设定</button>
          </>
        }
      >
        <div className="form-row">
          <label className="field">
            设定值
            <input value={pinValue} onChange={(event) => setPinValue(event.target.value)} inputMode="decimal" />
          </label>
        </div>
        <p className="hint">设定后变量保持该值,覆盖曲线与事件效果;「取消设定」回到曲线值。重载会话也会解除。</p>
      </Modal>

      {/* 删除确认 */}
      <ConfirmModal
        open={deleteTarget !== null}
        title="删除状态变量"
        danger
        confirmLabel="删除"
        message={deleteTarget === null ? '' : `确定删除 SVID ${deleteTarget}?其值绑定一并移除,正在采集它的任务会停止。`}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => {
          if (deleteTarget !== null) {
            void backend.deleteSvid(deleteTarget);
          }
          setDeleteTarget(null);
        }}
      />
    </Panel>
  );
}
