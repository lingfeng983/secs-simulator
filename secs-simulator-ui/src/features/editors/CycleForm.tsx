import { NumberField } from '../../shared/ui/CommitFields';
import { HintTitle } from '../../shared/ui/FieldLabel';
import {
  collectCeids,
  isJsonObject,
  numberField,
  objectField,
  readObjectArray,
  type JsonObject,
} from './templateConfig';

type Edit = (mutate: (root: JsonObject) => void) => void;

/**
 * 工艺周期（{@code cycle} 段，schema v7）。
 *
 * <p>一个周期 = 一片 wafer 的处理过程。参数曲线、晶圆属性、周期事件共用
 * 这一个时间轴：曲线拿片内相位 {@code [0, duration)}，事件用片内偏移
 * {@code atMs}，重复由本段的 <b>片数 × 抖动</b> 负责。</p>
 *
 * <p>事件在构建期按片展开成普通的时间触发计划——所以这里配 3 条、跑 25 片，
 * 编译出来就是 75 条计划；界面上写清这个乘法，避免有人以为只发 3 条。</p>
 */
export function CycleForm({ root, edit, readOnly = false, onSave, busy }: {
  root: JsonObject;
  edit: Edit;
  /**
   * 只读：按同样的字段把周期定义摆出来，但不给任何输入框与增删按钮。
   * 运行区「工艺周期」页用它显示"这台设备现在跑的是哪个周期"
   * （2026-11-18 用户要求：运行区要看到周期配置，不可编辑）。
   */
  readOnly?: boolean;
  /** 「保存」按钮（配置区设备页传 save；模板编辑器/只读视图不传则不显示）。 */
  onSave?: () => Promise<void> | void;
  busy?: boolean;
}): JSX.Element {
  const cycle = isJsonObject(root.cycle) ? root.cycle : null;
  const ceidChoices = collectCeids(root);
  /* 事件计划里已有计划时，标题说明里追加它与片内事件的分工（原底部提示的展示条件） */
  const hasEventPlans = isJsonObject(root.events)
    && Array.isArray(root.events.plans) && (root.events.plans as unknown[]).length > 0;

  if (cycle === null) {
    return (
      <div className="cfg-section">
        <div className="template-head">
          <h3>工艺周期（cycle）</h3>
          {readOnly
            ? <span className="hint-inline">只读 · 来自已保存的设备配置</span>
            : (
              <button
                type="button"
                onClick={() => edit((draft) => {
                  draft.cycle = {
                    durationMs: 180_000,
                    jitterMs: 0,
                    count: 25,
                    noisePerWafer: false,
                    events: [],
                  };
                })}
              >
                插入 cycle 段
              </button>
            )}
        </div>
        <p className="hint">
          {readOnly
            ? '这份配置没有周期段：设备按绝对时间跑一次，曲线的时间参数就是启动后的秒数。'
            : '没有周期段：设备按绝对时间跑一次，曲线时间参数就是启动后的秒数。插入后，曲线默认按片内相位每片重放，事件按片内偏移重复。'}
        </p>
      </div>
    );
  }

  const events = readObjectArray(cycle, 'events', 'cycle');
  const durationMs = numberField(cycle, 'durationMs') ?? 0;
  const jitterMs = numberField(cycle, 'jitterMs') ?? 0;
  const count = numberField(cycle, 'count') ?? 0;
  const shortest = Math.max(0, durationMs - jitterMs);

  /* 只读分支：与编辑态同一套字段名，值一律是文本，没有按钮可点 */
  if (readOnly) {
    return (
      <div className="cfg-section cfg-readonly">
        <div className="template-head">
          <h3>工艺周期（cycle）</h3>
          <span className="hint-inline">只读 · 来自已保存的设备配置</span>
        </div>
        <table className="effects-table">
          <tbody>
            <tr>
              <th>周期时长</th>
              <td className="mono">{durationMs} ms · {(durationMs / 1000).toFixed(1)} 秒</td>
            </tr>
            <tr>
              <th>每片误差</th>
              <td className="mono">
                {jitterMs > 0 ? `± ${jitterMs} ms` : '0（严格等间隔）'}
              </td>
            </tr>
            <tr>
              <th>片数</th>
              <td className="mono">{count} 片</td>
            </tr>
            <tr>
              <th>噪声</th>
              <td>{cycle.noisePerWafer === true ? '每片不同（随机）' : '每片相同'}</td>
            </tr>
          </tbody>
        </table>

        {typeof events === 'string' ? (
          <p className="problems">cycle.events {events}</p>
        ) : events.length === 0 ? (
          <p className="hint">周期内没有事件：只在片边界重放参数，不上报。</p>
        ) : (
          <>
            <p className="template-label">片内事件（{events.length} 条）</p>
            <table className="effects-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>片内偏移</th>
                  <th>CEID</th>
                  <th>说明</th>
                </tr>
              </thead>
              <tbody>
                {events.map((event, index) => {
                  const atMs = numberField(event, 'atMs') ?? 0;
                  const ceid = numberField(event, 'ceid');
                  return (
                    <tr key={index}>
                      <td className="mono">{index + 1}</td>
                      <td className="mono">{atMs} ms</td>
                      <td className="mono">{ceid ?? '—'}</td>
                      <td className="hint-inline">
                        {shortest > 0 && atMs > shortest ? `超过最短片长 ${shortest} ms，会落在下一片` : '片内发送'}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <p className="hint">
              编译期会按片展开：{count > 0 ? count : '?'} 片 × {events.length} 条 =
              {count > 0 ? ` ${count * events.length} ` : ' ? '}
              条触发计划。同一 CEID 在片内出现多次是允许的（每次都会上报）。
            </p>
          </>
        )}

        {jitterMs >= durationMs && (
          <p className="problems">每片误差必须小于周期时长，否则最短片长 ≤ 0。</p>
        )}
      </div>
    );
  }

  const setNumber = (key: string, value: number) => {
    edit((draft) => {
      objectField(draft, 'cycle')[key] = value;
    });
  };

  return (
    <div className="cfg-section">
      {/* 标题行右侧一行放「+ 周期事件」与「保存」（2026-11-21 用户要求）；
          「移除周期段」按钮已删——删整段去「全量配置」页改原文。 */}
      <div className="template-head">
        <h3>
          <HintTitle text={`时间轴只有一条：曲线用片内相位（每片从 0 重放），事件用片内偏移；时长误差只改变片长，不移动片内事件位置。${hasEventPlans ? '下面的「事件计划」里的周期触发不会自动转成片内事件：片内重复请写在上面这张表，事件计划留给周期外的异常路径（启动一次、条件触发、事件后跟随）。' : ''}`}>
            工艺周期（cycle）
          </HintTitle>
        </h3>
        <div className="template-actions">
          {typeof events !== 'string' && (
            <button
              type="button"
              onClick={() => edit((draft) => {
                const target = objectField(draft, 'cycle');
                const items = Array.isArray(target.events) ? (target.events as JsonObject[]) : [];
                items.push({ atMs: 1_000, ceid: ceidChoices[0]?.svid ?? 0 });
                target.events = items;
              })}
            >
              + 周期事件
            </button>
          )}
          {onSave !== undefined && (
            <button
              type="button"
              className="primary"
              disabled={busy === true}
              title="整份周期定义（时长/片数/片内事件）配置完点一次保存，只提交 cycle 段"
              onClick={() => void onSave()}
            >
              保存
            </button>
          )}
        </div>
      </div>

      {/* 与标题行拉开一段距离（2026-11-21 用户反馈上下太挤） */}
      <div className="form-row" style={{ marginTop: '1.1rem' }}>
        <label className="field field-cell">
          <span className="field-cell-name">周期时长 ms</span>
          <NumberField
            value={numberField(cycle, 'durationMs')}
            integer
            onCommit={(next) => setNumber('durationMs', next)}
          />
        </label>
        <label className="field field-cell">
          <span className="field-cell-name">每片误差 ± ms</span>
          <NumberField
            value={numberField(cycle, 'jitterMs')}
            integer
            onCommit={(next) => setNumber('jitterMs', Math.max(0, next))}
          />
        </label>
        <label className="field field-cell">
          <span className="field-cell-name">片数</span>
          <NumberField
            value={numberField(cycle, 'count')}
            integer
            onCommit={(next) => setNumber('count', Math.max(1, next))}
          />
        </label>
        <label className="check-inline" title="默认每片噪声相同，便于 A/B 与回归">
          <input
            type="checkbox"
            checked={cycle.noisePerWafer === true}
            onChange={(event) => edit((draft) => {
              objectField(draft, 'cycle').noisePerWafer = event.target.checked;
            })}
          />
          每片用不同噪声
        </label>
      </div>

      <datalist id="cfg-cycle-ceid-options">
        {ceidChoices.map((choice) => (
          <option key={choice.svid} value={choice.svid}>
            {choice.owner}.{choice.slot}
          </option>
        ))}
      </datalist>

      {typeof events === 'string' ? (
        <p className="problems">cycle.events {events}</p>
      ) : events.length === 0 ? (
        <p className="hint">周期内没有事件：只在片边界重放参数，不上报。</p>
      ) : (
        <>
          {events.map((event, index) => (
            <div className="form-row" key={index}>
              <label className="field field-cell">
                <span className="field-cell-name">片内偏移 ms</span>
                <NumberField
                  value={numberField(event, 'atMs')}
                  integer
                  onCommit={(next) => edit((draft) => {
                    const list = readObjectArray(objectField(draft, 'cycle'), 'events', 'cycle');
                    if (typeof list !== 'string' && isJsonObject(list[index])) {
                      list[index].atMs = next;
                    }
                  })}
                />
              </label>
              <label className="field field-cell">
                <span className="field-cell-name">CEID</span>
                <NumberField
                  value={numberField(event, 'ceid')}
                  integer
                  list="cfg-cycle-ceid-options"
                  onCommit={(next) => edit((draft) => {
                    const list = readObjectArray(objectField(draft, 'cycle'), 'events', 'cycle');
                    if (typeof list !== 'string' && isJsonObject(list[index])) {
                      list[index].ceid = next;
                    }
                  })}
                />
              </label>
              <span className="field-cell hint-inline">
                {shortest > 0 && numberField(event, 'atMs') !== undefined
                  && (numberField(event, 'atMs') ?? 0) > shortest
                  ? `超过最短片长 ${shortest} ms，会落在下一片`
                  : ''}
              </span>
              <button
                type="button"
                onClick={() => edit((draft) => {
                  const target = objectField(draft, 'cycle');
                  const list = Array.isArray(target.events) ? (target.events as unknown[]) : [];
                  list.splice(index, 1);
                })}
              >
                删除
              </button>
            </div>
          ))}
        </>
      )}

      {jitterMs >= durationMs && (
        <p className="problems">每片误差必须小于周期时长，否则最短片长 ≤ 0。</p>
      )}
    </div>
  );
}
