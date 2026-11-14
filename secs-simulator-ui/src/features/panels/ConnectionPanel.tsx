import { useMemo } from 'react';
import type { PanelBaseProps } from './types';
import { Panel } from '../../shared/ui/Panel';
import { SessionForm } from '../editors/ConfigFormEditor';
import { editDocument, parseConfigDocument, type JsonObject } from '../editors/templateConfig';
import { useConfigDraft } from '../../state/useConfigDraft';

/**
 * 设备 · 连接：配置里的 session 段。
 *
 * <p>原来这段表单在「配置」页，和 JSON 原文抢同一份事实来源；
 * 现在配置页只编辑原文，连接参数在这里结构化编辑，保存后仍然走
 * 「校验 → 保存 → 相关会话重载」的同一路径。</p>
 */
export function ConnectionPanel({ backend, sessionId }: PanelBaseProps & { sessionId?: string }): JSX.Element {
  const deviceId = sessionId ?? backend.activeSession;
  const draft = useConfigDraft(backend, deviceId);
  const parsed = useMemo(() => parseConfigDocument(draft.text), [draft.text]);

  const edit = (mutate: (root: JsonObject) => void) => {
    const result = editDocument(draft.text, mutate);
    if (result.ok) {
      draft.setText(result.text);
    }
  };

  return (
    <Panel
      id="panel-connection"
      title="连接"
      description={
        '会话标识：会话 id / deviceId / 随机种子\n'
        + '监听：HSMS 端口 / 绑定地址 / 被动模式\n'
        + '超时：T3–T8（秒，留空用协议默认）'
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
        <p className="hint">这台设备没有配置文件来源（演示会话）。可在「配置」页新建一份配置后再编辑连接参数。</p>
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
          <SessionForm root={parsed.config.root} edit={edit} />
        </>
      )}
    </Panel>
  );
}
