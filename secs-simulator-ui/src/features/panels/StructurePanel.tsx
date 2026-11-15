import { useMemo } from 'react';
import type { PanelBaseProps } from './types';
import { Panel } from '../../shared/ui/Panel';
import { EquipmentForm } from '../editors/ConfigFormEditor';
import { editDocument, parseConfigDocument, type JsonObject } from '../editors/templateConfig';
import { useConfigDraft } from '../../state/useConfigDraft';

/**
 * 设备 · 结构：配置里的 equipment 段。
 *
 * <p>号段规划与单元标识放在同一页。变量、设备常量、事件与报告
 * 各有自己的页签，这里只改设备本体。</p>
 */
export function StructurePanel({ backend, sessionId, configName }: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const deviceId = sessionId ?? backend.activeSession;
  const draft = useConfigDraft(backend, deviceId, configName);
  const parsed = useMemo(() => parseConfigDocument(draft.text), [draft.text]);

  const edit = (mutate: (root: JsonObject) => void) => {
    const result = editDocument(draft.text, mutate);
    if (result.ok) {
      draft.setText(result.text);
    }
  };

  return (
    <Panel
      id="panel-structure"
      title="结构"
      headless
      status={
        draft.configName === ''
          ? <span className="state-pill" data-tone="muted">无配置来源</span>
          : draft.dirty
            ? <span className="state-pill" data-tone="warn">有未保存改动</span>
            : undefined
      }
      actions={
        <>
          <button type="button" onClick={() => void draft.reload()} disabled={draft.busy || draft.configName === ''}>
            重新载入
          </button>
          <button type="button" onClick={() => void draft.validate()} disabled={draft.busy || draft.configName === ''}>
            校验
          </button>
          <button
            type="button"
            className="primary"
            onClick={() => void draft.save()}
            disabled={draft.busy || draft.configName === ''}
          >
            保存
          </button>
        </>
      }
    >
      {draft.configName === '' ? (
        <p className="hint">这台设备没有配置文件来源（演示会话）。可在「配置」页新建一份配置后再编辑结构。</p>
      ) : !parsed.ok ? (
        <p className="hint">
          配置原文不是合法 JSON：<code>{parsed.error}</code>。先到「配置」页修好原文，表单才能动手。
        </p>
      ) : (
        <>
          {draft.notice !== '' && <p className="inline-note">{draft.notice}</p>}
          {draft.problems.length > 0 && (
            <ul className="problems">
              {draft.problems.map((problem, index) => <li key={index}>{problem}</li>)}
            </ul>
          )}

          <EquipmentForm root={parsed.config.root} edit={edit} />
        </>
      )}
    </Panel>
  );
}
