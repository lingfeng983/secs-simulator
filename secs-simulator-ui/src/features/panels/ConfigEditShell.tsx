import type { ReactNode } from 'react';
import { Panel } from '../../shared/ui/Panel';
import { editDocument, isJsonObject, parseConfigDocument, type JsonObject } from '../editors/templateConfig';
import { useConfigDraft } from '../../state/useConfigDraft';
import type { BackendController } from '../../state/useBackend';

type Edit = (mutate: (root: JsonObject) => void) => void;

/** Shell 传给内容区的编辑上下文：解析结果 + 整文档编辑函数 + 草稿原文。 */
export interface ConfigEditContext {
  root: JsonObject;
  edit: Edit;
  /** 当前草稿原文（未保存的表单改动已在其中），供草稿级预览等场景。 */
  text: string;
}

/**
 * 配置区结构化编辑页的公共外壳：草稿载入 / 校验 / 保存 / 重新载入，
 * 加上「原文不是合法 JSON」与「无配置来源」两个兜底。
 *
 * <p>变量 / 事件与报告 / 事件计划 / 设备常量四个专项页共用同一套纪律：
 * 原文是唯一事实来源，表单改动经 editDocument 写回草稿，保存走
 * useConfigDraft 的统一路径（保存后相关会话自动重载）。</p>
 */
export function ConfigEditShell({
  backend,
  sessionId,
  configName,
  panelId,
  title,
  children,
}: {
  backend: BackendController;
  /** 设备会话 id；缺省用全局 activeSession。 */
  sessionId?: string;
  /** 显式配置名（数据库里还没启动的设备没有会话行，设备页直接给配置文件名）。 */
  configName?: string;
  panelId: string;
  title: string;
  /** 拿到解析后的根对象与编辑函数；非法 JSON 时不会调用。 */
  children: (context: ConfigEditContext) => ReactNode;
}): JSX.Element {
  const deviceId = sessionId ?? backend.activeSession;
  const draft = useConfigDraft(backend, deviceId, configName);
  const parsed = parseConfigDocument(draft.text);

  const edit: Edit = (mutate) => {
    const result = editDocument(draft.text, mutate);
    if (result.ok) {
      draft.setText(result.text);
    }
  };

  return (
    <Panel
      id={panelId}
      title={title}
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
        <p className="hint">这台设备没有配置文件来源（演示会话）。可在「配置原文」页新建一份配置后再编辑。</p>
      ) : !parsed.ok ? (
        <p className="hint">
          配置原文不是合法 JSON：<code>{parsed.error}</code>。先到「配置原文」页修好原文，表单才能动手。
        </p>
      ) : (
        <>
          {draft.notice !== '' && <p className="inline-note">{draft.notice}</p>}
          {draft.problems.length > 0 && (
            <ul className="problems">
              {draft.problems.map((problem, index) => <li key={index}>{problem}</li>)}
            </ul>
          )}
          {children({ root: parsed.config.root, edit, text: draft.text })}
        </>
      )}
    </Panel>
  );
}

/** 结构化编辑页通用的「equipment 段缺失」兜底：一键插入最小 equipment。 */
export function MissingEquipment({ edit }: { edit: Edit }): JSX.Element {
  return (
    <div className="cfg-section">
      <button
        type="button"
        onClick={() => edit((draft) => {
          if (!isJsonObject(draft.equipment)) {
            draft.equipment = {
              name: 'DEV-01',
              idSpace: { svidBase: 1000, ceidBase: 2000, reportBase: 3000, svidStride: 100, ceidStride: 100, reportStride: 100 },
              units: [],
            };
          }
        })}
      >
        插入 equipment 段
      </button>
      <p className="hint">这份配置还没有 equipment 段（设备名、号段、单元）。</p>
    </div>
  );
}
