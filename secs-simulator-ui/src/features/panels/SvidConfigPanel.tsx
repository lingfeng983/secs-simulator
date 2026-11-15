import { useState } from 'react';
import type { PanelBaseProps } from './types';
import { ConfigEditShell, MissingEquipment } from './ConfigEditShell';
import { nextId, SessionField } from '../editors/ConfigFormEditor';
import { ConfigItemModal } from '../editors/ConfigItemModal';
import { SourceEditor } from '../editors/SourceEditor';
import { SourcePreview } from '../editors/SourcePreview';
import { useParamTemplates } from '../../state/useParamTemplates';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import type { ParamTemplate } from '../../api/paramTemplates';
import {
  describeSource,
  isJsonObject,
  numberField,
  objectField,
  SECS_TYPES,
  stringField,
  type JsonObject,
} from '../editors/templateConfig';

type Edit = (mutate: (root: JsonObject) => void) => void;

/** 所属：-1 = 全局 SVID，其余为单元下标。 */
const GLOBAL = -1;

interface SvidRow {
  key: string;
  scope: number;
  index: number;
  absId: number;
  scopeLabel: string;
  name: string;
  item: JsonObject;
}

/**
 * 设备 · 配置区 · 变量配置（SVID 定义）。
 *
 * <p>全局 SVID 与各单元 SVID 合在一张表里，按行「编辑」弹窗改基本信息与值定义；
 * 弹窗「确定」写入本页草稿，「保存」后相关会话自动重载。「从模板库填入」
 * 把库里那条变化逻辑深拷贝进来，之后改库不影响这里。</p>
 */
export function SvidConfigPanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  const paramLibrary = useParamTemplates(backend);
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-svid-config"
      title="变量配置（SVID 定义）"
    >
      {({ root, edit }) => (
        isJsonObject(root.equipment)
          ? <SvidTable equipment={root.equipment} edit={edit} library={paramLibrary.entries} />
          : <MissingEquipment edit={edit} />
      )}
    </ConfigEditShell>
  );
}

function objectsOf(value: unknown): JsonObject[] {
  return Array.isArray(value) ? value.filter(isJsonObject) : [];
}

/** 数组里的对象及其原始下标（跳过非对象项，下标仍对得上草稿里的位置）。 */
function indexedObjects(value: unknown): Array<[JsonObject, number]> {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((item, index): Array<[JsonObject, number]> => (isJsonObject(item) ? [[item, index]] : []));
}

/** 单元 SVID 段起点：与运行期同一套默认值（1000 起、每单元 100）。 */
function unitBases(equipment: JsonObject): { base: number; stride: number } {
  const idSpace = objectField(equipment, 'idSpace');
  return {
    base: numberField(idSpace, 'svidBase') ?? 1000,
    stride: numberField(idSpace, 'svidStride') ?? 100,
  };
}

function unitLabel(unit: JsonObject | undefined, unitIndex: number): string {
  const name = unit === undefined ? '' : stringField(unit, 'name');
  return name === '' ? `单元 ${unitIndex + 1}` : `单元 ${name}`;
}

function rowsOf(equipment: JsonObject): SvidRow[] {
  const { base, stride } = unitBases(equipment);
  const rows: SvidRow[] = indexedObjects(equipment.globalSvids).map(([item, index]) => ({
    key: `g:${index}`,
    scope: GLOBAL,
    index,
    absId: numberField(item, 'id') ?? 0,
    scopeLabel: '全局',
    name: stringField(item, 'name'),
    item,
  }));
  indexedObjects(equipment.units).forEach(([unit, unitIndex]) => {
    indexedObjects(unit.svids).forEach(([item, index]) => {
      rows.push({
        key: `u${unitIndex}:${index}`,
        scope: unitIndex,
        index,
        absId: base + unitIndex * stride + (numberField(item, 'offset') ?? 0),
        scopeLabel: unitLabel(unit, unitIndex),
        name: stringField(item, 'slot'),
        item,
      });
    });
  });
  return rows;
}

