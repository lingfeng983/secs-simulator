import type { CatalogCeid } from '../../api';

/**
 * CEID 输入的快选配套:datalist 选项 + 目录 chip 快选。
 *
 * <p>压测卡与采集卡的"填 CEID"长得一样,抽出来共用;
 * 配合 `<input list={datalistId}>` 使用。</p>
 */
export function CeidDatalist({ id, ceids }: { id: string; ceids: readonly CatalogCeid[] }): JSX.Element {
  return (
    <datalist id={id}>
      {ceids.map((choice) => (
        <option key={choice.ceid} value={choice.ceid}>
          {choice.slot}
        </option>
      ))}
    </datalist>
  );
}

/** 目录里的 CEID 快选 chip;点一个把编号交给 onPick。 */
export function CeidChips({
  ceids,
  onPick,
  max = 12,
}: {
  ceids: readonly CatalogCeid[];
  onPick: (ceid: number) => void;
  max?: number;
}): JSX.Element | null {
  if (ceids.length === 0) {
    return null;
  }
  return (
    <div className="chip-row">
      {ceids.slice(0, max).map((choice) => (
        <button key={choice.ceid} type="button" className="chip" title={choice.slot} onClick={() => onPick(choice.ceid)}>
          +{choice.ceid}
        </button>
      ))}
    </div>
  );
}
