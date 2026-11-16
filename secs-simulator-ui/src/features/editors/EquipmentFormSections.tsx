import { RefDropdownSelect, type RefChoice } from './RefDropdownSelect';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import { HintTitle } from '../../shared/ui/FieldLabel';
import {
  booleanField,
  isJsonObject,
  numberField,
  objectField,
  readObjectArray,
  stringField,
  type JsonObject,
} from './templateConfig';
import { FormGridHead, FormRow, SectionHead, nextId, type Edit } from './ConfigFormParts';

/* ---------- 设备 ---------- */

export function EquipmentForm({ root, edit, onPreviewSvid }: {
  root: JsonObject;
  edit: Edit;
  onPreviewSvid?: (svid: number) => void;
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
      <div className="template-head">
        <h3>
          <HintTitle text="这里只管号段规划与单元标识。变量、设备常量、事件与报告分别到「变量配置」「设备常量」「事件与报告」页维护。">
            设备全局
          </HintTitle>
        </h3>
      </div>

      <IdSpaceForm equipment={equipment} edit={edit} />

      <div className="template-head" style={{ marginTop: '1.25rem' }}>
        <h3>
          <HintTitle text="单元只保留标识信息（unitId / 名称 / 是否自动注入 UNIT_ID）；单元里的变量、报告、事件到「变量配置」「事件与报告」页维护，避免同一份数据在几个地方填。">
            单元
          </HintTitle>
        </h3>
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
        单元只保留标识信息（unitId / 名称 / 是否自动注入 UNIT_ID）；单元里的变量、报告、事件
        到「变量配置」「事件与报告」页维护，避免同一份数据在几个地方填。
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
            onPreviewSvid={onPreviewSvid}
            unitSvidBase={unitSvidBase}
            unitSvidStride={unitSvidStride}
          />
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
        <span className="template-label">
          <HintTitle text="绝对 ID = 段起点 + (单元位置-1) × 步长 + offset。每个单元内最大 offset 必须小于步长。">
            号段规划（idSpace）
          </HintTitle>
        </span>
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

export function GlobalCeidsForm({ equipment, edit }: { equipment: JsonObject; edit: Edit }): JSX.Element {
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
          <FormGridHead columns={['绝对 id', '名称', '引用（勾选引用）']} />
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
            <RefDropdownSelect
              label="引用报告"
              align="right"
              choices={globalReportChoices(equipment)}
              values={stringListOf(item, 'reports')}
              onChange={(next) => edit((draft) => {
                globalCeidsOf(draft)[index].reports = next;
              })}
              emptyHint="还没有任何单元报告；先到单元里建报告"
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

/** 全部单元报告的「单元名.报告名」清单（全局 CEID 引用候选）。 */
function globalReportChoices(equipment: JsonObject): RefChoice[] {
  const units = readObjectArray(equipment, 'units', 'equipment');
  if (typeof units === 'string') {
    return [];
  }
  const out: RefChoice[] = [];
  for (const unit of units) {
    const unitName = stringField(unit, 'name');
    const reports = readObjectArray(unit, 'reports', 'unit');
    if (typeof reports === 'string') {
      continue;
    }
    for (const report of reports) {
      const slot = stringField(report, 'slot');
      if (slot !== '') {
        out.push({ value: `${unitName}.${slot}` });
      }
    }
  }
  return out;
}

export function stringListOf(item: JsonObject, key: string): string[] {
  const value = item[key];
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

export function unitsOf(root: JsonObject): JsonObject[] {
  const units = objectField(root, 'equipment').units;
  return Array.isArray(units) ? (units as JsonObject[]) : [];
}

function UnitCard({ unit, index, edit, onPreviewSvid, unitSvidBase, unitSvidStride }: {
  unit: JsonObject;
  index: number;
  edit: Edit;
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