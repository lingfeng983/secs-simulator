import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConfigTemplateEntry } from '../../api';
import type { PanelBaseProps } from '../panels/types';
import { Panel } from '../../shared/ui/Panel';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { Modal } from '../../shared/ui/Modal';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { saveHint, saveTextFile } from '../../shared/lib/saveFile';
import { Icon } from '../../shared/ui/Icon';
import { SessionField } from '../editors/ConfigFormEditor';
import { TemplateConfigEditor, type TemplateConfigPage } from '../editors/TemplateConfigEditor';
import { saveTemplateDraft } from '../editors/templateSave';
import { useParamTemplates } from '../../state/useParamTemplates';

/**
 * 从空白创建用的最小可编译骨架（等价于后端 DeviceTemplates 的 bare）。
 *
 * <p>真实后端的模板目录第一项是内置 bare，mock 环境没有；把骨架放在前端，
 * mock / 无源模板时「新建模板」也能走通，而不是只弹「没有可复制的源模板」。
 * 内容保持最小：一份能编译的配置，剩下的留给各页签去配。</p>
 */
const BLANK_TEMPLATE_TEXT = `{
  "schemaVersion": 7,
  "session": {
    "id": "line1",
    "passive": true,
    "bindHost": "127.0.0.1",
    "port": 5010,
    "deviceId": 0,
    "seed": 3
  },
  "context": [
    { "id": "LOTID", "type": "ASCII", "source": { "mode": "cycle", "pattern": "LOT{02}", "start": 1, "every": 25 } },
    { "id": "WAFERID", "type": "ASCII", "source": { "mode": "cycle", "pattern": "W{03}", "start": 1 } }
  ],
  "equipment": {
    "name": "LINE1",
    "idSpace": {},
    "units": [
      {
        "unitId": 1,
        "name": "CH1",
        "injectUnitId": false,
        "svids": [
          { "offset": 1, "slot": "TEMP", "type": "F4", "unit": "C",
            "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "sine", "min": 150, "max": 350, "periodMs": 60000 } } }
        ],
        "reports": [ { "offset": 1, "slot": "STATUS", "svids": ["TEMP"] } ],
        "ceids": [ { "offset": 1, "slot": "STATUS_REPORT", "reports": ["STATUS"] } ]
      }
    ]
  },
  "events": { "maxPerSec": 5, "plans": [] }
}`;

interface TemplateEditorState {
  id: string;
  source: 'new' | 'builtin' | 'custom';
  sourceId: string;
  text: string;
  baseline: string;
  saveAsId: string;
}