/** 在草稿里定位某个所属下的 SVID 数组（不存在就建出来）。 */
function svidListIn(draft: JsonObject, scope: number): JsonObject[] | null {
  const equipment = objectField(draft, 'equipment');
  if (scope === GLOBAL) {
    if (!Array.isArray(equipment.globalSvids)) {
      equipment.globalSvids = [];
    }
    return equipment.globalSvids as JsonObject[];
  }
  const unit = Array.isArray(equipment.units) ? (equipment.units as unknown[])[scope] : undefined;
  if (!isJsonObject(unit)) {
    return null;
  }
  if (!Array.isArray(unit.svids)) {
    unit.svids = [];
  }
  return unit.svids as JsonObject[];
}

/** 原始下标处的单元（非对象返回 undefined）。 */
function unitAt(equipment: JsonObject, unitIndex: number): JsonObject | undefined {
  const unit = Array.isArray(equipment.units) ? (equipment.units as unknown[])[unitIndex] : undefined;
  return isJsonObject(unit) ? unit : undefined;
}

/** 新建时的骨架；从别的所属切过来时保留类型、范围与值定义。 */
function draftFor(equipment: JsonObject, scope: number, from?: JsonObject): JsonObject {
  const keep: JsonObject = {};
  for (const key of ['type', 'min', 'max', 'source']) {
    if (from?.[key] !== undefined) {
      keep[key] = from[key];
    }
  }
  if (scope === GLOBAL) {
    const items = objectsOf(equipment.globalSvids);
    return {
      id: nextId(items, 'id', 9001),
      name: stringField(from ?? {}, 'slot') || stringField(from ?? {}, 'name') || `GLOBAL_SVID_${items.length + 1}`,
      type: 'F4',
      ...keep,
    };
  }
  const items = objectsOf(unitAt(equipment, scope)?.svids);
  return {
    offset: nextId(items, 'offset', 1),
    slot: stringField(from ?? {}, 'name') || stringField(from ?? {}, 'slot') || `SLOT_${items.length + 1}`,
    type: 'F4',
    ...keep,
  };
}

function rangeText(item: JsonObject): string {
  const min = numberField(item, 'min');
  const max = numberField(item, 'max');
  if (min === undefined && max === undefined) {
    return '不限';
  }
  return `${min ?? '−∞'} ~ ${max ?? '+∞'}`;
}

