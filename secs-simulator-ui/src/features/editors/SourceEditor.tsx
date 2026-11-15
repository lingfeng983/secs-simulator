import { useState } from 'react';
import type { ParamTemplate } from '../../api/paramTemplates';
import { CurveFields } from './CurveFields';
import { copyTemplateSource, templateShapeOf } from './paramTemplates';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import {
  clockOf,
  defaultCurveFields,
  defaultSource,
  describeSource,
  isExpressionName,
  isJsonObject,
  numberField,
  objectField,
  SOURCE_CLOCKS,
  SOURCE_MODES,
  sourceModeOf,
  sourceOf,
  stringField,
  type JsonObject,
  type SourceMode,
} from './templateConfig';

/** 编辑函数：直接改传入的对象（调用方负责这是文档树里的活引用）。 */
type ObjectEdit = (fn: (target: JsonObject) => void) => void;

/**
 * 一个数据项的值定义编辑器（schema v7）。
 *
 * <p>晶圆属性（Context）、设备常量（ECID）、参数变量（SVID）共用这一个组件：
 * 值来源只有 `source` 一个键，形态四选一——常量 / 曲线 / 表达式 / 按片。
 * 没有模板图、没有实例、没有绑定表；「从参数模板库填入」是<b>深拷贝</b>，
 * 拷完就与本设备无关，之后改库不影响这里。</p>
 *
 * <p>所有改动经 {@code mutate} 写穿（在整份文档的克隆上定位到这条定义），
 * 而不是改解析树上的活引用——直接改引用会在下一次重渲染重新解析原文时
 * 被丢掉，表现为"设置完又自己切回默认"。</p>
 */
export function SourceEditor({ def, mutate, text = false, library, onPreview }: {
  def: JsonObject;
  /** 写穿：fn 拿到这条数据项在<b>新文档</b>里的活引用。 */
  mutate: ObjectEdit;
  /** 是否文本型数据项（晶圆属性）：常量与按片都写字符串。 */
  text?: boolean;
  /** 参数模板库条目（所有设备共享）；给了才显示「从模板库填入」。 */
  library?: ParamTemplate[];
  /** 预览这条数据随时间的变化（可选）。 */
  onPreview?: () => void;
}): JSX.Element {
  const mode = sourceModeOf(def);
  const source = sourceOf(def);
  const libraryEntries = (library ?? []).filter((entry) => templateShapeOf(entry) !== '');

  const setMode = (next: SourceMode | '') => {
    mutate((target) => {
      if (next === '') {
        delete target.source;
        return;
      }
      target.source = defaultSource(next, text);
    });
  };

  const applyTemplate = (entry: ParamTemplate) => {
    const copied = copyTemplateSource(entry);
    if (copied === undefined) {
      return;
    }
    mutate((target) => {
      target.source = copied;
    });
  };

  const mutateSource = (fn: (source: JsonObject) => void) => {
    mutate((target) => {
      const child = target.source;
      if (isJsonObject(child)) {
        fn(child);
      }
    });
  };

  return (
    <div className="svid-source">
      <div className="svid-source-bar">
        <span className="svid-source-label" title={describeSource(def)}>{describeSource(def)}</span>
        <select
          className="svid-source-kind"
          value={mode}
          onChange={(event) => {
            /* 非用户事件（壳回放表单状态）不改值来源，避免一进页面就变形态 */
            if (!event.nativeEvent.isTrusted) {
              return;
            }
            setMode(event.target.value as SourceMode | '');
          }}
          aria-label="值定义形态"
        >
          <option value="">默认（运行期内置）</option>
          {SOURCE_MODES
            .filter((item) => !text || item.mode !== 'expr')
            .map((item) => (
              <option key={item.mode} value={item.mode}>{item.label}</option>
            ))}
        </select>
        {libraryEntries.length > 0 && (
          <select
            value=""
            aria-label="从参数模板库填入"
            title="选一条库模板，把它的值定义复制到这里；复制之后与库无关"
            onChange={(event) => {
              if (!event.nativeEvent.isTrusted) {
                return;
              }
              const hit = libraryEntries.find((item) => item.id === event.target.value);
              if (hit !== undefined) {
                applyTemplate(hit);
              }
            }}
          >
            <option value="">从模板库填入…</option>
            {libraryEntries.map((entry) => (
              <option key={entry.id} value={entry.id} title={entry.description}>{entry.name}</option>
            ))}
          </select>
        )}
        {onPreview !== undefined && (
          <button type="button" onClick={onPreview} title="按当前草稿预览这个数据随时间的变化">
            预览曲线
          </button>
        )}
      </div>

      {mode === 'const' && (
        <div className="form-row svid-source-detail">
          <label className="field">
            值
            {text
              ? (
                <TextField
                  value={stringField(source, 'value')}
                  onCommit={(next) => mutateSource((target) => {
                    target.value = next;
                  })}
                />
              )
              : (
                <NumberField
                  value={numberField(source, 'value')}
                  onCommit={(next) => mutateSource((target) => {
                    target.value = next;
                  })}
                />
              )}
          </label>
        </div>
      )}

      {mode === 'curve' && (
        <CurveSourceEditor
          source={source}
          mutateSource={mutateSource}
        />
      )}

      {mode === 'expr' && (
        <ExprSourceEditor source={source} mutateSource={mutateSource} />
      )}

      {mode === 'cycle' && (
        <CycleSourceEditor source={source} mutateSource={mutateSource} text={text} />
      )}
    </div>
  );
}

