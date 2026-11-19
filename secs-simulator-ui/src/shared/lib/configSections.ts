import type { ConfigSectionName } from '../../api';
import { parseConfigDocument, type JsonObject } from '../../features/editors/templateConfig';

/**
 * 页面 → 配置段 → 提交片段。
 *
 * <p>「按页保存」（2026-11-18）的前端一半：每个配置页声明自己拥有哪几段，
 * 保存时只把这些段从草稿里切出来提交，后端按段替换。这样在「工艺周期」保存
 * 不会把「事件计划」页没保存的半成品一起写进文件，反过来也一样。</p>
 *
 * <p>切片形状必须与后端 {@code ConfigSectionPatcher} 对齐：
 * 变量/报告/事件是 {@code {units:[{unitId, <字段>}]}}，结构与连接是整段对象，
 * 计划与发送口径从 {@code events} 里切。</p>
 */
export const CONFIG_SECTIONS: Record<ConfigSectionName, { label: string; effect: 'reconnect' | 'hot-reset' | 'hot' }> = {
  session: { label: '连接参数', effect: 'reconnect' },
  structure: { label: '设备结构', effect: 'hot-reset' },
  svids: { label: '变量定义', effect: 'hot-reset' },
  reports: { label: '报告定义', effect: 'hot-reset' },
  ceids: { label: '事件定义', effect: 'hot-reset' },
  constants: { label: '设备常量', effect: 'hot' },
  context: { label: '晶圆属性', effect: 'hot' },
  cycle: { label: '工艺周期', effect: 'hot' },
  plans: { label: '事件计划', effect: 'hot' },
  throttle: { label: '事件发送', effect: 'hot' },
};

/** 全部段（声明顺序 = 后端 ConfigSection 的顺序，比较与提示都按它来）。 */
export const CONFIG_SECTION_NAMES: readonly ConfigSectionName[] = [
  'session', 'structure', 'svids', 'reports', 'ceids',
  'constants', 'context', 'cycle', 'plans', 'throttle',
];

/**
 * 页面 → 拥有的段。
 *
 * <p>配置区每个页签对应哪几段只在这里写一次：面板声明它、页签上给未保存
 * 改动点小圆点，都用这一份。`document`（配置原文）拥有整份，不在此表里。</p>
 */
export const PAGE_SECTIONS: Readonly<Record<string, readonly ConfigSectionName[]>> = {
  connection: ['session'],
  structure: ['structure'],
  svids: ['svids'],
  context: ['context'],
  events: ['ceids', 'reports'],
  cycle: ['cycle'],
  plans: ['plans', 'throttle'],
  constants: ['constants'],
};

/** 段名 → 中文名（提示用）。 */
export function sectionLabel(name: ConfigSectionName): string {
  return CONFIG_SECTIONS[name]?.label ?? name;
}

/** 一组段 → 「工艺周期、事件计划」这样的中文列举。 */
export function sectionLabels(names: readonly ConfigSectionName[]): string {
  return names.map(sectionLabel).join('、');
}

/**
 * 规范 JSON：对象键排序、数组保序。
 *
 * <p>用来比较"这一段到底变没变"。只用于本地比较，不发给后端——
 * 后端有自己的一份（{@code ConfigSectionPatcher.canonical}）。</p>
 */
export function canonicalJson(value: unknown): string {
  return JSON.stringify(sortValue(value));
}

function sortValue(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortValue);
  }
  if (value !== null && typeof value === 'object') {
    const source = value as Record<string, unknown>;
    const out: Record<string, unknown> = {};
    for (const key of Object.keys(source).sort()) {
      if (source[key] !== undefined) {
        out[key] = sortValue(source[key]);
      }
    }
    return out;
  }
  return value === undefined ? null : value;
}

function objectOf(value: unknown): JsonObject | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as JsonObject)
    : null;
}

