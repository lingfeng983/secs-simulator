import { useMemo, useState } from 'react';
import type { PreviewData, SvidInput } from '../../api';
import { VariableChart } from '../charts/VariableChart';
import { Panel, PanelFootnote } from '../../shared/ui/Panel';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { Modal } from '../../shared/ui/Modal';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import type { PanelBaseProps } from './types';
import { saveHint } from '../../shared/lib/saveFile';

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

  /* 预览窗口用引擎 uptime，坐标轴要换算成墙钟才看得出来"这是几点"。
     偏移在进程生命周期内基本恒定，按 uptime 缓存一次即可。 */
  const uptimeMs = backend.health?.uptimeMs ?? 0;
  const timeOriginMs = useMemo(
    () => (uptimeMs > 0 ? Date.now() - uptimeMs : 0),
    [uptimeMs],
  );

  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [draft, setDraft] = useState<SvidInput>(EMPTY_SVID);
  const [busy, setBusy] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<number | null>(null);

  /* 值设定 */
  const [pinTarget, setPinTarget] = useState<number | null>(null);
  const [pinValue, setPinValue] = useState('0');

  /* 单变量预览：只画当前选中的那一个变量,坐标轴齐全(见 VariableChart) */
  const [singlePreview, setSinglePreview] = useState<PreviewData | null>(null);
  const [singleSvid, setSingleSvid] = useState<number | null>(null);
  const [previewWindowMs, setPreviewWindowMs] = useState(120_000);
  const [showEvents, setShowEvents] = useState(true);
  const [singleBusy, setSingleBusy] = useState(false);

  /* 已设定的变量:目录视图直接带 pinned 标志(后端钉住状态的真值),刷新不丢 */
  const rows: SvidRow[] = useMemo(
    () => svidChoices.map((choice) => ({
      svid: choice.svid,
      owner: choice.owner,
      slot: choice.slot,
      type: choice.type,
      unit: choice.unit,
      pinned: choice.pinned === true,
    })),
    [svidChoices],
  );

  /** 归属直接选已有单元/全局，不再手打。 */
  const ownerChoices = useMemo(
    () => [...new Set(svidChoices.map((choice) => choice.owner).filter((owner) => owner !== ''))].sort(),
    [svidChoices],
  );

  /* 图例里用符号名,别只给编号 */
  const previewLabels = useMemo(() => {
    const map = new Map<number, string>();
    for (const row of rows) {
      map.set(row.svid, row.slot);
    }
    return map;
  }, [rows]);

  const columns: Column<SvidRow>[] = [
    { key: 'svid', header: 'SVID', sortValue: (row) => row.svid, render: (row) => <span className="mono">{row.svid}</span> },
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
            disabled={activeSession === '' || singleBusy}
            onClick={() => void loadSinglePreview(row.svid)}
            title="预览这个变量最近一段时间的曲线（含事件效果与事件竖线）"
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

  /** 只预览一个变量：坐标轴齐全，事件竖线按计划分色。 */
  const loadSinglePreview = async (svid: number) => {
    if (activeSession === '') {
      return;
    }
    setSingleBusy(true);
    try {
      const data = await backend.previewSvid(activeSession, svid, previewWindowMs);
      if (data !== null) {
        setSinglePreview(data);
        setSingleSvid(svid);
      }
    } finally {
      setSingleBusy(false);
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
        setPinTarget(null);
      }
    } finally {
      setBusy(false);
    }
  };

  const unpin = async (svid: number) => {
    await backend.clearSvidValue(svid);
  };


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
        onExported={(result) => { const hint = saveHint(result); if (hint !== '') { notifier.success('已导出', hint); } }}
        onImport={async (imported) => {
          let done = 0;
          for (const row of imported) {
            const id = Number.parseInt(row.SVID ?? '', 10);
            if (!Number.isFinite(id) || id < 1) {
              continue;
            }
            const input = {
              id,
              slot: (row['符号名'] ?? '').trim() || `SVID_${id}`,
              owner: (row['归属'] ?? '').trim(),
              type: (row['类型'] ?? 'F4').trim() || 'F4',
              unit: (row['单位'] ?? '').trim(),
            };
            const exists = rows.some((item) => item.svid === id);
            const ok = exists ? await backend.updateSvid(id, input) : await backend.addSvid(input);
            if (ok) {
              done += 1;
            }
          }
          notifier.success(`已导入 ${done} 个状态变量`);
        }}
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
        「预览」只画这一个变量最近一段时间的曲线,事件竖线按计划用不同颜色标出。
        CEID 与报告的清册在「事件」页管理。
      </p>

      {/* 只保留单变量预览：一个参数的曲线 + 事件效果，不再提供"全部曲线"入口 */}

      {/* 单变量预览:只画选中的那一个变量,Y 轴是数值、X 轴是时间,
          事件竖线直接标出 CEID 与计划名。 */}
      {singlePreview !== null && singleSvid !== null && (
        <section className="panel-section block">
          <div className="section-head">
            <h2>
              单变量预览 · SVID {singleSvid}
              {previewLabels.get(singleSvid) !== undefined && (
                <span className="hint-inline"> {previewLabels.get(singleSvid)}</span>
              )}
            </h2>
            <div className="preview-controls">
              <label className="field">
                时间窗
                <select
                  value={previewWindowMs}
                  onChange={(event) => setPreviewWindowMs(Number(event.target.value))}
                  aria-label="单变量预览时间窗"
                >
                  {[30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000].map((ms) => (
                    <option key={ms} value={ms}>{ms >= 60_000 ? `${ms / 60_000} 分钟` : `${ms / 1000} 秒`}</option>
                  ))}
                </select>
              </label>
              <label className="check-inline" title="关闭后只画曲线，不画事件竖线/图例">
                <input
                  type="checkbox"
                  checked={showEvents}
                  onChange={(event) => setShowEvents(event.target.checked)}
                />
                事件标记
              </label>
              <button type="button" disabled={singleBusy} onClick={() => void loadSinglePreview(singleSvid)}>
                {singleBusy ? '计算中…' : '重算'}
              </button>
              <button type="button" onClick={() => { setSinglePreview(null); setSingleSvid(null); }}>收起</button>
            </div>
          </div>
          {singlePreview.series.length > 0 ? (
            <VariableChart
              series={singlePreview.series[0]}
              fromMs={singlePreview.from}
              timeOriginMs={timeOriginMs}
              bucketMs={singlePreview.bucketMs}
              events={showEvents ? (singlePreview.events ?? []) : []}
              height={200}
              eventLabel="发送事件"
            />
          ) : (
            <p className="hint">这个变量没有可预览的曲线。</p>
          )}
          <p className="hint">
            曲线是"最近一段时间的确定性重放"：回放会从窗口之前开始，窗口内仍在生效的
            set/shift 效果也会带上，所以不会出现"只有窗口里恰好触发的那条事件"。
            竖线按计划分色；关闭「事件标记」可只看曲线。
            拖动框选放大、滚轮缩放时间、Shift+滚轮平移、Ctrl+滚轮纵向缩放、双击复位。
          </p>
        </section>
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
            归属
            <select
              value={draft.owner ?? ''}
              onChange={(event) => setDraft({ ...draft, owner: event.target.value })}
            >
              {draft.owner !== undefined && !ownerChoices.includes(draft.owner) && (
                <option value={draft.owner}>{draft.owner}</option>
              )}
              {ownerChoices.map((owner) => <option key={owner} value={owner}>{owner}</option>)}
              {!ownerChoices.includes('GLOBAL') && <option value="GLOBAL">GLOBAL</option>}
            </select>
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
