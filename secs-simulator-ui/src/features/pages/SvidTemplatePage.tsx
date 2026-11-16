import { useEffect, useMemo, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { Panel } from '../../shared/ui/Panel';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { Modal } from '../../shared/ui/Modal';
import { HintTitle } from '../../shared/ui/FieldLabel';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { TextField } from '../../shared/ui/CommitFields';
import { tokenizeJson } from '../../shared/lib/jsonHighlight';
import { SourceEditor } from '../editors/SourceEditor';
import { templateShapeOf, templateSourceOf } from '../editors/paramTemplates';
import {
  SIM_DURATION_CHOICES,
  formatSimTime,
  formatSimValue,
  simulateSource,
  type SimSample,
} from '../editors/paramTemplateSimulation';
import { ParamCurveChart, nearestSample } from '../charts/ParamCurveChart';
import {
  defaultSource,
  editDocument,
  isJsonObject,
  parseConfigDocument,
  type JsonObject,
} from '../editors/templateConfig';
import { useParamTemplates } from '../../state/useParamTemplates';
import type { ParamTemplate } from '../../api/paramTemplates';

/**
 * 独立工具 · 参数模板（参数变化逻辑库）。
 *
 * <p>模板<b>属于所有设备</b>：库里一份「一个参数的变化逻辑」就是一段
 * {@code source}（常量 / 曲线 / 表达式 / 按片），不绑定任何变量。
 * 每个设备在变量配置里从库中选一条，选中即把这段 source <b>深拷贝</b>
 * 进自己的数据项——之后修改库不影响已配置设备，设备里也不留任何引用。
 * 内置条目只读，可「复制为自定义」再改。</p>
 */
export function SvidTemplatePage({ backend, notifier }: PanelBaseProps): JSX.Element {
  const library = useParamTemplates(backend);
  const [editing, setEditing] = useState<ParamTemplate | null>(null);
  const [copying, setCopying] = useState<ParamTemplate | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteId, setDeleteId] = useState<ParamTemplate | null>(null);
  /** 只读预览的库条目：内置模板不能改，但要能看清它的变化逻辑。 */
  const [previewing, setPreviewing] = useState<ParamTemplate | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const closeForm = () => {
    setCreateOpen(false);
    setEditing(null);
    setCopying(null);
  };

  const openCreate = () => {
    setError('');
    setEditing(null);
    setCopying(null);
    setCreateOpen(true);
  };

  const openEdit = (entry: ParamTemplate | null, asCopy = false) => {
    if (entry === null) {
      openCreate();
      return;
    }
    setError('');
    setCreateOpen(false);
    setCopying(asCopy ? entry : null);
    setEditing(asCopy ? null : entry);
  };

  const remove = (entry: ParamTemplate) => {
    setBusy(true);
    void backend.client?.deleteParamTemplate(entry.id)
      .then((ok) => {
        if (ok) {
          notifier.success(`已删除参数模板「${entry.name}」`);
          void library.reload();
        } else {
          notifier.error('删除失败', '后端拒绝（内置模板不能删除或模板已不存在）');
        }
      })
      .catch((e: Error) => notifier.error('删除失败', e.message))
      .finally(() => setBusy(false));
  };

  const columns: Column<ParamTemplate>[] = [
    { key: 'name', header: '名称', sortValue: (row) => row.name },
    {
      key: 'kind',
      header: '形式',
      sortValue: (row) => templateShapeOf(row),
      render: (row) => templateShapeOf(row) || '—',
    },
    { key: 'description', header: '说明', sortValue: (row) => row.description },
    {
      key: 'source',
      header: '来源',
      sortValue: (row) => row.source,
      render: (row) => (
        <span className="state-pill" data-tone={row.source === 'builtin' ? 'muted' : 'info'}>
          {row.source === 'builtin' ? '内置' : '自定义'}
        </span>
      ),
    },
    {
      key: 'actions',
      header: '操作',
      width: '190px',
      /* 内置只读：只给「复制」「预览」，不给编辑/删除；自定义才允许改和删。 */
      render: (row) => (
        <div className="row-actions">
          {row.source === 'builtin'
            ? (
              <button type="button" onClick={() => openEdit(row, true)} title="内置条目只读，复制一份自定义再改">
                复制
              </button>
            )
            : <button type="button" onClick={() => openEdit(row)}>编辑</button>}
          <button type="button" onClick={() => setPreviewing(row)} title="只读预览这条变化逻辑">
            预览
          </button>
          {row.source === 'custom' && (
            <button type="button" className="danger" disabled={busy} onClick={() => setDeleteId(row)}>
              删除
            </button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="panel-stack">
      <Panel id="panel-svid-templates" title="参数模板（参数变化逻辑库）" headless>
        {error !== '' && <p className="inline-note danger">{error}</p>}
        <DataTable
          tableKey="param-templates"
          columns={columns}
          rows={library.entries}
          rowKey={(row) => row.id}
          searchPlaceholder="搜索名称 / 说明"
          emptyText="参数模板库是空的。点「＋ 新建模板」定义一条变化逻辑，或重启后端载入内置模板。"
          /* 新增按钮靠右：左边是搜索，右边是"动手做什么" */
          toolbarEnd={
            <button type="button" className="primary" onClick={openCreate}>
              ＋ 新建模板
            </button>
          }
        />
      </Panel>

      <ParamTemplateForm
        open={createOpen || editing !== null || copying !== null}
        editing={editing}
        copying={copying}
        libraryChoices={library.entries}
        busy={busy}
        onClose={closeForm}
        onSubmit={async (request) => {
          setBusy(true);
          setError('');
          try {
            const client = backend.client;
            if (client === null) {
              throw new Error('未连接后端');
            }
            await client.saveParamTemplate(request);
            notifier.success(request.id === undefined
              ? `已新建参数模板「${request.name}」`
              : `已保存参数模板「${request.name}」（已配置的设备不受影响，需重新选择才会更新）`);
            await library.reload();
            closeForm();
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      />

      <ParamTemplatePreview entry={previewing} onClose={() => setPreviewing(null)} />

      <ConfirmModal
        open={deleteId !== null}
        title="删除参数模板"
        danger
        confirmLabel="删除"
        message={deleteId === null
          ? ''
          : `确定删除参数模板「${deleteId.name}」？已复制进设备配置的那份不受影响。`}
        onClose={() => setDeleteId(null)}
        onConfirm={() => {
          if (deleteId !== null) {
            remove(deleteId);
          }
          setDeleteId(null);
        }}
      />
    </div>
  );
}

/**
 * 只读预览：把这段 source 试跑一次，画出「参数随时间变化」的曲线。
 *
 * <p>求值全在前端按后端曲线/表达式语义本地完成，不连后端、不写任何东西。
 * 曲线是主体；元信息与定义原文收进可展开的只读详情里。</p>
 */
function ParamTemplatePreview({
  entry,
  onClose,
}: {
  entry: ParamTemplate | null;
  onClose: () => void;
}): JSX.Element | null {
  /** 0 = 自动（按曲线周期/关键帧推断）。 */
  const [durationChoice, setDurationChoice] = useState(0);
  const [seed, setSeed] = useState(1);
  const [speed, setSpeed] = useState(1);
  const [playing, setPlaying] = useState(false);
  const [cursorMs, setCursorMs] = useState<number | null>(null);
  const [hoverSample, setHoverSample] = useState<SimSample | null>(null);

  const source = entry === null ? undefined : templateSourceOf(entry);
  const simulation = useMemo(
    () => source === undefined
      ? null
      : simulateSource(source, { durationMs: durationChoice, seed }),
    [source, durationChoice, seed],
  );
  const text = useMemo(() => JSON.stringify(entry?.body ?? {}, null, 2), [entry]);
  const tokens = useMemo(() => tokenizeJson(text), [text]);

  /* 换一条模板（或关闭再打开）：状态归零，别把上一条的游标/时长带过来。 */
  useEffect(() => {
    setPlaying(false);
    setCursorMs(null);
    setHoverSample(null);
    setSeed(1);
  }, [entry?.id]);

  /** 播放：在 12 秒 / speed 内把游标从 0 推到窗口末尾，到头自动停。 */
  useEffect(() => {
    if (!playing || simulation === null || entry === null) {
      return;
    }
    let frame = 0;
    let last = performance.now();
    let elapsed = 0;
    const totalMs = 12_000 / speed;
    const step = (now: number) => {
      /* 后台标签页回来时 now 会跳一大截，钳住免得直接跳到末尾。 */
      elapsed += Math.min(Math.max(0, now - last), 100);
      last = now;
      const ratio = Math.min(1, elapsed / totalMs);
      setCursorMs(Math.round(ratio * simulation.durationMs));
      if (ratio >= 1) {
        setPlaying(false);
        return;
      }
      frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [playing, speed, simulation, entry]);

  const samples = simulation?.samples ?? [];
  const readout = hoverSample
    ?? (cursorMs === null ? null : nearestSample(samples, cursorMs))
    ?? samples.at(-1)
    ?? null;

  return (
    <Modal
      open={entry !== null}
      title={entry === null ? '' : `预览参数模板 · ${entry.name}`}
      hint={simulation === null
        ? undefined
        : `按这段值定义在本地试跑：横轴时间、纵轴输出值（${simulation.shape}）。曲线为示意——运行时噪声种子由会话与数据项派生，晶圆属性还按片号变化。`}
      onClose={onClose}
      width="min(940px, 94vw)"
      footer={<button type="button" onClick={onClose}>关闭</button>}
    >
      {entry !== null && simulation !== null && (
        <div className="param-preview">
          <div className="param-preview-toolbar">
            <label className="param-preview-control">
              <span>模拟时长</span>
              <select
                value={durationChoice}
                onChange={(event) => {
                  setPlaying(false);
                  setCursorMs(null);
                  setDurationChoice(Number(event.target.value));
                }}
              >
                {SIM_DURATION_CHOICES.map((choice) => (
                  <option key={choice.value} value={choice.value}>{choice.label}</option>
                ))}
              </select>
            </label>
            <label className="param-preview-control">
              <span>播放速度</span>
              <select value={speed} onChange={(event) => setSpeed(Number(event.target.value))}>
                <option value={1}>1×</option>
                <option value={2}>2×</option>
                <option value={4}>4×</option>
              </select>
            </label>
            <button type="button" className="primary" onClick={() => setPlaying(!playing)}>
              {playing ? '⏸ 暂停' : '▶ 播放'}
            </button>
            <button
              type="button"
              onClick={() => {
                setPlaying(false);
                setCursorMs(null);
                setSeed((value) => value + 1);
              }}
            >
              ⟳ 重新模拟
            </button>
          </div>

          <div className="param-preview-stats">
            <div className="param-preview-stat is-current">
              <span>当前读数{readout === null ? '' : ` · ${formatSimTime(readout.tMs)}`}</span>
              <strong>{readout === null ? '—' : formatSimValue(readout.value)}</strong>
            </div>
            <div className="param-preview-stat"><span>最小</span><strong>{formatSimValue(simulation.min)}</strong></div>
            <div className="param-preview-stat"><span>最大</span><strong>{formatSimValue(simulation.max)}</strong></div>
            <div className="param-preview-stat"><span>平均</span><strong>{formatSimValue(simulation.avg)}</strong></div>
            <div className="param-preview-stat">
              <span>模拟时长</span><strong>{formatSimTime(simulation.durationMs)}</strong>
            </div>
          </div>

          <ParamCurveChart
            samples={samples}
            durationMs={simulation.durationMs}
            cursorMs={cursorMs}
            onReadout={setHoverSample}
          />

          {simulation.warning !== '' && <p className="inline-note danger">模拟提示：{simulation.warning}</p>}

          <details className="param-preview-details">
            <summary>
              <HintTitle text="设备里从模板库选中一条时复制的是这份内容的深拷贝；之后修改库不影响已配置的设备。">
                模板信息与定义原文（只读）
              </HintTitle>
            </summary>
            <div className="session-form">
              <div className="session-field">
                <span className="session-field-name">来源</span>
                <span className={`badge ${entry.source === 'builtin' ? 'tone-muted' : 'tone-info'}`}>
                  {entry.source === 'builtin' ? '内置（只读）' : '自定义'}
                </span>
              </div>
              <div className="session-field">
                <span className="session-field-name">形式</span>
                <span>{templateShapeOf(entry) || '—'}</span>
              </div>
              <div className="session-field">
                <span className="session-field-name">说明</span>
                <span className="session-field-text">{entry.description || '—'}</span>
              </div>
            </div>
            <p className="sub-title">值定义（只读）</p>
            <pre className="sml-result param-template-preview-code">{tokens.map((token, index) => (
              <span key={index} className={`json-${token.kind}`}>{token.text}</span>
            ))}</pre>
          </details>
        </div>
      )}
    </Modal>
  );
}

/** 库条目 ↔ 编辑弹框：body 就是 {source}，用同一套 SourceEditor 编辑。 */
function ParamTemplateForm({
  open,
  editing,
  copying,
  libraryChoices,
  busy,
  onClose,
  onSubmit,
}: {
  open: boolean;
  editing: ParamTemplate | null;
  copying: ParamTemplate | null;
  libraryChoices: ParamTemplate[];
  busy: boolean;
  onClose: () => void;
  onSubmit: (request: { id?: string; name: string; description?: string; body: string }) => Promise<void>;
}): JSX.Element | null {
  const source = editing ?? copying;
  const key = editing !== null ? `edit:${editing.id}` : copying !== null ? `copy:${copying.id}` : 'new';
  const [name, setName] = useState(source?.name ?? '');
  const [description, setDescription] = useState(source?.description ?? '');
  const [wrapper, setWrapper] = useState(() => bodyToWrapper(source?.body));
  const [lastKey, setLastKey] = useState(key);
  if (key !== lastKey) {
    setLastKey(key);
    setName(copying !== null ? `${copying.name} 副本` : source?.name ?? '');
    setDescription(source?.description ?? '');
    setWrapper(bodyToWrapper(source?.body));
  }

  const parsed = useMemo(() => parseConfigDocument(wrapper), [wrapper]);
  const root = parsed.ok ? parsed.config.root : null;
  const hasSource = root !== null && isJsonObject(root.source);
  const errorText = !parsed.ok
    ? `值定义不是合法 JSON：${parsed.error}`
    : !hasSource
      ? '还没有值定义：先选一种形态'
      : '';

  const mutate = (fn: (target: JsonObject) => void) => {
    const edited = editDocument(wrapper, (draft) => {
      if (!isJsonObject(draft.source)) {
        draft.source = defaultSource('curve');
      }
      fn(draft);
    });
    if (edited.ok) {
      setWrapper(edited.text);
    }
  };

  return (
    <Modal
      open={open}
      title={editing !== null
        ? `编辑参数模板 ${editing.name}`
        : copying !== null
          ? `复制参数模板 ${copying.name}`
          : '新建参数模板'}
      hint="模板体只有一段值定义：常量 / 曲线 / 表达式（私有曲线组合）/ 按片。设备里选中它时复制的是这段内容的深拷贝，不保留任何引用。"
      onClose={onClose}
      width="min(920px, 92vw)"
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button
            type="button"
            className="primary"
            disabled={busy || name.trim() === '' || errorText !== ''}
            title={errorText !== '' ? errorText : undefined}
            onClick={() => {
              if (root === null || !isJsonObject(root.source)) {
                return;
              }
              void onSubmit({
                id: editing?.id,
                name: name.trim(),
                description: description.trim(),
                body: JSON.stringify({ source: root.source }),
              });
            }}
          >
            保存模板
          </button>
        </>
      }
    >
      <div className="session-form">
        <div className="session-field">
          <span className="session-field-name">名称</span>
          <TextField value={name} onCommit={setName} placeholder="例如 炉温三段程序" />
        </div>
        <div className="session-field">
          <span className="session-field-name">说明</span>
          <TextField allowBlank value={description} onCommit={setDescription} placeholder="一句话说明变化逻辑与量纲参考" />
        </div>
      </div>

      {root !== null && (
        <SourceEditor
          def={root}
          mutate={mutate}
          library={libraryChoices.filter((item) => item.id !== editing?.id)}
        />
      )}

      {errorText !== '' && <p className="problems">{errorText}</p>}
    </Modal>
  );
}

/** 库模板体 → 编辑文档（{source}）。老库里没有 source 的条目给一份曲线骨架。 */
function bodyToWrapper(body: Record<string, unknown> | undefined): string {
  const source = body !== undefined && isJsonObject(body.source) ? body.source : defaultSource('curve');
  return `${JSON.stringify({ source }, null, 2)}\n`;
}
