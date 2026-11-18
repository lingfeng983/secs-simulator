import { useState } from 'react';
import type { PanelBaseProps } from './types';
import { ConfigEditShell } from './ConfigEditShell';
import { SessionField } from '../editors/ConfigFormEditor';
import { ConfigItemModal } from '../editors/ConfigItemModal';
import { SourceEditor } from '../editors/SourceEditor';
import { SourcePreview } from '../editors/SourcePreview';
import { TextField } from '../../shared/ui/CommitFields';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import {
  defaultSource,
  describeSource,
  isExpressionName,
  isJsonObject,
  SECS_TYPES,
  sourceModeOf,
  stringField,
  type JsonObject,
} from '../editors/templateConfig';

/** 晶圆属性可用的类型：文本（LOTID 之类）或数值（片序、批次序号）。 */
const CONTEXT_TYPES = ['ASCII', ...SECS_TYPES.filter((type) => type !== 'ASCII')];

type Edit = (mutate: (root: JsonObject) => void) => void;

interface ContextRow {
  index: number;
  item: JsonObject;
}

/**
 * 设备 · 配置区 · 晶圆属性（{@code context} 段，schema v7）。
 *
 * <p>LOTID / RCPID / PPID / WAFERID / BATCHID 这类随晶圆走的数据：
 * 它们不是设备状态量，但报告要带上，所以单独成一段。一张表列出，按行弹窗编辑；
 * 取值器与 SVID 共用同一套 source，文本可以直接是字符串。</p>
 *
 * <p>报告里用 {@code ctx.<名>} 引用，例如 {@code ["ctx.LOTID", "ctx.WAFERID", "TEMP"]}；
 * 编译期会检查名字是否存在，写错了在「校验」时就会报。</p>
 */
export function ContextConfigPanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-context-config"
      sections={['context']}
      title="晶圆属性（context）"
    >
      {({ root, edit }) => (
        Array.isArray(root.context) || root.context === undefined
          ? <ContextTable root={root} edit={edit} />
          : <p className="problems">context 必须是数组</p>
      )}
    </ConfigEditShell>
  );
}

function contextIn(draft: JsonObject): JsonObject[] {
  if (!Array.isArray(draft.context)) {
    draft.context = [];
  }
  return draft.context as JsonObject[];
}

export function ContextTable({ root, edit }: { root: JsonObject; edit: Edit }): JSX.Element {
  const raw = Array.isArray(root.context) ? (root.context as unknown[]) : [];
  const rows: ContextRow[] = raw.flatMap((item, index) => (isJsonObject(item) ? [{ index, item }] : []));
  /** 正在编辑的下标；null = 新建；undefined = 弹窗关闭。 */
  const [editing, setEditing] = useState<number | null | undefined>(undefined);
  const [deleting, setDeleting] = useState<ContextRow | null>(null);
  const [previewing, setPreviewing] = useState<ContextRow | null>(null);
  const editingRow = typeof editing === 'number' ? rows.find((row) => row.index === editing) : undefined;

  const columns: Column<ContextRow>[] = [
    { key: 'id', header: '名称', sortValue: (row) => stringField(row.item, 'id'), render: (row) => stringField(row.item, 'id') || '—' },
    { key: 'ref', header: '报告里的写法', width: '160px', render: (row) => <span className="mono">ctx.{stringField(row.item, 'id', '?')}</span> },
    { key: 'type', header: '类型', width: '90px', sortValue: (row) => stringField(row.item, 'type', 'ASCII'), render: (row) => stringField(row.item, 'type', 'ASCII') },
    { key: 'source', header: '值定义', sortValue: (row) => describeSource(row.item), render: (row) => describeSource(row.item) },
    {
      key: 'actions',
      header: '操作',
      width: '170px',
      render: (row) => (
        <div className="row-actions">
          <button type="button" onClick={() => setEditing(row.index)}>编辑</button>
          <button
            type="button"
            disabled={!previewable(row.item)}
            title={previewable(row.item) ? '按当前草稿预览这个属性随片序的变化' : '文本属性没有曲线可看'}
            onClick={() => setPreviewing(row)}
          >
            预览
          </button>
          <button type="button" className="danger" onClick={() => setDeleting(row)}>删除</button>
        </div>
      ),
    },
  ];

  const blank = (): JsonObject => ({
    id: `VAR_${rows.length + 1}`,
    type: 'ASCII',
    source: defaultSource('cycle', true),
  });

  return (
    <>
      <DataTable
        tableKey="device-context"
        columns={columns}
        rows={rows}
        rowKey={(row) => String(row.index)}
        searchPlaceholder="搜索名称"
        searchText={(row) => stringField(row.item, 'id')}
        emptyText="还没有晶圆属性。加一条 LOTID / WAFERID，就能在报告里勾选它，S6F11 会带上「这一片」的批次号与片号。"
        toolbarEnd={
          <button
            type="button"
            className="primary"
            title="「按片变化」的 pattern 用 {03} 表示补零到 3 位的片序，例如 W{03} 生成 W001、W002；every 表示每几片进一档（每 25 片换批号时 every 填 25）。晶圆属性随工艺周期换片：停止时冻结，启动后从第 0 片起跑。"
            onClick={() => setEditing(null)}
          >
            ＋ 新建属性
          </button>
        }
      />

      {editing !== undefined && (editing === null || editingRow !== undefined) && (
        <ContextEditModal
          key={editing === null ? 'new' : String(editing)}
          creating={editing === null}
          initial={editingRow?.item ?? blank()}
          others={rows.filter((row) => row.index !== editing).map((row) => row.item)}
          onClose={() => setEditing(undefined)}
          onSubmit={(item) => {
            edit((draft) => {
              const list = contextIn(draft);
              if (editing === null) {
                list.push(item);
              } else {
                list[editing] = item;
              }
            });
            setEditing(undefined);
          }}
        />
      )}

      {previewing !== null && (
        <SourcePreview
          open
          title={`ctx.${stringField(previewing.item, 'id', '?')}`}
          def={previewing.item}
          onClose={() => setPreviewing(null)}
        />
      )}

      <ConfirmModal
        open={deleting !== null}
        title="删除晶圆属性"
        danger
        confirmLabel="删除"
        message={deleting === null
          ? ''
          : `确定删除晶圆属性「${stringField(deleting.item, 'id')}」？报告里引用 ctx.${stringField(deleting.item, 'id')} 的地方需要另行调整。`}
        onClose={() => setDeleting(null)}
        onConfirm={() => {
          if (deleting !== null) {
            edit((draft) => {
              contextIn(draft).splice(deleting.index, 1);
            });
          }
          setDeleting(null);
        }}
      />
    </>
  );
}

