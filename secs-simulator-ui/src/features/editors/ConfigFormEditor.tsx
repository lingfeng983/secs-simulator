import { useMemo, useState, type ReactNode } from 'react';
import type { ParamTemplate } from '../../api/paramTemplates';
import { SourceEditor } from './SourceEditor';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import { FieldLabel, HintTitle } from '../../shared/ui/FieldLabel';
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
import { ConfigItemModal } from './ConfigItemModal';
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
        <h3>
          <HintTitle text="表单修改的是原文解析后的结构；保存前仍要「校验」。未知键会保留，但整份文件的缩进会统一成 2 空格。每个变量的值定义就在它自己的卡片里。">
            配置（结构化编辑）
          </HintTitle>
        </h3>
      </div>
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

/** 一行"标题 + 控件"：标题列对齐；说明默认不展示，悬浮/聚焦在标题上时出气泡。 */
export function SessionField({ label, help, required = false, children }: {
  label: string;
  help: string;
  /** 必填项：标题后加红色星号（比"点了确定才弹一条横幅"更好用）。 */
  required?: boolean;
  children: ReactNode;
}): JSX.Element {
  return (
    <label className="field modal-field">
      <FieldLabel text={label} help={help} required={required} />
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
  const [editing, setEditing] = useState<{ index: number | null; draft: JsonObject } | undefined>(undefined);
  const events = isJsonObject(root.events) ? root.events : null;
  const ceidChoices = useMemo(() => collectCeids(root), [root]);

  if (events === null) {
    return (
      <div className="cfg-section">
        <SectionHead title="事件计划列表">
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
  const ceidSet = new Set(ceidChoices.map((choice) => choice.svid));

  const summaryOf = (plan: JsonObject): string => {
    const trigger = objectField(plan, 'trigger');
    const rawType = trigger.type;
    const type = TRIGGER_TYPES.find((item) => item === rawType);
    if (type === undefined) {
      return `未知触发方式：${String(rawType ?? '(缺失)')}`;
    }
    switch (type) {
      case 'at':
        return `启动后 ${numberField(trigger, 'atMs') ?? 0} ms 触发一次`;
      case 'periodic':
        return `每 ${numberField(trigger, 'periodMs') ?? 0} ms ±${numberField(trigger, 'jitterMs') ?? 0}`;
      case 'onCondition':
        return `${stringField(trigger, 'expression')}（防抖 ${numberField(trigger, 'debounceMs') ?? 0} ms）`;
      case 'afterEvent':
        return `${stringField(trigger, 'sourcePlan')} 后延迟 ${numberField(trigger, 'delayMs') ?? 0} ms`;
    }
  };

  const labelOf = (ceid: number): string => {
    const choice = ceidChoices.find((item) => item.svid === ceid);
    return choice === undefined ? String(ceid) : `${choice.owner}.${choice.slot}`;
  };

  /**
   * 计划行的字段编辑（弹框里用）：id / CEID / 触发方式 + 各类型参数。
   *
   * <p>一行一个配置项、标题列右对齐——与「新建事件」「新建设备常量」等
   * 弹框同一条纪律（{@link SessionField}），不再把五六个字段挤成一行。
   * 依赖关系强的两项（CEID、源计划）改成下拉，省得手敲编号敲错。</p>
   */
  const planFields = (item: JsonObject, mutate: (fn: (target: JsonObject) => void) => void) => {
    const trigger = objectField(item, 'trigger');
    const rawType = trigger.type;
    const type = TRIGGER_TYPES.find((entry) => entry === rawType);
    const mutateTrigger = (fn: (target: JsonObject) => void) => {
      mutate((target) => {
        fn(objectField(target, 'trigger'));
      });
    };
    const currentCeid = numberField(item, 'ceid');
    const currentSource = stringField(trigger, 'sourcePlan');
    const siblingPlans = (Array.isArray(plans) ? plans : [])
      .map((plan) => stringField(plan, 'id'))
      .filter((id) => id !== '' && id !== stringField(item, 'id'));

    return (
      <div className="session-fields">
        <SessionField label="计划 id" required help="唯一标识：运行区的启停/试跑台账按它归属；「事件后」触发用它当源计划。">
          <TextField
            value={stringField(item, 'id')}
            onCommit={(next) => mutate((target) => {
              target.id = next;
            })}
          />
        </SessionField>

        <SessionField label="CEID" help="这条计划触发时上报的事件；只能选设备模型里已定义的事件（在「事件与报告」页定义）。">
          <select
            value={currentCeid === undefined ? '' : String(currentCeid)}
            onChange={(event) => {
              const next = Number(event.target.value);
              if (Number.isFinite(next) && next > 0) {
                mutate((target) => {
                  target.ceid = next;
                });
              }
            }}
          >
            {(currentCeid === undefined || !ceidSet.has(currentCeid)) && (
              <option value="">{currentCeid === undefined ? '请选择事件' : `未知 CEID ${currentCeid}`}</option>
            )}
            {ceidChoices.map((choice) => (
              <option key={choice.svid} value={choice.svid}>
                {choice.svid} · {choice.owner}.{choice.slot}
              </option>
            ))}
          </select>
        </SessionField>

        <SessionField label="触发方式" help="定时一次 / 周期 / 条件 / 事件后——切换后下面的参数会换成对应那一组。">
          <select
            value={type ?? ''}
            onChange={(event) => {
              const next = TRIGGER_TYPES.find((entry) => entry === event.target.value);
              if (next !== undefined) {
                mutate((target) => {
                  target.trigger = defaultTrigger(next);
                });
              }
            }}
          >
            {type === undefined && <option value="">未知：{String(rawType ?? '')}</option>}
            {TRIGGER_TYPES.map((entry) => (
              <option key={entry} value={entry}>
                {TRIGGER_LABELS[entry]}
              </option>
            ))}
          </select>
        </SessionField>

        {type === 'at' && (
          <SessionField label="启动后 ms" help="计划启动后的第几毫秒触发一次；不是绝对时间。">
            <NumberField
              value={numberField(trigger, 'atMs')}
              integer
              onCommit={(next) => mutateTrigger((target) => {
                target.atMs = next;
              })}
            />
          </SessionField>
        )}

        {type === 'periodic' && (
          <>
            <SessionField label="周期 ms" help="每隔多少毫秒触发一次。">
              <NumberField
                value={numberField(trigger, 'periodMs')}
                integer
                onCommit={(next) => mutateTrigger((target) => {
                  target.periodMs = next;
                })}
              />
            </SessionField>
            <SessionField label="抖动 ms" help="每次触发的随机偏移上限（0 = 严格等间隔）：让曲线不像节拍器。">
              <NumberField
                value={numberField(trigger, 'jitterMs')}
                integer
                onCommit={(next) => mutateTrigger((target) => {
                  target.jitterMs = next;
                })}
              />
            </SessionField>
          </>
        )}

        {type === 'onCondition' && (
          <>
            <SessionField label="条件表达式" help="可用 t（模拟时间，秒）与 svid.<id>；表达式为真的那一刻触发。">
              <TextField
                value={stringField(trigger, 'expression')}
                onCommit={(next) => mutateTrigger((target) => {
                  target.expression = next;
                })}
              />
            </SessionField>
            <SessionField label="防抖 ms" help="条件需要连续成立多久才真正触发，用来滤掉抖动。">
              <NumberField
                value={numberField(trigger, 'debounceMs')}
                integer
                onCommit={(next) => mutateTrigger((target) => {
                  target.debounceMs = next;
                })}
              />
            </SessionField>
          </>
        )}

        {type === 'afterEvent' && (
          <>
            <SessionField label="源计划" help="跟在哪个计划后面：那个计划每发一次，本计划就排一次延迟触发。">
              <select
                value={currentSource}
                onChange={(event) => {
                  const next = event.target.value;
                  mutateTrigger((target) => {
                    target.sourcePlan = next;
                  });
                }}
              >
                <option value="">（未指定）</option>
                {siblingPlans.map((id) => <option key={id} value={id}>{id}</option>)}
                {/* 源计划可能已被删掉：原值保留在选项里，避免静默被改成空串 */}
                {currentSource !== '' && !siblingPlans.includes(currentSource) && (
                  <option value={currentSource}>{currentSource}（不在列表里）</option>
                )}
              </select>
            </SessionField>
            <SessionField label="延迟 ms" help="源计划发完之后再等多少毫秒触发。">
              <NumberField
                value={numberField(trigger, 'delayMs')}
                integer
                onCommit={(next) => mutateTrigger((target) => {
                  target.delayMs = next;
                })}
              />
            </SessionField>
          </>
        )}
      </div>
    );
  };

  return (
    <div className="cfg-section">
      <SectionHead title="事件计划列表">
        <button
          type="button"
          onClick={() => setEditing({ index: null, draft: { id: '', ceid: ceidChoices[0]?.svid ?? 0, trigger: defaultTrigger('periodic') } })}
        >
          + 事件计划
        </button>
      </SectionHead>

      {/* 这三项是 events 段的发送口径，不是某一条计划：放在列表上方一行，
          与计划逐条配置区分开（列表才是本页主体）。 */}
      <div className="form-row">
        <span className="template-label">事件发送</span>
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
        <p className="hint">还没有事件计划：点右上角「+ 事件计划」新增一条，新增/修改都只写进本页草稿。</p>
      ) : (
        <table className="effects-table" aria-label="事件计划列表">
          <thead>
            <tr>
              <th>计划 id</th>
              <th>CEID</th>
              <th>触发方式</th>
              <th>参数</th>
              <th>跟随</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {plans.map((plan, index) => {
              const planId = stringField(plan, 'id');
              const ceid = numberField(plan, 'ceid') ?? 0;
              const followers = plans.filter(
                (item) => stringField(objectField(item, 'trigger'), 'sourcePlan') === planId,
              ).length;
              return (
                <tr key={planId === '' ? `plan-${index}` : planId}>
                  <td className="mono">{planId}</td>
                  <td className="mono">
                    {ceid}
                    <span className="hint-inline"> {labelOf(ceid)}</span>
                  </td>
                  <td>
                    {(() => {
                      const rawType = objectField(plan, 'trigger').type;
                      const type = TRIGGER_TYPES.find((item) => item === rawType);
                      return type === undefined ? String(rawType ?? '(缺失)') : TRIGGER_LABELS[type];
                    })()}
                  </td>
                  <td>{summaryOf(plan)}</td>
                  <td>{followers > 0 ? `${followers} 个跟随` : '—'}</td>
                  <td>
                    <div className="row-actions">
                      <button
                        type="button"
                        onClick={() => setEditing({ index, draft: structuredClone(plan) })}
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        onClick={() => edit((draft) => {
                          const target = objectField(draft, 'events').plans;
                          if (Array.isArray(target)) {
                            (target as unknown[]).splice(index, 1);
                          }
                        })}
                      >
                        删除
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {editing !== undefined && (
        <ConfigItemModal
          title={editing.index === null ? '新建事件计划' : `编辑事件计划 · ${stringField(editing.draft, 'id')}`}
          initial={editing.draft}
          /* 必填/非法只落在「确定」的禁用与悬浮说明上：标题里的星号已经提示过，
             不必在弹框里常驻一条红横幅（用户刚打开就看到"不能为空"） */
          problemBanner={false}
          problemOf={(item) => {
            if (stringField(item, 'id').trim() === '') {
              return '计划 id 不能为空';
            }
            const ceid = numberField(item, 'ceid');
            if (ceid === undefined || !ceidSet.has(ceid)) {
              return `CEID ${ceid ?? ''} 在设备模型里不存在（先在「事件与报告」页定义）`;
            }
            return '';
          }}
          onClose={() => setEditing(undefined)}
          onSubmit={(item) => {
            edit((draft) => {
              const target = objectField(draft, 'events').plans;
              if (!Array.isArray(target)) {
                return;
              }
              if (editing.index === null) {
                (target as JsonObject[]).push(item);
              } else if (isJsonObject(target[editing.index])) {
                target[editing.index] = item;
              }
            });
            setEditing(undefined);
          }}
        >
          {(item, mutate) => planFields(item, mutate)}
        </ConfigItemModal>
      )}
    </div>
  );
}
