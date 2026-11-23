import { useState } from 'react';
import type { PanelBaseProps } from './types';
import { ConfigEditShell, MissingEquipment } from './ConfigEditShell';
import { nextId, SessionField } from '../editors/ConfigFormEditor';
import { SourceEditorLegend } from '../editors/ConfigFormParts';
import { ConfigItemModal } from '../editors/ConfigItemModal';
import { SourceEditor } from '../editors/SourceEditor';
import { SourcePreview } from '../editors/SourcePreview';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import {
  describeSource,
  isJsonObject,
  numberField,
  objectField,
  SECS_TYPES,
  sourceModeOf,
  stringField,
  type JsonObject,
} from '../editors/templateConfig';

type Edit = (mutate: (root: JsonObject) => void) => void;

interface ConstantRow {
  index: number;
  item: JsonObject;
}

/**
 * 设备 · 配置区 · 设备常量（ECV）。
 *
 * <p>equipment.constants：S2F13 查询 / S2F15 写入的 ECID 清单，一张表列出，
 * 按行弹窗编辑。主机写 S2F15 后值会写回配置正文并升一个版本；这里改的是
 * 「初始定义」。2026-11-20 起新建/编辑弹窗确认、删除确认都直接保存落盘
 * （constants 段），页面顶部不再有保存/校验/重新载入按钮。</p>
 */
export function ConstantsConfigPanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-constants-config"
      sections={['constants']}
      title="设备常量（ECV）"
    >
      {({ root, edit, save }) => (
        isJsonObject(root.equipment)
          ? <ConstantsTable equipment={root.equipment} edit={edit} onMutated={() => void save()} />
          : <MissingEquipment edit={edit} />
      )}
    </ConfigEditShell>
  );
}

function constantsIn(draft: JsonObject): JsonObject[] {
  const equipment = objectField(draft, 'equipment');
  if (!Array.isArray(equipment.constants)) {
    equipment.constants = [];
  }
  return equipment.constants as JsonObject[];
}

function valueText(item: JsonObject): string {
  const value = numberField(item, 'value');
  return value === undefined ? '—' : String(value);
}

export function ConstantsTable({ equipment, edit, onMutated }: { equipment: JsonObject; edit: Edit; /** 每次改动落进草稿后回调（设备页传 save，模板编辑器不传仍是纯草稿）。 */ onMutated?: () => void }): JSX.Element {
  const raw = Array.isArray(equipment.constants) ? (equipment.constants as unknown[]) : [];
  const rows: ConstantRow[] = raw.flatMap((item, index) => (isJsonObject(item) ? [{ index, item }] : []));
  /** 正在编辑的下标；null = 新建；undefined = 弹窗关闭。 */
  const [editing, setEditing] = useState<number | null | undefined>(undefined);
  const [deleting, setDeleting] = useState<ConstantRow | null>(null);
  const [previewing, setPreviewing] = useState<ConstantRow | null>(null);
  const editingRow = typeof editing === 'number' ? rows.find((row) => row.index === editing) : undefined;

  const columns: Column<ConstantRow>[] = [
    { key: 'id', header: 'ECID', width: '90px', sortValue: (row) => numberField(row.item, 'id') ?? 0, render: (row) => <span className="mono">{numberField(row.item, 'id') ?? '—'}</span> },
    { key: 'name', header: '名称', sortValue: (row) => stringField(row.item, 'name'), render: (row) => stringField(row.item, 'name') || '—' },
    { key: 'type', header: '类型', width: '80px', sortValue: (row) => stringField(row.item, 'type', 'F4'), render: (row) => stringField(row.item, 'type', 'F4') },
    { key: 'value', header: '初始值', width: '100px', sortValue: (row) => numberField(row.item, 'value') ?? 0, render: (row) => valueText(row.item) },
    { key: 'unit', header: '单位', width: '80px', sortValue: (row) => stringField(row.item, 'unit'), render: (row) => stringField(row.item, 'unit') || '—' },
    {
      key: 'source',
      header: '值定义',
      sortValue: (row) => describeSource(row.item),
      render: (row) => (sourceModeOf(row.item) === '' ? '固定为初始值（可被 S2F15 改写）' : describeSource(row.item)),
    },
    {
      key: 'actions',
      header: '操作',
      width: '170px',
      render: (row) => (
        <div className="row-actions">
          <button type="button" onClick={() => setEditing(row.index)}>编辑</button>
          <button
            type="button"
            disabled={sourceModeOf(row.item) === ''}
            title={sourceModeOf(row.item) === '' ? '只有初始值，不随时间变化' : '按当前草稿预览这个常量随时间的变化'}
            onClick={() => setPreviewing(row)}
          >
            预览
          </button>
          <button type="button" className="danger" onClick={() => setDeleting(row)}>删除</button>
        </div>
      ),
    },
  ];

  const blank = (): JsonObject => {
    const items = rows.map((row) => row.item);
    return { id: nextId(items, 'id', 501), name: `ECV_${items.length + 1}`, type: 'F4', value: 0 };
  };

  return (
    <>
      <DataTable
        tableKey="device-constants"
        columns={columns}
        rows={rows}
        rowKey={(row) => String(row.index)}
        searchPlaceholder="搜索名称 / ECID"
        searchText={(row) => `${stringField(row.item, 'name')} ${numberField(row.item, 'id') ?? ''}`}
        emptyText="没有设备常量；没有时主机查询（S2F13）得到空列表、写入（S2F15）被拒绝。点「＋ 新建常量」添加。"
        toolbarEnd={
          <button
            type="button"
            className="primary"
            title="常量可以只给「初始值」（可用 S2F15 写入），也可以配一份值定义让它随时间/周期变化；配了值定义之后 S2F15 会拒写（写死一个随时间变的值没有意义）。变化随工艺周期启停：停止时冻结，启动后从第 0 片起跑。"
            onClick={() => setEditing(null)}
          >
            ＋ 新建常量
          </button>
        }
      />

      {editing !== undefined && (editing === null || editingRow !== undefined) && (
        <ConstantEditModal
          key={editing === null ? 'new' : String(editing)}
          creating={editing === null}
          initial={editingRow?.item ?? blank()}
          others={rows.filter((row) => row.index !== editing).map((row) => row.item)}
          onClose={() => setEditing(undefined)}
          onSubmit={(item) => {
            edit((draft) => {
              const list = constantsIn(draft);
              if (editing === null) {
                list.push(item);
              } else {
                list[editing] = item;
              }
            });
            setEditing(undefined);
            onMutated?.();
          }}
        />
      )}

      {previewing !== null && (
        <SourcePreview
          open
          title={`${numberField(previewing.item, 'id') ?? '?'} · ${stringField(previewing.item, 'name') || 'ECV'}`}
          def={previewing.item}
          onClose={() => setPreviewing(null)}
        />
      )}

      <ConfirmModal
        open={deleting !== null}
        title="删除设备常量"
        danger
        confirmLabel="删除"
        message={deleting === null
          ? ''
          : `确定删除设备常量「${stringField(deleting.item, 'name') || numberField(deleting.item, 'id')}」（ECID ${numberField(deleting.item, 'id') ?? '?'}）？引用它的报告需要另行调整。`}
        onClose={() => setDeleting(null)}
        onConfirm={() => {
          if (deleting !== null) {
            edit((draft) => {
              constantsIn(draft).splice(deleting.index, 1);
            });
            onMutated?.();
          }
          setDeleting(null);
        }}
      />
    </>
  );
}

