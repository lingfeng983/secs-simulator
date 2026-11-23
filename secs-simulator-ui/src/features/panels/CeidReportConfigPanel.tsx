import { useState } from 'react';
import type { PanelBaseProps } from './types';
import { ConfigEditShell, MissingEquipment } from './ConfigEditShell';
import { nextId, SessionField, stringListOf } from '../editors/ConfigFormEditor';
import { RefDropdownSelect, type RefChoice } from '../editors/RefDropdownSelect';
import { mergeReportFields, splitReportFields, type ReportFieldGroup } from '../editors/reportFields';
import { ConfigItemModal } from '../editors/ConfigItemModal';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import {
  collectContexts,
  collectEcids,
  isJsonObject,
  numberField,
  objectField,
  stringField,
  type JsonObject,
} from '../editors/templateConfig';

type Edit = (mutate: (root: JsonObject) => void) => void;

/* ---------- 行模型：三张表各自一条记录 ---------- */

/** readObjectArray 的容错版：形状不对（返回错误串）时当空列表，表格只展示能解析的行。 */
function objectList(value: unknown): JsonObject[] {
  return Array.isArray(value) ? value.filter(isJsonObject) : [];
}

interface GlobalCeidRow { index: number; item: JsonObject; }
interface ReportRow { unitIndex: number; unitName: string; index: number; item: JsonObject; }
interface UnitCeidRow { unitIndex: number; unitName: string; index: number; item: JsonObject; }

/**
 * 设备 · 配置区 · 事件与报告（CEID / RPT 定义）。
 *
 * <p>三张表：全局 CEID（跨单元）、报告（RPT）、事件（CEID）——报告/事件按
 * 「所属单元」列合并所有单元。新建/编辑走弹窗，操作列编辑/删除，每一次改动
 * 确认后直接保存落盘（ceids / reports 段）；页面顶部不再有保存/校验/重新载入
 * （2026-11-21 用户要求）。</p>
 *
 * <p>报告的字段可以引用三类数据：本单元 SVID 的槽位名、设备常量
 * {@code ecid.<编号>}、晶圆属性 {@code ctx.<名>}；事件（CEID）引用报告名
 * （全局 CEID 引用「单元名.报告名」）。</p>
 */
export function CeidReportConfigPanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-ceid-report-config"
      sections={['ceids', 'reports']}
      title="事件与报告（CEID / RPT 定义）"
      actions="none"
    >
      {({ root, edit, save }) => (
        isJsonObject(root.equipment)
          ? <CeidReportEditor root={root} edit={edit} onMutated={() => void save()} />
          : <MissingEquipment edit={edit} />
      )}
    </ConfigEditShell>
  );
}

export function CeidReportEditor({ root, edit, onMutated }: {
  root: JsonObject;
  edit: Edit;
  /** 每次改动落进草稿后回调（设备页传 save；模板编辑器不传仍是纯草稿）。 */
  onMutated?: () => void;
}): JSX.Element {
  if (!isJsonObject(root.equipment)) {
    return <MissingEquipment edit={edit} />;
  }
  return (
    <>
      <section className="panel-section block">
        <div className="section-head">
          <h3>全局 CEID</h3>
          <span className="hint-inline">跨单元事件：引用报告要写「单元名.报告名」</span>
        </div>
        <GlobalCeidTable equipment={root.equipment} edit={edit} onMutated={onMutated} />
      </section>
      <section className="panel-section block">
        <div className="section-head">
          <h3>报告（RPT）</h3>
          <span className="hint-inline">上报顺序：先 SVID，再设备常量，最后晶圆属性；列内按勾选先后</span>
        </div>
        <ReportTable equipment={root.equipment} edit={edit} onMutated={onMutated} />
      </section>
      <section className="panel-section block">
        <div className="section-head">
          <h3>事件（CEID）</h3>
          <span className="hint-inline">事件引用同单元报告名；报告先在上方建好</span>
        </div>
        <UnitCeidTable equipment={root.equipment} edit={edit} onMutated={onMutated} />
      </section>
    </>
  );
}

/* ---------- 全局 CEID 表 ---------- */

function globalCeidsOf(equipment: JsonObject): JsonObject[] {
  return Array.isArray(equipment.globalCeids) ? (equipment.globalCeids as JsonObject[]) : [];
}

