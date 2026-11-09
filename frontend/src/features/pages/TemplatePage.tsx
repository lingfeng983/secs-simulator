import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ConfigTemplateEntry } from '../../api';
import type { PanelBaseProps } from '../panels/types';
import { Panel } from '../../shared/ui/Panel';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { JsonEditor } from '../../shared/ui/JsonEditor';
import { Modal } from '../../shared/ui/Modal';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { saveHint, saveTextFile } from '../../shared/lib/saveFile';

/**
 * 模板管理：设备模板的增删改查。
 *
 * <p>模板是完整设备配置。内置模板只读，自定义模板以 {@code <work-dir>/templates/<id>.json}
 * 存储；工作台新建设备只从模板列表里选，不再让用户手填配置文件。</p>
 */
export function TemplatePage({ backend, notifier }: PanelBaseProps): JSX.Element {
  const client = backend.client;
  const [templates, setTemplates] = useState<ConfigTemplateEntry[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [text, setText] = useState('');
  const [baseline, setBaseline] = useState('');
  const [problems, setProblems] = useState<string[]>([]);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [newId, setNewId] = useState('');
  const [sourceId, setSourceId] = useState('bare');
  const [deleteId, setDeleteId] = useState<string | null>(null);
  /** 有未保存改动时要打开的模板；null = 没有待确认的打开动作。 */
  const [pendingOpenId, setPendingOpenId] = useState<string | null>(null);
  const importRef = useRef<HTMLInputElement | null>(null);

  const selected = templates.find((item) => item.id === selectedId);
  const readOnly = selected?.source === 'builtin';
  const dirty = text !== baseline;

  /** 导出当前模板：能弹保存位置就弹，并在提示里说清文件去了哪。 */
  const exportJson = async () => {
    const result = await saveTextFile(
      `${selected?.id ?? 'template'}.json`,
      text,
      'application/json;charset=utf-8',
    );
    const hint = saveHint(result);
    if (hint !== '') {
      notifier.success('已导出模板', hint);
    }
  };

  const refresh = async () => {
    if (client === null) {
      return;
    }
    const list = await client.templates();
    setTemplates(list);
    if (selectedId === '' || !list.some((item) => item.id === selectedId)) {
      const first = list.find((item) => item.source === 'custom') ?? list[0];
      if (first !== undefined) {
        void open(first.id);
      }
    }
  };

  const open = async (id: string) => {
    if (client === null) {
      return;
    }
    setBusy(true);
    try {
      const next = await client.templateText(id);
      if (next === null) {
        notifier.error('读取模板失败');
        return;
      }
      setSelectedId(id);
      setText(next);
      setBaseline(next);
      setProblems([]);
      setNotice('');
    } finally {
      setBusy(false);
    }
  };

  /**
   * 打开另一个模板前的守卫。
   *
   * <p>open() 会用磁盘内容覆盖编辑器；dirty 时先问一次，
   * 否则用户点一下「打开」就把改了一半的模板静默丢了。</p>
   */
  const requestOpen = useCallback((id: string) => {
    if (id === selectedId) {
      return;
    }
    if (dirty) {
      setPendingOpenId(id);
      return;
    }
    void open(id);
    // open 只用到 client/notifier，二者稳定；依赖放 selectedId/dirty 即可
  }, [selectedId, dirty, client]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client]);

  const columns: Column<ConfigTemplateEntry>[] = useMemo(() => [
    { key: 'id', header: '模板 id', width: '150px', sortValue: (row) => row.id, render: (row) => <span className="mono">{row.id}</span> },
    { key: 'label', header: '名称', sortValue: (row) => row.label },
    { key: 'source', header: '来源', width: '80px', sortValue: (row) => row.source ?? '', render: (row) => <span className={`badge ${row.source === 'builtin' ? 'tone-muted' : 'tone-info'}`}>{row.source === 'builtin' ? '内置' : '自定义'}</span> },
    { key: 'description', header: '说明', sortValue: (row) => row.description },
    {
      key: 'actions',
      header: '',
      width: '100px',
      render: (row) => (
        <div className="row-actions">
          <button type="button" onClick={() => requestOpen(row.id)}>打开</button>
          {row.source === 'custom' && (
            <button type="button" className="danger" onClick={() => setDeleteId(row.id)}>删除</button>
          )}
        </div>
      ),
    },
  ], [client, requestOpen]);

  const validate = async () => {
    if (client === null) {
      return;
    }
    setBusy(true);
    try {
      const result = await client.validateConfig('template.json', text);
      setProblems(result.problems);
      setNotice(result.valid ? '校验通过' : '校验未通过');
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (client === null || selectedId === '') {
      return;
    }
    setBusy(true);
    try {
      const status = await client.updateTemplate(selectedId, text);
      if (status === 200) {
        setBaseline(text);
        setProblems([]);
        setNotice(`已保存模板 ${selectedId}`);
        await refresh();
      } else {
        setNotice(`保存失败（HTTP ${status}）`);
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
        await open(id);
      } else {
        notifier.error('新建模板失败');
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel
      id="panel-templates"
      title="模板管理"
      description="设备模板即完整设备配置。内置模板只读，可复制成自定义模板后修改；工作台新建设备只选模板。"
      actions={
        <button type="button" className="primary" onClick={() => setCreateOpen(true)}>新建模板</button>
      }
    >
      <DataTable
        tableKey="templates"
        columns={columns}
        rows={templates}
        rowKey={(row) => row.id}
        searchPlaceholder="搜索模板 id / 名称 / 说明"
        emptyText="还没有模板"
      />

      {selected !== undefined && (
        <section className="panel-section">
          <div className="section-head">
            <h2>
              {selected.id}
              <span className="hint-inline"> {selected.label}</span>
              {readOnly && <span className="badge tone-muted">内置只读</span>}
            </h2>
            <div className="preview-controls">
              <button type="button" onClick={() => void validate()} disabled={busy || readOnly}>校验</button>
              <button type="button" className="primary" onClick={() => void save()} disabled={busy || readOnly || !dirty}>保存</button>
              <button type="button" onClick={() => importRef.current?.click()} disabled={readOnly}>导入 JSON</button>
              <button
                type="button"
                onClick={() => void exportJson()}
                disabled={text.trim() === ''}
                title="导出当前模板 JSON（可选择保存位置）"
              >
                导出 JSON
              </button>
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
                    setText(next);
                    setProblems([]);
                    setNotice(`已导入 ${file.name}（未保存，请先校验）`);
                  });
                }}
              />
            </div>
          </div>
          {notice !== '' && <p className="inline-note">{notice}</p>}
          {problems.length > 0 && (
            <ul className="problems">
              {problems.map((problem, index) => <li key={index}>{problem}</li>)}
            </ul>
          )}
          <JsonEditor
            value={text}
            onChange={(next) => { setText(next); setProblems([]); }}
            ariaLabel="模板 JSON 原文"
          />
        </section>
      )}

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
        <p className="hint">新模板会复制源模板内容，之后可在这里直接改 JSON；保存前会校验。</p>
      </Modal>

      <ConfirmModal
        open={pendingOpenId !== null}
        title="丢弃未保存的改动"
        danger
        confirmLabel="丢弃并打开"
        message={`${selectedId} 有未保存的改动，打开 ${pendingOpenId ?? ''} 会丢掉它们。继续？`}
        onClose={() => setPendingOpenId(null)}
        onConfirm={() => {
          const id = pendingOpenId;
          setPendingOpenId(null);
          if (id !== null) {
            void open(id);
          }
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
                if (selectedId === deleteId) {
                  setSelectedId('');
                  setText('');
                  setBaseline('');
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
