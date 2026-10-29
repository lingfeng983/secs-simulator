import { useState } from 'react';
import { NumberField, TextField } from './ui/CommitFields';
import {
  booleanField,
  CURVE_TYPES,
  curveFieldSpecs,
  defaultCurveFields,
  defaultEffect,
  effectFieldSpecs,
  isCurveType,
  isJsonObject,
  numberField,
  objectField,
  stringField,
  svidSourceKind,
  EFFECT_OPS,
  type EffectOp,
  type JsonObject,
  type SourceKind,
} from './templateConfig';

/** 编辑函数:直接改传入的对象(调用方负责这是文档树里的活引用)。 */
type ObjectEdit = (fn: (target: JsonObject) => void) => void;

/**
 * SVID 直配值来源编辑器（schema v6）。
 *
 * <p>默认（随机游走）/ 内联曲线 / 模板引用 三选一,写回落在 SVID 定义
 * 对象的 curve/template/output/params 四个键上,与后端 schema v6 一一对应。
 * 「预览曲线」把绝对 SVID 交给上层,按草稿 + 事件标记画单变量曲线。</p>
 */
export function SvidSourceEditor({ def, templateIds, absoluteId, onPreviewSvid }: {
  def: JsonObject;
  templateIds: string[];
  absoluteId: number;
  onPreviewSvid?: (svid: number) => void;
}): JSX.Element {
  const kind = svidSourceKind(def);
  const [editing, setEditing] = useState(false);
  const expanded = kind !== 'default' && editing;

  const setKind = (next: SourceKind) => {
    if (next === 'default') {
      clearSourceKeys(def);
      setEditing(false);
      return;
    }
    clearSourceKeys(def);
    if (next === 'curve') {
      def.curve = defaultCurveFields('sine');
    } else {
      def.template = templateIds[0] ?? '';
    }
    setEditing(true);
  };

  const label =
    kind === 'curve'
      ? `值来源:曲线 (${stringField(objectField(def, 'curve'), 'type', '?')})`
      : kind === 'template'
        ? `值来源:模板 ${stringField(def, 'template', '?')}`
        : '值来源:默认';

  return (
    <div className="svid-source">
      <div className="svid-source-bar">
        <span className="svid-source-label" title={label}>{label}</span>
        <select
          className="svid-source-kind"
          value={kind}
          onChange={(event) => setKind(event.target.value as SourceKind)}
          aria-label="值来源类型"
        >
          <option value="default">默认（随机游走）</option>
          <option value="curve">内联曲线</option>
          <option value="template">引用模板</option>
        </select>
        {kind !== 'default' && (
          <button type="button" onClick={() => setEditing((previous) => !previous)}>
            {expanded ? '收起' : '展开编辑'}
          </button>
        )}
        {onPreviewSvid !== undefined && absoluteId > 0 && (
          <button type="button" onClick={() => onPreviewSvid(absoluteId)} title="按当前草稿预览这条 SVID 的曲线（含事件标记）">
            预览曲线
          </button>
        )}
      </div>

      {expanded && kind === 'curve' && <CurveSourceEditor def={def} />}
      {expanded && kind === 'template' && <TemplateSourceEditor def={def} templateIds={templateIds} />}
    </div>
  );
}

/** 清空值来源相关键(回到默认随机游走)。 */
function clearSourceKeys(def: JsonObject): void {
  delete def.curve;
  delete def.template;
  delete def.output;
  delete def.params;
}