function globalReportChoices(equipment: JsonObject): RefChoice[] {
  const out: RefChoice[] = [];
  for (const [unit] of unitsWithIndex(equipment)) {
    const unitName = stringField(unit, 'name');
    for (const report of objectList(unit.reports)) {
      const slot = stringField(report, 'slot');
      if (slot !== '') {
        out.push({ value: `${unitName}.${slot}` });
      }
    }
  }
  return out;
}

function GlobalCeidTable({ equipment, edit, onMutated }: {
  equipment: JsonObject;
  edit: Edit;
  onMutated?: () => void;
}): JSX.Element {
  const list = globalCeidsOf(equipment);
  const rows: GlobalCeidRow[] = list.map((item, index) => ({ index, item }));
  /** 正在编辑的下标；null = 新建；undefined = 弹窗关闭。 */
  const [editing, setEditing] = useState<number | null | undefined>(undefined);
  const [deleting, setDeleting] = useState<GlobalCeidRow | null>(null);

  const columns: Column<GlobalCeidRow>[] = [
    { key: 'id', header: '绝对 id', width: '110px', sortValue: (row) => numberField(row.item, 'id') ?? 0, render: (row) => <span className="mono">{numberField(row.item, 'id') ?? '—'}</span> },
    { key: 'name', header: '名称', sortValue: (row) => stringField(row.item, 'name'), render: (row) => stringField(row.item, 'name') || '—' },
    { key: 'reports', header: '引用报告', render: (row) => <span className="mono hint-inline">{stringListOf(row.item, 'reports').join('、') || '—'}</span> },
    {
      key: 'actions',
      header: '操作',
      width: '140px',
      render: (row) => (
        <div className="row-actions">
          <button type="button" onClick={() => setEditing(row.index)}>编辑</button>
          <button type="button" className="danger" onClick={() => setDeleting(row)}>删除</button>
        </div>
      ),
    },
  ];

  const blank = (): JsonObject => ({
    id: nextId(list, 'id', 1001),
    name: `GLOBAL_EVENT_${list.length + 1}`,
    reports: [],
  });

  return (
    <>
      <DataTable
        tableKey="device-global-ceids"
        columns={columns}
        rows={rows}
        rowKey={(row) => String(row.index)}
        searchPlaceholder="搜索名称 / 绝对 id"
        searchText={(row) => `${stringField(row.item, 'name')} ${numberField(row.item, 'id') ?? ''}`}
        emptyText="没有全局 CEID。跨单元都要触发的事件在这里建；单元内的事件到下方「事件」表添加。"
        exportName="device-global-ceids"
        exportColumns={[
          { header: '绝对 id', value: (row) => String(numberField(row.item, 'id') ?? '') },
          { header: '名称', value: (row) => stringField(row.item, 'name') },
          { header: '引用报告', value: (row) => stringListOf(row.item, 'reports').join('、') },
        ]}
        toolbarEnd={
          <button type="button" className="primary" onClick={() => setEditing(null)}>
            ＋ 新建全局 CEID
          </button>
        }
      />

      {editing !== undefined && (
        <ConfigItemModal
          title={editing === null ? '新建全局 CEID' : `编辑全局 CEID · ${stringField(rows[editing]?.item ?? {}, 'name')}`}
          initial={editing === null ? blank() : structuredClone(list[editing])}
          problemOf={(item) => {
            const id = numberField(item, 'id');
            if (id === undefined) {
              return '绝对 id 不能为空';
            }
            if (list.some((other, position) => position !== editing && numberField(other, 'id') === id)) {
              return `绝对 id ${id} 已被其他全局 CEID 使用`;
            }
            if (stringField(item, 'name').trim() === '') {
              return '名称不能为空';
            }
            return '';
          }}
          onClose={() => setEditing(undefined)}
          onSubmit={(item) => {
            edit((draft) => {
              const target = objectField(draft, 'equipment');
              const items = Array.isArray(target.globalCeids) ? (target.globalCeids as JsonObject[]) : [];
              if (editing === null) {
                items.push(item);
              } else if (isJsonObject(items[editing])) {
                items[editing] = item;
              }
              target.globalCeids = items;
            });
            setEditing(undefined);
            onMutated?.();
          }}
        >
          {(item, mutate) => (
            <div className="session-fields">
              <SessionField label="绝对 id" required help="主机事件编号；与单元事件一起构成设备的 CEID 空间，不能重复。">
                <NumberField
                  value={numberField(item, 'id')}
                  integer
                  onCommit={(next) => mutate((target) => { target.id = next; })}
                />
              </SessionField>
              <SessionField label="名称" required help="表达式与界面里显示的名字，例如 ALL_DONE。">
                <TextField
                  value={stringField(item, 'name')}
                  onCommit={(next) => mutate((target) => { target.name = next; })}
                />
              </SessionField>
              <SessionField label="引用报告" help="触发这个 CEID 时上报的报告，跨单元写「单元名.报告名」；可多选。">
                <RefDropdownSelect
                  label="引用报告"
                  choices={globalReportChoices(equipment)}
                  values={stringListOf(item, 'reports')}
                  onChange={(next) => mutate((target) => { target.reports = next; })}
                  emptyHint="还没有任何单元报告；先到下方「报告」表添加"
                />
              </SessionField>
            </div>
          )}
        </ConfigItemModal>
      )}

      <ConfirmModal
        open={deleting !== null}
        title="删除全局 CEID"
        danger
        confirmLabel="删除"
        message={deleting === null
          ? ''
          : `确定删除全局 CEID「${stringField(deleting.item, 'name') || numberField(deleting.item, 'id')}」？引用它的地方需要另行调整。`}
        onClose={() => setDeleting(null)}
        onConfirm={() => {
          if (deleting !== null) {
            edit((draft) => {
              const target = objectField(draft, 'equipment');
              if (Array.isArray(target.globalCeids)) {
                (target.globalCeids as unknown[]).splice(deleting.index, 1);
              }
            });
            onMutated?.();
          }
          setDeleting(null);
        }}
      />
    </>
  );
}

