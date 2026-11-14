import type { PanelBaseProps } from './types';
import { ConfigEditShell, MissingEquipment } from './ConfigEditShell';
import {
  FormGridHead,
  FormRow,
  GlobalCeidsForm,
  nextId,
} from '../editors/ConfigFormEditor';
import { RefMultiSelect, type RefChoice } from '../editors/RefMultiSelect';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import { isJsonObject, numberField, readObjectArray, stringField, type JsonObject } from '../editors/templateConfig';

/**
 * 设备 · 配置区 · 事件与报告（CEID / RPT 定义）。
 *
 * <p>报告（RPT）引用本单元 SVID 的槽位名，事件（CEID）引用报告名
 * （全局 CEID 引用「单元名.报告名」）。三类定义都在这一页改，
 * 写入配置草稿，「校验」→「保存」后相关会话自动重载。</p>
 */
export function CeidReportConfigPanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-ceid-report-config"
      title="事件与报告（CEID / RPT 定义）"
      description={
        '报告（RPT）= 一组 SVID 槽位名；事件（CEID）= 一组报告名。\n'
        + '单元报告 / 单元事件用 offset 换算绝对 id；全局事件跨单元引用要写「单元名.报告名」。\n'
        + '改动写入配置草稿，「校验」→「保存」后相关会话自动重载。'
      }
    >
      {({ root, edit }) => (
        isJsonObject(root.equipment)
          ? (
            <>
              <GlobalCeidsForm equipment={root.equipment} edit={edit} />
              <UnitForms root={root} edit={edit} />
            </>
          )
          : <MissingEquipment edit={edit} />
      )}
    </ConfigEditShell>
  );
}

function UnitForms({ root, edit }: {
  root: JsonObject;
  edit: (mutate: (root: JsonObject) => void) => void;
}): JSX.Element {
  const equipment = isJsonObject(root.equipment) ? root.equipment : {};
  const units = readObjectArray(equipment, 'units', 'equipment');

  if (typeof units === 'string') {
    return <p className="problems">equipment.units {units}</p>;
  }
  if (units.length === 0) {
    return (
      <p className="hint">还没有单元。单元本身（unitId/名称）在「结构」页维护，加好单元后这里才能配单元内报告与事件。</p>
    );
  }

  return (
    <>
      {units.map((unit, index) => (
        <div key={index} className="cfg-section">
          <UnitReportsForm unit={unit} unitIndex={index} edit={edit} />
          <UnitCeidsForm unit={unit} unitIndex={index} edit={edit} />
        </div>
      ))}
    </>
  );
}

