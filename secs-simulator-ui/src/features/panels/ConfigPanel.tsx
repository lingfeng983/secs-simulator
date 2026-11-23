import { useEffect, useMemo, useRef, useState } from 'react';
import { diffLines, foldUnchanged } from '../../shared/lib/textDiff';
import { saveHint, saveTextFile } from '../../shared/lib/saveFile';
import { JsonEditor } from '../../shared/ui/JsonEditor';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { Panel } from '../../shared/ui/Panel';
import { Icon } from '../../shared/ui/Icon';
import { DraftConfirmModal } from './ConfigEditShell';
import { useConfigDraft } from '../../state/useConfigDraft';
import type { ConfigTemplateEntry } from '../../api';
import type { PanelBaseProps } from './types';

/**
 * 「全量配置」面板：整份 JSON 原文的编辑入口。
 *
 * <p>只编辑<b>原始 JSON 文本</b>，逐字节保真。结构化字段分别放到「链接配置」
 * 「结构配置」等专项页——同一份配置不该有两个互相不知道对方改过什么的表单入口。</p>
 *
 * <p>2026-11-23：草稿改用 {@link useConfigDraft}（`sections` 不传 = 整份保存），
 * 与结构化页共用同一个草稿仓库。以前这里自己管一份 `draft/baseline/loadedMs`：
 * 在这页改的内容结构化页看不见，两边各用自己的 `modifiedMs` 做 If-Match，
 * 后保存的一方只会拿到 409。现在两页看到的是同一份草稿、同一条基线。</p>
 */