/* ---------- 报告（RPT）表：所有单元合并 ---------- */

function unitLabel(unit: JsonObject | undefined, unitIndex: number): string {
  const name = unit === undefined ? '' : stringField(unit, 'name');
  return name === '' ? `单元 ${unitIndex + 1}` : name;
}

function unitsWithIndex(equipment: JsonObject): Array<[JsonObject, number]> {
  return objectList(equipment.units)
    .map((unit, index) => [unit, index] as [JsonObject, number]);
}

function unitSvidChoices(unit: JsonObject | undefined): RefChoice[] {
  return objectList(isJsonObject(unit) ? unit.svids : [])
    .map((item) => stringField(item, 'slot'))
    .filter((slot) => slot !== '')
    .map((slot) => ({ value: slot, label: slot }));
}

function unitEcidChoices(root: JsonObject): RefChoice[] {
  return collectEcids(root).map((ecid) => ({
    value: `ecid.${ecid.id}`,
    label: `ecid.${ecid.id}${ecid.name === '' ? '' : `（${ecid.name}）`}`,
  }));
}

function unitCtxChoices(root: JsonObject): RefChoice[] {
  return collectContexts(root).map((context) => ({ value: `ctx.${context.id}` }));
}

/** 本单元的报告名清单（事件引用的候选）。 */
function unitReportChoices(unit: JsonObject | undefined): RefChoice[] {
  return objectList(isJsonObject(unit) ? unit.reports : [])
    .map((item) => stringField(item, 'slot'))
    .filter((slot) => slot !== '')
    .map((slot) => ({ value: slot }));
}

