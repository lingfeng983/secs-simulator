import { CurveSegmentsField } from './CurveSegmentsField';
import { NumberField } from '../../shared/ui/CommitFields';
import {
  arrayField,
  booleanField,
  CURVE_TYPES,
  curveFieldSpecs,
  defaultCurveFields,
  isCurveType,
  isJsonObject,
  numberField,
  stringField,
  type JsonObject,
} from './templateConfig';

/** 编辑函数：直接改传入的对象（调用方负责这是文档树里的活引用）。 */
type ObjectEdit = (fn: (target: JsonObject) => void) => void;

/** 曲线类型 + 标量字段 + 关键帧 / 分段。 */
export function CurveFields({ curve, mutateCurve }: {
  curve: JsonObject;
  mutateCurve: ObjectEdit;
}): JSX.Element {
  const rawType = stringField(curve, 'type', 'sine');
  const type = isCurveType(rawType) ? rawType : null;
  const specs = type !== null ? curveFieldSpecs(type) : [];

  return (
    <>
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
            <option key={item.type} value={item.type}>{item.label}</option>
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
                <option key={option} value={option}>{option}</option>
              ))}
            </select>
          </label>
        );
      })}

      {(type === 'step' || type === 'pwl') && (
        <CurveFramesField curve={curve} kind={type} mutateCurve={mutateCurve} />
      )}
      {type === 'sequence' && <CurveSegmentsField curve={curve} mutateCurve={mutateCurve} />}
    </>
  );
}

/**
 * step/pwl 的关键帧编辑：一行一帧（时刻 + 值），增删都写穿回文档。
 * 与分段序列同一套纪律——不让用户手写 JSON。
 */
function CurveFramesField({ curve, kind, mutateCurve }: {
  curve: JsonObject;
  kind: 'step' | 'pwl';
  mutateCurve: ObjectEdit;
}): JSX.Element {
  const key = kind === 'step' ? 'steps' : 'points';
  const timeKey = kind === 'step' ? 'fromMs' : 'tMs';
  const noun = kind === 'step' ? '阶跃' : '关键帧';
  const frames = arrayField(curve, key);

  const mutateFrames = (fn: (items: JsonObject[]) => void) => {
    mutateCurve((target) => {
      const items = Array.isArray(target[key]) ? (target[key] as JsonObject[]) : [];
      fn(items);
      target[key] = items;
    });
  };

  return (
    <div className="curve-frames">
      <div className="curve-frames-head">
        <span className="template-label">{noun}（{frames.length} 帧）</span>
        <button
          type="button"
          onClick={() => mutateFrames((items) => {
            const last = items[items.length - 1];
            const base = items.length === 0 ? 0 : (isJsonObject(last) ? numberField(last, timeKey) ?? 0 : 0);
            items.push({ [timeKey]: items.length === 0 ? 0 : base + 10_000, value: 0 });
          })}
        >
          + {noun}
        </button>
      </div>
      {frames.length === 0 && (
        <p className="hint">还没有{noun}，点「+ {noun}」添加第一帧。</p>
      )}
      {frames.map((frame, index) => (
        <div className="form-row curve-frame-row" key={index}>
          {isJsonObject(frame) ? (
            <>
              <label className="field">
                时刻 ms
                <NumberField
                  value={numberField(frame, timeKey)}
                  integer
                  onCommit={(next) => mutateFrames((items) => {
                    if (isJsonObject(items[index])) {
                      items[index][timeKey] = next;
                    }
                  })}
                />
              </label>
              <label className="field">
                值
                <NumberField
                  value={numberField(frame, 'value')}
                  onCommit={(next) => mutateFrames((items) => {
                    if (isJsonObject(items[index])) {
                      items[index].value = next;
                    }
                  })}
                />
              </label>
            </>
          ) : (
            <span className="problems">第 {index + 1} 帧不是对象，无法按表单编辑</span>
          )}
          <button
            type="button"
            onClick={() => mutateFrames((items) => {
              items.splice(index, 1);
            })}
          >
            删除
          </button>
        </div>
      ))}
    </div>
  );
}