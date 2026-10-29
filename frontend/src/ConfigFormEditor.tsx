import { useMemo, useState, type ReactNode } from 'react';
import { SvidSourceEditor, EffectsEditor } from './SvidSourceEditors';
import { NumberField, TextField } from './ui/CommitFields';
import { Collapsible } from './ui/Collapsible';
import {
  booleanField,
  collectCeids,
  collectSvids,
  CURVE_TYPES,
  curveFieldSpecs,
  defaultCurveFields,
  defaultEffect,
  effectFieldSpecs,
  editDocument,
  isCurveType,
  isJsonObject,
  numberField,
  objectField,
  parseConfigDocument,
  readObjectArray,
  SECS_TYPES,
  stringField,
  svidSourceKind,
  TRIGGER_TYPES,
  uniqueId,
  type JsonObject,
  type SourceKind,
  type TriggerType,
} from './templateConfig';

interface ConfigFormEditorProps {
  /** 配置原文；结构化编辑永远从这份文本出发。 */
  text: string;
  /** 写回新的原文（只改草稿，不落盘）。 */
  onChange: (next: string) => void;
  /** 预览某个 SVID 的曲线(v6:按当前草稿+事件标记画单变量)。 */
  onPreviewSvid?: (svid: number) => void;
}

type Edit = (mutate: (root: JsonObject) => void) => void;

/**
 * session / equipment / events 段的结构化编辑。
 *
 * <p>与 {@link import('./TemplateEditor').TemplateEditor} 同一套纪律：
 * 原文才是唯一事实来源，表单只是把解析后的结构投影出来，改完写回整份文档。
 * 未知键保留，缩进统一 2 空格；保存前仍要过「校验」（同一条启动路径）。</p>
 *
 * <p>templates 段的表单在 {@link import('./TemplateEditor').TemplateEditor} 里，
 * 本组件只负责其余三段。</p>
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
        但整份文件的缩进会统一成 2 空格。模板 / 曲线的表单在下方「模板」区。
      </p>
      {error !== '' && <p className="problems">{error}</p>}

      <SessionForm root={root} edit={edit} />
      <EquipmentForm root={root} edit={edit} templateIds={templateIdsOf(root)} onPreviewSvid={onPreviewSvid} />
      <EventsForm root={root} edit={edit} />
    </div>
  );
}

/** 文档里全部模板 id(曲线 + 组合):SVID 直配引用的候选。 */
export function templateIdsOf(root: JsonObject): string[] {
  const templates = objectField(root, 'templates');
  const ids: string[] = [];
  const groups = [readObjectArray(templates, 'curves', 'templates'), readObjectArray(templates, 'composites', 'templates')];
  for (const group of groups) {
    if (typeof group === 'string') {
      continue;
    }
    for (const item of group) {
      if (typeof item.id === 'string') {
        ids.push(item.id);
      }
    }
  }
  return ids;
}

/* ---------- 通用小件 ---------- */

function SectionHead({ title, children }: { title: string; children?: ReactNode }): JSX.Element {
  return (
    <div className="template-head">
      <h4>{title}</h4>
      {children !== undefined && <div className="template-actions">{children}</div>}
    </div>
  );
}

/**
 * 子表行：一排字段 + 行尾删除按钮。
 *
 * <p>用 CSS grid 而不是 flex 换行——字段一多，flex 换行会让每行的
 * 字段各占一段，列对不齐、右侧还留一大片空白。grid 的列宽由整组行共享，
 * 每一列的输入框左边缘天然对齐。</p>
 */
function FormRow({ onRemove, children, cols }: {
  onRemove: () => void;
  children: ReactNode;
  /** 列数：决定栅格模板，与表头一致。 */
  cols: 3 | 5 | 7 | 8;
}): JSX.Element {
  return (
    <div className={`form-grid-row form-grid-row--${cols}`}>
      {children}
      <button type="button" className="form-grid-del" onClick={onRemove} title="删除这一行">
        删除
      </button>
    </div>
  );
}