function ReportTable({ equipment, edit, onMutated }: {
  equipment: JsonObject;
  edit: Edit;
  onMutated?: () => void;
}): JSX.Element {
  const rows: ReportRow[] = unitsWithIndex(equipment).flatMap(([unit, unitIndex]) =>
    objectList(unit.reports)
      .map((item, index) => ({ unitIndex, unitName: unitLabel(unit, unitIndex), index, item })));
  const [editing, setEditing] = useState<{ unitIndex: number; index: number | null } | null>(null);
  const [deleting, setDeleting] = useState<ReportRow | null>(null);
  const ecidChoices = unitEcidChoices(equipment);
  const ctxChoices = unitCtxChoices(equipment);

  const columns: Column<ReportRow>[] = [
    { key: 'unit', header: '所属单元', width: '110px', sortValue: (row) => row.unitName, render: (row) => row.unitName },
    { key: 'offset', header: 'offset', width: '80px', sortValue: (row) => numberField(row.item, 'offset') ?? 0, render: (row) => <span className="mono">{numberField(row.item, 'offset') ?? '—'}</span> },
    { key: 'slot', header: '报告名', sortValue: (row) => stringField(row.item, 'slot'), render: (row) => stringField(row.item, 'slot') || '—' },
    {
      key: 'svid',
      header: 'SVID',
      render: (row) => <span className="mono hint-inline">{splitReportFields(stringListOf(row.item, 'svids')).svid.join('、') || '—'}</span>,
    },
    {
      key: 'ecid',
      header: '设备常量',
      render: (row) => <span className="mono hint-inline">{splitReportFields(stringListOf(row.item, 'svids')).ecid.join('、') || '—'}</span>,
    },
    {
      key: 'ctx',
      header: '晶圆属性',
      render: (row) => <span className="mono hint-inline">{splitReportFields(stringListOf(row.item, 'svids')).ctx.join('、') || '—'}</span>,
    },
    {
      key: 'actions',
      header: '操作',
      width: '140px',
      render: (row) => (
        <div className="row-actions">
          <button type="button" onClick={() => setEditing({ unitIndex: row.unitIndex, index: row.index })}>编辑</button>
          <button type="button" className="danger" onClick={() => setDeleting(row)}>删除</button>
        </div>
      ),
    },
  ];

  const writeList = (draft: JsonObject, unitIndex: number, mutate: (items: JsonObject[]) => void): void => {
    const units = objectField(draft, 'equipment').units;
    if (!Array.isArray(units) || !isJsonObject(units[unitIndex])) {
      return;
    }
    const unit = units[unitIndex] as JsonObject;
    const items = Array.isArray(unit.reports) ? (unit.reports as JsonObject[]) : [];
    mutate(items);
    unit.reports = items;
  };

  return (
    <>
      <DataTable
        tableKey="device-reports"
        columns={columns}
        rows={rows}
        rowKey={(row) => `r${row.unitIndex}:${row.index}`}
        searchPlaceholder="搜索单元 / 报告名"
        searchText={(row) => `${row.unitName} ${stringField(row.item, 'slot')}`}
        emptyText="还没有报告。报告把 SVID / 设备常量 / 晶圆属性打包成一条上报内容；先到「变量配置」页建好变量。"
        exportName="device-reports"
        exportColumns={[
          { header: '所属单元', value: (row) => row.unitName },
          { header: 'offset', value: (row) => String(numberField(row.item, 'offset') ?? '') },
          { header: '报告名', value: (row) => stringField(row.item, 'slot') },
          { header: '引用', value: (row) => stringListOf(row.item, 'svids').join('、') },
        ]}
        toolbarEnd={
          <button
            type="button"
            className="primary"
            disabled={unitsWithIndex(equipment).length === 0}
            title={unitsWithIndex(equipment).length === 0 ? '还没有单元：先到「结构配置」页新增单元' : undefined}
            onClick={() => setEditing({ unitIndex: unitsWithIndex(equipment)[0]?.[1] ?? 0, index: null })}
          >
            ＋ 新建报告
          </button>
        }
      />

      {editing !== null && (
        <ReportModal
          key={`${editing.unitIndex}:${editing.index}`}
          equipment={equipment}
          unitIndex={editing.unitIndex}
          index={editing.index}
          ecidChoices={ecidChoices}
          ctxChoices={ctxChoices}
          onClose={() => setEditing(null)}
          onSubmit={(unitIndex, index, item) => {
            edit((draft) => {
              writeList(draft, unitIndex, (items) => {
                if (index === null) {
                  items.push(item);
                } else if (isJsonObject(items[index])) {
                  items[index] = item;
                }
              });
            });
            setEditing(null);
            onMutated?.();
          }}
        />
      )}

      <ConfirmModal
        open={deleting !== null}
        title="删除报告"
        danger
        confirmLabel="删除"
        message={deleting === null
          ? ''
          : `确定删除报告「${deleting.unitName}.${stringField(deleting.item, 'slot')}」？引用它的全局 CEID 与单元事件需要另行调整。`}
        onClose={() => setDeleting(null)}
        onConfirm={() => {
          if (deleting !== null) {
            edit((draft) => {
              writeList(draft, deleting.unitIndex, (items) => {
                items.splice(deleting.index, 1);
              });
            });
            onMutated?.();
          }
          setDeleting(null);
        }}
      />
    </>
  );
}