/** 单元内 mutator：按位置定位单元，避免闭包捕获旧文档。 */
function unitMutator(edit: (mutate: (root: JsonObject) => void) => void, unitIndex: number) {
  return (mutate: (target: JsonObject) => void) => {
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
}

function UnitReportsForm({ unit, unitIndex, edit }: {
  unit: JsonObject;
  unitIndex: number;
  edit: (mutate: (root: JsonObject) => void) => void;
}): JSX.Element {
  const list = readObjectArray(unit, 'reports', 'unit');
  const unitName = stringField(unit, 'name', `unit${unitIndex + 1}`);
  const mutateUnit = unitMutator(edit, unitIndex);

  return (
    <div className="template-card">
      <div className="template-card-head">
        <span className="template-label">单元 {unitName} · 报告（RPT）</span>
        {typeof list !== 'string' && (
          <button
            type="button"
            onClick={() => mutateUnit((target) => {
              const items = Array.isArray(target.reports) ? (target.reports as JsonObject[]) : [];
              items.push({
                offset: nextId(items, 'offset', 1),
                slot: `RPT_${items.length + 1}`,
                svids: [],
              });
              target.reports = items;
            })}
          >
            + 报告
          </button>
        )}
      </div>
      {typeof list === 'string' ? (
        <p className="problems">{unitName}.reports {list}</p>
      ) : list.length === 0 ? (
        <p className="hint">这个单元还没有报告。报告引用同单元 SVID 的槽位名。</p>
      ) : (
        <>
          <FormGridHead columns={['offset', '报告名', 'SVID 槽位（勾选引用）']} />
          {list.map((item, index) => (
            <FormRow
              key={index}
              cols={3}
              onRemove={() => mutateUnit((target) => {
                if (Array.isArray(target.reports)) {
                  (target.reports as unknown[]).splice(index, 1);
                }
              })}
            >
              <label className="field field-cell">
                <span className="field-cell-name">offset</span>
                <NumberField
                  value={numberField(item, 'offset')}
                  integer
                  onCommit={(next) => mutateUnit((target) => {
                    const items = Array.isArray(target.reports) ? (target.reports as JsonObject[]) : [];
                    if (isJsonObject(items[index])) {
                      items[index].offset = next;
                    }
                  })}
                />
              </label>
              <label className="field field-cell">
                <span className="field-cell-name">报告名</span>
                <TextField
                  value={stringField(item, 'slot')}
                  onCommit={(next) => mutateUnit((target) => {
                    const items = Array.isArray(target.reports) ? (target.reports as JsonObject[]) : [];
                    if (isJsonObject(items[index])) {
                      items[index].slot = next;
                    }
                  })}
                />
              </label>
              <RefMultiSelect
                choices={unitSvidChoices(unit)}
                values={stringListOf(item, 'svids')}
                onChange={(next) => mutateUnit((target) => {
                  const items = Array.isArray(target.reports) ? (target.reports as JsonObject[]) : [];
                  if (isJsonObject(items[index])) {
                    items[index].svids = next;
                  }
                })}
                emptyHint="这个单元还没有 SVID，先到「变量配置」页添加"
              />
            </FormRow>
          ))}
        </>
      )}
    </div>
  );
}

function UnitCeidsForm({ unit, unitIndex, edit }: {
  unit: JsonObject;
  unitIndex: number;
  edit: (mutate: (root: JsonObject) => void) => void;
}): JSX.Element {
  const list = readObjectArray(unit, 'ceids', 'unit');
  const unitName = stringField(unit, 'name', `unit${unitIndex + 1}`);
  const mutateUnit = unitMutator(edit, unitIndex);

  return (
    <div className="template-card">
      <div className="template-card-head">
        <span className="template-label">单元 {unitName} · 事件（CEID）</span>
        {typeof list !== 'string' && (
          <button
            type="button"
            onClick={() => mutateUnit((target) => {
              const items = Array.isArray(target.ceids) ? (target.ceids as JsonObject[]) : [];
              items.push({
                offset: nextId(items, 'offset', 1),
                slot: `EVENT_${items.length + 1}`,
                reports: [],
              });
              target.ceids = items;
            })}
          >
            + 事件
          </button>
        )}
      </div>
      {typeof list === 'string' ? (
        <p className="problems">{unitName}.ceids {list}</p>
      ) : list.length === 0 ? (
        <p className="hint">这个单元还没有事件。事件引用同单元报告名。</p>
      ) : (
        <>
          <FormGridHead columns={['offset', '事件名', '引用报告（勾选引用）']} />
          {list.map((item, index) => (
            <FormRow
              key={index}
              cols={3}
              onRemove={() => mutateUnit((target) => {
                if (Array.isArray(target.ceids)) {
                  (target.ceids as unknown[]).splice(index, 1);
                }
              })}
            >
              <label className="field field-cell">
                <span className="field-cell-name">offset</span>
                <NumberField
                  value={numberField(item, 'offset')}
                  integer
                  onCommit={(next) => mutateUnit((target) => {
                    const items = Array.isArray(target.ceids) ? (target.ceids as JsonObject[]) : [];
                    if (isJsonObject(items[index])) {
                      items[index].offset = next;
                    }
                  })}
                />
              </label>
              <label className="field field-cell">
                <span className="field-cell-name">事件名</span>
                <TextField
                  value={stringField(item, 'slot')}
                  onCommit={(next) => mutateUnit((target) => {
                    const items = Array.isArray(target.ceids) ? (target.ceids as JsonObject[]) : [];
                    if (isJsonObject(items[index])) {
                      items[index].slot = next;
                    }
                  })}
                />
              </label>
              <RefMultiSelect
                choices={unitReportChoices(unit)}
                values={stringListOf(item, 'reports')}
                onChange={(next) => mutateUnit((target) => {
                  const items = Array.isArray(target.ceids) ? (target.ceids as JsonObject[]) : [];
                  if (isJsonObject(items[index])) {
                    items[index].reports = next;
                  }
                })}
                emptyHint="这个单元还没有报告，先在上方「+ 报告」添加"
              />
            </FormRow>
          ))}
        </>
      )}
    </div>
  );
}

function stringListOf(item: JsonObject, key: string): string[] {
  const value = item[key];
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

/** 本单元的 SVID 槽位名清单（报告引用的候选）。 */
function unitSvidChoices(unit: JsonObject): RefChoice[] {
  const list = readObjectArray(unit, 'svids', 'unit');
  if (typeof list === 'string') {
    return [];
  }
  return list
    .map((item) => {
      const slot = stringField(item, 'slot');
      return { value: slot, label: slot === '' ? `offset ${numberField(item, 'offset') ?? '?'}` : slot };
    })
    .filter((choice) => choice.value !== '');
}

/** 本单元的报告名清单（事件引用的候选）。 */
function unitReportChoices(unit: JsonObject): RefChoice[] {
  const list = readObjectArray(unit, 'reports', 'unit');
  if (typeof list === 'string') {
    return [];
  }
  return list
    .map((item) => stringField(item, 'slot'))
    .filter((slot) => slot !== '')
    .map((slot) => ({ value: slot }));
}