function ConstantEditModal({ creating, initial, others, onClose, onSubmit }: {
  creating: boolean;
  initial: JsonObject;
  /** 除自己以外的常量，用来查 ECID 重复。 */
  others: JsonObject[];
  onClose: () => void;
  onSubmit: (item: JsonObject) => void;
}): JSX.Element {
  const [previewing, setPreviewing] = useState(false);

  const problemOf = (item: JsonObject): string => {
    if (stringField(item, 'name').trim() === '') {
      return '名称不能为空';
    }
    const id = numberField(item, 'id');
    if (id === undefined) {
      return 'ECID 不能为空';
    }
    if (others.some((other) => numberField(other, 'id') === id)) {
      return `ECID ${id} 已被其他常量使用`;
    }
    if (numberField(item, 'value') === undefined) {
      return '初始值不能为空';
    }
    return '';
  };

  return (
    <ConfigItemModal
      title={creating ? '新建设备常量' : `编辑设备常量 · ${stringField(initial, 'name')}`}
      initial={initial}
      problemOf={problemOf}
      onClose={onClose}
      onSubmit={onSubmit}
    >
      {(item, mutate) => (
        <>
          <div className="session-fields">
            <SessionField label="ECID" help="主机查询（S2F13）/ 写入（S2F15）用的编号，常量之间不能重复">
              <NumberField value={numberField(item, 'id')} integer onCommit={(next) => mutate((target) => { target.id = next; })} />
            </SessionField>
            <SessionField label="名称" help="报告与表达式按这个名字引用">
              <TextField value={stringField(item, 'name')} onCommit={(next) => mutate((target) => { target.name = next; })} />
            </SessionField>
            <SessionField label="类型" help="回给主机的数据类型：F 为浮点，U / I 为无符号 / 有符号整数">
              <select value={stringField(item, 'type', 'F4')} onChange={(event) => mutate((target) => { target.type = event.target.value; })}>
                {SECS_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
              </select>
            </SessionField>
            <SessionField label="初始值" help="设备启动时的值；没配值定义时主机可用 S2F15 改写">
              <NumberField value={numberField(item, 'value')} onCommit={(next) => mutate((target) => { target.value = next; })} />
            </SessionField>
            <SessionField label="单位" help="显示用的量纲，例如 ℃、s；可留空">
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
          </div>

            <SourceEditorLegend />
            <SourceEditor
            def={item}
            mutate={mutate}
            onPreview={sourceModeOf(item) === '' ? undefined : () => setPreviewing(true)}
          />

          {previewing && (
            <SourcePreview
              open
              title={stringField(item, 'name') || 'ECV'}
              def={item}
              onClose={() => setPreviewing(false)}
            />
          )}
        </>
      )}
    </ConfigItemModal>
  );
}