/** 时钟选择 + 一条曲线的全部字段。 */
function CurveSourceEditor({ source, mutateSource }: {
  source: JsonObject;
  mutateSource: ObjectEdit;
}): JSX.Element {
  const clock = clockOf(source);
  return (
    <div className="form-row svid-source-detail">
      <label className="field">
        时钟
        <select
          value={clock}
          onChange={(event) => mutateSource((target) => {
            target.clock = event.target.value;
          })}
        >
          {SOURCE_CLOCKS.map((item) => (
            <option key={item.value} value={item.value}>{item.label}</option>
          ))}
        </select>
      </label>
      <CurveFields
        curve={objectField(source, 'curve')}
        mutateCurve={(fn) => mutateSource((target) => {
          const curve = target.curve;
          if (!isJsonObject(curve)) {
            target.curve = defaultCurveFields('sine');
          }
          fn(objectField(target, 'curve'));
        })}
      />
      <label className="field grow">
        变换（可选，V 为曲线输出，如 roundTo(V,0.1)）
        <TextField
          allowBlank
          value={stringField(source, 'transform')}
          onCommit={(next) => mutateSource((target) => {
            if (next.trim() === '') {
              delete target.transform;
            } else {
              target.transform = next;
            }
          })}
        />
      </label>
    </div>
  );
}

/** 表达式：私有曲线 vars + 表达式正文。 */
function ExprSourceEditor({ source, mutateSource }: {
  source: JsonObject;
  mutateSource: ObjectEdit;
}): JSX.Element {
  const vars = objectField(source, 'vars');
  const names = Object.keys(vars);
  const clock = clockOf(source);

  const addVar = () => {
    mutateSource((target) => {
      const current = isJsonObject(target.vars) ? target.vars : {};
      let index = 1;
      let name = 'a';
      while (current[name] !== undefined) {
        index += 1;
        name = `a${index}`;
      }
      current[name] = defaultCurveFields('sine');
      target.vars = current;
    });
  };

  return (
    <div className="svid-source-detail">
      <div className="form-row">
        <label className="field">
          时钟
          <select
            value={clock}
            onChange={(event) => mutateSource((target) => {
              target.clock = event.target.value;
            })}
          >
            {SOURCE_CLOCKS.map((item) => (
              <option key={item.value} value={item.value}>{item.label}</option>
            ))}
          </select>
        </label>
        <label className="field grow">
          表达式（变量：t 秒、私有曲线名）
          <TextField
            value={stringField(source, 'expr')}
            onCommit={(next) => mutateSource((target) => {
              target.expr = next;
            })}
          />
        </label>
      </div>

      <div className="template-subtable">
        <div className="template-subhead">
          <span>私有曲线（只在这个数据项里可见，不产生别的变量）</span>
          <span className="toolbar">
            <button type="button" onClick={addVar}>+ 曲线</button>
          </span>
        </div>
        {names.length === 0 ? (
          <p className="hint">还没有私有曲线，表达式只能用到 t。</p>
        ) : (
          names.map((name) => (
            <div className="template-card" key={name}>
              <div className="template-card-head">
                <label className="field field-cell">
                  <span className="field-cell-name">名字</span>
                  <TextField
                    value={name}
                    onCommit={(next) => {
                      const trimmed = next.trim();
                      if (trimmed === name) {
                        return;
                      }
                      if (!isExpressionName(trimmed)) {
                        return;
                      }
                      mutateSource((target) => {
                        const current = isJsonObject(target.vars) ? target.vars : {};
                        const moved = current[name];
                        delete current[name];
                        current[trimmed] = moved;
                        target.vars = current;
                      });
                    }}
                  />
                </label>
                <button
                  type="button"
                  onClick={() => mutateSource((target) => {
                    const current = isJsonObject(target.vars) ? { ...target.vars } : {};
                    delete current[name];
                    target.vars = current;
                  })}
                >
                  删除
                </button>
              </div>
              <CurveFields
                curve={objectField(vars, name)}
                mutateCurve={(fn) => mutateSource((target) => {
                  const current = isJsonObject(target.vars) ? target.vars : {};
                  if (!isJsonObject(current[name])) {
                    current[name] = defaultCurveFields('sine');
                  }
                  fn(current[name] as JsonObject);
                  target.vars = current;
                })}
              />
            </div>
          ))
        )}
      </div>
    </div>
  );
}