/** 新建/编辑报告弹窗：所属单元 + offset + 报告名 + 三类引用勾选。 */
function ReportModal({ equipment, unitIndex: initialUnitIndex, index, ecidChoices, ctxChoices, onClose, onSubmit }: {
  equipment: JsonObject;
  unitIndex: number;
  index: number | null;
  ecidChoices: RefChoice[];
  ctxChoices: RefChoice[];
  onClose: () => void;
  onSubmit: (unitIndex: number, index: number | null, item: JsonObject) => void;
}): JSX.Element {
  const [unitIndex, setUnitIndex] = useState(initialUnitIndex);
  const creating = index === null;
  const units = unitsWithIndex(equipment);
  const unit = units.find(([, position]) => position === unitIndex)?.[0];
  const siblings = objectList(isJsonObject(unit) ? unit.reports : []);
  const current = creating ? undefined : siblings[index];

  const problemOf = (item: JsonObject): string => {
    const offset = numberField(item, 'offset');
    if (offset === undefined) {
      return 'offset 不能为空';
    }
    if (siblings.some((other, position) => position !== index && numberField(other, 'offset') === offset)) {
      return `offset ${offset} 在本单元里已被使用`;
    }
    if (stringField(item, 'slot').trim() === '') {
      return '报告名不能为空';
    }
    return '';
  };

  return (
    <ConfigItemModal
      title={creating ? '新建报告' : `编辑报告 · ${unitLabel(unit, unitIndex)}.${stringField(current ?? {}, 'slot')}`}
      initial={current ?? { offset: nextId(siblings, 'offset', 1), slot: `RPT_${siblings.length + 1}`, svids: [] }}
      problemOf={problemOf}
      onClose={onClose}
      onSubmit={(item) => onSubmit(unitIndex, index, item)}
    >
      {(item, mutate) => {
        const fields = splitReportFields(stringListOf(item, 'svids'));
        const writeFields = (group: ReportFieldGroup, next: string[]) => mutate((target) => {
          target.svids = mergeReportFields(stringListOf(target, 'svids'), group, next);
        });
        return (
          <div className="session-fields">
            <SessionField
              label="所属单元"
              required
              help={creating ? '报告属于哪个单元；换单元会清空已勾选的 SVID（各单元变量独立）' : '所属不能改；要挪单元请新建一条再删掉这条'}
            >
              <select
                value={unitIndex}
                disabled={!creating}
                onChange={(event) => {
                  const next = Number(event.target.value);
                  setUnitIndex(next);
                  mutate((target) => {
                    const targetUnit = unitsWithIndex(equipment).find(([, position]) => position === next)?.[0];
                    const targetReports = objectList(isJsonObject(targetUnit) ? targetUnit.reports : []);
                    target.offset = nextId(targetReports, 'offset', 1);
                    target.svids = [];
                  });
                }}
              >
                {units.map(([unitOption, position]) => (
                  <option key={position} value={position}>{unitLabel(unitOption, position)}</option>
                ))}
              </select>
            </SessionField>
            <SessionField label="offset" required help="报告在本单元里的编号，不能重复；上报内容按勾选顺序带上。">
              <NumberField
                value={numberField(item, 'offset')}
                integer
                onCommit={(next) => mutate((target) => { target.offset = next; })}
              />
            </SessionField>
            <SessionField label="报告名" required help="本单元内唯一；单元事件与全局 CEID 按这个名字引用。">
              <TextField
                value={stringField(item, 'slot')}
                onCommit={(next) => mutate((target) => { target.slot = next; })}
              />
            </SessionField>
            <SessionField label="SVID 引用" help="本单元的变量槽位名；可多选，勾选顺序就是上报顺序。">
              <RefDropdownSelect
                label="SVID"
                choices={unitSvidChoices(unit)}
                values={fields.svid}
                onChange={(next) => writeFields('svid', next)}
                emptyHint="这个单元还没有 SVID，先到「变量配置」页添加"
              />
            </SessionField>
            <SessionField label="设备常量引用" help="ecid.编号 形式；可多选。">
              <RefDropdownSelect
                label="设备常量"
                choices={ecidChoices}
                values={fields.ecid}
                onChange={(next) => writeFields('ecid', next)}
                emptyHint="还没有设备常量，先到「设备常量」页添加"
              />
            </SessionField>
            <SessionField label="晶圆属性引用" help="ctx.名 形式；可多选。">
              <RefDropdownSelect
                label="晶圆属性"
                choices={ctxChoices}
                values={fields.ctx}
                onChange={(next) => writeFields('ctx', next)}
                emptyHint="还没有晶圆属性，先到「晶圆属性」页添加"
              />
            </SessionField>
          </div>
        );
      }}
    </ConfigItemModal>
  );
}

