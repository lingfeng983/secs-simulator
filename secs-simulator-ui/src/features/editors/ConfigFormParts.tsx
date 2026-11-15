import type { ReactNode } from 'react';
import { numberField, type JsonObject } from './templateConfig';

export type Edit = (mutate: (root: JsonObject) => void) => void;

export function SectionHead({ title, children }: { title: string; children?: ReactNode }): JSX.Element {
  return (
    <div className="template-head">
      <h4>{title}</h4>
      {children !== undefined && <div className="template-actions">{children}</div>}
    </div>
  );
}

/**
 * 子表行：一排字段 + 行尾删除按钮。
 *
 * <p>用 CSS grid 而不是 flex 换行——字段一多，flex 换行会让每行的
 * 字段各占一段，列对不齐、右侧还留一大片空白。grid 的列宽由整组行共享，
 * 每一列的输入框左边缘天然对齐。</p>
 */
export function FormRow({ onRemove, children, cols }: {
  onRemove: () => void;
  children: ReactNode;
  /** 列数：决定栅格模板，与表头一致。 */
  cols: 3 | 5 | 7 | 8;
}): JSX.Element {
  return (
    <div className={`form-grid-row form-grid-row--${cols}`}>
      {children}
      <button type="button" className="form-grid-del" onClick={onRemove} title="删除这一行">
        删除
      </button>
    </div>
  );
}

/**
 * 子表表头：与 {@link FormRow} 的列一一对应。
 *
 * <p>列名写在表头而不是每个字段旁边，是为了让"这一列是什么"只说一遍，
 * 行内就只剩控件本身。</p>
 */
export function FormGridHead({ columns }: { columns: string[] }): JSX.Element {
  return (
    <div className={`form-grid-row form-grid-head form-grid-row--${columns.length}`}>
      {columns.map((column) => (
        <span className="form-grid-label" key={column}>{column}</span>
      ))}
      <span className="form-grid-del" aria-hidden="true" />
    </div>
  );
}

/** 数组里最大数值 id + 1；空数组用 fallback。 */
export function nextId(items: readonly JsonObject[], key: string, fallback: number): number {
  let max = fallback - 1;
  for (const item of items) {
    const id = numberField(item, key);
    if (id !== undefined && id > max) {
      max = id;
    }
  }
  return max + 1;
}

/** 单元数组里下一个可用的 offset。 */
function nextOffset(items: readonly JsonObject[]): number {
  return nextId(items, 'offset', 1);
}