export function SvidTable({ equipment, edit, library }: {
  equipment: JsonObject;
  edit: Edit;
  library: ParamTemplate[];
}): JSX.Element {
  const rows = rowsOf(equipment);
  const firstUnit = indexedObjects(equipment.units)[0]?.[1] ?? GLOBAL;
  /** 正在编辑的行（index = null 表示新建）。 */
  const [editing, setEditing] = useState<{ scope: number; index: number | null } | null>(null);
  const [deleting, setDeleting] = useState<SvidRow | null>(null);
  const [previewing, setPreviewing] = useState<SvidRow | null>(null);

  const editingRow = editing === null || editing.index === null
    ? undefined
    : rows.find((row) => row.scope === editing.scope && row.index === editing.index);

  const columns: Column<SvidRow>[] = [
    { key: 'absId', header: '绝对 id', width: '90px', sortValue: (row) => row.absId, render: (row) => <span className="mono">{row.absId}</span> },
    { key: 'scope', header: '所属', width: '110px', sortValue: (row) => row.scopeLabel },
    { key: 'name', header: '名称', sortValue: (row) => row.name, render: (row) => row.name || '—' },
    { key: 'type', header: '类型', width: '80px', sortValue: (row) => stringField(row.item, 'type', 'F4'), render: (row) => stringField(row.item, 'type', 'F4') },
    { key: 'unit', header: '单位', width: '80px', sortValue: (row) => stringField(row.item, 'unit'), render: (row) => stringField(row.item, 'unit') || '—' },
    { key: 'range', header: '范围', width: '130px', render: (row) => rangeText(row.item) },
    { key: 'source', header: '值定义', sortValue: (row) => describeSource(row.item), render: (row) => describeSource(row.item) },
    {
      key: 'actions',
      header: '操作',
      width: '170px',
      render: (row) => (
        <div className="row-actions">
          <button type="button" onClick={() => setEditing({ scope: row.scope, index: row.index })}>编辑</button>
          <button type="button" onClick={() => setPreviewing(row)} title="按当前草稿预览这个变量随时间的变化">预览</button>
          <button type="button" className="danger" onClick={() => setDeleting(row)}>删除</button>
        </div>
      ),
    },
  ];

  const submit = (scope: number, index: number | null, item: JsonObject) => {
    edit((draft) => {
      const list = svidListIn(draft, scope);
      if (list === null) {
        return;
      }
      if (index === null) {
        list.push(item);
      } else {
        list[index] = item;
      }
    });
    setEditing(null);
  };

  return (
    <>
      <DataTable
        tableKey="device-svids"
        columns={columns}
        rows={rows}
        rowKey={(row) => row.key}
        searchPlaceholder="搜索名称 / 绝对 id / 所属"
        searchText={(row) => `${row.name} ${row.absId} ${row.scopeLabel}`}
        emptyText="还没有变量。点「＋ 新建变量」添加；单元本身在「结构」页维护。"
        toolbarEnd={
          <button
            type="button"
            className="primary"
            onClick={() => setEditing({ scope: firstUnit, index: null })}
          >
            ＋ 新建变量
          </button>
        }
      />
      <p className="hint">
        没配值定义的变量按内置默认曲线取值；值定义四选一：常量 / 曲线 / 表达式 / 按片。
        单元变量的绝对 id = 单元起点 + offset，起点与步长在「结构」页的号段规划里改。
      </p>

      {editing !== null && (editing.index === null || editingRow !== undefined) && (
        <SvidEditModal
          key={editing.index === null ? 'new' : `${editing.scope}:${editing.index}`}
          equipment={equipment}
          scope={editing.scope}
          index={editing.index}
          initial={editingRow?.item ?? draftFor(equipment, editing.scope)}
          library={library}
          onClose={() => setEditing(null)}
          onSubmit={submit}
        />
      )}

      {previewing !== null && (
        <SourcePreview
          open
          title={`${previewing.absId} · ${previewing.name || 'SVID'}`}
          def={previewing.item}
          onClose={() => setPreviewing(null)}
        />
      )}

      <ConfirmModal
        open={deleting !== null}
        title="删除变量"
        danger
        confirmLabel="删除"
        message={deleting === null
          ? ''
          : `确定删除变量「${deleting.name || deleting.absId}」（${deleting.scopeLabel}，绝对 id ${deleting.absId}）？引用它的报告需要另行调整。`}
        onClose={() => setDeleting(null)}
        onConfirm={() => {
          if (deleting !== null) {
            edit((draft) => {
              svidListIn(draft, deleting.scope)?.splice(deleting.index, 1);
            });
          }
          setDeleting(null);
        }}
      />
    </>
  );
}

