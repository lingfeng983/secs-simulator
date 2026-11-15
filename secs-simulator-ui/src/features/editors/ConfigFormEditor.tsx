import { useMemo, useState, type ReactNode } from 'react';
import type { ParamTemplate } from '../../api/paramTemplates';
import { SourceEditor } from './SourceEditor';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import { Collapsible } from '../../shared/ui/Collapsible';
import {
  booleanField,
  collectCeids,
  editDocument,
  isJsonObject,
  numberField,
  objectField,
  parseConfigDocument,
  readObjectArray,
  SECS_TYPES,
  stringField,
  TRIGGER_TYPES,
  uniqueId,
  type JsonObject,
  type TriggerType,
} from './templateConfig';
import { SectionHead, type Edit } from './ConfigFormParts';
import { EquipmentForm } from './EquipmentFormSections';

export { FormGridHead, FormRow, nextId, SectionHead } from './ConfigFormParts';
export { EquipmentForm, GlobalCeidsForm, stringListOf, unitsOf } from './EquipmentFormSections';

interface ConfigFormEditorProps {
  /** 配置原文；结构化编辑永远从这份文本出发。 */
  text: string;
  /** 写回新的原文（只改草稿，不落盘）。 */
  onChange: (next: string) => void;
  /** 预览某个 SVID 的曲线(v6:按当前草稿+事件标记画单变量)。 */
  onPreviewSvid?: (svid: number) => void;
}

/**
 * session / equipment / events 段的结构化编辑。
 *
 * <p>原文才是唯一事实来源，表单只是把解析后的结构投影出来，改完写回整份文档。
 * 未知键保留，缩进统一 2 空格；保存前仍要过「校验」（同一条启动路径）。
 * 值定义（source）按数据项挂在各自卡片上，没有独立的模板段。</p>
 */
export function ConfigFormEditor({ text, onChange, onPreviewSvid }: ConfigFormEditorProps): JSX.Element {
  const [error, setError] = useState('');
  const parsed = useMemo(() => parseConfigDocument(text), [text]);

  const edit: Edit = (mutate) => {
    const result = editDocument(text, mutate);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    setError('');
    onChange(result.text);
  };

  if (!parsed.ok) {
    return (
      <div className="cfg-editor">
        <h3>配置（结构化编辑）</h3>
        <p className="hint">
          原文还不是合法 JSON：<code>{parsed.error}</code>
          。切到「JSON 原文」改到能解析，表单才能动手。
        </p>
      </div>
    );
  }

  const root = parsed.config.root;

  return (
    <div className="cfg-editor">
      <div className="template-head">
        <h3>配置（结构化编辑）</h3>
      </div>
      <p className="hint">
        表单修改的是原文解析后的结构；保存前仍要「校验」。未知键会保留，
        但整份文件的缩进会统一成 2 空格。每个变量的值定义就在它自己的卡片里。
      </p>
      {error !== '' && <p className="problems">{error}</p>}

      <SessionForm root={root} edit={edit} />
      <EquipmentForm root={root} edit={edit} onPreviewSvid={onPreviewSvid} />
      <EventsForm root={root} edit={edit} />
    </div>
  );
}

/* ---------- 会话 ---------- */

export function SessionForm({ root, edit }: { root: JsonObject; edit: Edit }): JSX.Element {
  const session = isJsonObject(root.session) ? root.session : null;

  if (session === null) {
    return (
      <div className="cfg-section">
        <p className="hint">这份配置还没有 session 段（监听端口、设备号等）。</p>
        <button
          type="button"
          onClick={() => edit((draft) => {
            draft.session = { id: 'dev-01', passive: true, port: 5010, bindHost: '127.0.0.1' };
          })}
        >
          插入 session 段
        </button>
      </div>
    );
  }

  const set = (key: string, value: unknown) => {
    edit((draft) => {
      objectField(draft, 'session')[key] = value;
    });
  };

  return (
    <div className="cfg-section session-form">
      <div className="session-fields">
        <SessionField label="会话 id" help="唯一标识，用于 URL、日志、主机选择；改动会重建连接。">
          <TextField value={stringField(session, 'id')} onCommit={(next) => set('id', next)} />
        </SessionField>
        <SessionField label="HSMS 端口" help="必填（1–65535）：被动监听端口，必须与主机连接的目标端口一致，且不能与其他会话冲突。">
          <NumberField
            value={numberField(session, 'port')}
            integer
            onCommit={(next) => {
              /* 端口必填且不能为 0：非法值不写入，失焦恢复原值 */
              if (next >= 1 && next <= 65535) {
                set('port', next);
              }
            }}
          />
        </SessionField>
        <SessionField label="绑定地址" help="默认 127.0.0.1（只允许本机连接）；填 0.0.0.0 监听所有网卡。">
          <TextField
            allowBlank
            value={stringField(session, 'bindHost', '127.0.0.1')}
            onCommit={(next) => set('bindHost', next.trim() === '' ? '127.0.0.1' : next)}
          />
        </SessionField>
        <SessionField label="deviceId" help="HSMS Device ID（0–32767），主机按它区分设备，两侧必须一致。">
          <NumberField value={numberField(session, 'deviceId')} integer onCommit={(next) => set('deviceId', next)} />
        </SessionField>
        <SessionField label="随机种子" help="决定曲线与事件抖动的随机序列；固定种子便于复现同一条曲线。">
          <NumberField value={numberField(session, 'seed')} integer onCommit={(next) => set('seed', next)} />
        </SessionField>
        <SessionField label="被动监听" help="勾选 = 设备开端口等主机连；取消 = 设备主动连接主机。">
          <label className="check-inline">
            <input
              type="checkbox"
              checked={booleanField(session, 'passive', true)}
              onChange={(event) => set('passive', event.target.checked)}
            />
            {booleanField(session, 'passive', true) ? '设备监听' : '设备主动连接'}
          </label>
        </SessionField>
      </div>

      <h3 className="session-subhead">HSMS 超时（秒，留空用协议默认）</h3>
      <TimeoutFields session={session} set={set} />

      <h3 className="session-subhead">Trace 容量（留空用默认：同时 1 条 / 每条 ≤10 SVID）</h3>
      <TraceFields session={session} set={set} />
    </div>
  );
}