/**
 * 子表表头：与 {@link FormRow} 的列一一对应。
 *
 * <p>列名写在表头而不是每个字段旁边，是为了让"这一列是什么"只说一遍，
 * 行内就只剩控件本身。</p>
 */
function FormGridHead({ columns }: { columns: string[] }): JSX.Element {
  return (
    <div className={`form-grid-row form-grid-head form-grid-row--${columns.length}`}>
      {columns.map((column) => (
        <span className="form-grid-label" key={column}>{column}</span>
      ))}
      <span className="form-grid-del" aria-hidden="true" />
    </div>
  );
}

function TypeSelect({ value, onCommit }: { value: string; onCommit: (next: string) => void }): JSX.Element {
  return (
    <label className="field field-cell">
      <span className="field-cell-name">类型</span>
      <select value={value} onChange={(event) => onCommit(event.target.value)}>
        {SECS_TYPES.map((type) => (
          <option key={type} value={type}>
            {type}
          </option>
        ))}
      </select>
    </label>
  );
}

/** 逗号分隔的字符串列表（报告引用、CEID 引用等）；全角逗号也认。 */
function StringListField({ values, onCommit }: { values: string[]; onCommit: (next: string[]) => void }): JSX.Element {
  return (
    <label className="field field-cell">
      <span className="field-cell-name">引用（逗号分隔）</span>
      <TextField
        allowBlank
        wide
        value={values.join(', ')}
        onCommit={(next) =>
          onCommit(
            next
              .split(/[,，]/)
              .map((item) => item.trim())
              .filter((item) => item !== ''),
          )
        }
      />
    </label>
  );
}

/** 数组里最大数值 id + 1；空数组用 fallback。 */
function nextId(items: readonly JsonObject[], key: string, fallback: number): number {
  let max = fallback - 1;
  for (const item of items) {
    const id = numberField(item, key);
    if (id !== undefined && id > max) {
      max = id;
    }
  }
  return max + 1;
}

/** 单元数组里下一个可用的 offset。 */
function nextOffset(items: readonly JsonObject[]): number {
  return nextId(items, 'offset', 1);
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
            draft.session = { id: 'dev-01', passive: true, port: 5000 };
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
        <SessionField label="HSMS 端口" help="被动监听端口，必须与主机连接的目标端口一致，且不能与其他会话冲突。">
          <NumberField value={numberField(session, 'port')} integer onCommit={(next) => set('port', next)} />
        </SessionField>
        <SessionField label="绑定地址" help="留空监听所有网卡；填 127.0.0.1 则只允许本机连接。">
          <TextField
            allowBlank
            value={stringField(session, 'bindHost')}
            onCommit={(next) => set('bindHost', next === '' ? undefined : next)}
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
    </div>
  );
}

