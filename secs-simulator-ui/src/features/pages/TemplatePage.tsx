import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConfigTemplateEntry } from '../../api';
import type { PanelBaseProps } from '../panels/types';
import { Panel } from '../../shared/ui/Panel';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { JsonEditor } from '../../shared/ui/JsonEditor';
import { Modal } from '../../shared/ui/Modal';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { saveHint, saveTextFile } from '../../shared/lib/saveFile';
import { Icon } from '../../shared/ui/Icon';

/**
 * 设备模板（独立工具）：设备模板的增删改查。
 *
 * <p>模板是完整设备配置。内置模板只读，自定义模板以 {@code <work-dir>/templates/<id>.json}
 * 存储；工作台新建设备只从模板列表里选，不再让用户手填配置文件。
 * SVID 变化模板在「参数模板」页维护。</p>
 *
 * <p>编辑统一在弹框里完成：表格后面不再挂一整块编辑器，校验 / 导入 / 导出 /
 * 保存也都收进弹框。内置模板不能覆盖，弹框里给的是「另存为自定义模板」。</p>
 */
interface TemplateEditorState {
  id: string;
  label: string;
  /** builtin = 内置只读，保存时另存；其余都按自定义模板直接更新。 */
  source: string;
  text: string;
  baseline: string;
  /** 内置模板另存时使用的新模板 id。 */
  saveAsId: string;
}

