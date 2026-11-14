import type { PanelBaseProps } from './types';
import { ConfigEditShell, MissingEquipment } from './ConfigEditShell';
import { FormGridHead, FormRow, GlobalSvidsForm, nextId, templateIdsOf, TypeSelect } from '../editors/ConfigFormEditor';
import { SvidSourceEditor } from '../editors/SvidSourceEditors';
import { useParamTemplates } from '../../state/useParamTemplates';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import type { ParamTemplate } from '../../api/paramTemplates';
import { isJsonObject, numberField, readObjectArray, stringField, type JsonObject } from '../editors/templateConfig';

/**
 * 设备 · 配置区 · 变量配置（SVID 定义）。
 *
 * <p>全局 SVID 与各单元 SVID 的定义都在这里改：写入配置草稿，
 * 「校验」→「保存」后相关会话自动重载。值来源（曲线/模板直配）
 * 跟在每行定义下面；「引用模板」合并参数模板库（所有设备共享），
 * 选中库条目即把模板体复制进本配置。</p>
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
      description={
        '状态变量（SVID）的定义清单：全局 SVID 直接用绝对 id，单元 SVID 用 offset 换算\n'
        + '（绝对 id = 段起点 + (单元位置-1) × 步长 + offset）。\n'
        + '每行下面可给这个变量配曲线/模板值来源；改动写入配置草稿，校验保存后生效。'
      }
    >
      {({ root, edit }) => (
        isJsonObject(root.equipment)
          ? (
            <>
              <GlobalSvidsForm
                equipment={root.equipment}
                edit={edit}
                templateIds={templateIdsOf(root)}
                library={paramLibrary.entries}
              />
              <UnitSvidsForms
                root={root}
                edit={edit}
                templateIds={templateIdsOf(root)}
                library={paramLibrary.entries}
              />
            </>
          )
          : <MissingEquipment edit={edit} />
      )}
    </ConfigEditShell>
  );
}

/** 每个单元一张 SVID 子表：offset/slot/type/unit/decimals/min/max。 */
function UnitSvidsForms({ root, edit, templateIds, library }: {
  root: JsonObject;
  edit: (mutate: (root: JsonObject) => void) => void;
  templateIds: string[];
  library?: ParamTemplate[];
}): JSX.Element {
  const equipment = isJsonObject(root.equipment) ? root.equipment : {};
  const idSpace = isJsonObject(equipment.idSpace) ? equipment.idSpace : {};
  const svidBase = numberField(idSpace, 'svidBase') ?? 1000;
  const svidStride = numberField(idSpace, 'svidStride') ?? 100;
  const units = readObjectArray(equipment, 'units', 'equipment');

  if (typeof units === 'string') {
    return <p className="problems">equipment.units {units}</p>;
  }
  if (units.length === 0) {
    return (
      <p className="hint">还没有单元。单元本身（unitId/名称）在「结构」页维护，加好单元后这里才能配单元内变量。</p>
    );
  }

  return (
    <>
      {units.map((unit, index) => (
        <UnitSvidsForm
          key={index}
          unit={unit}
          unitIndex={index}
          edit={edit}
          templateIds={templateIds}
          library={library}
          absoluteBase={svidBase + index * svidStride}
        />
      ))}
    </>
  );
}