/** 一行"属性 + 说明 + 控件"：说明保证在左侧，控件在右侧。 */
function SessionField({ label, help, children }: { label: string; help: string; children: ReactNode }): JSX.Element {
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
    { key: 't6', label: 'T6 事务', help: '控制事务（Sx+Fy 多段交互）的整体超时。' },
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

/* ---------- 设备 ---------- */

export function EquipmentForm({ root, edit, templateIds, onPreviewSvid, section = 'all' }: {
  root: JsonObject;
  edit: Edit;
  templateIds: string[];
  onPreviewSvid?: (svid: number) => void;
  /** global = 设备名/号段/全局定义；units = 单元列表；all = 两者。 */
  section?: 'all' | 'global' | 'units';
}): JSX.Element {
  const equipment = isJsonObject(root.equipment) ? root.equipment : null;

  if (equipment === null) {
    return (
      <div className="cfg-section">
        <SectionHead title="设备">
          <button
            type="button"
            onClick={() => edit((draft) => {
              draft.equipment = {
                name: 'DEV-01',
                idSpace: { svidBase: 1000, ceidBase: 2000, reportBase: 3000, svidStride: 100, ceidStride: 100, reportStride: 100 },
                units: [],
              };
            })}
          >
            插入 equipment 段
          </button>
        </SectionHead>
        <p className="hint">这份配置还没有 equipment 段（设备名、号段、单元）。</p>
      </div>
    );
  }

  const units = readObjectArray(equipment, 'units', 'equipment');
  const svidsOk = typeof units !== 'string';
  const idSpace = objectField(equipment, 'idSpace');
  const unitSvidBase = numberField(idSpace, 'svidBase') ?? 1000;
  const unitSvidStride = numberField(idSpace, 'svidStride') ?? 100;

  return (
    <div className="cfg-section">
      {section !== 'units' && (
        <>
          <div className="template-head">
            <h3>设备全局</h3>
          </div>
          <p className="hint">
            只放设备级内容：号段规则与设备常量（ECV）。SVID / CEID / 报告分别到
            「状态变量」「事件」「报告」页维护，单元页只保留单元标识。
          </p>

          <IdSpaceForm equipment={equipment} edit={edit} />

          <ConstantsForm equipment={equipment} edit={edit} />
        </>
      )}

      {section !== 'global' && (
        <>
          <div className="template-head">
            <h3>单元</h3>
            {typeof units !== 'string' && (
              <button
                type="button"
                onClick={() => edit((draft) => {
                  const target = objectField(draft, 'equipment');
                  const list = Array.isArray(target.units) ? (target.units as JsonObject[]) : [];
                  const index = list.length + 1;
                  list.push({
                    unitId: nextId(list, 'unitId', 1),
                    name: `CH${index}`,
                    injectUnitId: index === 1,
                    svids: [],
                    reports: [],
                    ceids: [],
                  });
                  target.units = list;
                })}
              >
                + 单元
              </button>
            )}
          </div>
          <p className="hint">
            单元只保留标识信息（unitId / 名称 / 是否自动注入 UNIT_ID）。
            SVID、报告、事件分别到「状态变量」「报告」「事件」页维护，避免同一份数据在三个地方填。
          </p>
          {typeof units === 'string' ? (
            <p className="problems">equipment.units {units}</p>
          ) : units.length === 0 ? (
            <p className="hint">还没有单元，点「+ 单元」添加第一台腔体/工位。</p>
          ) : (
            units.map((unit, index) => (
              <UnitCard
                key={index}
                unit={unit}
                index={index}
                edit={edit}
                templateIds={templateIds}
                onPreviewSvid={onPreviewSvid}
                unitSvidBase={unitSvidBase}
                unitSvidStride={unitSvidStride}
              />
            ))
          )}
        </>
      )}
    </div>
  );
}

function IdSpaceForm({ equipment, edit }: { equipment: JsonObject; edit: Edit }): JSX.Element {
  const idSpace = objectField(equipment, 'idSpace');
  const reserve = Array.isArray(idSpace.reserve) ? (idSpace.reserve as unknown[]) : [];

  const setNumber = (key: string, value: number) => {
    edit((draft) => {
      objectField(objectField(draft, 'equipment'), 'idSpace')[key] = value;
    });
  };

  return (
    <div className="template-card">
      <div className="template-card-head">
        <span className="template-label">号段规划（idSpace）</span>
      </div>
      <div className="form-row">
        <label className="field field-cell">
          <span className="field-cell-name">SVID 起点</span>
          <NumberField value={numberField(idSpace, 'svidBase')} integer onCommit={(next) => setNumber('svidBase', next)} />
        </label>
        <label className="field field-cell">
          <span className="field-cell-name">CEID 起点</span>
          <NumberField value={numberField(idSpace, 'ceidBase')} integer onCommit={(next) => setNumber('ceidBase', next)} />
        </label>
        <label className="field field-cell">
          <span className="field-cell-name">RPTID 起点</span>
          <NumberField value={numberField(idSpace, 'reportBase')} integer onCommit={(next) => setNumber('reportBase', next)} />
        </label>
        <label className="field field-cell">
          <span className="field-cell-name">SVID 步长</span>
          <NumberField value={numberField(idSpace, 'svidStride')} integer onCommit={(next) => setNumber('svidStride', next)} />
        </label>
        <label className="field field-cell">
          <span className="field-cell-name">CEID 步长</span>
          <NumberField value={numberField(idSpace, 'ceidStride')} integer onCommit={(next) => setNumber('ceidStride', next)} />
        </label>
        <label className="field field-cell">
          <span className="field-cell-name">RPTID 步长</span>
          <NumberField value={numberField(idSpace, 'reportStride')} integer onCommit={(next) => setNumber('reportStride', next)} />
        </label>
      </div>
      <p className="hint">
        绝对 ID = 段起点 + (单元位置-1) × 步长 + offset。每个单元内最大 offset 必须小于步长。
      </p>
      {reserve.length > 0 && (
        <div className="template-subtable">
          <div className="template-subhead">
            <span>保留号段（禁止占用）</span>
          </div>
          {/* reserve 是 [起, 止] 数字对，schema 没有 id；用值拼 key 会在编辑时换 key，
              所以按行位置做 key（增删都走 splice，位置即身份）。 */}
          {reserve.map((pair, index) => (
            <div className="form-row" key={index}>
              {Array.isArray(pair) && pair.length >= 2 ? (
                <>
                  <label className="field field-cell">
                    <span className="field-cell-name">起</span>
                    <NumberField
                      value={typeof pair[0] === 'number' ? pair[0] : undefined}
                      integer
                      onCommit={(next) => edit((draft) => {
                        const target = reserveOf(draft);
                        const item = target[index];
                        if (Array.isArray(item)) {
                          item[0] = next;
                        }
                      })}
                    />
                  </label>
                  <label className="field field-cell">
                    <span className="field-cell-name">止</span>
                    <NumberField
                      value={typeof pair[1] === 'number' ? pair[1] : undefined}
                      integer
                      onCommit={(next) => edit((draft) => {
                        const target = reserveOf(draft);
                        const item = target[index];
                        if (Array.isArray(item)) {
                          item[1] = next;
                        }
                      })}
                    />
                  </label>
                  <button
                    type="button"
                    onClick={() => edit((draft) => {
                      const target = reserveOf(draft);
                      target.splice(index, 1);
                    })}
                  >
                    删除
                  </button>
                </>
              ) : (
                <p className="problems">reserve[{index}] 不是 [起, 止] 数组</p>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function reserveOf(root: JsonObject): unknown[] {
  const idSpace = objectField(objectField(root, 'equipment'), 'idSpace');
  const reserve = idSpace.reserve;
  if (Array.isArray(reserve)) {
    return reserve;
  }
  idSpace.reserve = [];
  return idSpace.reserve as unknown[];
}

function GlobalSvidsForm({ equipment, edit, templateIds, onPreviewSvid }: {
  equipment: JsonObject;
  edit: Edit;
  templateIds: string[];
  onPreviewSvid?: (svid: number) => void;
}): JSX.Element {
  const list = readObjectArray(equipment, 'globalSvids', 'equipment');

  return (
    <div className="template-card">
      <div className="template-card-head">
        <span className="template-label">全局 SVID（不属于任何单元）</span>
        {typeof list !== 'string' && (
          <button
            type="button"
            onClick={() => edit((draft) => {
              const target = objectField(draft, 'equipment');
              const items = Array.isArray(target.globalSvids) ? (target.globalSvids as JsonObject[]) : [];
              items.push({
                id: nextId(items, 'id', 9001),
                name: `GLOBAL_SVID_${items.length + 1}`,
                type: 'F4',
              });
              target.globalSvids = items;
            })}
          >
            + 全局 SVID
          </button>
        )}
      </div>
      {typeof list === 'string' ? (
        <p className="problems">equipment.globalSvids {list}</p>
      ) : list.length === 0 ? (
        <p className="hint">没有全局 SVID。单元内 SVID 用各单元卡片编辑。</p>
      ) : (
        <>
          <FormGridHead columns={['绝对 id', '名称', '类型', 'min', 'max']} />
          {list.map((item, index) => (
          <FormRow
            key={index}
            cols={5}
            onRemove={() => edit((draft) => {
              const target = objectField(draft, 'equipment');
              if (Array.isArray(target.globalSvids)) {
                (target.globalSvids as unknown[]).splice(index, 1);
              }
            })}
          >
            <label className="field field-cell">
              <span className="field-cell-name">绝对 id</span>
              <NumberField
                value={numberField(item, 'id')}
                integer
                onCommit={(next) => edit((draft) => {
                  globalSvidsOf(draft)[index].id = next;
                })}
              />
            </label>
            <label className="field field-cell">
              <span className="field-cell-name">名称</span>
              <TextField
                value={stringField(item, 'name')}
                onCommit={(next) => edit((draft) => {
                  globalSvidsOf(draft)[index].name = next;
                })}
              />
            </label>
            <TypeSelect
              value={stringField(item, 'type', 'F4')}
              onCommit={(next) => edit((draft) => {
                globalSvidsOf(draft)[index].type = next;
              })}
            />
            <label className="field field-cell">
              <span className="field-cell-name">min</span>
              <NumberField
                value={numberField(item, 'min')}
                onCommit={(next) => edit((draft) => {
                  globalSvidsOf(draft)[index].min = next;
                })}
              />
            </label>
            <label className="field field-cell">
              <span className="field-cell-name">max</span>
              <NumberField
                value={numberField(item, 'max')}
                onCommit={(next) => edit((draft) => {
                  globalSvidsOf(draft)[index].max = next;
                })}
              />
            </label>
          </FormRow>
        ))}
        </>
      )}
      {typeof list !== 'string' && list.map((item, index) => (
        <SvidSourceEditor
          key={`gsrc-${index}`}
          def={item}
          templateIds={templateIds}
          absoluteId={numberField(item, 'id') ?? 0}
          onPreviewSvid={onPreviewSvid}
        />
      ))}
      {typeof list !== 'string' && list.length > 0 && (
        <p className="hint">不配 min/max 时默认曲线退化为 0..100 有界随机游走;「值来源」可给这个 SVID 单独配曲线或模板。</p>
      )}
    </div>
  );
}

function globalSvidsOf(root: JsonObject): JsonObject[] {
  const target = objectField(root, 'equipment');
  const items = target.globalSvids;
  return Array.isArray(items) ? (items as JsonObject[]) : [];
}

function GlobalCeidsForm({ equipment, edit }: { equipment: JsonObject; edit: Edit }): JSX.Element {
  const list = readObjectArray(equipment, 'globalCeids', 'equipment');

  return (
    <div className="template-card">
      <div className="template-card-head">
        <span className="template-label">全局 CEID（跨单元报告要写「单元名.报告名」）</span>
        {typeof list !== 'string' && (
          <button
            type="button"
            onClick={() => edit((draft) => {
              const target = objectField(draft, 'equipment');
              const items = Array.isArray(target.globalCeids) ? (target.globalCeids as JsonObject[]) : [];
              items.push({
                id: nextId(items, 'id', 1001),
                name: `GLOBAL_EVENT_${items.length + 1}`,
                reports: [],
              });
              target.globalCeids = items;
            })}
          >
            + 全局 CEID
          </button>
        )}
      </div>
      {typeof list === 'string' ? (
        <p className="problems">equipment.globalCeids {list}</p>
      ) : list.length === 0 ? (
        <p className="hint">没有全局 CEID。</p>
      ) : (
        <>
          <FormGridHead columns={['绝对 id', '名称', '引用（逗号分隔）']} />
          {list.map((item, index) => (
          <FormRow
            key={index}
            cols={3}
            onRemove={() => edit((draft) => {
              const target = objectField(draft, 'equipment');
              if (Array.isArray(target.globalCeids)) {
                (target.globalCeids as unknown[]).splice(index, 1);
              }
            })}
          >
            <label className="field field-cell">
              <span className="field-cell-name">绝对 id</span>
              <NumberField
                value={numberField(item, 'id')}
                integer
                onCommit={(next) => edit((draft) => {
                  globalCeidsOf(draft)[index].id = next;
                })}
              />
            </label>
            <label className="field field-cell">
              <span className="field-cell-name">名称</span>
              <TextField
                value={stringField(item, 'name')}
                onCommit={(next) => edit((draft) => {
                  globalCeidsOf(draft)[index].name = next;
                })}
              />
            </label>
            <StringListField
              values={stringListOf(item, 'reports')}
              onCommit={(next) => edit((draft) => {
                globalCeidsOf(draft)[index].reports = next;
              })}
            />
          </FormRow>
          ))}
          </>
      )}
    </div>
  );
}

function globalCeidsOf(root: JsonObject): JsonObject[] {
  const target = objectField(root, 'equipment');
  const items = target.globalCeids;
  return Array.isArray(items) ? (items as JsonObject[]) : [];
}

function ConstantsForm({ equipment, edit }: { equipment: JsonObject; edit: Edit }): JSX.Element {
  const list = readObjectArray(equipment, 'constants', 'equipment');

  return (
    <div className="template-card">
      <div className="template-card-head">
        <span className="template-label">设备常量 ECV（S2F13 查询 / S2F15 写入）</span>
        {typeof list !== 'string' && (
          <button
            type="button"
            onClick={() => edit((draft) => {
              const target = objectField(draft, 'equipment');
              const items = Array.isArray(target.constants) ? (target.constants as JsonObject[]) : [];
              items.push({
                id: nextId(items, 'id', 501),
                name: `ECV_${items.length + 1}`,
                type: 'F4',
                value: 0,
              });
              target.constants = items;
            })}
          >
            + 常量
          </button>
        )}
      </div>
      {typeof list === 'string' ? (
        <p className="problems">equipment.constants {list}</p>
      ) : list.length === 0 ? (
        <p className="hint">没有设备常量；没有这段时 S2F13 回空列表、S2F15 回 EAC=1。</p>
      ) : (
        <>
          <FormGridHead columns={['ECID', '名称', '类型', '初始值', '单位']} />
          {list.map((item, index) => (
          <FormRow
            key={index}
            cols={5}
            onRemove={() => edit((draft) => {
              const target = objectField(draft, 'equipment');
              if (Array.isArray(target.constants)) {
                (target.constants as unknown[]).splice(index, 1);
              }
            })}
          >
            <label className="field field-cell">
              <span className="field-cell-name">ECID</span>
              <NumberField
                value={numberField(item, 'id')}
                integer
                onCommit={(next) => edit((draft) => {
                  constantsOf(draft)[index].id = next;
                })}
              />
            </label>
            <label className="field field-cell">
              <span className="field-cell-name">名称</span>
              <TextField
                value={stringField(item, 'name')}
                onCommit={(next) => edit((draft) => {
                  constantsOf(draft)[index].name = next;
                })}
              />
            </label>
            <TypeSelect
              value={stringField(item, 'type', 'F4')}
              onCommit={(next) => edit((draft) => {
                constantsOf(draft)[index].type = next;
              })}
            />
            <label className="field field-cell">
              <span className="field-cell-name">初始值</span>
              <NumberField
                value={numberField(item, 'value')}
                onCommit={(next) => edit((draft) => {
                  constantsOf(draft)[index].value = next;
                })}
              />
            </label>
            <label className="field field-cell">
              <span className="field-cell-name">单位</span>
              <TextField
                allowBlank
                value={stringField(item, 'unit')}
                onCommit={(next) => edit((draft) => {
                  constantsOf(draft)[index].unit = next === '' ? undefined : next;
                })}
              />
            </label>
          </FormRow>
        ))}
        </>
      )}
    </div>
  );
}

function constantsOf(root: JsonObject): JsonObject[] {
  const target = objectField(root, 'equipment');
  const items = target.constants;
  return Array.isArray(items) ? (items as JsonObject[]) : [];
}

function stringListOf(item: JsonObject, key: string): string[] {
  const value = item[key];
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

function unitsOf(root: JsonObject): JsonObject[] {
  const units = objectField(root, 'equipment').units;
  return Array.isArray(units) ? (units as JsonObject[]) : [];
}

function UnitCard({ unit, index, edit, templateIds, onPreviewSvid, unitSvidBase, unitSvidStride }: {
  unit: JsonObject;
  index: number;
  edit: Edit;
  templateIds: string[];
  onPreviewSvid?: (svid: number) => void;
  unitSvidBase: number;
  unitSvidStride: number;
}): JSX.Element {
  const mutateUnit = (mutate: (target: JsonObject) => void) => {
    edit((draft) => {
      const target = unitsOf(draft)[index];
      if (isJsonObject(target)) {
        mutate(target);
      }
    });
  };

  /* 本单元的 SVID 段起点(与 collectSvids 同一套默认值),供值来源预览换算绝对 id */
  const svidBase = unitSvidBase + index * unitSvidStride;

  const svids = readObjectArray(unit, 'svids', 'unit');
  const reports = readObjectArray(unit, 'reports', 'unit');
  const ceids = readObjectArray(unit, 'ceids', 'unit');

  return (
    <div className="template-card">
      <div className="template-card-head">
        <label className="field field-cell">
          <span className="field-cell-name">unitId</span>
          <NumberField
            value={numberField(unit, 'unitId')}
            integer
            onCommit={(next) => mutateUnit((target) => {
              target.unitId = next;
            })}
          />
        </label>
        <label className="field field-cell">
          <span className="field-cell-name">单元名</span>
          <TextField
            value={stringField(unit, 'name')}
            onCommit={(next) => mutateUnit((target) => {
              target.name = next;
            })}
          />
        </label>
        <label className="check-inline" title="缺省为 true：开启时必须在该单元定义 UNIT_ID 槽位">
          <input
            type="checkbox"
            checked={booleanField(unit, 'injectUnitId', true)}
            onChange={(event) => mutateUnit((target) => {
              target.injectUnitId = event.target.checked;
            })}
          />
          报告自动带 UNIT_ID
        </label>
        <button
          type="button"
          onClick={() => edit((draft) => {
            unitsOf(draft).splice(index, 1);
          })}
        >
          删除单元
        </button>
      </div>

    </div>
  );
}

function svidsOf(unit: JsonObject): JsonObject[] {
  const items = unit.svids;
  return Array.isArray(items) ? (items as JsonObject[]) : [];
}

function reportsOf(unit: JsonObject): JsonObject[] {
  const items = unit.reports;
  return Array.isArray(items) ? (items as JsonObject[]) : [];
}

function ceidsOf(unit: JsonObject): JsonObject[] {
  const items = unit.ceids;
  return Array.isArray(items) ? (items as JsonObject[]) : [];
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

function EventsForm({ root, edit }: { root: JsonObject; edit: Edit }): JSX.Element {
  const events = isJsonObject(root.events) ? root.events : null;
  const ceidChoices = useMemo(() => collectCeids(root), [root]);
  const svidChoices = useMemo(() => collectSvids(root), [root]);

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

              <EffectsEditor
                plan={plan}
                mutatePlan={mutatePlan}
                svidChoices={svidChoices}
              />
            </div>
          );
        })
      )}
      <p className="hint">
        切换触发方式会把该计划的 trigger 参数重置为该类型的默认值。表达式规则见 docs/schema-v4.md。
      </p>
      </Collapsible>
    </div>
  );
}
