/**
 * 表格的纯逻辑：搜索、排序、导出。
 *
 * <p>这些是界面里最容易写错、又最难靠眼睛验证的部分（排序稳定性、
 * 搜索的 AND 语义、CSV 的转义），所以单独抽出来做成纯函数，直接测。</p>
 */

/** 排序方向。 */
export type SortDirection = 'asc' | 'desc';

/** 排序状态；key 是列的 key。 */
export interface SortState {
  key: string;
  direction: SortDirection;
}

/**
 * 比较两个排序值。
 *
 * <p>数字按数值比，其余按字符串比（不区分大小写）。
 * 这样"状态""名称"这类文本列排出来是自然序，
 * 而不是按 ASCII 码把大写字母全甩到前面。</p>
 */
export function compareValues(left: unknown, right: unknown): number {
  if (typeof left === 'number' && typeof right === 'number') {
    return left - right;
  }
  if (typeof left === 'boolean' && typeof right === 'boolean') {
    return Number(left) - Number(right);
  }
  const l = String(left ?? '').toLowerCase();
  const r = String(right ?? '').toLowerCase();
  if (l === r) {
    return 0;
  }
  return l < r ? -1 : 1;
}

/**
 * 按排序状态排行。
 *
 * <p>保持稳定（相等时不动原顺序），
 * 否则每次轮询刷新都会让等值行跳来跳去，眼睛根本跟不上。</p>
 */
export function sortRows<T>(
  rows: readonly T[],
  state: SortState | null,
  accessor: (row: T, key: string) => unknown,
): T[] {
  if (state === null) {
    return [...rows];
  }
  const sign = state.direction === 'asc' ? 1 : -1;
  return [...rows]
    .map((row, index) => ({ row, index }))
    .sort((left, right) => {
      const result = compareValues(accessor(left.row, state.key), accessor(right.row, state.key));
      if (result !== 0) {
        return result * sign;
      }
      return left.index - right.index;
    })
    .map((item) => item.row);
}

/**
 * 按关键字过滤行。
 *
 * <p>多个词按空格分开，按 AND 组合："line 2001" 命中
 * 同时含有 line 和 2001 的行。空格分开是因为设备 ID 和名字
 * 常常要一起搜，OR 语义会捞出一堆不相干的。</p>
 */
export function filterRows<T>(
  rows: readonly T[],
  query: string,
  textOf: (row: T) => string,
): T[] {
  const terms = query
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter((term) => term !== '');
  if (terms.length === 0) {
    return [...rows];
  }
  return rows.filter((row) => {
    const text = textOf(row).toLowerCase();
    return terms.every((term) => text.includes(term));
  });
}

/** CSV 导出用的列定义。 */
export interface CsvColumn<T> {
  header: string;
  value: (row: T) => string | number | undefined;
}

/**
 * 生成 CSV 文本。
 *
 * <p>字段里的引号、逗号、换行都要转义，否则 Excel 打开就是错列。
 * 前面加 BOM：没有 BOM 的话 Excel 会按本地编码猜，中文全变乱码。</p>
 */
export function toCsv<T>(rows: readonly T[], columns: readonly CsvColumn<T>[]): string {
  const escape = (raw: string | number | undefined): string => {
    const text = raw === undefined || raw === null ? '' : String(raw);
    return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  const lines = [columns.map((column) => escape(column.header)).join(',')];
  for (const row of rows) {
    lines.push(columns.map((column) => escape(column.value(row))).join(','));
  }
  return `﻿${lines.join('\r\n')}`;
}

/** 触发一次浏览器下载。 */
export function downloadBlob(filename: string, blob: Blob): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  /* 立刻 revoke 会让部分浏览器还没开始下载就把 URL 释放掉，所以延后一拍 */
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

/** 下载一段文本。 */
export function downloadText(filename: string, text: string, mime = 'text/plain;charset=utf-8'): void {
  downloadBlob(filename, new Blob([text], { type: mime }));
}

/** 把文件名里的非法字符换成下划线。 */
export function safeFilename(name: string): string {
  return name.replace(/[\\/:*?"<>|]+/g, '_').trim();
}
