import { useMemo, useState } from 'react';
import { ConfirmModal } from '../../../shared/ui/ConfirmModal';
import { DataTable, type Column } from '../../../shared/ui/DataTable';
import { Modal } from '../../../shared/ui/Modal';
import { Panel } from '../../../shared/ui/Panel';
import { planId, useHostSendDraft } from '../../../state/hostPlans';
import {
  HOST_PRESETS,
  PRESET_GROUPS,
  mergePresets,
  presetTxfy,
  readPresetStore,
  writePresetStore,
  type HostPreset,
  type PresetOverride,
  type PresetStore,
} from './hostPresets';
import type { PanelArgs } from './hostPanelShared';

/** 表格行：模板本体 + 表格里的序号。 */
type PresetRow = HostPreset & { seq: number };

/** 新增/编辑弹窗的表单值。 */
interface PresetForm extends PresetOverride {
  /** 是否新增（只影响弹窗标题）。 */
  isNew: boolean;
}

/**
 * 报文模板：以表格列出 SxFy 与具体 SML 案例，可筛选、新增、编辑、删除，
 * 双击某行打开报文并可一键填进运行区「消息发送」。
 *
 * <p>内置模板正文仍来自 `hostPresets.ts` 的常量；用户的增删改只落
 * localStorage（`secs.host.presets.v1`）作为覆盖层，不改源码。</p>
 */