/** 按片变化：数值档位或文本模板/轮换。 */
function CycleSourceEditor({ source, mutateSource, text }: {
  source: JsonObject;
  mutateSource: ObjectEdit;
  text: boolean;
}): JSX.Element {
  const [listText, setListText] = useState(() => (Array.isArray(source.list) ? (source.list as unknown[]).join(', ') : ''));
  const [bad, setBad] = useState(false);
  const writing = source.pattern !== undefined ? 'pattern' : Array.isArray(source.list) ? 'list' : 'value';

  const commitList = () => {
    const items = listText.split(',').map((item) => item.trim()).filter((item) => item !== '');
    mutateSource((target) => {
      target.list = items;
      delete target.pattern;
      delete target.value;
    });
    setBad(items.length === 0);
  };

  return (
    <div className="form-row svid-source-detail">
      {text ? (
        <label className="field">
          写法
          <select
            value={writing}
            onChange={(event) => mutateSource((target) => {
              delete target.pattern;
              delete target.value;
              delete target.list;
              if (event.target.value === 'list') {
                target.list = ['A', 'B'];
              } else if (event.target.value === 'pattern') {
                target.pattern = 'W{03}';
              } else {
                target.value = 'VALUE';
              }
            })}
          >
            <option value="value">常量</option>
            <option value="pattern">模板（{'{03}'} 补零片序）</option>
            <option value="list">列表轮换</option>
          </select>
        </label>
      ) : null}

      {text && writing === 'value' && (
        <label className="field">
          值
          <TextField
            value={stringField(source, 'value')}
            onCommit={(next) => mutateSource((target) => {
              target.value = next;
            })}
          />
        </label>
      )}
      {text && writing === 'pattern' && (
        <label className="field">
          模板
          <TextField
            value={stringField(source, 'pattern')}
            onCommit={(next) => mutateSource((target) => {
              target.pattern = next;
            })}
          />
        </label>
      )}
      {text && writing === 'list' && (
        <label className="field grow">
          列表（逗号分隔，按 every 轮换）
          <TextField
            value={listText}
            onCommit={(next) => {
              setListText(next);
              const items = next.split(',').map((item) => item.trim()).filter((item) => item !== '');
              setBad(items.length === 0);
              if (items.length > 0) {
                mutateSource((target) => {
                  target.list = items;
                  delete target.pattern;
                  delete target.value;
                });
              }
            }}
          />
          {bad && <span className="problems">列表不能为空</span>}
        </label>
      )}

      {!text && (
        <>
          <label className="field">
            起始值
            <NumberField
              value={numberField(source, 'start')}
              integer
              onCommit={(next) => mutateSource((target) => {
                target.start = next;
              })}
            />
          </label>
          <label className="field">
            步长
            <NumberField
              value={numberField(source, 'step')}
              onCommit={(next) => mutateSource((target) => {
                target.step = next;
              })}
            />
          </label>
        </>
      )}

      {text && (
        <label className="field">
          片序起点
          <NumberField
            value={numberField(source, 'start')}
            integer
            onCommit={(next) => mutateSource((target) => {
              target.start = next;
            })}
          />
        </label>
      )}

      <label className="field">
        每几片进一档
        <NumberField
          value={numberField(source, 'every')}
          integer
          onCommit={(next) => mutateSource((target) => {
            target.every = Math.max(1, next);
          })}
        />
      </label>
    </div>
  );
}
