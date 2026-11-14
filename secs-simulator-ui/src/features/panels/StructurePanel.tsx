import { useMemo, useState } from 'react';
import type { PanelBaseProps } from './types';
import { Panel } from '../../shared/ui/Panel';
import { EquipmentForm, templateIdsOf } from '../editors/ConfigFormEditor';
import { editDocument, isJsonObject, parseConfigDocument, type JsonObject } from '../editors/templateConfig';
import { useConfigDraft } from '../../state/useConfigDraft';

type StructureTab = 'global' | 'units';

/**
 * 设备 · 结构：配置里的 equipment 段。
 *
 * <p>按"全局 / 单元"两个页签拆开：改设备名和号段时不用翻过一长串
 * 单元卡片。SVID 的曲线/组合模板管理已上收独立工具「模板管理」——
 * 这里只改设备本体，值来源引用模板在「变量配置」页绑定。</p>
 */
export function StructurePanel({ backend, notifier, sessionId, configName }: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const deviceId = sessionId ?? backend.activeSession;
  const draft = useConfigDraft(backend, deviceId, configName);
  const parsed = useMemo(() => parseConfigDocument(draft.text), [draft.text]);
  const [tab, setTab] = useState<StructureTab>('global');

  const templateIds = useMemo(
    () => (parsed.ok ? templateIdsOf(parsed.config.root) : []),
    [parsed],
  );

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
      description={
        '设备全局：名称 / 号段 / 全局 SVID / CEID / 常量\n'
        + '单元：每个腔体或工位的标识信息\n'
        + 'SVID 的曲线/组合模板在独立工具「模板管理」维护，变量配置里直接绑定'
      }
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
          <div className="segmented structure-tabs" role="tablist" aria-label="结构分区">
            {([
              ['global', '设备全局'],
              ['units', '单元'],
            ] as const).map(([key, label]) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={tab === key}
                className={`segment${tab === key ? ' active' : ''}`}
                onClick={() => setTab(key)}
              >
                {label}
              </button>
            ))}
          </div>

          {draft.notice !== '' && <p className="inline-note">{draft.notice}</p>}
          {draft.problems.length > 0 && (
            <ul className="problems">
              {draft.problems.map((problem, index) => <li key={index}>{problem}</li>)}
            </ul>
          )}

          {tab === 'global' && (
            <EquipmentForm
              root={parsed.config.root}
              edit={edit}
              templateIds={templateIds}
              section="global"
            />
          )}
          {tab === 'units' && (
            <EquipmentForm
              root={parsed.config.root}
              edit={edit}
              templateIds={templateIds}
              section="units"
            />
          )}
        </>
      )}
    </Panel>
  );
}
