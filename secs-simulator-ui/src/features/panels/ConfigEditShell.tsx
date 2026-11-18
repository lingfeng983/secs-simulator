import type { ReactNode } from 'react';
import { Panel } from '../../shared/ui/Panel';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { editDocument, isJsonObject, parseConfigDocument, type JsonObject } from '../editors/templateConfig';
import { useConfigDraft, type DraftConfirm } from '../../state/useConfigDraft';
import { sectionLabels } from '../../shared/lib/configSections';
import type { ConfigSectionName } from '../../api';
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
  sections,
  confirmOnSave,
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
  /** 拿到解析后的根对象与编辑函数；非法 JSON 时不会调用。 */
  children: (context: ConfigEditContext) => ReactNode;
}): JSX.Element {
  const deviceId = sessionId ?? backend.activeSession;
  const draft = useConfigDraft(backend, deviceId, configName, sections, confirmOnSave);
  /**
   * 结构页的守卫 A：设备「启动着」就不让点保存，省得白跑一次 409。
   *
   * <p>判据是会话端口开着（state = OPEN），不是 GEM 的 ONLINE：主机连上但
   * 还没发 S1F17 时控制状态仍是 OFFLINE，可设备确实在跑——那会儿放行保存
   * 会让后端整会话重建，对操作员来说等于静默重启设备。</p>
   */
  const sessionState = backend.sessions.find((session) => session.id === deviceId)?.state;
  const started = sessionState === 'OPEN' || sessionState === 'OPENING';
  const structureLocked = sections?.includes('structure') === true && started;
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
      /* 保存/校验/重新载入放内容上方：编辑页的这三个按钮是"随时要按"的，
         藏在长表单末尾要滚到底才看得见 */
      actionsAt="top"
      status={
        draft.configName === ''
          ? <span className="state-pill" data-tone="muted">无配置来源</span>
          : (
            <>
              {/* 守卫 A 提前说清楚：启动中保存按钮是灰的，原因得写在旁边 */}
              {structureLocked && <span className="state-pill" data-tone="warn">设备已启动：结构改动需先停止设备</span>}
              {draft.dirty && <span className="state-pill" data-tone="warn">本页有未保存改动</span>}
              {draft.dirtyElsewhere.length > 0 && (
                <span
                  className="state-pill"
                  data-tone="muted"
                  title={`其它页还有未保存改动：${sectionLabels(draft.dirtyElsewhere)}`}
                >
                  其它页 {draft.dirtyElsewhere.length} 段未保存
                </span>
              )}
            </>
          )
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
            disabled={draft.busy || draft.configName === '' || structureLocked}
            title={structureLocked
              ? '设备已启动：改单元结构前先停止设备'
              : sections === undefined ? undefined : `只保存并生效：${sectionLabels(sections)}`}
          >
            保存
          </button>
        </>
      }
    >
      <DraftConfirmModal draft={draft} />
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
