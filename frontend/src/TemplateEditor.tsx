import { useEffect, useMemo, useState } from 'react';
import {
  CURVE_TYPES,
  arrayField,
  booleanField,
  curveFieldSpecs,
  curveTypeLabel,
  defaultCurveFields,
  editTemplates,
  ensureObjectField,
  isCurveType,
  isExpressionName,
  isJsonObject,
  numberField,
  objectField,
  parseConfigDocument,
  readTemplates,
  stringField,
  templateOutputs,
  uniqueId,
  type JsonObject,
  type ReadTemplatesResult,
  type SvidChoice,
  type TemplateView,
} from './templateConfig';

interface TemplateEditorProps {
  /** 配置原文；结构化编辑永远从这份文本出发。 */
  text: string;
  /** 写回新的原文（只改草稿，不落盘）。 */
  onChange: (next: string) => void;
}

type MutateTemplates = (mutate: (templates: TemplateView) => void) => void;

/**
 * Schema v5 模板段的结构化编辑。
 *
 * <p>它<b>不替代</b>原文编辑：原文才是唯一事实来源。这里只是把 curves /
 * instances 解析成表单，改完再写回整份文档。JSON 里的未知键会保留，
 * 但缩进与空格会统一成 2 空格——这一点必须在界面上说清楚。</p>
 */