export function HostPresetsPanel({ hostId }: PanelArgs): JSX.Element {
  const [, setSendDraft] = useHostSendDraft(hostId, 'send');
  const [store, setStore] = useState<PresetStore>(readPresetStore);
  const [group, setGroup] = useState('全部');
  const [form, setForm] = useState<PresetForm | null>(null);
  const [formError, setFormError] = useState('');
  const [opened, setOpened] = useState<HostPreset | null>(null);
  const [pendingDelete, setPendingDelete] = useState<HostPreset | null>(null);
  const [notice, setNotice] = useState('');

  const merged = useMemo(() => mergePresets(store), [store]);
  const groupOptions = useMemo(
    () => [...new Set([...HOST_PRESETS.map((preset) => preset.group), ...merged.map((preset) => preset.group)])],
    [merged],
  );
  const visible = useMemo(
    () => (group === '全部' ? merged : merged.filter((preset) => preset.group === group)),
    [merged, group],
  );
  const rows: PresetRow[] = useMemo(
    () => visible.map((preset, index) => ({ ...preset, seq: index + 1 })),
    [visible],
  );
  const overridden = store.deleted.length > 0 || store.custom.length > 0;

  const commit = (next: PresetStore) => {
    setStore(next);
    writePresetStore(next);
  };

  const openNew = () => {
    setFormError('');
    setForm({
      id: planId(),
      label: '',
      group: group === '全部' ? groupOptions[0] ?? '自定义' : group,
      desc: '',
      reply: '',
      sml: '',
      isNew: true,
    });
  };

  const openEdit = (preset: HostPreset) => {
    setFormError('');
    const existing = store.custom.find((item) => item.label === preset.label);
    setForm({
      id: existing?.id ?? planId(),
      label: preset.label,
      group: preset.group,
      desc: preset.desc,
      reply: preset.reply,
      sml: preset.sml,
      isNew: existing === undefined && !HOST_PRESETS.some((item) => item.label === preset.label),
    });
  };

  const saveForm = () => {
    if (form === null) {
      return;
    }
    const label = form.label.trim();
    const sml = form.sml.trim();
    if (label === '' || sml === '') {
      setFormError('模板名称与 SML 正文不能为空');
      return;
    }
    const entry: PresetOverride = {
      id: form.id,
      label,
      group: form.group.trim() === '' ? '自定义' : form.group.trim(),
      desc: form.desc.trim(),
      reply: form.reply.trim() === '' ? '—' : form.reply.trim(),
      sml: form.sml,
    };
    const exists = store.custom.some((item) => item.id === entry.id || item.label === label);
    const custom = exists
      ? store.custom.map((item) => (item.id === entry.id || item.label === label ? entry : item))
      : [...store.custom, entry];
    commit({ deleted: store.deleted.filter((item) => item !== label), custom });
    setNotice(`已保存「${label}」`);
    setForm(null);
  };

  const removePreset = (preset: HostPreset) => {
    const builtin = HOST_PRESETS.some((item) => item.label === preset.label);
    commit({
      deleted: builtin && !store.deleted.includes(preset.label)
        ? [...store.deleted, preset.label]
        : store.deleted,
      custom: store.custom.filter((item) => item.label !== preset.label),
    });
    setNotice(`已删除「${preset.label}」`);
  };

  const fillSend = (preset: HostPreset) => {
    setSendDraft(preset.sml);
    setNotice(`已把「${preset.label}」填入运行区 · 消息发送`);
  };

  const copySml = (preset: HostPreset) => {
    void navigator.clipboard?.writeText(preset.sml)
      .then(() => setNotice(`已复制「${preset.label}」的 SML`))
      .catch(() => setNotice('复制失败，请手动选中文本框中内容'));
  };

  const columns: Column<PresetRow>[] = [
    {
      key: 'seq',
      header: '序号',
      width: '64px',
      sortValue: (row) => row.seq,
      render: (row) => row.seq,
    },
    {
      key: 'txfy',
      header: 'SxFy',
      width: '96px',
      sortValue: presetTxfy,
      render: (row) => <code>{presetTxfy(row)}</code>,
    },
    {
      key: 'desc',
      header: '描述',
      sortValue: (row) => `${row.label} ${row.group} ${row.desc}`,
      render: (row) => (
        <span>
          <strong>{row.label}</strong>
          <span className="hint-inline"> · {row.group} · 回复 {row.reply}</span>
          {row.desc !== '' && <span className="hint-inline" style={{ display: 'block' }}>{row.desc}</span>}
        </span>
      ),
    },
    {
      key: 'actions',
      header: '操作',
      width: '150px',
      render: (row) => (
        /* 双击按钮不该顺手打开报文：动作块自己吞掉双击 */
        <span className="toolbar" onDoubleClick={(event) => event.stopPropagation()}>
          <button type="button" onClick={() => openEdit(row)}>编辑</button>
          <button type="button" className="danger" onClick={() => setPendingDelete(row)}>删除</button>
        </span>
      ),
    },
  ];

  return (
    <>
      <Panel id="panel-host-presets" title="报文模板 · SML 案例" headless>
        {notice !== '' && <p className="inline-note">{notice}</p>}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.label}
          searchPlaceholder="筛选报文（SxFy / 名称 / 描述）"
          searchText={(row) => `${row.label} ${presetTxfy(row)} ${row.group} ${row.desc} ${row.sml}`}
          toolbar={
            <label className="field">
              分组
              <select value={group} onChange={(event) => setGroup(event.target.value)}>
                <option value="全部">全部</option>
                {groupOptions.map((item) => (
                  <option key={item} value={item}>{item}</option>
                ))}
              </select>
            </label>
          }
          toolbarEnd={
            <>
              {overridden && (
                <button
                  type="button"
                  onClick={() => {
                    commit({ deleted: [], custom: [] });
                    setNotice('已恢复默认模板');
                  }}
                  title="清空自定义并恢复内置模板"
                >
                  恢复默认
                </button>
              )}
              <button type="button" className="primary" onClick={openNew}>新增</button>
            </>
          }
          emptyText="没有匹配的报文模板"
          onRowDoubleClick={setOpened}
        />
      </Panel>

      <Modal
        open={opened !== null}
        title={opened === null ? '报文' : `${opened.label} · → ${opened.reply}`}
        onClose={() => setOpened(null)}
        width="min(720px, 92vw)"
        footer={
          <>
            <button
              type="button"
              onClick={() => {
                if (opened !== null) {
                  openEdit(opened);
                }
                setOpened(null);
              }}
            >
              编辑
            </button>
            <button
              type="button"
              onClick={() => {
                if (opened !== null) {
                  copySml(opened);
                }
              }}
            >
              复制 SML
            </button>
            <button
              type="button"
              className="primary"
              onClick={() => {
                if (opened !== null) {
                  fillSend(opened);
                }
                setOpened(null);
              }}
            >
              填入运行区发送
            </button>
          </>
        }
      >
        {opened !== null && (
          <>
            <p className="inline-note">{opened.group}{opened.desc === '' ? '' : ` · ${opened.desc}`}</p>
            <pre>{opened.sml}</pre>
          </>
        )}
      </Modal>

      <Modal
        open={form !== null}
        title={form?.isNew === true ? '新增报文模板' : '编辑报文模板'}
        onClose={() => setForm(null)}
        width="min(760px, 92vw)"
        footer={
          <>
            <button type="button" onClick={() => setForm(null)}>取消</button>
            <button type="button" className="primary" onClick={saveForm}>保存</button>
          </>
        }
      >
        {form !== null && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: 10 }}>
            <div className="form-row">
              <label className="field grow">
                名称
                <input
                  value={form.label}
                  onChange={(event) => setForm({ ...form, label: event.target.value })}
                  placeholder="例如 S1F17 上线"
                />
              </label>
              <label className="field">
                分组
                <select
                  value={form.group}
                  onChange={(event) => setForm({ ...form, group: event.target.value })}
                  aria-label="分组"
                >
                  {!groupOptions.includes(form.group) && <option value={form.group}>{form.group}</option>}
                  {groupOptions.map((item) => (
                    <option key={item} value={item}>{item}</option>
                  ))}
                </select>
              </label>
              <label className="field">
                回复
                <input
                  value={form.reply}
                  onChange={(event) => setForm({ ...form, reply: event.target.value })}
                  placeholder="S1F18"
                />
              </label>
            </div>
            <label className="field">
              描述
              <textarea
                rows={3}
                style={{ width: '100%', resize: 'vertical' }}
                value={form.desc}
                onChange={(event) => setForm({ ...form, desc: event.target.value })}
                placeholder="一句话说明这条报文干什么"
              />
            </label>
            <label className="field">
              SML
              <textarea
                rows={10}
                style={{ fontFamily: 'var(--font-mono)', width: '100%' }}
                value={form.sml}
                onChange={(event) => setForm({ ...form, sml: event.target.value })}
                placeholder={'S1F1 W.'}
              />
            </label>
            {formError !== '' && <p className="inline-note" role="alert">{formError}</p>}
          </div>
        )}
      </Modal>

      <ConfirmModal
        open={pendingDelete !== null}
        title="删除报文模板"
        message={pendingDelete === null ? '' : `删除「${pendingDelete.label}」后该模板不再出现在列表里；内置模板可用「恢复默认」找回。`}
        confirmLabel="删除"
        danger
        onConfirm={() => {
          if (pendingDelete !== null) {
            removePreset(pendingDelete);
          }
        }}
        onClose={() => setPendingDelete(null)}
      />
    </>
  );
}