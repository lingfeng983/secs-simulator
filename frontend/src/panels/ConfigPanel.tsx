import { useEffect, useMemo, useState } from 'react';
import { ConfigFormEditor } from '../ConfigFormEditor';
import { diffLines, foldUnchanged } from '../lib/textDiff';
import { TemplateEditor } from '../TemplateEditor';
import { Panel, PanelFootnote, StatCard } from '../ui/Panel';
import type { ConfigTemplateEntry } from '../api';
import type { PanelBaseProps } from './types';

/** 编辑视图：表单优先，原文兜底，改动看差异。 */
type ConfigView = 'form' | 'json' | 'diff';

/**
 * 配置编辑器面板。
 *
 * <p>编辑的是<b>原始 JSON 文本</b>：不动模板表单时保存逐字节写回，
 * 多写的键、空格和缩进都不会被动过。模板表单改动会保留未知键，
 * 但会把整份文件重排成 2 空格缩进——这一点在界面上说清楚，
 * 别让人以为文件被"改坏了"。</p>
 */
export function ConfigPanel({ backend }: PanelBaseProps): JSX.Element {
  const { configList, refreshConfigs } = backend;

  const [configName, setConfigName] = useState('');
  const [draft, setDraft] = useState('');
  const [baseline, setBaseline] = useState('');
  const [problems, setProblems] = useState<string[]>([]);
  const [notice, setNotice] = useState('');
  const [newName, setNewName] = useState('');
  const [newTemplate, setNewTemplate] = useState('bare');
  const [templates, setTemplates] = useState<ConfigTemplateEntry[]>([]);
  const [busy, setBusy] = useState(false);
  const [view, setView] = useState<ConfigView>('form');

  /** 新建选中的模板说明；目录取不到时为空串。 */
  const templateHint = useMemo(
    () => templates.find((entry) => entry.id === newTemplate)?.description ?? '',
    [templates, newTemplate],
  );

  /* 模板目录取一次就够：它来自后端代码里的常量 */
  useEffect(() => {
    void backend.configTemplates().then(setTemplates);
  }, [backend]);

  /** 编辑区里的内容相对磁盘有没有变。 */
  const dirty = draft !== baseline;

  /* 差异视图的行集：折叠长段未改动，现场几百行的配置只看改动附近 */
  const diffBlocks = useMemo(
    () => (dirty ? foldUnchanged(diffLines(baseline, draft)) : []),
    [baseline, draft, dirty],
  );

  /* 配置列表变了以后，如果当前打开的配置已经被删掉，就关掉它 */
  useEffect(() => {
    if (configName !== '' && !configList.some((entry) => entry.name === configName)) {
      setConfigName('');
      setDraft('');
      setBaseline('');
      setProblems([]);
      setNotice('配置已不存在，编辑器已关闭');
    }
  }, [configList, configName]);

  const openConfig = async (name: string) => {
    /* 有未保存改动时先问一句：悄悄丢掉别人正在写的东西最招人烦 */
    if (dirty && !window.confirm(`${configName} 有未保存的改动，切换会丢掉。继续？`)) {
      return;
    }
    setBusy(true);
    try {
      const text = await backend.readConfig(name);
      if (text === null) {
        return;
      }
      setConfigName(name);
      setDraft(text);
      setBaseline(text);
      setProblems([]);
      setNotice(`已打开 ${name}`);
    } finally {
      setBusy(false);
    }
  };

  /** 丢掉本地改动，重新拉磁盘上的原文。 */
  const reloadConfig = async () => {
    if (configName === '') {
      return;
    }
    setBusy(true);
    try {
      const text = await backend.readConfig(configName);
      if (text === null) {
        return;
      }
      setDraft(text);
      setBaseline(text);
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
      const result = await backend.saveConfig(configName, draft);
      if (result === null) {
        return;
      }
      if (result.status === 200) {
        setProblems([]);
        setBaseline(draft);
        setNotice(`已保存 ${configName}（已在运行的会话需要重载才生效）`);
        void refreshConfigs();
      } else {
        setProblems(result.problems);
        setNotice(result.status === 422 ? '保存被拒：配置有问题，未落盘' : `保存失败（HTTP ${result.status}）`);
      }
    } finally {
      setBusy(false);
    }
  };

  /** 新建：让后端生成一份能直接编译的骨架。 */
  const onNewConfig = async () => {
    const name = newName.trim();
    if (name === '') {
      return;
    }
    if (!name.toLowerCase().endsWith('.json')) {
      setNotice('文件名必须以 .json 结尾');
      return;
    }
    if (dirty && !window.confirm(`${configName} 有未保存的改动，新建会丢掉。继续？`)) {
      return;
    }

    setBusy(true);
    try {
      const status = await backend.createConfig(name, newTemplate);
      if (status !== 201) {
        return;
      }
      const text = await backend.readConfig(name);
      if (text === null) {
        return;
      }
      setConfigName(name);
      setDraft(text);
      setBaseline(text);
      setProblems([]);
      setNotice(
        newTemplate === 'bare'
          ? `已新建 ${name}，改完点「保存」才会落盘`
          : `已新建 ${name}（内置模板: ${newTemplate}），照着改参数后点「保存」`,
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel
      id="panel-config"
      title="配置编辑器"
      description="读 / 改 / 校验 / 保存 / 新建。校验按真实启动路径走（解析 → 编译设备模型 → 建模板图），过了才会落盘。"
      stats={
        <StatCard
          label="当前状态"
          value={configName === '' ? '未打开' : dirty ? '有未保存改动' : '与磁盘一致'}
          tone={configName !== '' && dirty ? 'warn' : undefined}
        />
      }
      actions={
        <>
          <label className="field">
            新文件名
            <input
              value={newName}
              onChange={(event) => setNewName(event.target.value)}
              placeholder="新文件名.json"
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
          <button type="button" onClick={() => void onNewConfig()} disabled={newName.trim() === '' || busy}>
            新建
          </button>
          <button type="button" onClick={() => void reloadConfig()} disabled={configName === '' || busy}>
            重新载入
          </button>
          <button type="button" onClick={() => void onValidate()} disabled={configName === '' || busy}>
            校验
          </button>
          <button
            type="button"
            className="primary"
            onClick={() => void onSave()}
            disabled={configName === '' || busy}
            title='校验通过后原子落盘'
          >
            保存
          </button>
        </>
      }
    >
      <div className="preview-controls">
        <label className="field">
          配置
          <select
            value={configList.some((entry) => entry.name === configName) ? configName : ''}
            onChange={(event) => {
              if (event.target.value !== '') {
                void openConfig(event.target.value);
              }
            }}
          >
            <option value="">（选择配置）</option>
            {configList.map((entry) => (
              <option key={entry.name} value={entry.name}>
                {entry.name}（{entry.sizeBytes} 字节）
              </option>
            ))}
          </select>
        </label>
      </div>

      {configName === '' ? (
        <p className="hint">
          从上面挑一份配置，或输入新文件名选一台「设备模板」点「新建」（刻蚀腔 / CVD / 测试机都是能直接编译的起点）。
          配置只认 <code>--work-dir</code> 下的文件名。
        </p>
      ) : (
        <>
          <p className="hint">
            {configName}
            {dirty ? '（有未保存的改动）' : '（与磁盘一致）'}
            {notice !== '' ? `　${notice}` : ''}
          </p>
          <div className="segmented" role="group" aria-label="编辑视图">
            <button
              type="button"
              className={`segment${view === 'form' ? ' active' : ''}`}
              onClick={() => setView('form')}
            >
              表单
            </button>
            <button
              type="button"
              className={`segment${view === 'diff' ? ' active' : ''}`}
              onClick={() => setView('diff')}
            >
              差异
            </button>
            <button
              type="button"
              className={`segment${view === 'json' ? ' active' : ''}`}
              onClick={() => setView('json')}
            >
              JSON 原文
            </button>
          </div>
          {view === 'diff' ? (
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
          ) : view === 'json' ? (
            <textarea
              className="config-editor"
              spellCheck={false}
              value={draft}
              onChange={(event) => {
                setDraft(event.target.value);
                setProblems([]);
              }}
              aria-label="配置 JSON 原文"
            />
          ) : (
            <>
              <ConfigFormEditor
                text={draft}
                onChange={(next) => {
                  setDraft(next);
                  setProblems([]);
                  setNotice('结构化编辑已修改草稿，保存前请校验');
                }}
              />
              <TemplateEditor
                text={draft}
                onChange={(next) => {
                  setDraft(next);
                  setProblems([]);
                  setNotice('结构化编辑已修改草稿，保存前请校验');
                }}
              />
            </>
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
      <p className="hint">
        「表单」覆盖 session / equipment / events / templates；「JSON 原文」适合粘贴整份配置或修原文里的语法错。
        不动表单时保存逐字节写回，多写的键、空格和缩进都不会被动过；表单改动会保留未知键，
        但整份文件重排成 2 空格缩进。
      </p>
          <PanelFootnote items={[{ label: '目录内配置', value: configList.length }]} />
    </Panel>
  );
}