export function TemplateEditor({ text, onChange }: TemplateEditorProps): JSX.Element {
  const [error, setError] = useState('');

  const parsed = useMemo(() => parseConfigDocument(text), [text]);
  const viewResult = useMemo<ReadTemplatesResult | null>(
    () => (parsed.ok ? readTemplates(parsed.config.root) : null),
    [parsed],
  );

  const edit: MutateTemplates = (mutate) => {
    const result = editTemplates(text, mutate);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    setError('');
    onChange(result.text);
  };

  if (!parsed.ok) {
    return (
      <div className="template-editor">
        <h3>模板（结构化编辑）</h3>
        <p className="hint">
          原文还不是合法 JSON：<code>{parsed.error}</code>
          。先把上面的文本改到能解析，左边的表单才能动手。
        </p>
      </div>
    );
  }

  if (viewResult === null || !viewResult.ok) {
    return (
      <div className="template-editor">
        <h3>模板（结构化编辑）</h3>
        <p className="problems">模板段结构不对：{viewResult === null ? '无法读取' : viewResult.error}</p>
        <p className="hint">先按 JSON 结构修文本，或删掉整个 templates 段后重新插入。</p>
      </div>
    );
  }

  const view = viewResult.templates;
  const svids = parsed.config.svids;

  if (!view.present) {
    return (
      <div className="template-editor">
        <div className="template-head">
          <h3>模板（结构化编辑）</h3>
          <div className="template-actions">
            <button
              type="button"
              onClick={() => {
                edit(() => {
                  /* 空 mutator：editTemplates 会插入空的 curves / composites / instances */
                });
              }}
            >
              插入空白模板段
            </button>
            <button
              type="button"
              onClick={() => {
                const first = svids[0];
                if (first === undefined) {
                  setError('配置里还没有 SVID，先添加设备变量再插入模板');
                  return;
                }
                edit((templates) => {
                  const curveId = uniqueId(templates.curves.map((item) => item.id), 'curve');
                  templates.curves.push({ id: curveId, ...defaultCurveFields('sine') });
                  const instanceId = uniqueId(templates.instances.map((item) => item.id), 'inst');
                  templates.instances.push({
                    id: instanceId,
                    template: curveId,
                    svids: { V: first.svid },
                    params: {},
                  });
                });
              }}
            >
              插入示例（正弦 + 实例）
            </button>
          </div>
        </div>
        <p className="hint">
          这份配置还没有 <code>templates</code> 段，运行时走 v4 默认曲线。
          插入模板段后，未绑定到实例的 SVID 会保持 0（见 docs/schema-v5.md）。
        </p>
        {error !== '' && <p className="problems">{error}</p>}
      </div>
    );
  }

  const addCurve = () => {
    edit((templates) => {
      const id = uniqueId(templates.curves.map((item) => item.id), 'curve');
      templates.curves.push({ id, ...defaultCurveFields('sine') });
    });
  };

  const addComposite = () => {
    edit((templates) => {
      const id = uniqueId(
        [...templates.curves, ...templates.composites].map((item) => item.id),
        'composite',
      );
      templates.composites.push({ id, outputs: { OUT: '0' } });
    });
  };

  const addInstance = () => {
    const candidates = [...view.curves, ...view.composites];
    const firstTemplate = candidates[0];
    const firstSvid = svids[0];
    if (firstTemplate === undefined) {
      setError('先添加一条曲线或组合模板');
      return;
    }
    if (firstSvid === undefined) {
      setError('配置里还没有 SVID，实例没有可绑定的变量');
      return;
    }

    const templateId = stringField(firstTemplate, 'id');
    const outputs = templateOutputs(view, templateId);
    if (outputs.length === 0) {
      setError(`模板 ${templateId} 没有输出名，无法绑定`);
      return;
    }

    edit((templates) => {
      const id = uniqueId(templates.instances.map((item) => item.id), 'inst');
      templates.instances.push({
        id,
        template: templateId,
        svids: { [outputs[0]]: firstSvid.svid },
        params: {},
      });
    });
  };

  return (
    <div className="template-editor">
      <div className="template-head">
        <h3>模板（结构化编辑）</h3>
        <div className="template-actions">
          <button type="button" onClick={addCurve}>
            + 曲线
          </button>
          <button type="button" onClick={addComposite}>
            + 组合模板
          </button>
          <button type="button" onClick={addInstance}>
            + 实例
          </button>
        </div>
      </div>
      <p className="hint">
        表单修改的是上面原文解析后的结构；保存前仍要「校验」。未知键会保留，
        但整份文件的缩进与空格会统一成 2 空格（原文编辑器里的逐字节保真只在不动表单时成立）。
      </p>
      {error !== '' && <p className="problems">{error}</p>}

      <datalist id="template-svid-options">
        {svids.map((choice) => (
          <option key={choice.svid} value={choice.svid}>
            {choice.owner}.{choice.slot}
          </option>
        ))}
      </datalist>

      <h4>曲线</h4>
      {view.curves.length === 0 && <p className="hint">还没有曲线。点上面的「+ 曲线」加一条。</p>}
      {view.curves.map((curve, index) => (
        <CurveCard
          key={`curve-${index}`}
          curve={curve}
          index={index}
          view={view}
          onMutate={edit}
          onError={setError}
        />
      ))}

      <h4>组合模板</h4>
      {view.composites.length === 0 && <p className="hint">还没有组合模板。</p>}
      {view.composites.map((composite, index) => (
        <CompositeCard
          key={`composite-${index}`}
          composite={composite}
          index={index}
          view={view}
          onMutate={edit}
          onError={setError}
        />
      ))}

      <h4>实例</h4>
      {view.instances.length === 0 && (
        <p className="hint">还没有实例。实例把模板输出绑定到绝对 SVID，并放实例参数。</p>
      )}
      {view.instances.map((instance, index) => (
        <InstanceCard
          key={`instance-${index}`}
          instance={instance}
          index={index}
          view={view}
          svids={svids}
          onMutate={edit}
          onError={setError}
        />
      ))}
    </div>
  );
}

interface CurveCardProps {
  curve: JsonObject;
  index: number;
  view: TemplateView;
  onMutate: MutateTemplates;
  onError: (message: string) => void;
}

