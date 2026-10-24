import { useMemo, useState, type ReactNode } from 'react';
import { NumberField, TextField } from './ui/CommitFields';
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

interface ConfigFormEditorProps {
  /** 配置原文；结构化编辑永远从这份文本出发。 */
  text: string;
  /** 写回新的原文（只改草稿，不落盘）。 */
  onChange: (next: string) => void;
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
export function ConfigFormEditor({ text, onChange }: ConfigFormEditorProps): JSX.Element {
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
      <EquipmentForm root={root} edit={edit} />
      <EventsForm root={root} edit={edit} />
    </div>
  );
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

/** 子表行：一排字段 + 行尾删除按钮。 */
function FormRow({ onRemove, children }: { onRemove: () => void; children: ReactNode }): JSX.Element {
  return (
    <div className="form-row">
      {children}
      <button type="button" onClick={onRemove}>
        删除
      </button>
    </div>
  );
}

function TypeSelect({ value, onCommit }: { value: string; onCommit: (next: string) => void }): JSX.Element {
  return (
    <label className="field">
      类型
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
    <label className="field">
      引用（逗号分隔）
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

function SessionForm({ root, edit }: { root: JsonObject; edit: Edit }): JSX.Element {
  const session = isJsonObject(root.session) ? root.session : null;

  if (session === null) {
    return (
      <div className="cfg-section">
        <SectionHead title="会话">
          <button
            type="button"
            onClick={() => edit((draft) => {
              draft.session = { id: 'dev-01', passive: true, port: 5000 };
            })}
          >
            插入 session 段
          </button>
        </SectionHead>
        <p className="hint">这份配置还没有 session 段（监听端口、设备号等）。</p>
      </div>
    );
  }

  const set = (key: string, value: unknown) => {
    edit((draft) => {
      objectField(draft, 'session')[key] = value;
    });
  };

  return (
    <div className="cfg-section">
      <SectionHead title="会话（session）" />
      <div className="form-row">
        <label className="field">
          会话 id
          <TextField value={stringField(session, 'id')} onCommit={(next) => set('id', next)} />
        </label>
        <label className="field">
          HSMS 端口
          <NumberField value={numberField(session, 'port')} integer onCommit={(next) => set('port', next)} />
        </label>
        <label className="field">
          绑定地址
          <TextField
            allowBlank
            value={stringField(session, 'bindHost')}
            onCommit={(next) => set('bindHost', next === '' ? undefined : next)}
          />
        </label>
        <label className="field">
          deviceId
          <NumberField value={numberField(session, 'deviceId')} integer onCommit={(next) => set('deviceId', next)} />
        </label>
        <label className="field">
          随机种子
          <NumberField value={numberField(session, 'seed')} integer onCommit={(next) => set('seed', next)} />
        </label>
        <label className="check-inline">
          <input
            type="checkbox"
            checked={booleanField(session, 'passive')}
            onChange={(event) => set('passive', event.target.checked)}
          />
          被动监听
        </label>
      </div>
      <p className="hint">
        被动监听 = 设备开端口等主机连；取消勾选则由设备主动连主机。
        会话 id 唯一，端口不能和正在运行的会话冲突。
      </p>
    </div>
  );
}

/* ---------- 设备 ---------- */

function EquipmentForm({ root, edit }: { root: JsonObject; edit: Edit }): JSX.Element {
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

  return (
    <div className="cfg-section">
      <SectionHead title="设备（equipment）">
        {svidsOk && (
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
      </SectionHead>

      <div className="form-row">
        <label className="field">
          设备名
          <TextField
            value={stringField(equipment, 'name')}
            onCommit={(next) => edit((draft) => {
              objectField(draft, 'equipment').name = next;
            })}
          />
        </label>
      </div>

      <IdSpaceForm equipment={equipment} edit={edit} />
      <GlobalSvidsForm equipment={equipment} edit={edit} />
      <GlobalCeidsForm equipment={equipment} edit={edit} />
      <ConstantsForm equipment={equipment} edit={edit} />

      {typeof units === 'string' ? (
        <p className="problems">equipment.units {units}</p>
      ) : (
        units.map((unit, index) => (
          <UnitCard key={index} unit={unit} index={index} edit={edit} />
        ))
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
        <label className="field">
          SVID 起点
          <NumberField value={numberField(idSpace, 'svidBase')} integer onCommit={(next) => setNumber('svidBase', next)} />
        </label>
        <label className="field">
          CEID 起点
          <NumberField value={numberField(idSpace, 'ceidBase')} integer onCommit={(next) => setNumber('ceidBase', next)} />
        </label>
        <label className="field">
          RPTID 起点
          <NumberField value={numberField(idSpace, 'reportBase')} integer onCommit={(next) => setNumber('reportBase', next)} />
        </label>
        <label className="field">
          SVID 步长
          <NumberField value={numberField(idSpace, 'svidStride')} integer onCommit={(next) => setNumber('svidStride', next)} />
        </label>
        <label className="field">
          CEID 步长
          <NumberField value={numberField(idSpace, 'ceidStride')} integer onCommit={(next) => setNumber('ceidStride', next)} />
        </label>
        <label className="field">
          RPTID 步长
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
          {reserve.map((pair, index) => (
            <div className="form-row" key={index}>
              {Array.isArray(pair) && pair.length >= 2 ? (
                <>
                  <label className="field">
                    起
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
                  <label className="field">
                    止
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

function GlobalSvidsForm({ equipment, edit }: { equipment: JsonObject; edit: Edit }): JSX.Element {
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
        list.map((item, index) => (
          <FormRow
            key={index}
            onRemove={() => edit((draft) => {
              const target = objectField(draft, 'equipment');
              if (Array.isArray(target.globalSvids)) {
                (target.globalSvids as unknown[]).splice(index, 1);
              }
            })}
          >
            <label className="field">
              绝对 id
              <NumberField
                value={numberField(item, 'id')}
                integer
                onCommit={(next) => edit((draft) => {
                  globalSvidsOf(draft)[index].id = next;
                })}
              />
            </label>
            <label className="field">
              名称
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
            <label className="field">
              min
              <NumberField
                value={numberField(item, 'min')}
                onCommit={(next) => edit((draft) => {
                  globalSvidsOf(draft)[index].min = next;
                })}
              />
            </label>
            <label className="field">
              max
              <NumberField
                value={numberField(item, 'max')}
                onCommit={(next) => edit((draft) => {
                  globalSvidsOf(draft)[index].max = next;
                })}
              />
            </label>
          </FormRow>
        ))
      )}
      {typeof list !== 'string' && list.length > 0 && (
        <p className="hint">不配 min/max 时默认曲线退化为 0..100 有界随机游走。</p>
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
        list.map((item, index) => (
          <FormRow
            key={index}
            onRemove={() => edit((draft) => {
              const target = objectField(draft, 'equipment');
              if (Array.isArray(target.globalCeids)) {
                (target.globalCeids as unknown[]).splice(index, 1);
              }
            })}
          >
            <label className="field">
              绝对 id
              <NumberField
                value={numberField(item, 'id')}
                integer
                onCommit={(next) => edit((draft) => {
                  globalCeidsOf(draft)[index].id = next;
                })}
              />
            </label>
            <label className="field">
              名称
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
        ))
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
        list.map((item, index) => (
          <FormRow
            key={index}
            onRemove={() => edit((draft) => {
              const target = objectField(draft, 'equipment');
              if (Array.isArray(target.constants)) {
                (target.constants as unknown[]).splice(index, 1);
              }
            })}
          >
            <label className="field">
              ECID
              <NumberField
                value={numberField(item, 'id')}
                integer
                onCommit={(next) => edit((draft) => {
                  constantsOf(draft)[index].id = next;
                })}
              />
            </label>
            <label className="field">
              名称
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
            <label className="field">
              初始值
              <NumberField
                value={numberField(item, 'value')}
                onCommit={(next) => edit((draft) => {
                  constantsOf(draft)[index].value = next;
                })}
              />
            </label>
            <label className="field">
              单位
              <TextField
                allowBlank
                value={stringField(item, 'unit')}
                onCommit={(next) => edit((draft) => {
                  constantsOf(draft)[index].unit = next === '' ? undefined : next;
                })}
              />
            </label>
          </FormRow>
        ))
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

function UnitCard({ unit, index, edit }: { unit: JsonObject; index: number; edit: Edit }): JSX.Element {
  const mutateUnit = (mutate: (target: JsonObject) => void) => {
    edit((draft) => {
      const target = unitsOf(draft)[index];
      if (isJsonObject(target)) {
        mutate(target);
      }
    });
  };

  const svids = readObjectArray(unit, 'svids', 'unit');
  const reports = readObjectArray(unit, 'reports', 'unit');
  const ceids = readObjectArray(unit, 'ceids', 'unit');

  return (
    <div className="template-card">
      <div className="template-card-head">
        <label className="field">
          unitId
          <NumberField
            value={numberField(unit, 'unitId')}
            integer
            onCommit={(next) => mutateUnit((target) => {
              target.unitId = next;
            })}
          />
        </label>
        <label className="field">
          单元名
          <TextField
            value={stringField(unit, 'name')}
            onCommit={(next) => mutateUnit((target) => {
              target.name = next;
            })}
          />
        </label>
        <label className="check-inline">
          <input
            type="checkbox"
            checked={booleanField(unit, 'injectUnitId')}
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

      {/* SVID 定义 */}
      <div className="template-subtable">
        <div className="template-subhead">
          <span>SVID（绝对 id = 段起点 + offset）</span>
          {typeof svids !== 'string' && (
            <button
              type="button"
              onClick={() => mutateUnit((target) => {
                const items = Array.isArray(target.svids) ? (target.svids as JsonObject[]) : [];
                const offset = nextOffset(items);
                items.push({ offset, slot: `SVID_${offset}`, type: 'F4' });
                target.svids = items;
              })}
            >
              + SVID
            </button>
          )}
        </div>
        {typeof svids === 'string' ? (
          <p className="problems">svids {svids}</p>
        ) : svids.length === 0 ? (
          <p className="hint">还没有 SVID。</p>
        ) : (
          svids.map((item, row) => (
            <FormRow
              key={row}
              onRemove={() => mutateUnit((target) => {
                if (Array.isArray(target.svids)) {
                  (target.svids as unknown[]).splice(row, 1);
                }
              })}
            >
              <label className="field">
                offset
                <NumberField
                  value={numberField(item, 'offset')}
                  integer
                  onCommit={(next) => mutateUnit((target) => {
                    svidsOf(target)[row].offset = next;
                  })}
                />
              </label>
              <label className="field">
                槽位名
                <TextField
                  value={stringField(item, 'slot')}
                  onCommit={(next) => mutateUnit((target) => {
                    svidsOf(target)[row].slot = next;
                  })}
                />
              </label>
              <TypeSelect
                value={stringField(item, 'type', 'F4')}
                onCommit={(next) => mutateUnit((target) => {
                  svidsOf(target)[row].type = next;
                })}
              />
              <label className="field">
                单位
                <TextField
                  allowBlank
                  value={stringField(item, 'unit')}
                  onCommit={(next) => mutateUnit((target) => {
                    svidsOf(target)[row].unit = next === '' ? undefined : next;
                  })}
                />
              </label>
              <label className="field">
                小数位
                <NumberField
                  value={numberField(item, 'decimals')}
                  integer
                  onCommit={(next) => mutateUnit((target) => {
                    svidsOf(target)[row].decimals = next;
                  })}
                />
              </label>
              <label className="field">
                min
                <NumberField
                  value={numberField(item, 'min')}
                  onCommit={(next) => mutateUnit((target) => {
                    svidsOf(target)[row].min = next;
                  })}
                />
              </label>
              <label className="field">
                max
                <NumberField
                  value={numberField(item, 'max')}
                  onCommit={(next) => mutateUnit((target) => {
                    svidsOf(target)[row].max = next;
                  })}
                />
              </label>
            </FormRow>
          ))
        )}
      </div>

      {/* 报告定义 */}
      <div className="template-subtable">
        <div className="template-subhead">
          <span>报告（RPTID = 段起点 + offset）</span>
          {typeof reports !== 'string' && (
            <button
              type="button"
              onClick={() => mutateUnit((target) => {
                const items = Array.isArray(target.reports) ? (target.reports as JsonObject[]) : [];
                const offset = nextOffset(items);
                items.push({ offset, slot: `RPT_${offset}`, svids: [] });
                target.reports = items;
              })}
            >
              + 报告
            </button>
          )}
        </div>
        {typeof reports === 'string' ? (
          <p className="problems">reports {reports}</p>
        ) : reports.length === 0 ? (
          <p className="hint">还没有报告。</p>
        ) : (
          reports.map((item, row) => (
            <FormRow
              key={row}
              onRemove={() => mutateUnit((target) => {
                if (Array.isArray(target.reports)) {
                  (target.reports as unknown[]).splice(row, 1);
                }
              })}
            >
              <label className="field">
                offset
                <NumberField
                  value={numberField(item, 'offset')}
                  integer
                  onCommit={(next) => mutateUnit((target) => {
                    reportsOf(target)[row].offset = next;
                  })}
                />
              </label>
              <label className="field">
                槽位名
                <TextField
                  value={stringField(item, 'slot')}
                  onCommit={(next) => mutateUnit((target) => {
                    reportsOf(target)[row].slot = next;
                  })}
                />
              </label>
              <StringListField
                values={stringListOf(item, 'svids')}
                onCommit={(next) => mutateUnit((target) => {
                  reportsOf(target)[row].svids = next;
                })}
              />
            </FormRow>
          ))
        )}
        <p className="hint">引用写槽位名；跨单元引用写「单元名.槽位名」。</p>
      </div>

      {/* CEID 定义 */}
      <div className="template-subtable">
        <div className="template-subhead">
          <span>CEID（绝对 id = 段起点 + offset）</span>
          {typeof ceids !== 'string' && (
            <button
              type="button"
              onClick={() => mutateUnit((target) => {
                const items = Array.isArray(target.ceids) ? (target.ceids as JsonObject[]) : [];
                const offset = nextOffset(items);
                items.push({ offset, slot: `EVT_${offset}`, reports: [] });
                target.ceids = items;
              })}
            >
              + CEID
            </button>
          )}
        </div>
        {typeof ceids === 'string' ? (
          <p className="problems">ceids {ceids}</p>
        ) : ceids.length === 0 ? (
          <p className="hint">还没有 CEID。</p>
        ) : (
          ceids.map((item, row) => (
            <FormRow
              key={row}
              onRemove={() => mutateUnit((target) => {
                if (Array.isArray(target.ceids)) {
                  (target.ceids as unknown[]).splice(row, 1);
                }
              })}
            >
              <label className="field">
                offset
                <NumberField
                  value={numberField(item, 'offset')}
                  integer
                  onCommit={(next) => mutateUnit((target) => {
                    ceidsOf(target)[row].offset = next;
                  })}
                />
              </label>
              <label className="field">
                槽位名
                <TextField
                  value={stringField(item, 'slot')}
                  onCommit={(next) => mutateUnit((target) => {
                    ceidsOf(target)[row].slot = next;
                  })}
                />
              </label>
              <StringListField
                values={stringListOf(item, 'reports')}
                onCommit={(next) => mutateUnit((target) => {
                  ceidsOf(target)[row].reports = next;
                })}
              />
            </FormRow>
          ))
        )}
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
      <SectionHead title="事件编排（events）" />
      <div className="form-row">
        <label className="field">
          每秒事件上限
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
            <div className="template-card" key={index}>
              <div className="template-card-head">
                <label className="field">
                  计划 id
                  <TextField
                    value={stringField(plan, 'id')}
                    onCommit={(next) => mutatePlan((target) => {
                      target.id = next;
                    })}
                  />
                </label>
                <label className="field">
                  CEID
                  <NumberField
                    value={numberField(plan, 'ceid')}
                    integer
                    list="cfg-ceid-options"
                    onCommit={(next) => mutatePlan((target) => {
                      target.ceid = next;
                    })}
                  />
                </label>
                <label className="field">
                  触发方式
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
                    <label className="field">
                      atMs（启动后毫秒）
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
                      <label className="field">
                        周期 ms
                        <NumberField
                          value={numberField(trigger, 'periodMs')}
                          integer
                          onCommit={(next) => mutateTrigger((target) => {
                            target.periodMs = next;
                          })}
                        />
                      </label>
                      <label className="field">
                        抖动 ms
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
                      <label className="field">
                        条件表达式（t、svid.&lt;id&gt;）
                        <TextField
                          wide
                          value={stringField(trigger, 'expression')}
                          onCommit={(next) => mutateTrigger((target) => {
                            target.expression = next;
                          })}
                        />
                      </label>
                      <label className="field">
                        防抖 ms
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
                      <label className="field">
                        源计划 id
                        <TextField
                          value={stringField(trigger, 'sourcePlan')}
                          onCommit={(next) => mutateTrigger((target) => {
                            target.sourcePlan = next;
                          })}
                        />
                      </label>
                      <label className="field">
                        延迟 ms
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
        切换触发方式会把该计划的 trigger 参数重置为该类型的默认值。表达式规则见 docs/schema-v4.md。
      </p>
    </div>
  );
}
