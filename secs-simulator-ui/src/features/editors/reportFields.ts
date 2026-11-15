/**
 * 报告字段的三类引用：SVID 槽位名 / 设备常量 ecid.<编号> / 晶圆属性 ctx.<名>。
 *
 * <p>界面上三类各占一列（各自下拉多选），写回文档时仍是一个扁平数组：
 * 先 SVID、再设备常量、最后晶圆属性；列内保持勾选先后。这里只做纯函数，
 * 方便单测顺序语义。</p>
 */

export type ReportFieldGroup = 'svid' | 'ecid' | 'ctx';

export function reportFieldGroupOf(value: string): ReportFieldGroup {
  if (value.startsWith('ecid.')) {
    return 'ecid';
  }
  if (value.startsWith('ctx.')) {
    return 'ctx';
  }
  return 'svid';
}

/** 把报告字段按三类拆开；列内保持原文里的先后顺序。 */
export function splitReportFields(values: ReadonlyArray<string>): Record<ReportFieldGroup, string[]> {
  const groups: Record<ReportFieldGroup, string[]> = { svid: [], ecid: [], ctx: [] };
  for (const value of values) {
    groups[reportFieldGroupOf(value)].push(value);
  }
  return groups;
}

/**
 * 某一列改动后合并出整条报告的字段顺序。
 *
 * <p>未改动的列保持原文顺序；改动的列用新顺序（勾选先后）。
 * 原文里三类交错时，第一次编辑会把跨类顺序归一成 SVID → 设备常量 → 晶圆属性。</p>
 */
export function mergeReportFields(
  current: ReadonlyArray<string>,
  group: ReportFieldGroup,
  next: ReadonlyArray<string>,
): string[] {
  const groups = splitReportFields(current);
  groups[group] = [...next];
  return [...groups.svid, ...groups.ecid, ...groups.ctx];
}