/* ---------- 事件（CEID）表：所有单元合并 ---------- */

function UnitCeidTable({ equipment, edit, onMutated }: {
  equipment: JsonObject;
  edit: Edit;
  onMutated?: () => void;
}): JSX.Element {
  const rows: UnitCeidRow[] = unitsWithIndex(equipment).flatMap(([unit, unitIndex]) =>
    objectList(unit.ceids)
      .map((item, index) => ({ unitIndex, unitName: unitLabel(unit, unitIndex), index, item })));
  const [editing, setEditing] = useState<{ unitIndex: number; index: number | null } | null>(null);
  const [deleting, setDeleting] = useState<UnitCeidRow | null>(null);

  const columns: Column<UnitCeidRow>[] = [
    { key: 'unit', header: '所属单元', width: '110px', sortValue: (row) => row.unitName, render: (row) => row.unitName },
    { key: 'offset', header: 'offset', width: '80px', sortValue: (row) => numberField(row.item, 'offset') ?? 0, render: (row) => <span className="mono">{numberField(row.item, 'offset') ?? '—'}</span> },
    { key: 'slot', header: '事件名', sortValue: (row) => stringField(row.item, 'slot'), render: (row) => stringField(row.item, 'slot') || '—' },
    { key: 'reports', header: '引用报告', render: (row) => <span className="mono hint-inline">{stringListOf(row.item, 'reports').join('、') || '—'}</span> },
    {
      key: 'actions',
      header: '操作',
      width: '140px',
      render: (row) => (
        <div className="row-actions">
          <button type="button" onClick={() => setEditing({ unitIndex: row.unitIndex, index: row.index })}>编辑</button>
          <button type="button" className="danger" onClick={() => setDeleting(row)}>删除</button>
        </div>
      ),
    },
  ];

  const writeList = (draft: JsonObject, unitIndex: number, mutate: (items: JsonObject[]) => void): void => {
    const units = objectField(draft, 'equipment').units;
    if (!Array.isArray(units) || !isJsonObject(units[unitIndex])) {
      return;
    }
    const unit = units[unitIndex] as JsonObject;
    const items = Array.isArray(unit.ceids) ? (unit.ceids as JsonObject[]) : [];
    mutate(items);
    unit.ceids = items;
  };

  return (
    <>
      <DataTable
        tableKey="device-unit-ceids"
        columns={columns}
        rows={rows}
        rowKey={(row) => `c${row.unitIndex}:${row.index}`}
        searchPlaceholder="搜索单元 / 事件名"
        searchText={(row) => `${row.unitName} ${stringField(row.item, 'slot')}`}
        emptyText="还没有事件。事件是设备上报的触发点（S6F11），引用同单元报告；工艺周期与事件计划按 CEID 引用它。"
        exportName="device-unit-ceids"
        exportColumns={[
          { header: '所属单元', value: (row) => row.unitName },
          { header: 'offset', value: (row) => String(numberField(row.item, 'offset') ?? '') },
          { header: '事件名', value: (row) => stringField(row.item, 'slot') },
          { header: '引用报告', value: (row) => stringListOf(row.item, 'reports').join('、') },
        ]}
        toolbarEnd={
          <button
            type="button"
            className="primary"
            disabled={unitsWithIndex(equipment).length === 0}
            title={unitsWithIndex(equipment).length === 0 ? '还没有单元：先到「结构配置」页新增单元' : undefined}
            onClick={() => setEditing({ unitIndex: unitsWithIndex(equipment)[0]?.[1] ?? 0, index: null })}
          >
            ＋ 新建事件
          </button>
        }
      />

      {editing !== null && (
        <UnitCeidModal
          key={`${editing.unitIndex}:${editing.index}`}
          equipment={equipment}
          unitIndex={editing.unitIndex}
          index={editing.index}
          onClose={() => setEditing(null)}
          onSubmit={(unitIndex, index, item) => {
            edit((draft) => {
              writeList(draft, unitIndex, (items) => {
                if (index === null) {
                  items.push(item);
                } else if (isJsonObject(items[index])) {
                  items[index] = item;
                }
              });
            });
            setEditing(null);
            onMutated?.();
          }}
        />
      )}

      <ConfirmModal
        open={deleting !== null}
        title="删除事件"
        danger
        confirmLabel="删除"
        message={deleting === null
          ? ''
          : `确定删除事件「${deleting.unitName}.${stringField(deleting.item, 'slot')}」？事件计划与工艺周期里按 CEID 引用它的地方需要另行调整。`}
        onClose={() => setDeleting(null)}
        onConfirm={() => {
          if (deleting !== null) {
            edit((draft) => {
              writeList(draft, deleting.unitIndex, (items) => {
                items.splice(deleting.index, 1);
              });
            });
            onMutated?.();
          }
          setDeleting(null);
        }}
      />
    </>
  );
}

