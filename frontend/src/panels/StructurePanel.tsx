import { useMemo, useState } from 'react';
import type { PanelBaseProps } from './types';
import { Panel } from '../ui/Panel';
import { EquipmentForm, templateIdsOf } from '../ConfigFormEditor';
import { TemplateEditor } from '../TemplateEditor';
import { SvidCheckList } from './hostsim/SvidCheckList';
import { editDocument, isJsonObject, parseConfigDocument, type JsonObject } from '../templateConfig';
import { useConfigDraft } from '../state/useConfigDraft';

type StructureTab = 'global' | 'units' | 'templates';

/**
 * 设备 · 结构：配置里的 equipment / templates 段。
 *
 * <p>按"全局 / 单元 / 模板"拆三个页签：改设备名和号段时不用翻过一长串
 * 单元卡片，模板也不再埋在设备表单最底下。三个页签共用同一份配置草稿，
 * 保存仍走统一的校验/保存/相关会话重载路径。</p>
 */
export function StructurePanel({ backend, notifier, sessionId }: PanelBaseProps & { sessionId?: string }): JSX.Element {
  const deviceId = sessionId ?? backend.activeSession;
  const draft = useConfigDraft(backend, deviceId);
  const parsed = useMemo(() => parseConfigDocument(draft.text), [draft.text]);
  const [tab, setTab] = useState<StructureTab>('global');
  const [quickTemplate, setQuickTemplate] = useState('');
  const [quickSvids, setQuickSvids] = useState<number[]>([]);
  const [quickNotice, setQuickNotice] = useState('');

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

  /** 简化模板使用：选模板 + 勾 SVID，自动生成实例，不用手写 id/绑定表。 */
  const quickBind = () => {
    if (quickTemplate === '') {
      notifier.error('先选一个曲线/组合模板');
      return;
    }
    if (quickSvids.length === 0) {
      notifier.error('至少勾选一个 SVID');
      return;
    }
    let created = 0;
    edit((root) => {
      const templates = isJsonObject(root.templates) ? root.templates : {};
      const instances = Array.isArray(templates.instances) ? (templates.instances as JsonObject[]) : [];
      for (const svid of quickSvids) {
        const exists = instances.some((item) => (
          item.template === quickTemplate
          && isJsonObject(item.svids)
          && Object.values(item.svids).includes(svid)
        ));
        if (!exists) {
          instances.push({
            id: `auto-${quickTemplate}-${svid}`.replace(/[^A-Za-z0-9._-]/g, '-'),
            template: quickTemplate,
            svids: { V: svid },
          });
          created += 1;
        }
      }
      templates.instances = instances;
      root.templates = templates;
    });
    setQuickNotice(created === 0 ? '这些 SVID 已经绑定过该模板，没有重复生成。' : `已生成 ${created} 个实例；保存并重载后生效。`);
  };

  return (
    <Panel
      id="panel-structure"
      title="结构"
      description={
        '设备全局：名称 / 号段 / 全局 SVID / CEID / 常量\n'
        + '单元：每个腔体或工位的 SVID、报告、CEID\n'
        + '模板：曲线 / 组合 / 实例，以及它们如何被 SVID 值来源引用'
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
              ['templates', '模板'],
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
          {tab === 'templates' && (
            <>
              <section className="template-quick">
                <div className="template-head">
                  <h3>快速绑定（选模板 + 勾 SVID）</h3>
                  <button type="button" className="primary" onClick={quickBind}>生成实例</button>
                </div>
                <p className="hint">
                  选一条曲线/组合模板，勾选要驱动的 SVID，点「生成实例」即可。
                  默认把模板输出 <code>V</code> 绑到 SVID；参数覆盖等高级设置仍可在下方实例卡片里改。
                </p>
                {templateIds.length === 0 ? (
                  <p className="hint">还没有曲线/组合模板，先在下方「+ 曲线」创建一条。</p>
                ) : (
                  <>
                    <label className="field">
                      模板
                      <select value={quickTemplate} onChange={(event) => setQuickTemplate(event.target.value)}>
                        <option value="">选择模板…</option>
                        {templateIds.map((id) => <option key={id} value={id}>{id}</option>)}
                      </select>
                    </label>
                    <SvidCheckList
                      choices={backend.catalog?.svids ?? []}
                      selected={quickSvids}
                      onChange={setQuickSvids}
                      max={32}
                    />
                  </>
                )}
                {quickNotice !== '' && <p className="inline-note">{quickNotice}</p>}
              </section>
              <TemplateGuide />
              <TemplateEditor text={draft.text} onChange={draft.setText} />
            </>
          )}
        </>
      )}
    </Panel>
  );
}

/** 模板的用途、引用位置与配置步骤——模板表单本身不解释这些。 */
function TemplateGuide(): JSX.Element {
  return (
    <section className="template-guide">
      <h3>模板是什么</h3>
      <p>
        模板是"值来源"的复用单元：先在 <code>templates</code> 里定义曲线（随时间变化的数值），
        再通过实例把模板输出绑定到具体 SVID。设备和单元只引用模板，不重复写参数。
      </p>
      <ul>
        <li><b>曲线</b>：常量、斜坡、正弦、阶跃、折线关键帧、区间随机、有界随机游走；输出名固定为 <code>V</code>。</li>
        <li><b>组合模板</b>：用表达式把多条曲线/其它模板拼起来（如 <code>V * gain + offset</code>），输出名由 <code>outputs</code> 定义。</li>
        <li><b>实例</b>：指定用哪条模板、把输出绑到哪些绝对 SVID，并可覆盖 <code>params</code>；参数会写到参数链最高层（UNIT），所以同一模板能给不同单元不同基准值。</li>
      </ul>

      <h3>在什么地方用</h3>
      <ol>
        <li>设备全局或单元里的 <b>SVID「值来源」</b>选「模板」：填模板 id、输出名和参数；也可以直接把曲线对象写进 SVID（二选一，不能同时写）。</li>
        <li><b>实例</b>是另一条路径：在模板页建实例，把输出绑定到 SVID（<code>svids: {'{'} V: 1001 {'}'}</code>），SVID 不需要再配值来源。</li>
        <li>运行时的曲线、S2F23 Trace、S6F11 上报都读同一个值引擎，所以模板参数改动会直接反映到这些报文里。</li>
      </ol>

      <h3>怎么配（推荐顺序）</h3>
      <ol>
        <li>「+ 曲线」：填唯一 id、选类型、设置参数（如正弦的基准值/振幅/周期）。</li>
        <li>（可选）「+ 组合模板」：给输出名写表达式，引用已存在的曲线/模板 id。</li>
        <li>「+ 实例」：选模板、填绝对 SVID 绑定，必要时在 params 里覆盖基准值。</li>
        <li>回「设备全局」或「单元」检查 SVID 的值来源；最后「校验」→「保存」，相关会话自动重载。</li>
      </ol>

      <h3>容易踩的坑</h3>
      <ul>
        <li>只要配置里存在 <code>templates</code> 段，<b>没有绑定值来源的 SVID 会返回 0</b>；没有 templates 段时才走 v4 默认曲线。</li>
        <li>曲线/模板 id 必须唯一；删除仍被实例引用的曲线会被阻止，先把实例改掉。</li>
        <li>同一 SVID 不能既被实例绑定、又配直配值来源（单写者规则）。</li>
        <li>改 id 时表单会同步实例引用；如果手写 JSON，记得两边一起改。</li>
      </ul>
    </section>
  );
}