function arrayOf(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

/** 每个单元的某个字段：{@code [{unitId, <field>}, ...]}。 */
function unitField(root: JsonObject, field: 'svids' | 'reports' | 'ceids'): unknown[] {
  return arrayOf(objectOf(root.equipment)?.units)
    .map(objectOf)
    .filter((unit): unit is JsonObject => unit !== null)
    .map((unit) => ({ unitId: unit.unitId, [field]: arrayOf(unit[field]) }));
}

/**
 * 从整份草稿里切出这些段的提交片段。
 *
 * <p>缺段时给 {@code null}/空数组而不是省略键——保存「删掉周期段」也是一次
 * 合法修改；省略键在后端会被当成"没提交这一段"。</p>
 */
export function sectionPayload(
  sections: readonly ConfigSectionName[],
  root: JsonObject,
): Partial<Record<ConfigSectionName, unknown>> {
  const payload: Partial<Record<ConfigSectionName, unknown>> = {};
  const equipment = objectOf(root.equipment);
  const events = objectOf(root.events);

  for (const section of sections) {
    switch (section) {
      case 'session':
        payload.session = objectOf(root.session) ?? {};
        break;
      case 'structure': {
        const units = arrayOf(equipment?.units)
          .map(objectOf)
          .filter((unit): unit is JsonObject => unit !== null)
          .map((unit) => ({
            unitId: unit.unitId,
            name: unit.name,
            injectUnitId: unit.injectUnitId,
          }));
        payload.structure = { name: equipment?.name, idSpace: equipment?.idSpace, units };
        break;
      }
      case 'svids':
        payload.svids = { units: unitField(root, 'svids'), globalSvids: equipment?.globalSvids };
        break;
      case 'reports':
        payload.reports = { units: unitField(root, 'reports') };
        break;
      case 'ceids':
        payload.ceids = { units: unitField(root, 'ceids') };
        break;
      case 'constants':
        payload.constants = equipment?.constants ?? [];
        break;
      case 'context':
        payload.context = root.context ?? [];
        break;
      case 'cycle':
        payload.cycle = root.cycle ?? null;
        break;
      case 'plans':
        payload.plans = arrayOf(events?.plans);
        break;
      case 'throttle':
        payload.throttle = {
          maxPerSec: events?.maxPerSec,
          s6f11Wbit: events?.s6f11Wbit,
          s6f1Wbit: events?.s6f1Wbit,
        };
        break;
      default:
        break;
    }
  }
  return payload;
}

/**
 * 运行态重置项 → 一句人话。
 *
 * <p>保存成功后页面要能说清"刚发生了什么"：周期被停了、钉值失效了几个。
 * 没变化就不写（右下角已经有「已保存」的 toast）。</p>
 */
export function effectNote(
  effect: 'reconnect' | 'hot-reset' | 'hot',
  reset: readonly string[],
  pinsDropped: number,
  plansKept: number,
): string {
  const notes: string[] = [];
  if (effect === 'reconnect') {
    notes.push('连接参数变了：设备已重建，主机需重新连接，运行状态已全部清空');
  }
  if (reset.includes('cycle')) {
    notes.push('工艺周期已停止，请到运行区重新启动周期');
  }
  if (plansKept > 0) {
    notes.push(`事件计划已更新：${plansKept} 条未改动的计划保持运行`);
  } else if (reset.includes('plans') && effect === 'hot-reset') {
    notes.push('设备模型变了：事件计划与手动设定的变量已重置，需要重新启动计划');
  }
  if (pinsDropped > 0) {
    notes.push(`有 ${pinsDropped} 个手动设定的变量因被删而失效`);
  }
  return notes.join('；');
}

/** 一份配置里所有段的规范值（载荷形状与提交时完全一致）。 */
export function allSectionCanonicals(root: JsonObject): Record<ConfigSectionName, string> {
  const out = {} as Record<ConfigSectionName, string>;
  for (const section of CONFIG_SECTION_NAMES) {
    out[section] = canonicalJson(sectionPayload([section], root)[section]);
  }
  return out;
}

/** 空基线：解析不了原文时的兜底（每段都是 null）。 */
export function emptySectionCanonicals(): Record<ConfigSectionName, string> {
  const out = {} as Record<ConfigSectionName, string>;
  for (const section of CONFIG_SECTION_NAMES) {
    out[section] = 'null';
  }
  return out;
}

/**
 * 从配置原文算出所有段的规范值。
 *
 * <p>原文不是合法 JSON 时返回 {@code ok:false}：这时候没法按段判断脏，
 * 调用方退回"全文对比"。</p>
 */
export function sectionsFromText(text: string): {
  ok: boolean;
  canonicals: Record<ConfigSectionName, string>;
} {
  const parsed = parseConfigDocument(text);
  if (!parsed.ok) {
    return { ok: false, canonicals: emptySectionCanonicals() };
  }
  return { ok: true, canonicals: allSectionCanonicals(parsed.config.root) };
}

/** 某几段里，当前值与基线不同的那些（保持段声明顺序）。 */
export function changedSections(
  current: Record<ConfigSectionName, string>,
  baseline: Record<ConfigSectionName, string>,
  scope: readonly ConfigSectionName[] = CONFIG_SECTION_NAMES,
): ConfigSectionName[] {
  return CONFIG_SECTION_NAMES.filter(
    (section) => scope.includes(section) && current[section] !== baseline[section],
  );
}