/** 新建/编辑事件弹窗：所属单元 + offset + 事件名 + 引用报告勾选。 */
function UnitCeidModal({ equipment, unitIndex: initialUnitIndex, index, onClose, onSubmit }: {
  equipment: JsonObject;
  unitIndex: number;
  index: number | null;
  onClose: () => void;
  onSubmit: (unitIndex: number, index: number | null, item: JsonObject) => void;
}): JSX.Element {
  const [unitIndex, setUnitIndex] = useState(initialUnitIndex);
  const creating = index === null;
  const units = unitsWithIndex(equipment);
  const unit = units.find(([, position]) => position === unitIndex)?.[0];
  const siblings = objectList(isJsonObject(unit) ? unit.ceids : []);
  const current = creating ? undefined : siblings[index];

  const problemOf = (item: JsonObject): string => {
    const offset = numberField(item, 'offset');
    if (offset === undefined) {
      return 'offset 不能为空';
    }
    if (siblings.some((other, position) => position !== index && numberField(other, 'offset') === offset)) {
      return `offset ${offset} 在本单元里已被使用`;
    }
    if (stringField(item, 'slot').trim() === '') {
      return '事件名不能为空';
    }
    return '';
  };

  return (
    <ConfigItemModal
      title={creating ? '新建事件' : `编辑事件 · ${unitLabel(unit, unitIndex)}.${stringField(current ?? {}, 'slot')}`}
      initial={current ?? { offset: nextId(siblings, 'offset', 1), slot: `EVENT_${siblings.length + 1}`, reports: [] }}
      problemOf={problemOf}
      onClose={onClose}
      onSubmit={(item) => onSubmit(unitIndex, index, item)}
    >
      {(item, mutate) => (
        <div className="session-fields">
          <SessionField
            label="所属单元"
            required
            help={creating ? '事件属于哪个单元；换单元会清空已勾选的报告' : '所属不能改；要挪单元请新建一条再删掉这条'}
          >
            <select
              value={unitIndex}
              disabled={!creating}
              onChange={(event) => {
                const next = Number(event.target.value);
                setUnitIndex(next);
                mutate((target) => {
                  const targetUnit = unitsWithIndex(equipment).find(([, position]) => position === next)?.[0];
                  const targetCeids = objectList(isJsonObject(targetUnit) ? targetUnit.ceids : []);
                  target.offset = nextId(targetCeids, 'offset', 1);
                  target.reports = [];
                });
              }}
            >
              {units.map(([unitOption, position]) => (
                <option key={position} value={position}>{unitLabel(unitOption, position)}</option>
              ))}
            </select>
          </SessionField>
          <SessionField label="offset" required help="事件在本单元里的编号，不能重复。">
            <NumberField
              value={numberField(item, 'offset')}
              integer
              onCommit={(next) => mutate((target) => { target.offset = next; })}
            />
          </SessionField>
          <SessionField label="事件名" required help="本单元内唯一；主机侧看到的事件按 CEID 编号，名字用于界面与表达式。">
            <TextField
              value={stringField(item, 'slot')}
              onCommit={(next) => mutate((target) => { target.slot = next; })}
            />
          </SessionField>
          <SessionField label="引用报告" help="触发这个 CEID 时上报的同单元报告；可多选。">
            <RefDropdownSelect
              label="引用报告"
              choices={unitReportChoices(unit)}
              values={stringListOf(item, 'reports')}
              onChange={(next) => mutate((target) => { target.reports = next; })}
              emptyHint="这个单元还没有报告，先到上方「报告」表添加"
            />
          </SessionField>
        </div>
      )}
    </ConfigItemModal>
  );
}
