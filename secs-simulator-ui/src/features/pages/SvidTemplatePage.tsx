import { useMemo, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { Panel } from '../../shared/ui/Panel';
import { TemplateEditor } from '../editors/TemplateEditor';
import { parseConfigDocument } from '../editors/templateConfig';
import { useConfigDraft } from '../../state/useConfigDraft';

/**
 * 参数模板（独立工具）：SVID 变化模板（曲线/组合/实例）按设备配置维护。
 *
 * <p>模板跟设备配置走（config.db 的 templates 段），这里的编辑与「结构」页
 * 同一条草稿管线：改动写回草稿，「校验」→「保存」落库并让相关会话重载。
 * 变量配置里的值来源「引用模板」直接列出这里的模板 id；设备配置区不再做
 * 模板管理。</p>
 */
/**
 * SVID 变化模板管理：按设备配置维护 templates 段（曲线/组合/实例）。
 *
 * <p>模板跟着配置文档走（config.db），这里的编辑与「结构」页同一条
 * 草稿管线：改动写回草稿，「校验」→「保存」落库并让相关会话重载。
 * 变量配置里的值来源「引用模板」直接列出这里的模板 id。</p>
 */
export function SvidTemplatePage({ backend, notifier, preferences, updatePreferences }: PanelBaseProps): JSX.Element {
  const configs = backend.configList;
  const [configName, setConfigName] = useState('');
  const draft = useConfigDraft(backend, '', configName);
  const parsed = useMemo(() => parseConfigDocument(draft.text), [draft.text]);

  return (
    <Panel
      id="panel-svid-templates"
      title="参数模板（SVID 变化模板）"
      description={
        '曲线 / 组合 / 实例按设备配置保存在配置文档里（config.db）。\n'
        + '选一台设备配置后即可维护它的模板；变量配置里通过「引用模板」绑定，设备配置区不再做模板管理。'
      }
      status={
        configName === ''
          ? <span className="state-pill" data-tone="muted">未选择配置</span>
          : draft.dirty
            ? <span className="state-pill" data-tone="warn">有未保存改动</span>
            : undefined
      }
      actions={
        <>
          <button type="button" onClick={() => void draft.reload()} disabled={draft.busy || configName === ''}>
            重新载入
          </button>
          <button type="button" onClick={() => void draft.validate()} disabled={draft.busy || configName === ''}>
            校验
          </button>
          <button
            type="button"
            className="primary"
            onClick={() => void draft.save()}
            disabled={draft.busy || configName === ''}
          >
            保存
          </button>
        </>
      }
    >
      <label className="field">
        设备配置
        <select value={configName} onChange={(event) => setConfigName(event.target.value)}>
          <option value="">选择配置…</option>
          {configs.map((entry) => <option key={entry.name} value={entry.name}>{entry.name}</option>)}
        </select>
      </label>

      {configName === '' ? (
        <p className="hint">先选择要维护的设备配置；列表来自配置库。</p>
      ) : !parsed.ok ? (
        <p className="hint">
          配置原文不是合法 JSON：<code>{parsed.error}</code>。先修好原文，模板表单才能动手。
        </p>
      ) : (
        <>
          {draft.notice !== '' && <p className="inline-note">{draft.notice}</p>}
          {draft.problems.length > 0 && (
            <ul className="problems">
              {draft.problems.map((problem, index) => <li key={index}>{problem}</li>)}
            </ul>
          )}
          <TemplateEditor text={draft.text} onChange={draft.setText} />
        </>
      )}
    </Panel>
  );
}
