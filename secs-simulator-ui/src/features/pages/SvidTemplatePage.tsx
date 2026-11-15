import { useMemo, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { Panel } from '../../shared/ui/Panel';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { Modal } from '../../shared/ui/Modal';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { TextField } from '../../shared/ui/CommitFields';
import { TemplateEditor } from '../editors/TemplateEditor';
import { singleCurveBodyOf } from '../editors/paramTemplates';
import {
  editDocument,
  isJsonObject,
  parseConfigDocument,
  readTemplates,
  stringField,
  templateOutputs,
  type JsonObject,
} from '../editors/templateConfig';
import { useParamTemplates } from '../../state/useParamTemplates';
import type { ParamTemplate } from '../../api/paramTemplates';

/**
 * 独立工具 · 参数模板（参数变化逻辑库）。
 *
 * <p>模板<b>属于所有设备</b>：库里一份「一个参数的变化逻辑」
 * （单曲线或 曲线+组合表达式，不绑定 SVID），每个设备在变量配置里从库中
 * 选择，选中即把模板体复制进自己的配置文档（复制快照语义）——之后修改库
 * 不影响已配置设备。内置条目只读，可「复制为自定义」再改。</p>
 */
export function SvidTemplatePage({ backend, notifier }: PanelBaseProps): JSX.Element {
  const library = useParamTemplates(backend);
  const [editing, setEditing] = useState<ParamTemplate | null>(null);
  const [copying, setCopying] = useState<ParamTemplate | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteId, setDeleteId] = useState<ParamTemplate | null>(null);
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
      sortValue: (row) => (singleCurveBodyOf(row) !== undefined ? '单曲线' : '表达式组合'),
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
      header: '',
      render: (row) => (
        <div className="row-actions">
          {row.source === 'builtin'
            ? (
              <button type="button" onClick={() => openEdit(row, true)} title="内置条目只读，复制一份自定义再改">
                复制为自定义
              </button>
            )
            : <button type="button" onClick={() => openEdit(row)}>编辑</button>}
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
      {/* 标题行已删；「刷新」也去掉了——顶栏已有全局刷新（R），这里只留新建 */}
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

/** 库条目 ↔ 编辑弹框：模板体包成 {templates:…, result} 文档喂给 TemplateEditor。 */
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
  const [wrapper, setWrapper] = useState(bodyToWrapper(source?.body));
  const [lastKey, setLastKey] = useState(key);
  if (key !== lastKey) {
    setLastKey(key);
    setName(copying !== null ? `${copying.name} 副本` : source?.name ?? '');
    setDescription(source?.description ?? '');
    setWrapper(bodyToWrapper(source?.body));
  }

  const parsed = useMemo(() => parseConfigDocument(wrapper), [wrapper]);
  const view = useMemo(() => (parsed.ok ? readTemplates(parsed.config.root) : null), [parsed]);
  const choices = useMemo(() => {
    if (view === null || !view.ok) {
      return [];
    }
    const items: Array<{ instance: string; output: string }> = [];
    for (const instance of view.templates.instances) {
      for (const output of templateOutputs(view.templates, stringField(instance, 'id'))) {
        items.push({ instance: stringField(instance, 'id'), output });
      }
    }
    if (items.length === 0) {
      /* 还没有实例：直接列曲线/组合的输出（保存后后端会自动补一枚空绑定实例），
         单曲线模板就不必手加实例了。 */
      for (const template of [...view.templates.curves, ...view.templates.composites]) {
        for (const output of templateOutputs(view.templates, stringField(template, 'id'))) {
          items.push({ instance: stringField(template, 'id'), output });
        }
      }
    }
    return items;
  }, [view]);
  const result = parsed.ok ? stringField(parsed.config.root, 'result') : '';
  /* result 为空时自动取第一个候选：单曲线模板零选择即可保存 */
  const effectiveResult = result !== ''
    ? result
    : choices.length > 0
      ? `${choices[0].instance}.${choices[0].output}`
      : '';
  const templateSection = parsed.ok && isJsonObject(parsed.config.root.templates)
    ? parsed.config.root.templates
    : null;

  const changeResult = (next: string) => {
    const edited = editDocument(wrapper, (root) => {
      root.result = next;
    });
    if (edited.ok) {
      setWrapper(edited.text);
    }
  };

  const libraryCurveChoices = libraryChoices
    .map((entry) => ({ entry, curve: singleCurveBodyOf(entry) }))
    .filter((item): item is { entry: ParamTemplate; curve: JsonObject } => item.curve !== undefined)
    .map(({ entry, curve }) => ({ id: entry.id, name: entry.name, description: entry.description, curve }));

  const valid = name.trim() !== '' && effectiveResult !== '' && templateSection !== null;
  const errorText = !parsed.ok
    ? `模板体不是合法 JSON：${parsed.error}`
    : view !== null && !view.ok
      ? `模板段结构不对：${view.error}`
      : effectiveResult === ''
        ? '还没有指定最终输出：先建曲线/组合与实例，再从「最终输出」里选'
        : '';

  return (
    <Modal
      open={open}
      title={editing !== null
        ? `编辑参数模板 ${editing.name}`
        : copying !== null
          ? `复制参数模板 ${copying.name}`
          : '新建参数模板'}
      onClose={onClose}
      width="min(920px, 92vw)"
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button
            type="button"
            className="primary"
            disabled={busy || !valid}
            title={errorText !== '' ? errorText : undefined}
            onClick={() => {
              if (templateSection === null || effectiveResult === '') {
                return;
              }
              void onSubmit({
                id: editing?.id,
                name: name.trim(),
                description: description.trim(),
                body: JSON.stringify({ ...templateSection, result: effectiveResult }),
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

      <TemplateEditor text={wrapper} onChange={setWrapper} curveChoices={libraryCurveChoices} />

      <div className="session-form" style={{ marginTop: 12 }}>
        <div className="session-field">
          <span className="session-field-name">最终输出</span>
          <select value={effectiveResult} onChange={(event) => changeResult(event.target.value)}>
            {result !== '' && !choices.some((item) => `${item.instance}.${item.output}` === result) && (
              <option value={result}>{result}（实例已被删除）</option>
            )}
            {choices.length === 0 && <option value="">（先建曲线/组合与实例）</option>}
            {choices.map((item) => (
              <option key={`${item.instance}.${item.output}`} value={`${item.instance}.${item.output}`}>
                {item.instance}.{item.output}
              </option>
            ))}
          </select>
          <span className="session-field-help">
            绑定到设备变量时，这个输出就是该变量的值；单曲线只有一个输出，组合可从多个输出里挑。
          </span>
        </div>
      </div>

      {errorText !== '' && <p className="problems">{errorText}</p>}
    </Modal>
  );
}

/** 库模板体 → 编辑文档（{templates:{curves,composites,instances}, result}）。 */
function bodyToWrapper(body: Record<string, unknown> | undefined): string {
  const { result, ...templates } = body ?? {};
  return `${JSON.stringify({ templates, result: typeof result === 'string' ? result : '' }, null, 2)}\n`;
}