/** 只有数值型且配了值定义的属性才有曲线可看。 */
function previewable(item: JsonObject): boolean {
  return stringField(item, 'type', 'ASCII') !== 'ASCII' && sourceModeOf(item) !== '';
}

function ContextEditModal({ creating, initial, others, onClose, onSubmit }: {
  creating: boolean;
  initial: JsonObject;
  /** 除自己以外的属性，用来查重名。 */
  others: JsonObject[];
  onClose: () => void;
  onSubmit: (item: JsonObject) => void;
}): JSX.Element {
  const [previewing, setPreviewing] = useState(false);

  const problemOf = (item: JsonObject): string => {
    const id = stringField(item, 'id').trim();
    if (id === '') {
      return '名称不能为空，否则报告引用不到它';
    }
    if (!isExpressionName(id)) {
      return '名称只能用字母、数字、下划线，且不能以数字开头';
    }
    if (others.some((other) => stringField(other, 'id') === id)) {
      return `已有名为 ${id} 的属性`;
    }
    return '';
  };

  return (
    <ConfigItemModal
      title={creating ? '新建晶圆属性' : `编辑晶圆属性 · ${stringField(initial, 'id')}`}
      initial={initial}
      problemOf={problemOf}
      onClose={onClose}
      onSubmit={onSubmit}
    >
      {(item, mutate) => {
        const type = stringField(item, 'type', 'ASCII');
        return (
          <>
            <div className="session-form">
              <SessionField label="名称" help={`报告里写作 ctx.${stringField(item, 'id', '名称')}，例如 LOTID、WAFERID`}>
                <TextField value={stringField(item, 'id')} onCommit={(next) => mutate((target) => { target.id = next.trim(); })} />
              </SessionField>
              <SessionField label="类型" help="ASCII 为文本（批号、片号）；数值类型用于片序、批次序号。换类型会重置值定义">
                <select
                  value={type}
                  onChange={(event) => {
                    const next = event.target.value;
                    mutate((target) => {
                      target.type = next;
                      /* 类型一换，旧值定义多半不再成立（数值曲线 vs 文本模板），
                         直接给一份该类型下最常用的默认值。 */
                      target.source = defaultSource('cycle', next === 'ASCII');
                    });
                  }}
                >
                  {CONTEXT_TYPES.map((option) => <option key={option} value={option}>{option}</option>)}
                </select>
              </SessionField>
            </div>

            <p className="sub-title">值定义</p>
            <SourceEditor
              def={item}
              text={type === 'ASCII'}
              mutate={mutate}
              onPreview={previewable(item) ? () => setPreviewing(true) : undefined}
            />

            {previewing && (
              <SourcePreview
                open
                title={`ctx.${stringField(item, 'id', '?')}`}
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