export function ConfigPanel({ backend, sessionId, configName: configNameOverride, notifier }: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { configList, configTemplates } = backend;

  /** 用户在这页新建/切到的配置名；空串 = 跟随设备自己的配置。 */
  const [opened, setOpened] = useState('');
  const [newName, setNewName] = useState('');
  const [newTemplate, setNewTemplate] = useState('bare');
  const [templates, setTemplates] = useState<ConfigTemplateEntry[]>([]);
  /** 这页自己的提示（新建 / 导入 / 删除），与草稿的保存提示分开。 */
  const [localNotice, setLocalNotice] = useState('');
  /** 差异视图:叠加在上面,不改当前编辑器 */
  const [showDiff, setShowDiff] = useState(false);
  const importRef = useRef<HTMLInputElement | null>(null);
  /** 用户确认"配置已被删掉、编辑器关掉"的那一份：避免目录轮询反复弹窗。 */
  const [dismissed, setDismissed] = useState('');
  const [deletedOpen, setDeletedOpen] = useState(false);

  /** 新建选中的模板说明；目录取不到时为空串。 */
  const templateHint = useMemo(
    () => templates.find((entry) => entry.id === newTemplate)?.description ?? '',
    [templates, newTemplate],
  );

  /* 模板目录取一次就够：它来自后端代码里的常量。
     依赖 configTemplates（其本身依赖 client）而不是整个 backend——
     backend 每次渲染都是新对象，原来那样会每 2 秒重新拉一遍目录。 */
  useEffect(() => {
    let cancelled = false;
    void configTemplates()
      .then((list) => {
        if (!cancelled) {
          setTemplates(list);
        }
      })
      .catch(() => {
        /* 目录拉不到就让下拉留空（后面会再试），别把未处理的 rejection 丢出去 */
        if (!cancelled) {
          setTemplates([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [configTemplates]);

  /* 打开哪一份：用户刚新建/选中的优先，其次设备自己的配置，
     最后"目录里只有一份就用那一份"（演示会话的兜底）。
     sessionId 由设备页显式传入：全局 activeSession 在 effect 里切换，
     首次渲染时还是上一台设备，靠它会打开"上一个设备"的配置。 */
  const activeSessionId = sessionId ?? backend.activeSession;
  const sessionConfig = backend.sessions.find((session) => session.id === activeSessionId)?.config ?? '';
  const explicitConfig = sessionConfig === ''
    && configNameOverride !== undefined
    && configList.some((entry) => entry.name === configNameOverride)
    ? configNameOverride
    : '';
  const fallbackConfig = sessionConfig === '' && explicitConfig === '' && configList.length === 1 ? configList[0].name : '';
  const targetConfig = opened !== '' ? opened : (sessionConfig !== '' ? sessionConfig : (explicitConfig !== '' ? explicitConfig : fallbackConfig));

  /* 不传 sections = 整份保存：这条路径本来就写在 useConfigDraft 里，
     以前只是没人用，配置原文页自己又实现了一遍。 */
  const draft = useConfigDraft(backend, activeSessionId, targetConfig === '' ? undefined : targetConfig);
  const configName = draft.configName;
  const dirty = draft.dirty;
  const busy = draft.busy;
  /** 被删掉且用户已确认关闭：这一份不再显示编辑器。 */
  const closed = dismissed !== '' && dismissed === configName;
  const missing = draft.status === 'missing';

  /* 配置列表变了以后，如果当前打开的配置已经被删掉：
     没有改动就直接关；有改动则弹确认，避免把草稿静默丢掉。 */
  useEffect(() => {
    if (configName === '' || configList.length === 0) {
      return;
    }
    if (configList.some((entry) => entry.name === configName)) {
      /* 配置又回来了（或被换掉）：重置"已确认"标记 */
      setDismissed('');
      return;
    }
    if (dismissed === configName) {
      return;
    }
    if (dirty) {
      setDeletedOpen(true);
      return;
    }
    setDismissed(configName);
    setLocalNotice('配置已不存在，编辑器已关闭');
  }, [configList, configName, dirty, dismissed]);

  /** 导出草稿：能弹保存位置就弹；提示里说清文件去了哪，用户不用猜下载目录。 */
  const exportJson = async () => {
    const result = await saveTextFile(
      configName === '' ? 'config.json' : configName,
      draft.text,
      'application/json;charset=utf-8',
    );
    const hint = saveHint(result);
    if (hint !== '') {
      notifier.success('已导出配置', hint);
    }
  };

  /* 差异视图的行集：折叠长段未改动，现场几百行的配置只看改动附近 */
  const diffBlocks = useMemo(
    () => (dirty ? foldUnchanged(diffLines(draft.baselineText, draft.text)) : []),
    [draft.baselineText, draft.text, dirty],
  );

  /** 新建：让后端生成一份能直接编译的骨架，然后切到它。 */
  const createNewConfig = async () => {
    const name = newName.trim();
    if (name === '') {
      return;
    }
    if (!name.toLowerCase().endsWith('.json')) {
      setLocalNotice('文件名必须以 .json 结尾');
      return;
    }
    const status = await backend.createConfig(name, newTemplate);
    if (status !== 201) {
      return;
    }
    setOpened(name);
    setLocalNotice(
      newTemplate === 'bare'
        ? `已新建 ${name}，改完点「保存」才会写入文件`
        : `已新建 ${name}（内置模板: ${newTemplate}），照着改参数后点「保存」`,
    );
  };

  return (
    <Panel
      id="panel-config"
      title={configName === '' || closed || missing ? '配置编辑器' : configName}
      headless
      status={
        <>
          <span className="state-pill" data-tone="muted">目录内配置 {configList.length}</span>
          {configName === '' || closed || missing
            ? <span className="state-pill" data-tone="muted">未打开</span>
            : dirty ? <span className="state-pill" data-tone="warn">有未保存改动</span> : undefined}
        </>
      }
    >
      <DraftConfirmModal draft={draft} />

      {configName === '' || closed || missing ? (
        <div className="empty-state">
          <p className="empty-text">
            {missing
              ? `读不到 ${configName}：文件可能已被删除。`
              : sessionConfig === ''
                ? '这台设备没有配置文件来源（演示会话或手动建立的会话）。'
                : `还没打开 ${sessionConfig}。`}
          </p>
          <p className="empty-hint">
            可以从设备模板新建一份（刻蚀腔 / CVD / 测试机都是能直接编译的起点），
            新建后会自动打开。配置只认 <code>--work-dir</code> 下的文件名。
          </p>
          <div className="empty-action">
            <div className="toolbar">
              <label className="field">
                新文件名
                <input
                  value={newName}
                  onChange={(event) => setNewName(event.target.value)}
                  placeholder="如 line1.json"
                />
              </label>
              <label className="field">
                设备模板
                <select
                  value={newTemplate}
                  onChange={(event) => setNewTemplate(event.target.value)}
                  title={templateHint}
                >
                  {templates.map((entry) => (
                    <option key={entry.id} value={entry.id}>
                      {entry.label}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                className="primary"
                onClick={() => void createNewConfig()}
                disabled={newName.trim() === '' || busy}
              >
                新建并打开
              </button>
            </div>
          </div>
        </div>
      ) : (
        <>
          {(draft.notice !== '' || localNotice !== '') && (
            <p className="inline-note">{draft.notice !== '' ? draft.notice : localNotice}</p>
          )}
          {/* 重新载入 / 校验 / 导入导出 / 保存 / 查看差异 收进同一行工具条（2026-11-20），
              不再拆在面板头与编辑区上方两处 */}
          <div className="config-toolbar">
            <span className="hint-inline">只编辑原文；分段编辑到「链接配置」「结构配置」等子页。</span>
            <span className="spacer" />
            <button
              type="button"
              onClick={() => void draft.reload()}
              disabled={busy}
              title="丢掉本地改动，重新拉磁盘上的原文"
            >
              重新载入
            </button>
            <button
              type="button"
              onClick={() => void draft.validate()}
              disabled={busy}
              title="只做校验，不写文件：确认改动能不能编译"
            >
              校验
            </button>
            <span className="action-sep" aria-hidden="true" />
            <button
              type="button"
              className="icon-btn"
              onClick={() => importRef.current?.click()}
              title="导入 JSON：从本地文件读入草稿（只进编辑器，仍需校验/保存）"
              aria-label="导入 JSON"
            >
              <Icon name="upload" size={15} />
            </button>
            <button
              type="button"
              className="icon-btn"
              onClick={() => void exportJson()}
              disabled={draft.text.trim() === ''}
              title="导出 JSON：把当前草稿存成文件（可选择保存位置）"
              aria-label="导出 JSON"
            >
              <Icon name="download" size={15} />
            </button>
            <button
              type="button"
              className={showDiff ? 'active' : undefined}
              onClick={() => {
                setShowDiff((previous) => !previous);
              }}
              disabled={!dirty && !showDiff}
              title="看当前草稿与磁盘版本的差异"
            >
              {showDiff ? '收起差异' : '查看差异'}
            </button>
            <button
              type="button"
              className="primary"
              onClick={() => void draft.save()}
              disabled={busy}
              title="校验通过后整份写回，不会写一半"
            >
              保存
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
                void file.text().then((text) => {
                  draft.setText(text);
                  setLocalNotice(`已导入 ${file.name}（未保存，请先校验）`);
                });
              }}
            />
          </div>
          <JsonEditor
            value={draft.text}
            onChange={(next) => {
              draft.setText(next);
              setLocalNotice('');
            }}
            ariaLabel="配置 JSON 原文"
          />
          {/* 差异:不是互斥视图,是一块可以随时展开看的对照 */}
          {showDiff && (
            <section className="panel-section block">
              <div className="section-head">
                <h2>与磁盘版本的差异</h2>
                <button type="button" onClick={() => setShowDiff(false)}>收起</button>
              </div>
              {diffBlocks.length === 0 ? (
                <p className="hint">当前草稿与磁盘上一致，没有差异。</p>
              ) : (
                <div className="config-diff" aria-label="与磁盘版本的差异">
                  {diffBlocks.map((block, index) =>
                    block.op === 'skip' ? (
                      <div className="diff-line diff-skip" key={index}>
                        ··· {block.count} 行未改动
                      </div>
                    ) : (
                      <div className={`diff-line diff-${block.op}`} key={index}>
                        <span className="diff-gutter">{block.lineNumber}</span>
                        <span className="diff-mark">
                          {block.op === 'added' ? '+' : block.op === 'removed' ? '−' : ' '}
                        </span>
                        <span className="diff-text">{block.text === '' ? ' ' : block.text}</span>
                      </div>
                    ),
                  )}
                </div>
              )}
            </section>
          )}

          {draft.problems.length > 0 && (
            <ul className="problems">
              {draft.problems.map((problem, index) => (
                <li key={index}>{problem}</li>
              ))}
            </ul>
          )}
        </>
      )}
      <ConfirmModal
        open={deletedOpen}
        title="配置已被删除"
        danger
        confirmLabel="丢弃草稿并关闭"
        message={`${configName} 已不在配置目录里（可能被其他窗口删除）。编辑器里还有未保存的改动，关闭后刷新页面就会丢掉；也可以先点「导出 JSON」备份。`}
        onClose={() => {
          setDeletedOpen(false);
          setDismissed(configName);
          setLocalNotice('配置已被删除：草稿还在编辑器里，可先「导出 JSON」备份');
        }}
        onConfirm={() => {
          setDeletedOpen(false);
          setDismissed(configName);
          setLocalNotice('配置已不存在，编辑器已关闭');
        }}
      />
    </Panel>
  );
}