export function TemplatePage({ backend, notifier }: PanelBaseProps): JSX.Element {
  const client = backend.client;
  const library = useParamTemplates(backend);
  const [templates, setTemplates] = useState<ConfigTemplateEntry[]>([]);
  const [editor, setEditor] = useState<TemplateEditorState | null>(null);
  const [page, setPage] = useState<TemplateConfigPage>('overview');
  const [problems, setProblems] = useState<string[]>([]);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [pendingSource, setPendingSource] = useState<string | null>(null);
  const importRef = useRef<HTMLInputElement | null>(null);
  const busyRef = useRef(false);

  const dirty = editor !== null && editor.text !== editor.baseline;
  const builtin = editor?.source === 'builtin';
  const creating = editor?.source === 'new';
  const targetId = (builtin ? editor?.saveAsId : editor?.id)?.trim() ?? '';
  const duplicate = (builtin || creating) && templates.some((item) => item.id === targetId);
  const idValid = /^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(targetId);
  const canSave = editor !== null && idValid && !duplicate && (creating || builtin || dirty);

  const refresh = useCallback(async () => {
    if (client !== null) setTemplates(await client.templates());
  }, [client]);

  useEffect(() => {
    void refresh().catch((error) => notifier.error('读取模板清单失败', (error as Error).message));
  }, [refresh, notifier]);

  const openEditor = async (row?: ConfigTemplateEntry) => {
    if (client === null || busyRef.current) return;
    const sourceId = row?.id ?? (templates.find((item) => item.id === 'bare') ?? templates[0])?.id;
    if (sourceId === undefined) {
      /* 没有可复制的源模板（mock 环境常见）：从空白骨架创建，而不是只报错。
         骨架自带合法 session / equipment，切到各页签就能直接改，保存时仍需填写模板 id。 */
      setEditor({
        id: '', source: 'new', sourceId: 'bare',
        text: BLANK_TEMPLATE_TEXT, baseline: BLANK_TEMPLATE_TEXT, saveAsId: 'my-template',
      });
      setPage('overview');
      setProblems([]);
      setNotice('当前没有可复制的模板，已从空白骨架开始；填好模板 id 后保存即可创建。');
      return;
    }
    busyRef.current = true;
    setBusy(true);
    try {
      const text = await client.templateText(sourceId);
      if (text === null) {
        notifier.error('读取模板失败');
        return;
      }
      setEditor({
        id: row?.id ?? '', source: row === undefined ? 'new' : row.source === 'builtin' ? 'builtin' : 'custom',
        sourceId, text, baseline: text, saveAsId: `${sourceId}-custom`,
      });
      setPage('overview');
      setProblems([]);
      setNotice('');
    } catch (error) {
      notifier.error('读取模板失败', (error as Error).message);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const requestClose = () => {
    if (busyRef.current) return;
    if (dirty || (creating && targetId !== '')) setConfirmDiscard(true);
    else setEditor(null);
  };

  const changeText = (text: string) => {
    setEditor((previous) => previous === null ? null : { ...previous, text });
    setProblems([]);
    setNotice('');
  };

  const replaceSource = async (sourceId: string) => {
    if (client === null || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const text = await client.templateText(sourceId);
      if (text === null) { notifier.error('读取源模板失败'); return; }
      setEditor((previous) => previous === null ? null : { ...previous, sourceId, text, baseline: text });
      setProblems([]);
      setNotice('已复制源模板，可切换页签继续配置，保存后才会创建。');
    } catch (error) {
      notifier.error('读取源模板失败', (error as Error).message);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const validate = async () => {
    if (client === null || editor === null || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const result = await client.validateConfig('template.json', editor.text);
      setProblems(result.problems);
      setNotice(result.valid ? '校验通过' : '校验未通过');
    } catch (error) {
      notifier.error('校验失败', (error as Error).message);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const save = async () => {
    if (client === null || editor === null || !canSave || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const result = await saveTemplateDraft(client, { id: targetId, text: editor.text, create: creating || builtin }, templates.map((item) => item.id));
      setProblems(result.problems);
      if (!result.saved) { setNotice('未保存，请检查提示'); return; }
      setEditor({ ...editor, id: targetId, source: 'custom', baseline: editor.text, saveAsId: '' });
      setNotice(`已保存模板 ${targetId}`);
      notifier.success(`已保存模板 ${targetId}`);
      await refresh();
    } catch (error) {
      notifier.error('保存模板失败', (error as Error).message);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const exportJson = async () => {
    if (editor === null) return;
    /* 文件名只用通过校验的 id：id 非法/为空时回落固定名，避免把未规范化的
       用户输入（如 ../x、a/b）当作路径片段交给另存为对话框。 */
    const base = idValid ? targetId : 'template';
    const result = await saveTextFile(`${base}.json`, editor.text, 'application/json;charset=utf-8');
    const hint = saveHint(result);
    if (hint !== '') notifier.success('已导出模板', hint);
  };

  const importJson = async (file: File) => {
    busyRef.current = true;
    setBusy(true);
    try {
      changeText(await file.text());
      setPage('document');
      setNotice(`已导入 ${file.name}（未保存，请先校验）`);
    } catch (error) {
      notifier.error('导入失败', (error as Error).message);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const columns: Column<ConfigTemplateEntry>[] = [
    { key: 'id', header: '模板 id', width: '150px', sortValue: (row) => row.id, render: (row) => <span className="mono">{row.id}</span> },
    { key: 'label', header: '名称', sortValue: (row) => row.label },
    { key: 'source', header: '来源', width: '80px', sortValue: (row) => row.source ?? '', render: (row) => <span className={`badge ${row.source === 'builtin' ? 'tone-muted' : 'tone-info'}`}>{row.source === 'builtin' ? '内置' : '自定义'}</span> },
    { key: 'description', header: '说明', sortValue: (row) => row.description },
    { key: 'actions', header: '操作', width: '120px', render: (row) => (
      <div className="row-actions">
        <button type="button" onClick={() => void openEditor(row)} disabled={busy}>编辑</button>
        {row.source !== 'builtin' && <button type="button" className="danger" disabled={busy} onClick={() => setDeleteId(row.id)}>删除</button>}
      </div>
    ) },
  ];

  return (
    <Panel id="panel-templates" title="设备模板" headless>
      {templates.length === 0 ? (
        /* 空态也要给下一步：直接放「新建模板」CTA，而不是只有一行"还没有模板"。
           无源模板时 openEditor() 会从空白骨架开始。 */
        <div className="empty-state">
          <p className="empty-text">还没有设备模板。</p>
          <p className="empty-hint">
            从空白骨架新建一份最小可编译配置；也可以在编辑器里导入 JSON，
            改完填写模板 id 后保存。
          </p>
          <div className="empty-action">
            <button type="button" className="primary" disabled={busy || client === null}
              onClick={() => void openEditor()}>
              ＋ 新建模板（从空白）
            </button>
          </div>
        </div>
      ) : (
        <DataTable tableKey="templates" columns={columns} rows={templates} rowKey={(row) => row.id}
          searchPlaceholder="搜索模板 id / 名称 / 说明" emptyText="还没有模板"
          toolbarEnd={<button type="button" className="primary" disabled={busy || client === null} onClick={() => void openEditor()}>＋ 新建模板</button>} />
      )}

      <Modal open={editor !== null} title={creating ? '新建设备模板' : `编辑设备模板 · ${editor?.id ?? ''}`}
        hint="各页修改汇总到同一份模板，点底部「保存」后才生效；已创建的设备不会随模板改变。"
        onClose={requestClose} width="min(1240px, 96vw)"
        footer={
          <>
            <span className="hint-inline template-editor-status" role="status">{busy ? '处理中…' : creating ? '尚未创建' : dirty ? '有未保存改动' : builtin ? '内置模板 · 只能另存' : '已保存'}</span>
            <button type="button" onClick={requestClose} disabled={busy}>取消</button>
            <button type="button" className="icon-btn" onClick={() => importRef.current?.click()} disabled={busy}
              title="导入 JSON：读入当前模板，保存后才生效" aria-label="导入 JSON"><Icon name="upload" size={15} /></button>
            <button type="button" className="icon-btn" onClick={() => void exportJson()} disabled={busy || editor === null}
              title="导出 JSON：保存当前模板原文" aria-label="导出 JSON"><Icon name="download" size={15} /></button>
            <button type="button" onClick={() => void validate()} disabled={busy || editor === null}>校验</button>
            <button type="button" className="primary" onClick={() => void save()} disabled={busy || !canSave}
              title={duplicate ? '模板 id 已存在' : !idValid ? '请在概览中填写有效的模板 id' : undefined}>
              {builtin ? '另存为模板' : creating ? '保存并创建' : '保存'}
            </button>
          </>
        }>
        {editor !== null && (
          <>
            {notice !== '' && <p className="inline-note" role="status">{notice}</p>}
            {problems.length > 0 && <ul className="problems">{problems.map((problem, index) => <li key={index}>{problem}</li>)}</ul>}
            <fieldset className="template-editor-fields" disabled={busy}>
              <TemplateConfigEditor text={editor.text} onChange={changeText} page={page} onSelect={setPage}
                backend={backend} library={library.entries}
                overview={
                  <>
                    <SessionField label={builtin ? '另存为模板 id' : '模板 id'} help="以字母或数字开头，可包含点、下划线和短横线；保存后不能修改。">
                      <input value={builtin ? editor.saveAsId : editor.id} disabled={!creating && !builtin}
                        onChange={(event) => setEditor({ ...editor, ...(builtin ? { saveAsId: event.target.value } : { id: event.target.value }) })}
                        placeholder="如 my-etch" aria-label={builtin ? '另存为模板 id' : '模板 id'} />
                    </SessionField>
                    {duplicate && <p className="problems">模板 id 已存在，请换一个。</p>}
                    {creating && templates.length > 0 && (
                      <SessionField label="从哪个模板复制" help="复制连接、单元、变量和事件定义；在其它页签修改后统一保存。">
                        <select value={editor.sourceId} aria-label="从哪个模板复制" onChange={(event) => {
                          if (dirty) setPendingSource(event.target.value);
                          else void replaceSource(event.target.value);
                        }}>
                          {templates.map((item) => <option key={item.id} value={item.id}>{item.id} · {item.label}</option>)}
                        </select>
                      </SessionField>
                    )}
                    {creating && templates.length === 0 && (
                      <p className="hint">当前没有可复制的模板，已从空白骨架开始；在「全量配置」页可导入 JSON，其它页签可直接改。填好模板 id 后保存即可创建。</p>
                    )}
                    {builtin && <p className="hint">内置模板不能覆盖，修改后会另存为自定义模板。</p>}
                  </>
                } />
            </fieldset>
            <input ref={importRef} type="file" accept=".json,application/json,text/plain" hidden onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = '';
              if (file !== undefined) void importJson(file);
            }} />
          </>
        )}
      </Modal>

      <ConfirmModal open={confirmDiscard} title="丢弃未保存的改动" danger confirmLabel="丢弃并关闭"
        message="关闭后会丢弃各页尚未保存的修改，新建模板不会被创建。继续？"
        onClose={() => setConfirmDiscard(false)} onConfirm={() => { setConfirmDiscard(false); setEditor(null); }} />
      <ConfirmModal open={pendingSource !== null} title="更换源模板" confirmLabel="更换"
        message="更换源模板会替换各页尚未保存的配置，模板 id 保持不变。继续？"
        onClose={() => setPendingSource(null)} onConfirm={() => {
          const source = pendingSource;
          setPendingSource(null);
          if (source !== null) void replaceSource(source);
        }} />
      <ConfirmModal open={deleteId !== null} title="删除模板" danger confirmLabel="删除"
        message={`确定删除模板 ${deleteId ?? ''}？已用它创建的设备配置不受影响。`}
        onClose={() => setDeleteId(null)} onConfirm={() => {
          if (deleteId !== null && client !== null) {
            void client.deleteTemplate(deleteId).then(async (status) => {
              if (status === 200) { notifier.success(`已删除模板 ${deleteId}`); await refresh(); }
              else notifier.error('删除模板失败');
            }).catch((error) => notifier.error('删除模板失败', (error as Error).message));
          }
          setDeleteId(null);
        }} />
    </Panel>
  );
}
