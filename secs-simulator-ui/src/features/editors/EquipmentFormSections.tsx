import { useState } from 'react';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import { HintTitle } from '../../shared/ui/FieldLabel';
import { ConfigItemModal } from './ConfigItemModal';
import { SessionField } from './ConfigFormParts';
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

export function EquipmentForm({ root, edit, onPreviewSvid, onMutated, onSave, busy }: {
  root: JsonObject;
  edit: Edit;
  onPreviewSvid?: (svid: number) => void;
  /** 每次改动落进草稿后回调：单元增删改确认后立即保存（设备页传 save）。 */
  onMutated?: () => void;
  /** 设备全局表单的保存按钮（设备页传 save；模板编辑器不传则不显示按钮）。 */
  onSave?: () => Promise<void> | void;
  busy?: boolean;
}): JSX.Element {
  const equipment = isJsonObject(root.equipment) ? root.equipment : null;
  /* 「新增单元」弹窗：undefined = 关闭；null = 新建。 */
  const [addingUnit, setAddingUnit] = useState<boolean | undefined>(undefined);

  if (equipment === null) {
    return (
      <div className="cfg-section">
        <SectionHead title="设备">
          <button
            type="button"
            onClick={() => {
              edit((draft) => {
                draft.equipment = {
                  name: 'DEV-01',
                  idSpace: { svidBase: 1000, ceidBase: 2000, reportBase: 3000, svidStride: 100, ceidStride: 100, reportStride: 100 },
                  units: [],
                };
              });
              onMutated?.();
            }}
          >
            插入 equipment 段
          </button>
        </SectionHead>
        <p className="hint">这份配置还没有 equipment 段（设备名、号段、单元）。</p>
      </div>
    );
  }

  const units = readObjectArray(equipment, 'units', 'equipment');
  const idSpace = objectField(equipment, 'idSpace');
  const unitSvidBase = numberField(idSpace, 'svidBase') ?? 1000;
  const unitSvidStride = numberField(idSpace, 'svidStride') ?? 100;
  const existingUnits = typeof units === 'string' ? [] : units;

  return (
    <div className="cfg-section">
      <div className="template-head">
        <h3>
          <HintTitle text="这里只管号段规划、事件发送口径与单元标识。变量、设备常量、事件与报告分别到「变量配置」「设备常量」「事件与报告」页维护。">
            设备全局
          </HintTitle>
        </h3>
        {onSave !== undefined && (
          <div className="template-actions">
            <button
              type="button"
              className="primary"
              disabled={busy === true}
              title='保存设备全局（号段规划 + 事件发送）与单元改动，只提交这两段；设备启动中保存的重启设备后生效'
              onClick={() => void onSave()}
            >
              保存
            </button>
          </div>
        )}
      </div>

      <IdSpaceForm equipment={equipment} edit={edit} />
      <EventThrottleForm root={root} edit={edit} />

      <div className="template-head" style={{ marginTop: '1.25rem' }}>
        <h3>
          <HintTitle text="单元只保留标识信息（unitId / 名称 / 是否自动注入 UNIT_ID）；单元里的变量、报告、事件到「变量配置」「事件与报告」页维护，避免同一份数据在几个地方填。">
            单元
          </HintTitle>
        </h3>
        {typeof units !== 'string' && (
          <div className="template-actions">
            <button
              type="button"
              onClick={() => setAddingUnit(true)}
            >
              + 新增单元
            </button>
          </div>
        )}
      </div>
      <p className="hint">
        单元只保留标识信息（unitId / 名称 / 是否自动注入 UNIT_ID），新增、修改与删除都立即保存；
        单元里的变量、报告、事件到「变量配置」「事件与报告」页维护，避免同一份数据在几个地方填。
      </p>
      {typeof units === 'string' ? (
        <p className="problems">equipment.units {units}</p>
      ) : units.length === 0 ? (
        <p className="hint">还没有单元，点「+ 新增单元」添加第一台腔体/工位。</p>
      ) : (
        units.map((unit, index) => (
          <UnitCard
            key={index}
            unit={unit}
            index={index}
            edit={edit}
            onMutated={onMutated}
            onPreviewSvid={onPreviewSvid}
            unitSvidBase={unitSvidBase}
            unitSvidStride={unitSvidStride}
          />
        ))
      )}

      {addingUnit === true && (
        <AddUnitModal
          existing={existingUnits}
          onClose={() => setAddingUnit(undefined)}
          onSubmit={(unit) => {
            edit((draft) => {
              const target = objectField(draft, 'equipment');
              const list = Array.isArray(target.units) ? (target.units as JsonObject[]) : [];
              list.push({ unitId: unit.unitId, name: unit.name, injectUnitId: unit.injectUnitId, svids: [], reports: [], ceids: [] });
              target.units = list;
            });
            setAddingUnit(undefined);
            onMutated?.();
          }}
        />
      )}
    </div>
  );
}

