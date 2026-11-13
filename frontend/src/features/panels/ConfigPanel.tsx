import { useEffect, useMemo, useRef, useState } from 'react';
import { diffLines, foldUnchanged } from '../../shared/lib/textDiff';
import { saveHint, saveTextFile } from '../../shared/lib/saveFile';
import { JsonEditor } from '../../shared/ui/JsonEditor';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { Panel, PanelFootnote } from '../../shared/ui/Panel';
import type { ConfigTemplateEntry } from '../../api';
import type { PanelBaseProps } from './types';

/**
 * 配置编辑器面板。
 *
 * <p>只编辑<b>原始 JSON 文本</b>，逐字节保真。结构化字段已经分别放到
 * 「连接」（session）与「结构」（equipment / templates）页——同一份配置
 * 不该有两个互相不知道对方改过什么的表单入口。</p>
 */
export function ConfigPanel({ backend, sessionId, notifier }: PanelBaseProps & { sessionId?: string }): JSX.Element {
  const { configList, refreshConfigs, configTemplates } = backend;

  const [configName, setConfigName] = useState('');
  const [draft, setDraft] = useState('');
  const [baseline, setBaseline] = useState('');
  /** 打开/重载时的文件修改时间快照:保存时经 If-Match 带回,防双窗口互相覆盖。 */
  const [loadedMs, setLoadedMs] = useState<number | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [notice, setNotice] = useState('');
  const [newName, setNewName] = useState('');
  const [newTemplate, setNewTemplate] = useState('bare');
  const [templates, setTemplates] = useState<ConfigTemplateEntry[]>([]);
  const [busy, setBusy] = useState(false);
  /** 差异视图:叠加在上面,不改当前编辑器 */
  const [showDiff, setShowDiff] = useState(false);
  const importRef = useRef<HTMLInputElement | null>(null);

  /** 新建选中的模板说明；目录取不到时为空串。 */
  const templateHint = useMemo(
    () => templates.find((entry) => entry.id === newTemplate)?.description ?? '',
    [templates, newTemplate],
  );
  /** 新建/重载会丢掉未保存改动时，先走确认框而不是 window.confirm。 */
  const [discardNewOpen, setDiscardNewOpen] = useState(false);
  const [discardReloadOpen, setDiscardReloadOpen] = useState(false);

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

  /* 自动打开本设备的配置:进了设备页·配置就该直接看到这台设备的配置,
     不再让用户自己挑文件。
     会话列表或配置列表没就绪就等下一拍;演示会话没有来源时,
     目录里只有一份配置就用那一份,多份则保持空态让人新建。
     sessionId 由设备页显式传入:全局 activeSession 在 effect 里切换,
     首次渲染时还是上一台设备,靠它会打开"上一个设备"的配置。 */
  const activeSessionId = sessionId ?? backend.activeSession;
  const sessionConfig = backend.sessions.find((session) => session.id === activeSessionId)?.config ?? '';
  const fallbackConfig = sessionConfig === '' && configList.length === 1 ? configList[0].name : '';
  const targetConfig = sessionConfig !== '' ? sessionConfig : fallbackConfig;
  useEffect(() => {
    if (configName !== '' || targetConfig === '' || !configList.some((entry) => entry.name === targetConfig)) {
      return;
    }
    setBusy(true);
    void backend.readConfig(targetConfig).then((snapshot) => {
      if (snapshot !== null) {
        setConfigName(targetConfig);
        setDraft(snapshot.text);
        setBaseline(snapshot.text);
        setLoadedMs(snapshot.modifiedMs);
        setProblems([]);
      }
      setBusy(false);
    });
    // 只在"会话配置名/目录"就绪时自动开一次;configName 变化后不再抢
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetConfig, configList]);

  /** 编辑区里的内容相对磁盘有没有变。 */
  const dirty = draft !== baseline;

  /** 导出草稿：能弹保存位置就弹；提示里说清文件去了哪，用户不用猜下载目录。 */
  const exportJson = async () => {
    const result = await saveTextFile(
      configName === '' ? 'config.json' : configName,
      draft,
      'application/json;charset=utf-8',
    );
    const hint = saveHint(result);
    if (hint !== '') {
      notifier.success('已导出配置', hint);
    }
  };

  /* 差异视图的行集：折叠长段未改动，现场几百行的配置只看改动附近 */
  const diffBlocks = useMemo(
    () => (dirty ? foldUnchanged(diffLines(baseline, draft)) : []),
    [baseline, draft, dirty],
  );

  /** 当前配置被别处删掉、且草稿有未保存改动时，先让用户确认再关闭。 */
  const [deletedConfigOpen, setDeletedConfigOpen] = useState(false);
  /** 用户已经选择"先留着草稿"：别再被 5 秒一次的目录轮询反复弹窗。 */
  const deletedConfigDismissedRef = useRef(false);

  /* 配置列表变了以后，如果当前打开的配置已经被删掉：
     没有改动就直接关；有改动则弹确认，避免把草稿静默丢掉。 */
  useEffect(() => {
    if (configName === '' || configList.some((entry) => entry.name === configName)) {
      /* 配置又回来了（或被换掉）：重置"已确认"标记 */
      deletedConfigDismissedRef.current = false;
      return;
    }
    if (deletedConfigDismissedRef.current) {
      return;
    }
    if (dirty) {
      setDeletedConfigOpen(true);
      return;
    }
    setConfigName('');
    setDraft('');
    setBaseline('');
    setLoadedMs(null);
    setProblems([]);
    setNotice('配置已不存在，编辑器已关闭');
  }, [configList, configName, dirty]);

  /** 丢掉本地改动，重新拉磁盘上的原文。 */
  const reloadConfig = async () => {
    if (configName === '') {
      return;
    }
    setBusy(true);
    try {
      const snapshot = await backend.readConfig(configName);
      if (snapshot === null) {
        return;
      }
      setDraft(snapshot.text);
      setBaseline(snapshot.text);
      setLoadedMs(snapshot.modifiedMs);
      setProblems([]);
      setNotice(`已重新载入 ${configName}`);
    } finally {
      setBusy(false);
    }
  };

  /** 只校验不落盘：用户想确认改动能不能编译，不该被迫先覆盖一份能用的配置。 */
  const onValidate = async () => {
    if (configName === '') {
      return;
    }
    setBusy(true);
    try {
      const result = await backend.validateConfig(configName, draft);
      if (result === null) {
        return;
      }
      setProblems(result.problems);
      setNotice(result.valid ? '校验通过' : '校验未通过');
    } finally {
      setBusy(false);
    }
  };

  const onSave = async () => {
    if (configName === '') {
      return;
    }
    setBusy(true);
    try {
      const result = await backend.saveConfig(configName, draft, loadedMs);
      if (result === null) {
        return;
      }
      if (result.status === 200) {
        setProblems([]);
        setBaseline(draft);
        /* 拿一份新的修改时间快照,下一次保存的 If-Match 才对得上 */
        const fresh = await backend.readConfig(configName);
        if (fresh !== null) {
          setLoadedMs(fresh.modifiedMs);
        }
        setNotice(`已保存 ${configName}，相关会话已按新配置重载`);
        void refreshConfigs();
      } else {
        setProblems(result.problems);
        setNotice(
          result.status === 422
            ? '保存被拒：配置有问题，文件没有改动'
            : result.status === 409
              ? '保存被拒：配置在编辑期间被其他窗口修改过，未覆盖——点「重新载入」拿到最新内容再改'
              : `保存失败（HTTP ${result.status}）`,
        );
      }
    } finally {
      setBusy(false);
    }
  };

  /** 新建：让后端生成一份能直接编译的骨架。 */
  const createNewConfig = async () => {
    const name = newName.trim();
    if (name === '') {
      return;
    }
    if (!name.toLowerCase().endsWith('.json')) {
      setNotice('文件名必须以 .json 结尾');
      return;
    }

    setBusy(true);
    try {
      const status = await backend.createConfig(name, newTemplate);
      if (status !== 201) {
        return;
      }
      const snapshot = await backend.readConfig(name);
      if (snapshot === null) {
        return;
      }
      setConfigName(name);
      setDraft(snapshot.text);
      setBaseline(snapshot.text);
      setLoadedMs(snapshot.modifiedMs);
      setProblems([]);
      setNotice(
        newTemplate === 'bare'
          ? `已新建 ${name}，改完点「保存」才会写入文件`
          : `已新建 ${name}（内置模板: ${newTemplate}），照着改参数后点「保存」`,
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel
      id="panel-config"
      title={configName === '' ? '配置编辑器' : configName}
      description={
        configName === ''
          ? '进入设备页·配置就会自动打开这台设备的配置文件，不用自己挑。'
          : `${configName} · 只编辑 JSON 原文：改完先「校验」，过了再「保存」。连接/结构/模板请用左侧对应子页。`
      }
      status={
        configName === ''
          ? <span className="state-pill" data-tone="muted">未打开</span>
          : dirty
            ? <span className="state-pill" data-tone="warn">有未保存改动</span>
            : undefined
      }
      actions={
        <>
          <button
            type="button"
            onClick={() => {
              /* 有未保存改动时重新载入 = 丢草稿，先确认 */
              if (dirty) {
                setDiscardReloadOpen(true);
                return;
              }
              void reloadConfig();
            }}
            disabled={configName === '' || busy}
            title="丢掉本地改动，重新拉磁盘上的原文"
          >
            重新载入
          </button>
          <button
            type="button"
            onClick={() => void onValidate()}
            disabled={configName === '' || busy}
            title="只做校验，不写文件：确认改动能不能编译"
          >
            校验
          </button>
          <span className="action-sep" aria-hidden="true" />
          <button
            type="button"
            onClick={() => importRef.current?.click()}
            title="从本地 JSON 文件读入草稿（只进编辑器，仍需校验/保存）"
          >
            导入 JSON
          </button>
          <button
            type="button"
            onClick={() => void exportJson()}
            disabled={draft.trim() === ''}
            title="把当前草稿导出为 JSON 文件（可选择保存位置）"
          >
            导出 JSON
          </button>
          <span className="action-sep" aria-hidden="true" />
          <button
            type="button"
            className="primary"
            onClick={() => void onSave()}
            disabled={configName === '' || busy}
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
                setDraft(text);
                setProblems([]);
                setNotice(`已导入 ${file.name}（未保存，请先校验）`);
              });
            }}
          />
        </>
      }
    >

      {configName === '' ? (
        <div className="empty-state">
          <p className="empty-text">
            {sessionConfig === ''
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
                onClick={() => {
                  /* 新建会替换编辑器里的草稿：dirty 时先确认，别静默丢改动 */
                  if (dirty) {
                    setDiscardNewOpen(true);
                    return;
                  }
                  void createNewConfig();
                }}
                disabled={newName.trim() === '' || busy}
              >
                新建并打开
              </button>
            </div>
          </div>
        </div>
      ) : (
        <>
          {notice !== '' && <p className="inline-note">{notice}</p>}
          <div className="config-toolbar">
            <span className="hint-inline">只编辑原文；连接 / 设备结构 / 模板请到左侧「连接」「结构」子页。</span>
            <span className="spacer" />
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
          </div>
          <JsonEditor
            value={draft}
            onChange={(next) => {
              setDraft(next);
              setProblems([]);
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

          {problems.length > 0 && (
            <ul className="problems">
              {problems.map((problem, index) => (
                <li key={index}>{problem}</li>
              ))}
            </ul>
          )}
        </>
      )}
      <ConfirmModal
        open={deletedConfigOpen}
        title="配置已被删除"
        danger
        confirmLabel="丢弃草稿并关闭"
        message={`${configName} 已不在配置目录里（可能被其他窗口删除）。编辑器里还有未保存的改动，关闭会丢掉它们；也可以先点「导出 JSON」备份。`}
        onClose={() => {
          setDeletedConfigOpen(false);
          deletedConfigDismissedRef.current = true;
          setNotice('配置已被删除：草稿还在编辑器里，可先「导出 JSON」备份');
        }}
        onConfirm={() => {
          setDeletedConfigOpen(false);
          setConfigName('');
          setDraft('');
          setBaseline('');
          setLoadedMs(null);
          setProblems([]);
          setNotice('配置已不存在，编辑器已关闭');
        }}
      />
      <ConfirmModal
        open={discardReloadOpen}
        title="丢弃未保存的改动"
        danger
        confirmLabel="丢弃并重新载入"
        message={`${configName} 有未保存的改动，重新载入会丢掉它们。继续？`}
        onClose={() => setDiscardReloadOpen(false)}
        onConfirm={() => {
          setDiscardReloadOpen(false);
          void reloadConfig();
        }}
      />
      <ConfirmModal
        open={discardNewOpen}
        title="丢弃未保存的改动"
        danger
        confirmLabel="丢弃并新建"
        message={configName === '' ? '当前草稿还没有保存，新建会丢掉它。继续？' : `${configName} 有未保存的改动，新建会丢掉。继续？`}
        onClose={() => setDiscardNewOpen(false)}
        onConfirm={() => {
          setDiscardNewOpen(false);
          void createNewConfig();
        }}
      />
      <PanelFootnote items={[{ label: '目录内配置', value: configList.length }]} />
    </Panel>
  );
}