/** 一行"属性 + 说明 + 控件"：说明保证在左侧，控件在右侧。 */
export function SessionField({ label, help, children }: { label: string; help: string; children: ReactNode }): JSX.Element {
  return (
    <label className="field session-field">
      <span className="session-field-text">
        <span className="session-field-name">{label}</span>
        <span className="session-field-help">{help}</span>
      </span>
      {children}
    </label>
  );
}

/** HSMS 超时（秒，可选）：写进 session.timeouts；清空删除键，用协议层默认。 */
function TimeoutFields({ session, set }: { session: JsonObject; set: (key: string, value: unknown) => void }): JSX.Element {
  const timeouts = isJsonObject(session.timeouts) ? session.timeouts : {};
  const fields: ReadonlyArray<{ key: string; label: string; help: string }> = [
    { key: 't3', label: 'T3 回复', help: '发出 W 位报文后等回复的上限；超时记为失败。' },
    { key: 't5', label: 'T5 重连', help: '连接断开后再次尝试建连的间隔。' },
    { key: 't6', label: 'T6 多段交互', help: '一次多段交互（Sx+Fy 带多个数据块）从第一块到收尾的整体上限。' },
    { key: 't7', label: 'T7 选择', help: '连接建立后等待 Select/控制状态切换的超时。' },
    { key: 't8', label: 'T8 字符间', help: '单条报文内相邻字节允许的最大间隔。' },
  ];

  const commit = (key: string, value: number | undefined) => {
    editTimeout(set, timeouts, key, value);
  };

  return (
    <div className="session-fields">
      {fields.map((field) => (
        <SessionField key={field.key} label={field.label} help={field.help}>
          <NumberField
            value={numberField(timeouts, field.key)}
            onCommit={(next) => commit(field.key, next)}
          />
        </SessionField>
      ))}
    </div>
  );
}

function editTimeout(
  set: (key: string, value: unknown) => void,
  timeouts: JsonObject,
  key: string,
  value: number | undefined,
): void {
  if (value === undefined) {
    const remaining = { ...timeouts };
    delete remaining[key];
    set('timeouts', Object.keys(remaining).length === 0 ? undefined : remaining);
    return;
  }
  set('timeouts', { ...timeouts, [key]: value });
}

/** Trace 容量限制（可选）：写进 session.trace；清空删除键，用内置默认。 */
function TraceFields({ session, set }: { session: JsonObject; set: (key: string, value: unknown) => void }): JSX.Element {
  const trace = isJsonObject(session.trace) ? session.trace : {};
  const fields: ReadonlyArray<{ key: string; label: string; help: string }> = [
    { key: 'maxTraces', label: '同时 Trace 条数', help: '同一时刻允许多少条 S2F23 周期采样；真机通常是 1。' },
    { key: 'maxSvidCount', label: '单条 SVID 上限', help: '一条 Trace 最多携带多少个状态变量；真机通常是 10。' },
  ];

  const commit = (key: string, value: number | undefined) => {
    editTraceLimit(set, trace, key, value);
  };

  return (
    <div className="session-fields">
      {fields.map((field) => (
        <SessionField key={field.key} label={field.label} help={field.help}>
          <NumberField
            value={numberField(trace, field.key)}
            integer
            onCommit={(next) => commit(field.key, next)}
          />
        </SessionField>
      ))}
    </div>
  );
}

