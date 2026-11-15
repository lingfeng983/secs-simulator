import { describeSource, isJsonObject, type JsonObject } from './templateConfig';
import type { ParamTemplate } from '../../api/paramTemplates';

/**
 * 参数模板库 ↔ 设备配置的复制语义（schema v7）。
 *
 * <p>库模板体就是 <b>一段 source</b>：</p>
 * <pre>{ "source": { "mode": "curve", "clock": "phase", "curve": {...} } }</pre>
 *
 * <p>没有曲线 id、没有组合、没有实例、没有 result——「组合模板」在库里是
 * 一条带私有 vars 的 {@code expr}。复制到某个数据项时做一次<b>深拷贝</b>
 * 并直接当成该数据项的 {@code source}：拷完即独立，改库不影响设备，
 * 设备里也不存在任何指向库的引用。</p>
 */

/** 库条目的 source；形状不对返回 undefined。 */
export function templateSourceOf(entry: ParamTemplate): JsonObject | undefined {
  const source = entry.body.source;
  return isJsonObject(source) ? source : undefined;
}

/** 库条目的形态描述（列表「形式」列与预览标题用）；无 source 返回空串。 */
export function templateShapeOf(entry: ParamTemplate): string {
  const source = templateSourceOf(entry);
  if (source === undefined) {
    return '';
  }
  return describeSource({ source });
}

/** 深拷贝一份可直接挂到数据项上的 source；没有 source 返回 undefined。 */
export function copyTemplateSource(entry: ParamTemplate): JsonObject | undefined {
  const source = templateSourceOf(entry);
  if (source === undefined) {
    return undefined;
  }
  return JSON.parse(JSON.stringify(source)) as JsonObject;
}