function UnitSvidsForm({ unit, unitIndex, edit, templateIds, library, absoluteBase }: {
  unit: JsonObject;
  unitIndex: number;
  edit: (mutate: (root: JsonObject) => void) => void;
  templateIds: string[];
  library?: ParamTemplate[];
  absoluteBase: number;
}): JSX.Element {
  const list = readObjectArray(unit, 'svids', 'unit');
  const unitName = stringField(unit, 'name', `unit${unitIndex + 1}`);

  const mutateUnit = (mutate: (target: JsonObject) => void) => {
    edit((draft) => {
      const equipment = draft.equipment;
      if (!isJsonObject(equipment) || !Array.isArray(equipment.units)) {
        return;
      }
      const target = (equipment.units as unknown[])[unitIndex];
      if (isJsonObject(target)) {
        mutate(target);
      }
    });
  };

  const mutateItem = (index: number, mutate: (target: JsonObject) => void) => {
    mutateUnit((target) => {
      const items = Array.isArray(target.svids) ? (target.svids as JsonObject[]) : [];
      if (isJsonObject(items[index])) {
        mutate(items[index]);
      }
    });
  };

  return (
    <div className="template-card">
      <div className="template-card-head">
        <span className="template-label">单元 {unitName} · SVID（{absoluteBase} 起）</span>
        {typeof list !== 'string' && (
          <button
            type="button"
            onClick={() => mutateUnit((target) => {
              const items = Array.isArray(target.svids) ? (target.svids as JsonObject[]) : [];
              items.push({
                offset: nextId(items, 'offset', 1),
                slot: `SLOT_${items.length + 1}`,
                type: 'F4',
              });
              target.svids = items;
            })}
          >
            + SVID
          </button>
        )}
      </div>
      {typeof list === 'string' ? (
        <p className="problems">{unitName}.svids {list}</p>
      ) : list.length === 0 ? (
        <p className="hint">这个单元还没有 SVID。</p>
      ) : (
        <>
          <FormGridHead columns={['绝对 id', 'offset', '槽位名', '类型', '单位', 'min', 'max']} />
          {list.map((item, index) => {
            const offset = numberField(item, 'offset') ?? 0;
            return (
              <div key={index}>
                <FormRow
                  cols={7}
                  onRemove={() => mutateUnit((target) => {
                    if (Array.isArray(target.svids)) {
                      (target.svids as unknown[]).splice(index, 1);
                    }
                  })}
                >
                  <label className="field field-cell">
                    <span className="field-cell-name">绝对 id</span>
                    <span className="mono">{absoluteBase + offset}</span>
                  </label>
                  <label className="field field-cell">
                    <span className="field-cell-name">offset</span>
                    <NumberField
                      value={numberField(item, 'offset')}
                      integer
                      onCommit={(next) => mutateItem(index, (target) => { target.offset = next; })}
                    />
                  </label>
                  <label className="field field-cell">
                    <span className="field-cell-name">槽位名</span>
                    <TextField
                      value={stringField(item, 'slot')}
                      onCommit={(next) => mutateItem(index, (target) => { target.slot = next; })}
                    />
                  </label>
                  <TypeSelect
                    value={stringField(item, 'type', 'F4')}
                    onCommit={(next) => mutateItem(index, (target) => { target.type = next; })}
                  />
                  <label className="field field-cell">
                    <span className="field-cell-name">单位</span>
                    <TextField
                      allowBlank
                      value={stringField(item, 'unit')}
                      onCommit={(next) => mutateItem(index, (target) => {
                        target.unit = next === '' ? undefined : next;
                      })}
                    />
                  </label>
                  <label className="field field-cell">
                    <span className="field-cell-name">min</span>
                    <NumberField
                      value={numberField(item, 'min')}
                      onCommit={(next) => mutateItem(index, (target) => { target.min = next; })}
                    />
                  </label>
                  <label className="field field-cell">
                    <span className="field-cell-name">max</span>
                    <NumberField
                      value={numberField(item, 'max')}
                      onCommit={(next) => mutateItem(index, (target) => { target.max = next; })}
                    />
                  </label>
                </FormRow>
                <SvidSourceEditor
                  def={item}
                  mutate={(fn) => mutateItem(index, fn)}
                  templateIds={templateIds}
                  absoluteId={absoluteBase + offset}
                  library={library}
                  editRootAndDef={library === undefined ? undefined : (fn) => edit((draft) => {
                    /* 注意 fn 的第一个参数必须是文档根（物化要往根上的 templates 段追加），
                       不能传单元对象——传错会把 templates 写进 unit 里。 */
                    const draftEquipment = isJsonObject(draft.equipment) ? draft.equipment : null;
                    const draftUnits = draftEquipment !== null && Array.isArray(draftEquipment.units)
                      ? draftEquipment.units as unknown[]
                      : [];
                    const draftUnit = isJsonObject(draftUnits[unitIndex]) ? draftUnits[unitIndex] as JsonObject : null;
                    const draftItems = draftUnit !== null && Array.isArray(draftUnit.svids)
                      ? draftUnit.svids as JsonObject[]
                      : [];
                    if (draftUnit !== null && isJsonObject(draftItems[index])) {
                      fn(draft, draftItems[index]);
                    }
                  })}
                />
              </div>
            );
          })}
        </>
      )}
    </div>
  );
}