function SvidEditModal({ equipment, scope: initialScope, index, initial, library, onClose, onSubmit }: {
  equipment: JsonObject;
  scope: number;
  index: number | null;
  initial: JsonObject;
  library: ParamTemplate[];
  onClose: () => void;
  onSubmit: (scope: number, index: number | null, item: JsonObject) => void;
}): JSX.Element {
  const [scope, setScope] = useState(initialScope);
  const [previewing, setPreviewing] = useState(false);
  const units = indexedObjects(equipment.units);
  const { base, stride } = unitBases(equipment);
  const unitBase = base + scope * stride;
  const creating = index === null;

  const problemOf = (item: JsonObject): string => {
    const siblings = indexedObjects(scope === GLOBAL ? equipment.globalSvids : unitAt(equipment, scope)?.svids);
    const others = siblings
      .filter(([, position]) => creating || position !== index)
      .map(([other]) => other);
    const min = numberField(item, 'min');
    const max = numberField(item, 'max');
    if (scope === GLOBAL) {
      if (stringField(item, 'name').trim() === '') {
        return '名称不能为空';
      }
      const id = numberField(item, 'id');
      if (others.some((other) => numberField(other, 'id') === id)) {
        return `绝对 id ${id} 已被其他全局变量使用`;
      }
    } else {
      if (stringField(item, 'slot').trim() === '') {
        return '槽位名不能为空';
      }
      const offset = numberField(item, 'offset');
      if (others.some((other) => numberField(other, 'offset') === offset)) {
        return `offset ${offset} 在本单元里已被使用`;
      }
    }
    if (min !== undefined && max !== undefined && min > max) {
      return `min ${min} 大于 max ${max}`;
    }
    return '';
  };

  return (
    <ConfigItemModal
      title={creating ? '新建变量' : `编辑变量 · ${stringField(initial, scope === GLOBAL ? 'name' : 'slot')}`}
      initial={initial}
      problemOf={problemOf}
      onClose={onClose}
      onSubmit={(item) => onSubmit(scope, index, item)}
    >
      {(item, mutate) => {
        const setNumber = (key: string) => (next: number) => mutate((target) => { target[key] = next; });
        const clear = (key: string) => () => mutate((target) => { delete target[key]; });
        return (
          <>
            <div className="session-form">
              <SessionField
                label="所属"
                help={creating ? '全局变量不属于任何单元；单元变量随单元编号排布' : '所属不能改；要换单元请新建一条再删掉这条'}
              >
                <select
                  value={scope}
                  disabled={!creating}
                  onChange={(event) => {
                    const next = Number(event.target.value);
                    setScope(next);
                    mutate((target) => {
                      const converted = draftFor(equipment, next, target);
                      for (const key of Object.keys(target)) {
                        delete target[key];
                      }
                      Object.assign(target, converted);
                    });
                  }}
                >
                  {units.map(([unit, unitIndex]) => (
                    <option key={unitIndex} value={unitIndex}>{unitLabel(unit, unitIndex)}</option>
                  ))}
                  <option value={GLOBAL}>全局（不属于任何单元）</option>
                </select>
              </SessionField>
              {scope === GLOBAL ? (
                <>
                  <SessionField label="绝对 id" help="主机查询（S1F3）与报告里用的编号，全局变量之间不能重复">
                    <NumberField value={numberField(item, 'id')} integer onCommit={setNumber('id')} />
                  </SessionField>
                  <SessionField label="名称" help="报告与表达式按这个名字引用">
                    <TextField value={stringField(item, 'name')} onCommit={(next) => mutate((target) => { target.name = next; })} />
                  </SessionField>
                </>
              ) : (
                <>
                  <SessionField
                    label="offset"
                    help={`本单元从 ${unitBase} 起，绝对 id = ${unitBase} + offset = ${unitBase + (numberField(item, 'offset') ?? 0)}`}
                  >
                    <NumberField value={numberField(item, 'offset')} integer onCommit={setNumber('offset')} />
                  </SessionField>
                  <SessionField label="槽位名" help="报告与表达式按这个名字引用，例如 TEMP、PRESS">
                    <TextField value={stringField(item, 'slot')} onCommit={(next) => mutate((target) => { target.slot = next; })} />
                  </SessionField>
                </>
              )}
              <SessionField label="类型" help="上报给主机的数据类型：F 为浮点，U / I 为无符号 / 有符号整数">
                <select value={stringField(item, 'type', 'F4')} onChange={(event) => mutate((target) => { target.type = event.target.value; })}>
                  {SECS_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
                </select>
              </SessionField>
              {scope !== GLOBAL && (
                <>
                  <SessionField label="单位" help="显示用的量纲，例如 ℃、Pa；可留空">
                    <TextField
                      allowBlank
                      value={stringField(item, 'unit')}
                      onCommit={(next) => mutate((target) => {
                        if (next.trim() === '') {
                          delete target.unit;
                        } else {
                          target.unit = next;
                        }
                      })}
                    />
                  </SessionField>
                  <SessionField label="小数位" help="显示用的小数位数；留空 = 0">
                    <NumberField value={numberField(item, 'decimals')} integer onCommit={setNumber('decimals')} onClear={clear('decimals')} />
                  </SessionField>
                </>
              )}
              <SessionField label="min" help="取值下限；没配值定义时内置默认曲线在 min~max 内变化。留空 = 不限">
                <NumberField value={numberField(item, 'min')} onCommit={setNumber('min')} onClear={clear('min')} />
              </SessionField>
              <SessionField label="max" help="取值上限，不能小于 min。留空 = 不限">
                <NumberField value={numberField(item, 'max')} onCommit={setNumber('max')} onClear={clear('max')} />
              </SessionField>
            </div>

            <p className="sub-title">值定义</p>
            <SourceEditor def={item} mutate={mutate} library={library} onPreview={() => setPreviewing(true)} />

            {previewing && (
              <SourcePreview
                open
                title={stringField(item, scope === GLOBAL ? 'name' : 'slot') || 'SVID'}
                def={item}
                onClose={() => setPreviewing(false)}
              />
            )}
          </>
        );
      }}
    </ConfigItemModal>
  );
}