function editTraceLimit(
  set: (key: string, value: unknown) => void,
  trace: JsonObject,
  key: string,
  value: number | undefined,
): void {
  if (value === undefined) {
    const remaining = { ...trace };
    delete remaining[key];
    set('trace', Object.keys(remaining).length === 0 ? undefined : remaining);
    return;
  }
  set('trace', { ...trace, [key]: value });
}

/* ---------- 事件编排 ---------- */

function defaultTrigger(type: TriggerType): JsonObject {
  switch (type) {
    case 'at':
      return { type, atMs: 1000 };
    case 'periodic':
      return { type, periodMs: 5000, jitterMs: 0 };
    case 'onCondition':
      return { type, expression: 'svid.1001 > 0', debounceMs: 0 };
    case 'afterEvent':
      return { type, sourcePlan: '', delayMs: 0 };
  }
}

const TRIGGER_LABELS: Record<TriggerType, string> = {
  at: '定时一次（at）',
  periodic: '周期（periodic）',
  onCondition: '条件（onCondition）',
  afterEvent: '事件后（afterEvent）',
};

export function EventsForm({ root, edit }: { root: JsonObject; edit: Edit }): JSX.Element {
  const events = isJsonObject(root.events) ? root.events : null;
  const ceidChoices = useMemo(() => collectCeids(root), [root]);

  if (events === null) {
    return (
      <div className="cfg-section">
        <SectionHead title="事件编排">
          <button
            type="button"
            onClick={() => edit((draft) => {
              draft.events = { maxPerSec: 20, plans: [] };
            })}
          >
            插入 events 段
          </button>
        </SectionHead>
        <p className="hint">这份配置还没有 events 段（S6F11 的 W 位、事件计划）。</p>
      </div>
    );
  }

  const plans = readObjectArray(events, 'plans', 'events');

  return (
    <div className="cfg-section">
      <Collapsible title={`事件编排（events · ${typeof plans === 'string' ? '结构错误' : plans.length + ' 个计划'}）`} badge="热生效">
      <div className="form-row">
        <label className="field field-cell">
          <span className="field-cell-name">每秒事件上限</span>
          <NumberField
            value={numberField(events, 'maxPerSec')}
            integer
            onCommit={(next) => edit((draft) => {
              objectField(draft, 'events').maxPerSec = next;
            })}
          />
        </label>
        <label className="check-inline">
          <input
            type="checkbox"
            checked={booleanField(events, 's6f11Wbit')}
            onChange={(event) => edit((draft) => {
              objectField(draft, 'events').s6f11Wbit = event.target.checked;
            })}
          />
          S6F11 带 W 位
        </label>
        <label className="check-inline">
          <input
            type="checkbox"
            checked={booleanField(events, 's6f1Wbit')}
            onChange={(event) => edit((draft) => {
              objectField(draft, 'events').s6f1Wbit = event.target.checked;
            })}
          />
          S6F1 带 W 位
        </label>
        {typeof plans !== 'string' && (
          <button
            type="button"
            onClick={() => edit((draft) => {
              const target = objectField(draft, 'events');
              const items = Array.isArray(target.plans) ? (target.plans as JsonObject[]) : [];
              items.push({
                id: uniqueId(items.map((item) => stringField(item, 'id')), 'plan'),
                ceid: ceidChoices[0]?.svid ?? 0,
                trigger: defaultTrigger('periodic'),
              });
              target.plans = items;
            })}
          >
            + 事件计划
          </button>
        )}
      </div>

      <datalist id="cfg-ceid-options">
        {ceidChoices.map((choice) => (
          <option key={choice.svid} value={choice.svid}>
            {choice.owner}.{choice.slot}
          </option>
        ))}
      </datalist>

      {typeof plans === 'string' ? (
        <p className="problems">events.plans {plans}</p>
      ) : plans.length === 0 ? (
        <p className="hint">还没有事件计划。</p>
      ) : (
        plans.map((plan, index) => {
          const trigger = objectField(plan, 'trigger');
          const rawType = trigger.type;
          const type = TRIGGER_TYPES.find((item) => item === rawType) ?? null;
          const planId = stringField(plan, 'id');
          const followers =
            typeof plans === 'string'
              ? 0
              : plans.filter((item) => stringField(objectField(item, 'trigger'), 'sourcePlan') === planId).length;

          const mutatePlan = (mutate: (target: JsonObject) => void) => {
            edit((draft) => {
              const target = objectField(draft, 'events').plans;
              if (Array.isArray(target) && isJsonObject(target[index])) {
                mutate(target[index]);
              }
            });
          };

          const mutateTrigger = (mutate: (trigger: JsonObject) => void) => {
            mutatePlan((target) => {
              mutate(objectField(target, 'trigger'));
            });
          };

          return (
            /* 计划有稳定 id 就用它：删除中间一条时后面的卡片不会跟着重挂；
               刚新建还没填 id 时退回位置 key。 */
            <div className="template-card" key={planId === '' ? `plan-${index}` : planId}>
              <div className="template-card-head">
                <label className="field field-cell">
                  <span className="field-cell-name">计划 id</span>
                  <TextField
                    value={stringField(plan, 'id')}
                    onCommit={(next) => mutatePlan((target) => {
                      target.id = next;
                    })}
                  />
                </label>
                <label className="field field-cell">
                  <span className="field-cell-name">CEID</span>
                  <NumberField
                    value={numberField(plan, 'ceid')}
                    integer
                    list="cfg-ceid-options"
                    onCommit={(next) => mutatePlan((target) => {
                      target.ceid = next;
                    })}
                  />
                </label>
                <label className="field field-cell">
                  <span className="field-cell-name">触发方式</span>
                  <select
                    value={type ?? ''}
                    onChange={(event) => {
                      const next = TRIGGER_TYPES.find((item) => item === event.target.value);
                      if (next !== undefined) {
                        mutatePlan((target) => {
                          target.trigger = defaultTrigger(next);
                        });
                      }
                    }}
                  >
                    {type === null && <option value="">未知：{String(rawType ?? '')}</option>}
                    {TRIGGER_TYPES.map((item) => (
                      <option key={item} value={item}>
                        {TRIGGER_LABELS[item]}
                      </option>
                    ))}
                  </select>
                </label>
                <span className="template-badge">{followers > 0 ? `${followers} 个计划跟随` : ''}</span>
                <button
                  type="button"
                  onClick={() => edit((draft) => {
                    const target = objectField(draft, 'events').plans;
                    if (Array.isArray(target)) {
                      (target as unknown[]).splice(index, 1);
                    }
                  })}
                >
                  删除计划
                </button>
              </div>

              {type === null ? (
                <p className="problems">未知触发类型：{String(rawType ?? '(缺失)')}</p>
              ) : (
                <div className="form-row">
                  {type === 'at' && (
                    <label className="field field-cell">
                      <span className="field-cell-name">atMs（启动后毫秒）</span>
                      <NumberField
                        value={numberField(trigger, 'atMs')}
                        integer
                        onCommit={(next) => mutateTrigger((target) => {
                          target.atMs = next;
                        })}
                      />
                    </label>
                  )}
                  {type === 'periodic' && (
                    <>
                      <label className="field field-cell">
                        <span className="field-cell-name">周期 ms</span>
                        <NumberField
                          value={numberField(trigger, 'periodMs')}
                          integer
                          onCommit={(next) => mutateTrigger((target) => {
                            target.periodMs = next;
                          })}
                        />
                      </label>
                      <label className="field field-cell">
                        <span className="field-cell-name">抖动 ms</span>
                        <NumberField
                          value={numberField(trigger, 'jitterMs')}
                          integer
                          onCommit={(next) => mutateTrigger((target) => {
                            target.jitterMs = next;
                          })}
                        />
                      </label>
                    </>
                  )}
                  {type === 'onCondition' && (
                    <>
                      <label className="field field-cell">
                        <span className="field-cell-name">条件表达式（t、svid.&lt;id&gt;）</span>
                        <TextField
                          wide
                          value={stringField(trigger, 'expression')}
                          onCommit={(next) => mutateTrigger((target) => {
                            target.expression = next;
                          })}
                        />
                      </label>
                      <label className="field field-cell">
                        <span className="field-cell-name">防抖 ms</span>
                        <NumberField
                          value={numberField(trigger, 'debounceMs')}
                          integer
                          onCommit={(next) => mutateTrigger((target) => {
                            target.debounceMs = next;
                          })}
                        />
                      </label>
                    </>
                  )}
                  {type === 'afterEvent' && (
                    <>
                      <label className="field field-cell">
                        <span className="field-cell-name">源计划 id</span>
                        <TextField
                          value={stringField(trigger, 'sourcePlan')}
                          onCommit={(next) => mutateTrigger((target) => {
                            target.sourcePlan = next;
                          })}
                        />
                      </label>
                      <label className="field field-cell">
                        <span className="field-cell-name">延迟 ms</span>
                        <NumberField
                          value={numberField(trigger, 'delayMs')}
                          integer
                          onCommit={(next) => mutateTrigger((target) => {
                            target.delayMs = next;
                          })}
                        />
                      </label>
                    </>
                  )}
                </div>
              )}

            </div>
          );
        })
      )}
      <p className="hint">
        切换触发方式会把该计划的 trigger 参数重置为该类型的默认值。事件只上报、不改任何值；按片重复的事件写在「工艺周期」里。
      </p>
      </Collapsible>
    </div>
  );
}