/** 内联曲线的参数编辑(step/pwl 的关键帧用 JSON 文本编辑)。 */
export function CurveSourceEditor({ def }: { def: JsonObject }): JSX.Element {
  const curve = objectField(def, 'curve');
  const rawType = stringField(curve, 'type', 'sine');
  const type = isCurveType(rawType) ? rawType : null;
  const specs = type !== null ? curveFieldSpecs(type) : [];

  const mutateCurve = (fn: (target: JsonObject) => void) => {
    fn(objectField(def, 'curve'));
  };

  return (
    <div className="form-row svid-source-detail">
      <label className="field">
        曲线类型
        <select
          value={type ?? ''}
          onChange={(event) => {
            const next = CURVE_TYPES.find((item) => item.type === event.target.value);
            if (next !== undefined) {
              mutateCurve((target) => {
                for (const key of Object.keys(target)) {
                  delete target[key];
                }
                Object.assign(target, defaultCurveFields(next.type));
              });
            }
          }}
        >
          {type === null && <option value="">未知:{rawType}</option>}
          {CURVE_TYPES.map((item) => (
            <option key={item.type} value={item.type}>
              {item.label}
            </option>
          ))}
        </select>
      </label>

      {specs.map((field) => {
        if (field.kind === 'number') {
          return (
            <label className="field" key={field.key}>
              {field.label}
              <NumberField
                value={numberField(curve, field.key)}
                integer={field.integer ?? false}
                step={field.step}
                onCommit={(next) => mutateCurve((target) => {
                  target[field.key] = next;
                })}
              />
            </label>
          );
        }
        if (field.kind === 'boolean') {
          return (
            <label className="check-inline" key={field.key}>
              <input
                type="checkbox"
                checked={booleanField(curve, field.key)}
                onChange={(event) => mutateCurve((target) => {
                  target[field.key] = event.target.checked;
                })}
              />
              {field.label}
            </label>
          );
        }
        return (
          <label className="field" key={field.key}>
            {field.label}
            <select
              value={stringField(curve, field.key, field.options[0] ?? '')}
              onChange={(event) => mutateCurve((target) => {
                target[field.key] = event.target.value;
              })}
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

      {(type === 'step' || type === 'pwl') && <CurveFramesField curve={curve} kind={type} />}
    </div>
  );
}

/** step/pwl 的关键帧编辑:数组以 JSON 文本编辑,blur 时解析写回。 */
function CurveFramesField({ curve, kind }: { curve: JsonObject; kind: 'step' | 'pwl' }): JSX.Element {
  const key = kind === 'step' ? 'steps' : 'points';
  const [text, setText] = useState(() => JSON.stringify(Array.isArray(curve[key]) ? curve[key] : [], null, 1));
  const [bad, setBad] = useState(false);

  const commit = () => {
    try {
      const parsed = JSON.parse(text) as unknown;
      if (!Array.isArray(parsed)) {
        setBad(true);
        return;
      }
      curve[key] = parsed;
      setBad(false);
    } catch {
      setBad(true);
    }
  };

  return (
    <label className="field grow">
      {kind === 'step' ? 'steps' : 'points'}
      <textarea
        value={text}
        rows={3}
        spellCheck={false}
        onChange={(event) => setText(event.target.value)}
        onBlur={commit}
        className={bad ? 'invalid' : undefined}
      />
      {bad && <span className="problems">JSON 解析失败,未写回</span>}
    </label>
  );
}

/** 模板引用编辑:模板选择 + 可选输出 + 参数(JSON 对象,值为数字)。 */
export function TemplateSourceEditor({ def, templateIds }: {
  def: JsonObject;
  templateIds: string[];
}): JSX.Element {
  const params = objectField(def, 'params');
  const [paramsText, setParamsText] = useState(() => {
    const keys = Object.keys(params);
    return keys.length === 0 ? '' : JSON.stringify(params);
  });
  const [bad, setBad] = useState(false);

  const commitParams = () => {
    if (paramsText.trim() === '') {
      delete def.params;
      setBad(false);
      return;
    }
    try {
      const parsed = JSON.parse(paramsText) as unknown;
      if (!isJsonObject(parsed) || Object.values(parsed).some((value) => typeof value !== 'number')) {
        setBad(true);
        return;
      }
      def.params = parsed;
      setBad(false);
    } catch {
      setBad(true);
    }
  };

  return (
    <div className="form-row svid-source-detail">
      <label className="field">
        模板
        <select
          value={stringField(def, 'template')}
          onChange={(event) => {
            def.template = event.target.value;
          }}
        >
          {templateIds.length === 0 && <option value="">（尚无模板）</option>}
          {templateIds.map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        输出（组合模板多输出时必填）
        <TextField
          allowBlank
          value={stringField(def, 'output')}
          onCommit={(next) => {
            if (next === '') {
              delete def.output;
            } else {
              def.output = next;
            }
          }}
        />
      </label>
      <label className="field grow">
        参数（JSON,如 {'{"base": 45}'})
        <textarea
          value={paramsText}
          rows={1}
          spellCheck={false}
          onChange={(event) => setParamsText(event.target.value)}
          onBlur={commitParams}
          className={bad ? 'invalid' : undefined}
          placeholder='{"base": 45}'
        />
        {bad && <span className="problems">应为数字键值对的 JSON,未写回</span>}
      </label>
    </div>
  );
}

/**
 * 事件效果编辑器（v6）:计划触发时对 SVID 施加的值变化。
 *
 * <p>effects 数组直接落在计划对象上;op 切换会重置为该 op 的默认形状
 * （保留 svid）。per-op 字段按 effectFieldSpecs 渲染,可省字段留空即删除。</p>
 */
export function EffectsEditor({ plan, mutatePlan, svidChoices }: {
  plan: JsonObject;
  mutatePlan: ObjectEdit;
  svidChoices: { svid: number; owner: string; slot: string }[];
}): JSX.Element {
  const effects = Array.isArray(plan.effects) ? (plan.effects as JsonObject[]) : [];

  const mutateEffect = (index: number, fn: (target: JsonObject) => void) => {
    mutatePlan((target) => {
      const list = Array.isArray(target.effects) ? (target.effects as JsonObject[]) : [];
      if (isJsonObject(list[index])) {
        fn(list[index]);
      }
    });
  };

  const addEffect = (op: EffectOp) => {
    mutatePlan((target) => {
      const list = Array.isArray(target.effects) ? (target.effects as JsonObject[]) : [];
      const svid = typeof plan.ceid === 'number' && plan.ceid > 0 ? 0 : 0;
      void svid;
      list.push(defaultEffect(op, 1));
      target.effects = list;
    });
  };

  const removeEffect = (index: number) => {
    mutatePlan((target) => {
      const list = Array.isArray(target.effects) ? (target.effects as unknown[]) : [];
      list.splice(index, 1);
    });
  };

  return (
    <div className="template-subtable effects-editor">
      <div className="template-subhead">
        <span>事件效果（触发时对 SVID 施加的值变化,v6）</span>
        <span className="toolbar">
          {EFFECT_OPS.map((op) => (
            <button key={op} type="button" onClick={() => addEffect(op)} title={`新增一个 ${op} 效果`}>
              + {op}
            </button>
          ))}
        </span>
      </div>

      {effects.length === 0 ? (
        <p className="hint">没有效果:事件触发只上报,不改值。加一个效果即可让曲线随事件跳变。</p>
      ) : (
        effects.map((effect, index) => {
          const rawOp = effect.op;
          const op: EffectOp = EFFECT_OPS.includes(rawOp as EffectOp) ? (rawOp as EffectOp) : 'set';
          const specs = effectFieldSpecs(op);

          return (
            /* 效果条目没有稳定 id（schema 里就是无 id 的数组）：只能按位置做 key。
               用 `${op}-${svid}` 拼 key 会在改 op/svid 时换 key、把输入框卸载重挂，
               反而丢失焦点；增删只发生在尾部，位置 key 足够稳定。 */
            <FormRowLite key={index} onRemove={() => removeEffect(index)}>
              <label className="field">
                SVID
                <select
                  value={numberField(effect, 'svid') ?? ''}
                  onChange={(event) => {
                    const next = Number(event.target.value);
                    if (Number.isFinite(next) && next > 0) {
                      mutateEffect(index, (target) => {
                        target.svid = next;
                      });
                    }
                  }}
                >
                  <option value="">选择 SVID…</option>
                  {svidChoices.map((choice) => (
                    <option key={choice.svid} value={choice.svid}>{choice.svid} · {choice.slot}</option>
                  ))}
                </select>
              </label>
              <label className="field">
                op
                <select
                  value={op}
                  onChange={(event) => {
                    const next = EFFECT_OPS.find((item) => item === event.target.value);
                    if (next !== undefined) {
                      mutateEffect(index, (target) => {
                        const svid = typeof target.svid === 'number' ? target.svid : 1;
                        delete target.value;
                        delete target.delta;
                        delete target.holdMs;
                        delete target.decayMs;
                        target.op = next;
                        Object.assign(target, defaultEffect(next, svid));
                        delete target.svid;
                        target.svid = svid;
                      });
                    }
                  }}
                >
                  {EFFECT_OPS.map((item) => (
                    <option key={item} value={item}>
                      {item}
                    </option>
                  ))}
                </select>
              </label>
              {specs.map((spec) =>
                spec.optional ? (
                  <label className="field" key={spec.key}>
                    {spec.label}（可省）
                    <OptionalNumberField
                      value={numberField(effect, spec.key)}
                      onCommit={(next) => mutateEffect(index, (target) => {
                        if (next === undefined) {
                          delete target[spec.key];
                        } else {
                          target[spec.key] = next;
                        }
                      })}
                    />
                  </label>
                ) : (
                  <label className="field" key={spec.key}>
                    {spec.label}
                    <NumberField
                      value={numberField(effect, spec.key)}
                      onCommit={(next) => mutateEffect(index, (target) => {
                        target[spec.key] = next;
                      })}
                    />
                  </label>
                ),
              )}
            </FormRowLite>
          );
        })
      )}
      {svidChoices.length > 0 && (
        <datalist id="cfg-svid-options">
          {svidChoices.map((choice) => (
            <option key={choice.svid} value={choice.svid}>
              {choice.owner}.{choice.slot}
            </option>
          ))}
        </datalist>
      )}
    </div>
  );
}

/** 效果行:一排字段 + 删除按钮(与 FormRow 同型,避免循环依赖)。 */
function FormRowLite({ onRemove, children }: { onRemove: () => void; children: React.ReactNode }): JSX.Element {
  return (
    <div className="form-row">
      {children}
      <button type="button" onClick={onRemove}>
        删除
      </button>
    </div>
  );
}

/** 可省数字字段:空 = 删除该键(set 的 decayMs 之类)。 */
function OptionalNumberField({ value, onCommit }: { value: number | undefined; onCommit: (next: number | undefined) => void }): JSX.Element {
  const [draft, setDraft] = useState(value === undefined ? '' : String(value));

  return (
    <input
      value={draft}
      inputMode="numeric"
      onChange={(event) => setDraft(event.target.value)}
      onBlur={() => {
        if (draft.trim() === '') {
          onCommit(undefined);
          return;
        }
        const parsed = Number(draft);
        if (Number.isFinite(parsed)) {
          onCommit(parsed);
        } else {
          setDraft(value === undefined ? '' : String(value));
        }
      }}
    />
  );
}