/** 事件发送口径（events 段的三个全局项）：2026-11-20 从「事件计划」页搬到这里。 */
function EventThrottleForm({ root, edit }: { root: JsonObject; edit: Edit }): JSX.Element {
  const events = isJsonObject(root.events) ? root.events : null;
  return (
    <div className="template-card">
      <div className="template-card-head">
        <span className="template-label">
          <HintTitle text="这三项是 events 段的发送口径，不是某一条计划的参数；随「保存」一起提交。">
            事件发送
          </HintTitle>
        </span>
      </div>
      <div className="form-row">
        <label className="field field-cell">
          <span className="field-cell-name">每秒事件上限</span>
          <NumberField
            value={events === null ? undefined : numberField(events, 'maxPerSec')}
            integer
            onCommit={(next) => edit((draft) => {
              objectField(draft, 'events').maxPerSec = next;
            })}
          />
        </label>
        <label className="check-inline">
          <input
            type="checkbox"
            checked={booleanField(events ?? {}, 's6f11Wbit')}
            onChange={(event) => edit((draft) => {
              objectField(draft, 'events').s6f11Wbit = event.target.checked;
            })}
          />
          S6F11 带 W 位
        </label>
        <label className="check-inline">
          <input
            type="checkbox"
            checked={booleanField(events ?? {}, 's6f1Wbit')}
            onChange={(event) => edit((draft) => {
              objectField(draft, 'events').s6f1Wbit = event.target.checked;
            })}
          />
          S6F1 带 W 位
        </label>
      </div>
    </div>
  );
}

/** 新增单元弹窗：只填标识三件套，确认即保存（变量/报告/事件到各自专项页配）。 */
function AddUnitModal({ existing, onClose, onSubmit }: {
  existing: readonly JsonObject[];
  onClose: () => void;
  onSubmit: (unit: JsonObject) => void;
}): JSX.Element {
  const index = existing.length + 1;
  const problemOf = (item: JsonObject): string => {
    const unitId = numberField(item, 'unitId');
    if (unitId === undefined) {
      return 'unitId 不能为空';
    }
    if (existing.some((unit) => numberField(unit, 'unitId') === unitId)) {
      return `unitId ${unitId} 已被其他单元使用`;
    }
    if (stringField(item, 'name').trim() === '') {
      return '单元名不能为空';
    }
    return '';
  };
  return (
    <ConfigItemModal
      title="新增单元"
      initial={{ unitId: nextId(existing, 'unitId', 1), name: `CH${index}`, injectUnitId: index === 1 }}
      problemOf={problemOf}
      onClose={onClose}
      onSubmit={onSubmit}
    >
      {(item, mutate) => (
        <div className="session-fields">
          <SessionField label="unitId" required help="单元编号，设备内不能重复；报告里的 UNIT_ID 用它。">
            <NumberField
              value={numberField(item, 'unitId')}
              integer
              onCommit={(next) => mutate((target) => { target.unitId = next; })}
            />
          </SessionField>
          <SessionField label="单元名" required help="报告与事件引用用「单元名.槽位名」限定归属，例如 CH1.TEMP。">
            <TextField
              value={stringField(item, 'name')}
              onCommit={(next) => mutate((target) => { target.name = next; })}
            />
          </SessionField>
          <SessionField label="报告自动带 UNIT_ID" help="缺省第一个单元开启：开启时该单元必须定义 UNIT_ID 槽位。">
            <label className="check-inline">
              <input
                type="checkbox"
                checked={booleanField(item, 'injectUnitId', true)}
                onChange={(event) => mutate((target) => { target.injectUnitId = event.target.checked; })}
              />
              {booleanField(item, 'injectUnitId', true) ? '报告自动带 UNIT_ID' : '不自动带'}
            </label>
          </SessionField>
        </div>
      )}
    </ConfigItemModal>
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

export function stringListOf(item: JsonObject, key: string): string[] {
  const value = item[key];
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

export function unitsOf(root: JsonObject): JsonObject[] {
  const units = objectField(root, 'equipment').units;
  return Array.isArray(units) ? (units as JsonObject[]) : [];
}

function UnitCard({ unit, index, edit, onMutated, onPreviewSvid, unitSvidBase, unitSvidStride }: {
  unit: JsonObject;
  index: number;
  edit: Edit;
  /** 单元增删改都立即保存（设备页传 save；模板编辑器不传仍是纯草稿）。 */
  onMutated?: () => void;
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
    onMutated?.();
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
          onClick={() => {
            edit((draft) => {
              unitsOf(draft).splice(index, 1);
            });
            onMutated?.();
          }}
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