export function TemplatePage({ backend, notifier }: PanelBaseProps): JSX.Element {
  const client = backend.client;
  const [templates, setTemplates] = useState<ConfigTemplateEntry[]>([]);
  /** null = 编辑框关闭；非 null 时表格下方不渲染任何编辑器。 */
  const [editor, setEditor] = useState<TemplateEditorState | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [newId, setNewId] = useState('');
  const [sourceId, setSourceId] = useState('bare');
  const [deleteId, setDeleteId] = useState<string | null>(null);
  /** 有未保存改动时先问一次再关闭编辑框。 */
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const importRef = useRef<HTMLInputElement | null>(null);

  const dirty = editor !== null && editor.text !== editor.baseline;
  const builtin = editor?.source === 'builtin';
  const canSave = editor !== null && (builtin ? editor.saveAsId.trim() !== '' : dirty);

  const refresh = useCallback(async () => {
    if (client === null) {
      return;
    }
    const list = await client.templates();
    setTemplates(list);
    setSourceId((previous) => (
      list.length > 0 && !list.some((item) => item.id === previous) ? list[0].id : previous
    ));
  }, [client]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  /** 点任意模板的「编辑」：直接把原文装进弹框，不再往表格后面挂编辑器。 */
  const openEditor = useCallback(async (row: ConfigTemplateEntry) => {
    if (client === null) {
      return;
    }
    setBusy(true);
    try {
      const text = await client.templateText(row.id);
      if (text === null) {
        notifier.error('读取模板失败');
        return;
      }
      setEditor({
        id: row.id,
        label: row.label,
        source: row.source ?? 'custom',
        text,
        baseline: text,
        saveAsId: `${row.id}-custom`,
      });
      setProblems([]);
      setNotice('');
    } finally {
      setBusy(false);
    }
  }, [client, notifier]);

  const requestClose = () => {
    if (dirty) {
      setConfirmDiscard(true);
      return;
    }
    setEditor(null);
  };

  /** 导出弹框里的当前原文：能弹保存位置就弹，并在提示里说清文件去了哪。 */
  const exportJson = async () => {
    if (editor === null) {
      return;
    }
    const result = await saveTextFile(
      `${editor.id}.json`,
      editor.text,
      'application/json;charset=utf-8',
    );
    const hint = saveHint(result);
    if (hint !== '') {
      notifier.success('已导出模板', hint);
    }
  };

  const validate = async () => {
    if (client === null || editor === null) {
      return;
    }
    setBusy(true);
    try {
      const result = await client.validateConfig('template.json', editor.text);
      setProblems(result.problems);
      setNotice(result.valid ? '校验通过' : '校验未通过');
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (client === null || editor === null || !canSave) {
      return;
    }
    setBusy(true);
    try {
      if (builtin) {
        /* 内置模板不能覆盖：把弹框里改好的原文另存成一份自定义模板。 */
        const id = editor.saveAsId.trim().replace(/[^A-Za-z0-9._-]/g, '-');
        const status = await client.createTemplate(id, editor.text);
        if (status === 201 || status === 200) {
          notifier.success(`已另存为模板 ${id}`);
          setEditor(null);
          await refresh();
        } else {
          setNotice(`保存失败（HTTP ${status}）`);
        }
      } else {
        const status = await client.updateTemplate(editor.id, editor.text);
        if (status === 200) {
          setEditor((previous) => (previous === null ? null : { ...previous, baseline: previous.text }));
          setProblems([]);
          setNotice(`已保存模板 ${editor.id}`);
          await refresh();
        } else {
          setNotice(`保存失败（HTTP ${status}）`);
        }
      }
    } catch (error) {
      notifier.error('保存模板失败', (error as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const create = async () => {
    if (client === null) {
      return;
    }
    const id = newId.trim().replace(/[^A-Za-z0-9._-]/g, '-');
    if (id === '') {
      notifier.error('模板 id 不能为空');
      return;
    }
    setBusy(true);
    try {
      const src = await client.templateText(sourceId);
      if (src === null) {
        notifier.error('读取源模板失败');
        return;
      }
      const status = await client.createTemplate(id, src);
      if (status === 201 || status === 200) {
        notifier.success(`已新建模板 ${id}`);
        setCreateOpen(false);
        setNewId('');
        await refresh();
        /* 建完直接落进编辑弹框，省得再回表格里找这一行 */
        setEditor({ id, label: id, source: 'custom', text: src, baseline: src, saveAsId: '' });
        setProblems([]);
        setNotice('已新建模板，可直接改 JSON 后点「保存」。');
      } else {
        notifier.error('新建模板失败');
      }
    } finally {
      setBusy(false);
    }
  };

  const columns: Column<ConfigTemplateEntry>[] = [
    { key: 'id', header: '模板 id', width: '150px', sortValue: (row) => row.id, render: (row) => <span className="mono">{row.id}</span> },
    { key: 'label', header: '名称', sortValue: (row) => row.label },
    { key: 'source', header: '来源', width: '80px', sortValue: (row) => row.source ?? '', render: (row) => <span className={`badge ${row.source === 'builtin' ? 'tone-muted' : 'tone-info'}`}>{row.source === 'builtin' ? '内置' : '自定义'}</span> },
    { key: 'description', header: '说明', sortValue: (row) => row.description },
    {
      key: 'actions',
      header: '操作',
      width: '120px',
      render: (row) => (
        <div className="row-actions">
          <button type="button" onClick={() => void openEditor(row)} disabled={busy}>编辑</button>
          {row.source !== 'builtin' && (
            <button type="button" className="danger" onClick={() => setDeleteId(row.id)}>删除</button>
          )}
        </div>
      ),
    },
  ];

  return (
    <Panel id="panel-templates" title="设备模板" headless>
      <DataTable
        tableKey="templates"
        columns={columns}
        rows={templates}
        rowKey={(row) => row.id}
        searchPlaceholder="搜索模板 id / 名称 / 说明"
        emptyText="还没有模板"
        /* 新增按钮靠右：左边是搜索，右边是"动手做什么" */
        toolbarEnd={
          <button type="button" className="primary" onClick={() => setCreateOpen(true)}>新建模板</button>
        }
      />

      {/* 模板编辑器：所有按钮都在这个弹框里，表格下方保持干净 */}
      <Modal
        /* 丢弃确认浮层打开时先收起编辑器，避免两层浮层同时抢焦点/Esc */
        open={editor !== null && !confirmDiscard}
        title={editor === null ? '' : `编辑模板 · ${editor.id}`}
        onClose={requestClose}
        width="min(58rem, 100%)"
        footer={
          <>
            <button type="button" onClick={requestClose}>取消</button>
            <button
              type="button"
              className="icon-btn"
              onClick={() => importRef.current?.click()}
              disabled={busy}
              title="导入 JSON：从本地文件读入草稿（仍需校验/保存）"
              aria-label="导入 JSON"
            >
              <Icon name="upload" size={15} />
            </button>
            <button
              type="button"
              className="icon-btn"
              onClick={() => void exportJson()}
              disabled={busy || editor === null || editor.text.trim() === ''}
              title="导出 JSON：把当前模板原文存成文件（可选择保存位置）"
              aria-label="导出 JSON"
            >
              <Icon name="download" size={15} />
            </button>
            <button
              type="button"
              onClick={() => void validate()}
              disabled={busy || editor === null || editor.text.trim() === ''}
            >
              校验
            </button>
            <button
              type="button"
              className="primary"
              disabled={busy || !canSave}
              onClick={() => void save()}
            >
              {builtin ? '另存为模板' : '保存'}
            </button>
          </>
        }
      >
        {editor !== null && (
          <>
            <div className="section-head">
              <h2>
                {editor.label}
                <span className="hint-inline mono"> {editor.id}</span>
                {builtin && <span className="badge tone-muted">内置只读</span>}
              </h2>
            </div>
            {builtin && (
              <>
                <label className="field field-wide">
                  另存为模板 id
                  <input
                    value={editor.saveAsId}
                    onChange={(event) => setEditor({ ...editor, saveAsId: event.target.value })}
                    placeholder="如 my-etch"
                  />
                </label>
                <p className="hint">内置模板不能覆盖；点「另存为模板」会用上面的 id 生成一份可编辑的自定义模板。</p>
              </>
            )}
            {notice !== '' && <p className="inline-note">{notice}</p>}
            {problems.length > 0 && (
              <ul className="problems">
                {problems.map((problem, index) => <li key={index}>{problem}</li>)}
              </ul>
            )}
            <JsonEditor
              value={editor.text}
              onChange={(next) => { setEditor({ ...editor, text: next }); setProblems([]); }}
              ariaLabel="模板 JSON 原文"
            />
            <input
              ref={importRef}
              type="file"
              accept=".json,application/json,text/plain"
              style={{ display: 'none' }}
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = '';
                if (file === undefined) {
                  return;
                }
                void file.text().then((next) => {
                  setEditor({ ...editor, text: next });
                  setProblems([]);
                  setNotice(`已导入 ${file.name}（未保存，请先校验）`);
                });
              }}
            />
          </>
        )}
      </Modal>

      <Modal
        open={createOpen}
        title="新建模板"
        onClose={() => setCreateOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setCreateOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={busy || newId.trim() === ''} onClick={() => void create()}>创建</button>
          </>
        }
      >
        <div className="form-row">
          <label className="field">
            模板 id
            <input
              value={newId}
              onChange={(event) => setNewId(event.target.value)}
              placeholder="如 my-etch"
            />
          </label>
          <label className="field">
            从哪个模板复制
            <select value={sourceId} onChange={(event) => setSourceId(event.target.value)}>
              {templates.map((item) => <option key={item.id} value={item.id}>{item.id} · {item.label}</option>)}
            </select>
          </label>
        </div>
        <p className="hint">新模板会复制源模板内容，创建后直接在编辑弹框里改 JSON；保存前会校验。</p>
      </Modal>

      <ConfirmModal
        open={confirmDiscard}
        title="丢弃未保存的改动"
        danger
        confirmLabel="丢弃并关闭"
        message={`${editor?.id ?? ''} 有未保存的改动，关闭编辑框会丢掉它们。继续？`}
        onClose={() => setConfirmDiscard(false)}
        onConfirm={() => {
          setConfirmDiscard(false);
          setEditor(null);
        }}
      />

      <ConfirmModal
        open={deleteId !== null}
        title="删除模板"
        danger
        confirmLabel="删除"
        message={deleteId === null ? '' : `确定删除模板 ${deleteId}？已用它创建的设备配置不受影响。`}
        onClose={() => setDeleteId(null)}
        onConfirm={() => {
          if (deleteId !== null && client !== null) {
            void client.deleteTemplate(deleteId).then((status) => {
              if (status === 200) {
                notifier.success(`已删除模板 ${deleteId}`);
                if (editor?.id === deleteId) {
                  setEditor(null);
                }
                void refresh();
              } else {
                notifier.error('删除模板失败');
              }
            });
          }
          setDeleteId(null);
        }}
      />
    </Panel>
  );
}
