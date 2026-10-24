/**
 * 行级 diff（LCS 算法）：比较两段文本，输出按行的增/删/不变。
 *
 * <p>配置文件是 JSON，但用户看的是行：改一个字段就是"一行旧、一行新"。
 * 做字符级或 JSON 树级 diff 都更花哨，也更容易把"语义上的一处改动"
 * 撕成看不懂的碎片。行级 + 上下文折叠是编辑器几十年的标准答案。</p>
 *
 * <p>O(N·M) 的 DP 对配置文件（几百行）足够快；不引入库。</p>
 */

/** 一行 diff 的种类。 */
export type DiffOp = 'same' | 'added' | 'removed';

/** 一行 diff 结果。 */
export interface DiffLine {
  op: DiffOp;
  /** 行号：added 是新文本的行号，removed 是旧文本的行号，same 是新文本的。 */
  lineNumber: number;
  text: string;
}

/**
 * 计算两段文本的行级 diff。
 *
 * @param oldText 旧文本（磁盘上的版本）
 * @param newText 新文本（编辑器里的草稿）
 * @returns 逐行结果，按原文顺序排列
 */
export function diffLines(oldText: string, newText: string): DiffLine[] {
  const oldLines = oldText.split('\n');
  const newLines = newText.split('\n');

  /* LCS 长度表：(old 的前 i 行) 与 (new 的前 j 行) 的最长公共子序列 */
  const rows = oldLines.length;
  const cols = newLines.length;
  const table: number[][] = Array.from(
    { length: rows + 1 },
    () => new Array<number>(cols + 1).fill(0),
  );
  for (let i = rows - 1; i >= 0; i--) {
    for (let j = cols - 1; j >= 0; j--) {
      table[i][j] = oldLines[i] === newLines[j]
        ? table[i + 1][j + 1] + 1
        : Math.max(table[i + 1][j], table[i][j + 1]);
    }
  }

  /* 回溯出操作序列 */
  const result: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < rows && j < cols) {
    if (oldLines[i] === newLines[j]) {
      result.push({ op: 'same', lineNumber: j + 1, text: newLines[j] });
      i += 1;
      j += 1;
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      result.push({ op: 'removed', lineNumber: i + 1, text: oldLines[i] });
      i += 1;
    } else {
      result.push({ op: 'added', lineNumber: j + 1, text: newLines[j] });
      j += 1;
    }
  }
  while (i < rows) {
    result.push({ op: 'removed', lineNumber: i + 1, text: oldLines[i] });
    i += 1;
  }
  while (j < cols) {
    result.push({ op: 'added', lineNumber: j + 1, text: newLines[j] });
    j += 1;
  }
  return result;
}

/**
 * 折叠长段未改动的行：只保留改动前后各 {@link context} 行，中间合并成一个标记。
 *
 * @param lines 完整 diff
 * @param context 上下文行数
 * @returns 带折叠标记的序列（op 为 'same' 的 skip 行数记在 text 里）
 */
export interface FoldedLine {
  op: DiffOp | 'skip';
  /** skip 时空，其余同 DiffLine。 */
  text: string;
  /** skip 时是被折叠的行数，用于显示 "··· 42 行未改动"。 */
  count?: number;
  lineNumber?: number;
}

export function foldUnchanged(lines: readonly DiffLine[], context = 3): FoldedLine[] {
  const keep = new Array<boolean>(lines.length).fill(false);
  lines.forEach((line, index) => {
    if (line.op !== 'same') {
      const from = Math.max(0, index - context);
      const to = Math.min(lines.length - 1, index + context);
      for (let k = from; k <= to; k++) {
        keep[k] = true;
      }
    }
  });

  const out: FoldedLine[] = [];
  let index = 0;
  while (index < lines.length) {
    if (keep[index]) {
      const line = lines[index];
      out.push({ op: line.op, text: line.text, lineNumber: line.lineNumber });
      index += 1;
      continue;
    }
    let end = index;
    while (end < lines.length && !keep[end]) {
      end += 1;
    }
    out.push({ op: 'skip', text: '', count: end - index });
    index = end;
  }
  return out;
}