function CurveCard({ curve, index, view, onMutate, onError }: CurveCardProps): JSX.Element {
  const id = stringField(curve, 'id');
  const rawType = curve.type;
  const type = isCurveType(rawType) ? rawType : null;
  const references = view.instances.filter((item) => stringField(item, 'template') === id).length;

  const rename = (next: string) => {
    const value = next.trim();
    if (value === '') {
      onError('曲线 id 不能为空');
      return;
    }
    if (value === id) {
      return;
    }
    if (
      view.curves.some((item, itemIndex) => itemIndex !== index && stringField(item, 'id') === value) ||
      view.composites.some((item) => stringField(item, 'id') === value)
    ) {
      onError(`模板 id 已存在: ${value}`);
      return;
    }
    onMutate((templates) => {
      templates.curves[index].id = value;
      for (const instance of templates.instances) {
        if (stringField(instance, 'template') === id) {
          instance.template = value;
        }
      }
    });
  };

  const changeType = (next: string) => {
    if (!isCurveType(next)) {
      return;
    }
    onMutate((templates) => {
      const target = templates.curves[index];
      const oldId = target.id;
      for (const key of Object.keys(target)) {
        if (key !== 'id') {
          delete target[key];
        }
      }
      Object.assign(target, defaultCurveFields(next));
      target.id = oldId;
    });
  };

  const remove = () => {
    if (references > 0) {
      onError(`曲线 ${id} 仍被 ${references} 个实例引用，先把这些实例的模板改掉`);
      return;
    }
    onMutate((templates) => {
      templates.curves.splice(index, 1);
    });
  };

  const setField = (key: string, value: unknown) => {
    onMutate((templates) => {
      templates.curves[index][key] = value;
    });
  };

  return (
    <div className="template-card">
      <div className="template-card-head">
        <label>
          id
          <TextField value={id} onCommit={rename} />
        </label>
        <label>
          类型
          <select value={type ?? ''} onChange={(event) => changeType(event.target.value)}>
            {type === null && <option value="">未知: {String(rawType ?? '')}</option>}
            {CURVE_TYPES.map((item) => (
              <option key={item.type} value={item.type}>
                {item.label}（{item.type}）
              </option>
            ))}
          </select>
        </label>
        <span className="template-badge">{references} 个实例引用</span>
        <button type="button" onClick={remove}>
          删除曲线
        </button>
      </div>

      {type === null ? (
        <p className="problems">未知曲线类型：{String(rawType ?? '(缺失)')}</p>
      ) : (
        <div className="template-fields">
          {curveFieldSpecs(type).map((field) => {
            if (field.kind === 'number') {
              return (
                <label key={field.key}>
                  {field.label}
                  <NumberField
                    value={numberField(curve, field.key)}
                    integer={field.integer}
                    step={field.step}
                    onCommit={(value) => setField(field.key, value)}
                  />
                </label>
              );
            }
            if (field.kind === 'boolean') {
              return (
                <label key={field.key} className="template-checkbox">
                  <input
                    type="checkbox"
                    checked={booleanField(curve, field.key)}
                    onChange={(event) => setField(field.key, event.target.checked)}
                  />
                  {field.label}
                </label>
              );
            }
            return (
              <label key={field.key}>
                {field.label}
                <select
                  value={typeof curve[field.key] === 'string' ? String(curve[field.key]) : field.options[0]}
                  onChange={(event) => setField(field.key, event.target.value)}
                >
                  {field.options.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </label>
            );
          })}
          {type === 'step' && <StepEditor curve={curve} index={index} onMutate={onMutate} />}
          {type === 'pwl' && <PointEditor curve={curve} index={index} onMutate={onMutate} />}
        </div>
      )}
      <p className="hint">
        曲线输出固定为 <code>V</code>。
        {type === null ? '' : `${curveTypeLabel(type)}参数直接写进 templates.curves。`}
      </p>
    </div>
  );
}

function StepEditor({
  curve,
  index,
  onMutate,
}: {
  curve: JsonObject;
  index: number;
  onMutate: MutateTemplates;
}): JSX.Element {
  const steps = arrayField(curve, 'steps');

  const add = () => {
    onMutate((templates) => {
      const target = templates.curves[index];
      const list = Array.isArray(target.steps) ? (target.steps as unknown[]) : [];
      const last = list[list.length - 1];
      list.push({
        fromMs: (isJsonObject(last) ? numberField(last, 'fromMs') ?? 0 : 0) + 1_000,
        value: isJsonObject(last) ? numberField(last, 'value') ?? 0 : 0,
      });
      target.steps = list;
    });
  };

  const update = (row: number, key: string, value: number) => {
    onMutate((templates) => {
      const target = templates.curves[index];
      const list = Array.isArray(target.steps) ? (target.steps as unknown[]) : [];
      const item = list[row];
      if (isJsonObject(item)) {
        item[key] = value;
      }
    });
  };

  const remove = (row: number) => {
    onMutate((templates) => {
      const target = templates.curves[index];
      if (Array.isArray(target.steps)) {
        target.steps.splice(row, 1);
      }
    });
  };

  return (
    <div className="template-subtable">
      <div className="template-subhead">
        <span>从 ms</span>
        <span>值</span>
        <button type="button" onClick={add}>
          + 阶跃
        </button>
      </div>
      {steps.map((step, row) =>
        isJsonObject(step) ? (
          <div className="template-subrow" key={row}>
            <NumberField
              value={numberField(step, 'fromMs')}
              integer
              onCommit={(value) => update(row, 'fromMs', value)}
            />
            <NumberField
              value={numberField(step, 'value')}
              onCommit={(value) => update(row, 'value', value)}
            />
            <button type="button" onClick={() => remove(row)}>
              删除
            </button>
          </div>
        ) : (
          <p className="problems" key={row}>
            steps[{row}] 不是对象
          </p>
        ),
      )}
      {steps.length === 0 && <p className="hint">至少加一个阶跃点。</p>}
    </div>
  );
}

function PointEditor({
  curve,
  index,
  onMutate,
}: {
  curve: JsonObject;
  index: number;
  onMutate: MutateTemplates;
}): JSX.Element {
  const points = arrayField(curve, 'points');

  const add = () => {
    onMutate((templates) => {
      const target = templates.curves[index];
      const list = Array.isArray(target.points) ? (target.points as unknown[]) : [];
      const last = list[list.length - 1];
      list.push({
        tMs: (isJsonObject(last) ? numberField(last, 'tMs') ?? 0 : 0) + 1_000,
        value: isJsonObject(last) ? numberField(last, 'value') ?? 0 : 0,
      });
      target.points = list;
    });
  };

  const update = (row: number, key: string, value: number) => {
    onMutate((templates) => {
      const target = templates.curves[index];
      const list = Array.isArray(target.points) ? (target.points as unknown[]) : [];
      const item = list[row];
      if (isJsonObject(item)) {
        item[key] = value;
      }
    });
  };

  const remove = (row: number) => {
    onMutate((templates) => {
      const target = templates.curves[index];
      if (Array.isArray(target.points)) {
        target.points.splice(row, 1);
      }
    });
  };

  return (
    <div className="template-subtable">
      <div className="template-subhead">
        <span>t ms</span>
        <span>值</span>
        <button type="button" onClick={add}>
          + 关键帧
        </button>
      </div>
      {points.map((point, row) =>
        isJsonObject(point) ? (
          <div className="template-subrow" key={row}>
            <NumberField
              value={numberField(point, 'tMs')}
              integer
              onCommit={(value) => update(row, 'tMs', value)}
            />
            <NumberField
              value={numberField(point, 'value')}
              onCommit={(value) => update(row, 'value', value)}
            />
            <button type="button" onClick={() => remove(row)}>
              删除
            </button>
          </div>
        ) : (
          <p className="problems" key={row}>
            points[{row}] 不是对象
          </p>
        ),
      )}
      {points.length === 0 && <p className="hint">至少加一个关键帧。</p>}
    </div>
  );
}

function CompositeCard({
  composite,
  index,
  view,
  onMutate,
  onError,
}: {
  composite: JsonObject;
  index: number;
  view: TemplateView;
  onMutate: MutateTemplates;
  onError: (message: string) => void;
}): JSX.Element {
  const id = stringField(composite, 'id');
  const outputs = objectField(composite, 'outputs');
  const references = view.instances.filter((item) => stringField(item, 'template') === id).length;

  const rename = (next: string) => {
    const value = next.trim();
    if (value === '') {
      onError('组合模板 id 不能为空');
      return;
    }
    if (value === id) {
      return;
    }
    if (
      view.curves.some((item) => stringField(item, 'id') === value) ||
      view.composites.some((item, itemIndex) => itemIndex !== index && stringField(item, 'id') === value)
    ) {
      onError(`模板 id 已存在: ${value}`);
      return;
    }
    onMutate((templates) => {
      templates.composites[index].id = value;
      for (const instance of templates.instances) {
        if (stringField(instance, 'template') === id) {
          instance.template = value;
        }
      }
    });
  };

  const remove = () => {
    if (references > 0) {
      onError(`组合模板 ${id} 仍被 ${references} 个实例引用，先把这些实例的模板改掉`);
      return;
    }
    onMutate((templates) => {
      templates.composites.splice(index, 1);
    });
  };

  const renameOutput = (oldName: string, next: string) => {
    const value = next.trim();
    if (value === '' || value === oldName) {
      return;
    }
    if (!isExpressionName(value)) {
      onError('输出名只能包含字母、数字、下划线，且不能以数字开头（表达式要按 in.<实例>.<输出> 引用）');
      return;
    }
    if (Object.hasOwn(outputs, value)) {
      onError(`输出名已存在: ${value}`);
      return;
    }
    onMutate((templates) => {
      const target = templates.composites[index];
      const current = ensureObjectField(target, 'outputs');
      target.outputs = renameKey(current, oldName, value);
      for (const instance of templates.instances) {
        if (stringField(instance, 'template') !== id) {
          continue;
        }
        const bindings = ensureObjectField(instance, 'svids');
        instance.svids = renameKey(bindings, oldName, value);
      }
    });
  };

  const setExpression = (name: string, source: string) => {
    onMutate((templates) => {
      ensureObjectField(templates.composites[index], 'outputs')[name] = source;
    });
  };

  const addOutput = () => {
    onMutate((templates) => {
      const target = templates.composites[index];
      const current = ensureObjectField(target, 'outputs');
      const name = uniqueId(Object.keys(current), 'OUT');
      current[name] = '0';
    });
  };

  const removeOutput = (name: string) => {
    const bound = view.instances.some(
      (instance) => stringField(instance, 'template') === id && Object.hasOwn(objectField(instance, 'svids'), name),
    );
    if (bound) {
      onError(`输出 ${name} 已绑定到实例，先删除实例里的绑定`);
      return;
    }
    onMutate((templates) => {
      const target = templates.composites[index];
      const current = ensureObjectField(target, 'outputs');
      delete current[name];
    });
  };

  return (
    <div className="template-card">
      <div className="template-card-head">
        <label>
          id
          <TextField value={id} onCommit={rename} />
        </label>
        <span className="template-badge">{references} 个实例引用</span>
        <button type="button" onClick={remove}>
          删除组合模板
        </button>
      </div>
      <div className="template-outputs">
        {Object.entries(outputs).map(([name, expression]) => (
          <div className="template-output-row" key={name}>
            <TextField value={name} onCommit={(next) => renameOutput(name, next)} />
            <span>=</span>
            <TextField
              value={typeof expression === 'string' ? expression : String(expression)}
              allowBlank
              wide
              onCommit={(next) => setExpression(name, next)}
            />
            <button type="button" onClick={() => removeOutput(name)}>
              删除
            </button>
          </div>
        ))}
        <button type="button" onClick={addOutput}>
          + 输出
        </button>
      </div>
      <p className="hint">表达式语法见 ADR-0004；这里只改表达式，不改求值规则。</p>
    </div>
  );
}

function InstanceCard({
  instance,
  index,
  view,
  svids,
  onMutate,
  onError,
}: {
  instance: JsonObject;
  index: number;
  view: TemplateView;
  svids: SvidChoice[];
  onMutate: MutateTemplates;
  onError: (message: string) => void;
}): JSX.Element {
  const id = stringField(instance, 'id');
  const template = stringField(instance, 'template');
  const allTemplates = [...view.curves, ...view.composites];
  const knownTemplate = allTemplates.some((item) => stringField(item, 'id') === template);
  const outputs = templateOutputs(view, template);
  const bindings = objectField(instance, 'svids');
  const params = objectField(instance, 'params');
  const firstSvid = svids[0]?.svid;

  const rename = (next: string) => {
    const value = next.trim();
    if (value === '') {
      onError('实例 id 不能为空');
      return;
    }
    if (!isExpressionName(value)) {
      onError('实例 id 只能包含字母、数字、下划线，且不能以数字开头（表达式要按 in.<实例>.<输出> 引用）');
      return;
    }
    if (value === id) {
      return;
    }
    if (view.instances.some((item, itemIndex) => itemIndex !== index && stringField(item, 'id') === value)) {
      onError(`实例 id 已存在: ${value}`);
      return;
    }
    onMutate((templates) => {
      templates.instances[index].id = value;
      /* 组合模板表达式里的 in.<实例>.<输出> 也要跟着改，否则图会引用一个不存在的实例 */
      for (const composite of templates.composites) {
        const source = ensureObjectField(composite, 'outputs');
        for (const [key, expression] of Object.entries(source)) {
          if (typeof expression === 'string') {
            source[key] = expression.split(`in.${id}.`).join(`in.${value}.`);
          }
        }
      }
    });
  };

  const changeTemplate = (next: string) => {
    onMutate((templates) => {
      templates.instances[index].template = next;
    });
  };

  const renameBinding = (oldName: string, next: string) => {
    const value = next.trim();
    if (value === '' || value === oldName) {
      return;
    }
    if (!isExpressionName(value)) {
      onError('输出名只能包含字母、数字、下划线，且不能以数字开头（表达式要按 in.<实例>.<输出> 引用）');
      return;
    }
    if (Object.hasOwn(bindings, value)) {
      onError(`输出名已绑定: ${value}`);
      return;
    }
    onMutate((templates) => {
      const target = templates.instances[index];
      const current = ensureObjectField(target, 'svids');
      target.svids = renameKey(current, oldName, value);
    });
  };

  const setBindingSvid = (name: string, value: number) => {
    onMutate((templates) => {
      ensureObjectField(templates.instances[index], 'svids')[name] = value;
    });
  };

  const addBinding = () => {
    if (outputs.length === 0) {
      onError('所选模板没有输出名，无法绑定');
      return;
    }
    const free = outputs.find((name) => !Object.hasOwn(bindings, name));
    if (free === undefined) {
      onError('该模板的输出都已绑定');
      return;
    }
    if (firstSvid === undefined) {
      onError('配置里还没有 SVID');
      return;
    }
    onMutate((templates) => {
      ensureObjectField(templates.instances[index], 'svids')[free] = firstSvid;
    });
  };

  const removeBinding = (name: string) => {
    onMutate((templates) => {
      const target = templates.instances[index];
      const current = ensureObjectField(target, 'svids');
      delete current[name];
      target.svids = current;
    });
  };

  const renameParam = (oldName: string, next: string) => {
    const value = next.trim();
    if (value === '' || value === oldName) {
      return;
    }
    if (!isExpressionName(value)) {
      onError('参数名只能包含字母、数字、下划线，且不能以数字开头（表达式要按 p.<参数> 引用）');
      return;
    }
    if (Object.hasOwn(params, value)) {
      onError(`参数名已存在: ${value}`);
      return;
    }
    onMutate((templates) => {
      const target = templates.instances[index];
      const current = ensureObjectField(target, 'params');
      target.params = renameKey(current, oldName, value);
    });
  };

  const setParam = (name: string, value: number) => {
    onMutate((templates) => {
      ensureObjectField(templates.instances[index], 'params')[name] = value;
    });
  };

  const addParam = () => {
    onMutate((templates) => {
      const target = templates.instances[index];
      const current = ensureObjectField(target, 'params');
      const name = uniqueId(Object.keys(current), 'param');
      current[name] = 0;
    });
  };

  const removeParam = (name: string) => {
    onMutate((templates) => {
      const target = templates.instances[index];
      const current = ensureObjectField(target, 'params');
      delete current[name];
      target.params = current;
    });
  };

  const remove = () => {
    onMutate((templates) => {
      templates.instances.splice(index, 1);
    });
  };

  return (
    <div className="template-card">
      <div className="template-card-head">
        <label>
          id
          <TextField value={id} onCommit={rename} />
        </label>
        <label>
          模板
          <select value={template} onChange={(event) => changeTemplate(event.target.value)}>
            {!knownTemplate && <option value={template}>{template}（不存在）</option>}
            {allTemplates.map((item, templateIndex) => {
              const name = stringField(item, 'id');
              return (
                <option key={`${name}-${templateIndex}`} value={name}>
                  {name}
                </option>
              );
            })}
          </select>
        </label>
        <button type="button" onClick={remove}>
          删除实例
        </button>
      </div>

      <div className="template-section">
        <span className="template-label">输出 → SVID</span>
        {Object.entries(bindings).map(([name, svid]) => (
          <div className="template-binding-row" key={name}>
            <select value={name} onChange={(event) => renameBinding(name, event.target.value)}>
              {!outputs.includes(name) && <option value={name}>{name}（模板无此输出）</option>}
              {outputs.map((output) => (
                <option key={output} value={output}>
                  {output}
                </option>
              ))}
            </select>
            <NumberField
              value={typeof svid === 'number' ? svid : undefined}
              integer
              list="template-svid-options"
              onCommit={(value) => setBindingSvid(name, value)}
            />
            <button type="button" onClick={() => removeBinding(name)}>
              删除
            </button>
          </div>
        ))}
        <button type="button" onClick={addBinding}>
          + 绑定输出
        </button>
      </div>

      <div className="template-section">
        <span className="template-label">实例参数（p.名字）</span>
        {Object.entries(params).map(([name, value]) => (
          <div className="template-binding-row" key={name}>
            <TextField value={name} onCommit={(next) => renameParam(name, next)} />
            <NumberField
              value={typeof value === 'number' ? value : undefined}
              onCommit={(next) => setParam(name, next)}
            />
            <button type="button" onClick={() => removeParam(name)}>
              删除
            </button>
          </div>
        ))}
        <button type="button" onClick={addParam}>
          + 参数
        </button>
      </div>

      {outputs.length === 0 && <p className="problems">所选模板没有可识别的输出名。</p>}
      {!knownTemplate && <p className="problems">模板 {template} 不在 curves / composites 里。</p>}
    </div>
  );
}

function renameKey(source: JsonObject, oldName: string, next: string): JsonObject {
  const result: JsonObject = {};
  for (const [key, value] of Object.entries(source)) {
    result[key === oldName ? next : key] = value;
  }
  return result;
}

interface TextFieldProps {
  value: string;
  allowBlank?: boolean;
  wide?: boolean;
  onCommit: (next: string) => void;
}

/** 失焦/回车才提交的文本框：避免每敲一个字就重写整份配置。 */
function TextField({ value, allowBlank = false, wide = false, onCommit }: TextFieldProps): JSX.Element {
  const [draft, setDraft] = useState(value);

  useEffect(() => {
    setDraft(value);
  }, [value]);

  const commit = () => {
    if (!allowBlank && draft.trim() === '') {
      setDraft(value);
      return;
    }
    if (draft !== value) {
      onCommit(draft);
    }
  };

  return (
    <input
      className={wide ? 'template-input-wide' : undefined}
      value={draft}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.currentTarget.blur();
        }
      }}
    />
  );
}

interface NumberFieldProps {
  value: number | undefined;
  integer?: boolean;
  step?: number;
  list?: string;
  onCommit: (next: number) => void;
}

/** 失焦/回车才提交的数字框；非法输入恢复原值，避免把 NaN 写进 JSON。 */
function NumberField({ value, integer = false, step, list, onCommit }: NumberFieldProps): JSX.Element {
  const external = value === undefined ? '' : String(value);
  const [draft, setDraft] = useState(external);

  useEffect(() => {
    setDraft(external);
  }, [external]);

  const commit = () => {
    const parsed = Number(draft);
    if (draft.trim() === '' || !Number.isFinite(parsed) || (integer && !Number.isInteger(parsed))) {
      setDraft(external);
      return;
    }
    if (parsed !== value) {
      onCommit(parsed);
    }
  };

  return (
    <input
      type="number"
      list={list}
      step={step ?? (integer ? 1 : 'any')}
      value={draft}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.currentTarget.blur();
        }
      }}
    />
  );
}
