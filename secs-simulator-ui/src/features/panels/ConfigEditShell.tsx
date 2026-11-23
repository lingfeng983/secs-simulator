import type { ReactNode } from 'react';
import { Panel } from '../../shared/ui/Panel';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { editDocument, isJsonObject, parseConfigDocument, type JsonObject } from '../editors/templateConfig';
import { useConfigDraft, type DraftConfirm } from '../../state/useConfigDraft';
import { sectionLabels } from '../../shared/lib/configSections';
import type { ConfigSectionName } from '../../api';
import type { BackendController } from '../../state/useBackend';

type Edit = (mutate: (root: JsonObject) => void) => void;

/** Shell 传给内容区的编辑上下文：解析结果 + 整文档编辑函数 + 草稿原文 + 直接保存。 */
export interface ConfigEditContext {
  root: JsonObject;
  edit: Edit;
  /** 当前草稿原文（未保存的表单改动已在其中），供草稿级预览等场景。 */
  text: string;
  /** 行内动作（新建/删除确认后）直接落盘：只提交本页拥有的段。 */
  save: () => Promise<void>;
  /** 保存请求在途。 */
  busy: boolean;
}

/** 顶部动作组：默认三个全给；'save' 只留保存；'none' 全收（行内动作直接保存的页面）。 */
export type ShellActions = 'save-validate-reload' | 'save' | 'none';

/**
 * 配置区结构化编辑页的公共外壳：草稿载入 / 校验 / 保存 / 重新载入，
 * 加上「原文不是合法 JSON」与「无配置来源」两个兜底。
 *
 * <p>变量 / 事件与报告 / 事件计划 / 设备常量四个专项页共用同一套纪律：
 * 原文是唯一事实来源，表单改动经 editDocument 写回草稿，保存走
 * useConfigDraft 的统一路径（保存后相关会话自动重载）。</p>
 *
 * <p>2026-11-20：页面上不再常驻「本页有未保存改动 / 其它页 N 段未保存」
 * 提示（未保存与否由页签小圆点表达）；行内动作直接保存的页面传
 * {@code actions="none"}，弹窗确认后由页面自己调 {@code save()}。</p>
 */
export function ConfigEditShell({
  backend,
  sessionId,
  configName,
  panelId,
  title,
  sections,
  confirmOnSave,
  actions = 'save-validate-reload',
  children,
}: {
  backend: BackendController;
  /** 设备会话 id；缺省用全局 activeSession。 */
  sessionId?: string;
  /** 显式配置名（数据库里还没启动的设备没有会话行，设备页直接给配置文件名）。 */
  configName?: string;
  panelId: string;
  title: string;
  /**
   * 本页拥有哪些配置段（2026-11-18 按页保存）：保存只提交这些段、只让它们生效。
   * 不传 = 整份保存（配置原文页的场景，那个页面不走这个外壳）。
   */
  sections?: readonly ConfigSectionName[];
  /** 设备启动中保存会打断运行时的二次确认（连接页用：保存要重建会话）。 */
  confirmOnSave?: { title: string; message: string; confirmLabel: string };
  /** 顶部动作组（2026-11-20）：默认 重新载入/校验/保存；'save' 只留保存；'none' 全收。 */
  actions?: ShellActions;
  /** 拿到解析后的根对象与编辑函数；非法 JSON 时不会调用。 */
  children: (context: ConfigEditContext) => ReactNode;
}): JSX.Element {
  const deviceId = sessionId ?? backend.activeSession;
  const draft = useConfigDraft(backend, deviceId, configName, sections, confirmOnSave);
  /* 2026-11-21：结构段不再有「设备已启动禁保存」守卫——随时可保存，
     结构改动经 applySavedSections 延迟到设备重启后生效。 */
  const parsed = parseConfigDocument(draft.text);

  const edit: Edit = (mutate) => {
    const result = editDocument(draft.text, mutate);
    if (result.ok) {
      draft.setText(result.text);
    }
  };

  const save = () => draft.save();

  return (
    <Panel
      id={panelId}
      title={title}
      headless
      actionsAt="top"
      status={
        draft.configName === ''
          ? <span className="state-pill" data-tone="muted">无配置来源</span>
          : undefined
      }
      actions={actions === 'none'
        ? undefined
        : (
          <>
            {actions === 'save-validate-reload' && (
              <button type="button" onClick={() => void draft.reload()} disabled={draft.busy || draft.configName === ''}>
                重新载入
              </button>
            )}
            {actions === 'save-validate-reload' && (
              <button type="button" onClick={() => void draft.validate()} disabled={draft.busy || draft.configName === ''}>
                校验
              </button>
            )}
            <button
              type="button"
              className="primary"
              onClick={() => void draft.save()}
              disabled={draft.busy || draft.configName === ''}
              title={sections === undefined ? undefined : `只保存并生效：${sectionLabels(sections)}`}
            >
              保存
            </button>
          </>
        )}
    >
      <DraftConfirmModal draft={draft} />
      {draft.configName === '' ? (
        <p className="hint">这台设备没有配置文件来源（演示会话）。可在「全量配置」页新建一份配置后再编辑。</p>
      ) : !parsed.ok ? (
        <p className="hint">
          配置原文不是合法 JSON：<code>{parsed.error}</code>。先到「全量配置」页修好原文，表单才能动手。
        </p>
      ) : (
        <>
          {draft.notice !== '' && <p className="inline-note">{draft.notice}</p>}
          {draft.problems.length > 0 && (
            <ul className="problems">
              {draft.problems.map((problem, index) => <li key={index}>{problem}</li>)}
            </ul>
          )}
          {children({ root: parsed.config.root, edit, text: draft.text, save, busy: draft.busy })}
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

/**
 * 草稿的确认框：常量覆盖（守卫 C）与「重新载入会丢改动」共用。
 *
 * <p>不用 {@code window.confirm}（项目统一走 {@link ConfirmModal}），
 * 也顺便让所有配置页的行为一致——包括不走 {@link ConfigEditShell} 的页面。</p>
 */
export function DraftConfirmModal({ draft }: { draft: { confirm: DraftConfirm | null; confirmPending: () => Promise<void>; cancelPending: () => void } }): JSX.Element {
  const pending = draft.confirm;
  return (
    <ConfirmModal
      open={pending !== null}
      title={pending?.title ?? ''}
      message={pending?.message ?? ''}
      confirmLabel={pending?.confirmLabel ?? '确认'}
      danger={pending?.danger ?? false}
      onConfirm={() => void draft.confirmPending()}
      onClose={draft.cancelPending}
    />
  );
}
