import type { CatalogSvid } from '../../../api';

/**
 * SVID 多选清单：S6F1 采集与 S1F3 采集共用同一套排版。
 *
 * <p>用紧凑网格而不是横向 flex：变量多时每行排满、左对齐，
 * 不会出现一大片空白，也不会把后面的按钮挤到看不见。</p>
 */
export function SvidCheckList({
  choices,
  selected,
  onChange,
  max,
  emptyText = '设备目录里还没有 SVID。',
}: {
  choices: readonly CatalogSvid[];
  selected: readonly number[];
  onChange: (next: number[]) => void;
  /**
   * 前端一次最多勾几个；{@code undefined} = 不限制。
   *
   * <p>主机模拟的采集页传 {@code undefined}：真正的容量上限在设备侧
   * （Trace 看 {@code session.trace.maxSvidCount}，S1F3 采集器看自己的 256），
   * 前端再截一刀只会让用户以为是界面坏了。</p>
   */
  max?: number;
  emptyText?: string;
}): JSX.Element {
  if (choices.length === 0) {
    return <p className="hint">{emptyText}</p>;
  }

  const cap = max === undefined ? Number.POSITIVE_INFINITY : max;

  return (
    <div className="svid-check-grid">
      {choices.map((item) => {
        const checked = selected.includes(item.svid);
        const disabled = !checked && selected.length >= cap;
        return (
          <label
            key={item.svid}
            className={`check-inline svid-check${disabled ? ' disabled' : ''}`}
            title={`${item.owner}.${item.slot}${item.unit === undefined ? '' : ` (${item.unit})`}`}
          >
            <input
              type="checkbox"
              checked={checked}
              disabled={disabled}
              onChange={(event) => {
                if (event.target.checked) {
                  /* 不限数量时不要走 slice(0, Infinity)：虽然结果一样，
                     但读代码的人会以为这里有截断 */
                  onChange(max === undefined
                    ? [...selected, item.svid]
                    : [...selected, item.svid].slice(0, max));
                } else {
                  onChange(selected.filter((id) => id !== item.svid));
                }
              }}
            />
            <span className="mono">{item.svid}</span>
            <span className="svid-check-name">{item.slot || item.owner}</span>
          </label>
        );
      })}
    </div>
  );
}
