/** Schema v5 模板段的前端结构化编辑模型。
 *
 * <p>这里刻意不 import 任何 React：解析、读取、写回都是纯函数，方便单测。
 * 原文始终是唯一事实来源——结构化编辑只改内存里的 JSON 对象，
 * 再把整份文档写回文本。未知键会保留，但缩进与空格会被统一。</p>
 */

export type JsonObject = Record<string, unknown>;

export const CURVE_TYPES = [
  { type: 'constant', label: '常量' },
  { type: 'ramp', label: '斜坡' },
  { type: 'sine', label: '正弦' },
  { type: 'step', label: '阶跃序列' },
  { type: 'pwl', label: '折线关键帧' },
  { type: 'uniform', label: '区间随机' },
  { type: 'randomWalk', label: '有界随机游走' },
] as const;

export type CurveType = (typeof CURVE_TYPES)[number]['type'];

export type CurveField =
  | { key: string; label: string; kind: 'number'; integer?: boolean; step?: number }
  | { key: string; label: string; kind: 'boolean' }
  | { key: string; label: string; kind: 'select'; options: readonly string[] };

const NUMBER = (key: string, label: string, integer = false, step?: number): CurveField =>
  integer ? { key, label, kind: 'number', integer: true } : { key, label, kind: 'number', step };
const BOOLEAN = (key: string, label: string): CurveField => ({ key, label, kind: 'boolean' });
const SELECT = (key: string, label: string, options: readonly string[]): CurveField =>
  ({ key, label, kind: 'select', options });

/** 每种曲线的标量字段；step / pwl 的关键帧单独渲染。 */
export function curveFieldSpecs(type: CurveType): CurveField[] {
  switch (type) {
    case 'constant':
      return [NUMBER('value', '值')];
    case 'ramp':
      return [NUMBER('from', '起点'), NUMBER('to', '终点'), NUMBER('durationMs', '时长 ms', true), BOOLEAN('cyclic', '循环')];
    case 'sine':
      return [NUMBER('min', '最小值'), NUMBER('max', '最大值'), NUMBER('periodMs', '周期 ms', true), NUMBER('phaseMs', '相位 ms', true)];
    case 'uniform':
      return [NUMBER('min', '最小值'), NUMBER('max', '最大值'), NUMBER('resampleMs', '重采样 ms', true)];
    case 'randomWalk':
      return [
        NUMBER('start', '起点'),
        NUMBER('driftPerSec', '漂移/秒'),
        NUMBER('sigma', '波动'),
        NUMBER('gridMs', '网格 ms', true),
        NUMBER('octaves', '八度数', true),
        NUMBER('min', '下界'),
        NUMBER('max', '上界'),
        SELECT('policy', '越界策略', ['CLAMP', 'REFLECT', 'WRAP']),
      ];
    case 'step':
    case 'pwl':
      return [];
  }
}

/** 一种曲线的默认字段（不含 id）。 */
export function defaultCurveFields(type: CurveType): JsonObject {
  switch (type) {
    case 'constant':
      return { type, value: 0 };
    case 'ramp':
      return { type, from: 0, to: 100, durationMs: 60_000, cyclic: true };
    case 'sine':
      return { type, min: 0, max: 100, periodMs: 60_000, phaseMs: 0 };
    case 'step':
      return { type, steps: [{ fromMs: 0, value: 0 }, { fromMs: 30_000, value: 100 }] };
    case 'pwl':
      return { type, points: [{ tMs: 0, value: 0 }, { tMs: 30_000, value: 100 }, { tMs: 60_000, value: 0 }] };
    case 'uniform':
      return { type, min: 0, max: 100, resampleMs: 1_000 };
    case 'randomWalk':
      return {
        type,
        start: 50,
        driftPerSec: 0,
        sigma: 1,
        gridMs: 1_000,
        octaves: 1,
        min: 0,
        max: 100,
        policy: 'REFLECT',
      };
  }
}

export function isCurveType(value: unknown): value is CurveType {
  return typeof value === 'string' && CURVE_TYPES.some((item) => item.type === value);
}

/**
 * 这个名字能否安全地出现在表达式里（`in.<实例>.<输出>` / `p.<参数>`）。
 *
 * <p>后端表达式把标识符限制为字母 / 数字 / 下划线；连字符会被当成减法。
 * 模板 id 不进表达式，所以这里只用于实例 id、输出名和参数名。</p>
 */
export function isExpressionName(value: string): boolean {
  return /^[\p{L}_][\p{L}\p{N}_]*$/u.test(value);
}

export function curveTypeLabel(type: CurveType): string {
  return CURVE_TYPES.find((item) => item.type === type)?.label ?? type;
}

export interface SvidChoice {
  svid: number;
  owner: string;
  slot: string;
}

export interface TemplateView {
  present: boolean;
  /** 这几个数组里的对象仍是文档树里的引用，只读渲染，改动必须经过 editTemplates。 */
  curves: JsonObject[];
  composites: JsonObject[];
  instances: JsonObject[];
}

export interface ParsedConfig {
  root: JsonObject;
  svids: SvidChoice[];
}

export type ParseConfigResult = { ok: true; config: ParsedConfig } | { ok: false; error: string };
export type ReadTemplatesResult = { ok: true; templates: TemplateView } | { ok: false; error: string };
export type EditTemplatesResult = { ok: true; text: string } | { ok: false; error: string };

/** 后端 ValueType 枚举接受的全部类型（secs-core ValueType）。 */
export const SECS_TYPES = ['U1', 'U2', 'U4', 'U8', 'I1', 'I2', 'I4', 'I8', 'F4', 'F8', 'ASCII', 'BOOLEAN'] as const;

/** 事件计划的触发类型（docs/guides/schema-v4.md）。 */
export const TRIGGER_TYPES = ['at', 'periodic', 'onCondition', 'afterEvent'] as const;
export type TriggerType = (typeof TRIGGER_TYPES)[number];

/** 事件效果的 op（schema v6，docs/guides/redesign-device-workbench.md §1.1）。 */
export const EFFECT_OPS = ['set', 'add', 'pulse', 'shift', 'resume'] as const;
export type EffectOp = (typeof EFFECT_OPS)[number];

export function isEffectOp(value: unknown): value is EffectOp {
  return typeof value === 'string' && (EFFECT_OPS as readonly string[]).includes(value);
}

/** 某个 op 的参数字段；set 的 decayMs 可省略。 */
export function effectFieldSpecs(op: EffectOp): { key: string; label: string; optional?: boolean }[] {
  switch (op) {
    case 'set':
      return [{ key: 'value', label: '值' }, { key: 'decayMs', label: '衰减 ms', optional: true }];
    case 'add':
    case 'shift':
      return [{ key: 'delta', label: '增量' }];
    case 'pulse':
      return [{ key: 'value', label: '值' }, { key: 'holdMs', label: '保持 ms' }];
    case 'resume':
      return [];
  }
}

/** 一种 op 的默认效果对象。 */
export function defaultEffect(op: EffectOp, svid: number): JsonObject {
  switch (op) {
    case 'set':
      return { svid, op, value: 0 };
    case 'add':
    case 'shift':
      return { svid, op, delta: 0 };
    case 'pulse':
      return { svid, op, value: 0, holdMs: 5_000 };
    case 'resume':
      return { svid, op };
  }
}

/** SVID 直配值来源形态（schema v6；default = 默认随机游走）。 */
export type SourceKind = 'default' | 'curve' | 'template';

export function svidSourceKind(def: JsonObject): SourceKind {
  if (isJsonObject(def.curve)) {
    return 'curve';
  }
  if (typeof def.template === 'string') {
    return 'template';
  }
  return 'default';
}

export function isJsonObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** 读取字段；不是对象时返回空对象（仅用于渲染，不用于写回）。 */
export function objectField(value: JsonObject, key: string): JsonObject {
  const child = value[key];
  return isJsonObject(child) ? child : {};
}

/** 读取或创建一个可写子对象。 */
export function ensureObjectField(value: JsonObject, key: string): JsonObject {
  const child = value[key];
  if (isJsonObject(child)) {
    return child;
  }
  const created: JsonObject = {};
  value[key] = created;
  return created;
}

export function arrayField(value: JsonObject, key: string): unknown[] {
  const child = value[key];
  return Array.isArray(child) ? child : [];
}

export function stringField(value: JsonObject, key: string, fallback = ''): string {
  const child = value[key];
  return typeof child === 'string' ? child : fallback;
}

export function numberField(value: JsonObject, key: string): number | undefined {
  const child = value[key];
  return typeof child === 'number' && Number.isFinite(child) ? child : undefined;
}

export function booleanField(value: JsonObject, key: string, fallback = false): boolean {
  const child = value[key];
  return typeof child === 'boolean' ? child : fallback;
}

/** 解析配置原文；只校验 JSON 与根对象，templates 的形状由 readTemplates 校验。 */
export function parseConfigDocument(text: string): ParseConfigResult {
  /* Windows 上保存的配置常带 BOM；JsonHub 接受它，JSON.parse 不接受。
     不剥掉的话，后端能读的文件在表单里会变成"非法 JSON"。 */
  const source = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  let value: unknown;
  try {
    value = JSON.parse(source) as unknown;
  } catch (error) {
    return { ok: false, error: (error as Error).message };
  }
  if (!isJsonObject(value)) {
    return { ok: false, error: '配置根必须是 JSON 对象' };
  }
  return { ok: true, config: { root: value, svids: collectSvids(value) } };
}

/**
 * 读取 templates 段。
 *
 * <p>形状不对时明确报字段路径，而不是让 React 在渲染中途抛 "map is not a function"。
 * </p>
 */
export function readTemplates(root: JsonObject): ReadTemplatesResult {
  const raw = root.templates;
  if (raw === undefined) {
    return { ok: true, templates: { present: false, curves: [], composites: [], instances: [] } };
  }
  if (!isJsonObject(raw)) {
    return { ok: false, error: 'templates 必须是对象' };
  }

  const curves = readObjectArray(raw, 'curves', 'templates');
  if (typeof curves === 'string') {
    return { ok: false, error: curves };
  }
  const composites = readObjectArray(raw, 'composites', 'templates');
  if (typeof composites === 'string') {
    return { ok: false, error: composites };
  }
  const instances = readObjectArray(raw, 'instances', 'templates');
  if (typeof instances === 'string') {
    return { ok: false, error: instances };
  }

  for (const [index, composite] of composites.entries()) {
    if (composite.outputs !== undefined && !isJsonObject(composite.outputs)) {
      return { ok: false, error: `templates.composites[${index}].outputs 必须是对象` };
    }
  }
  for (const [index, instance] of instances.entries()) {
    if (instance.svids !== undefined && !isJsonObject(instance.svids)) {
      return { ok: false, error: `templates.instances[${index}].svids 必须是对象` };
    }
    if (instance.params !== undefined && !isJsonObject(instance.params)) {
      return { ok: false, error: `templates.instances[${index}].params 必须是对象` };
    }
  }

  return { ok: true, templates: { present: true, curves, composites, instances } };
}

function readObjectArray(parent: JsonObject, key: string, path: string): JsonObject[] | string {
  const value = parent[key];
  if (value === undefined) {
    return [];
  }
  if (!Array.isArray(value)) {
    return `${path}.${key} 必须是数组`;
  }
  /* 必须返回原数组本身：编辑函数会往这个数组里 push，复制一份等于改动丢失 */
  for (const [index, item] of value.entries()) {
    if (!isJsonObject(item)) {
      return `${path}.${key}[${index}] 必须是对象`;
    }
  }
  return value as JsonObject[];
}

export { readObjectArray };

/** 把整份文档写回文本。结构化编辑会统一成 2 空格缩进。 */
export function stringifyConfig(root: JsonObject): string {
  return `${JSON.stringify(root, null, 2)}
`;
}

/**
 * 在原文之上做一次模板编辑。
 *
 * <p>编辑函数拿到的是<b>克隆后</b>文档里的 templates 视图；原文本不变时不会写回。
 * 没有 templates 段时自动插入空的 curves/composites/instances。
 * </p>
 */
export function editTemplates(
  text: string,
  mutate: (templates: TemplateView, root: JsonObject) => void,
): EditTemplatesResult {
  const parsed = parseConfigDocument(text);
  if (!parsed.ok) {
    return parsed;
  }

  const root = cloneJson(parsed.config.root);
  const initial = readTemplates(root);
  if (!initial.ok) {
    return initial;
  }

  /* 缺省数组要先落到文档里：readTemplates 返回的空数组只是视图，
     push 进去不会写回文档。缺失时补空数组，已有的数组保持原引用。 */
  const section = ensureObjectField(root, 'templates');
  for (const key of ['curves', 'composites', 'instances'] as const) {
    if (section[key] === undefined) {
      section[key] = [];
    }
  }
  const read = readTemplates(root);
  if (!read.ok) {
    return read;
  }
  if (!read.templates.present) {
    return { ok: false, error: 'templates 段插入失败' };
  }

  try {
    mutate(read.templates, root);
  } catch (error) {
    return { ok: false, error: (error as Error).message };
  }
  return { ok: true, text: stringifyConfig(root) };
}

function cloneJson(root: JsonObject): JsonObject {
  return JSON.parse(JSON.stringify(root)) as JsonObject;
}

/**
 * 在原文之上做一次<b>整文档</b>编辑（session / equipment / events 等任意段）。
 *
 * <p>与 {@link editTemplates} 同一套纪律：编辑函数拿到克隆后的根对象，
 * 异常转成错误文案，未知键保留，写回统一 2 空格缩进。</p>
 */
export function editDocument(text: string, mutate: (root: JsonObject) => void): EditTemplatesResult {
  const parsed = parseConfigDocument(text);
  if (!parsed.ok) {
    return parsed;
  }
  const root = cloneJson(parsed.config.root);
  try {
    mutate(root);
  } catch (error) {
    return { ok: false, error: (error as Error).message };
  }
  return { ok: true, text: stringifyConfig(root) };
}

/** 生成不与 existing 冲突的 id。 */
export function uniqueId(existing: ReadonlyArray<unknown>, base: string): string {
  const used = new Set(existing.map((item) => String(item)));
  if (!used.has(base)) {
    return base;
  }
  let index = 2;
  while (used.has(`${base}${index}`)) {
    index += 1;
  }
  return `${base}${index}`;
}

/** 一个模板的输出名；曲线固定只有 V。 */
export function templateOutputs(view: TemplateView, templateId: string): string[] {
  const composite = view.composites.find((item) => stringField(item, 'id') === templateId);
  if (composite !== undefined) {
    return Object.keys(objectField(composite, 'outputs'));
  }
  if (view.curves.some((item) => stringField(item, 'id') === templateId)) {
    return ['V'];
  }
  return [];
}

/** 当前配置里的 SVID 候选（按单元号段 + offset 解析，含 globalSvids）。 */
export function collectSvids(root: JsonObject): SvidChoice[] {
  const equipment = objectField(root, 'equipment');
  const idSpace = objectField(equipment, 'idSpace');
  const svidBase = numberField(idSpace, 'svidBase') ?? 1000;
  const svidStride = numberField(idSpace, 'svidStride') ?? 100;

  const choices: SvidChoice[] = [];
  const units = arrayField(equipment, 'units');
  for (const [index, rawUnit] of units.entries()) {
    if (!isJsonObject(rawUnit)) {
      continue;
    }
    const owner = stringField(rawUnit, 'name', `unit${index + 1}`);
    const base = svidBase + index * svidStride;
    for (const rawDef of arrayField(rawUnit, 'svids')) {
      if (!isJsonObject(rawDef)) {
        continue;
      }
      const offset = numberField(rawDef, 'offset');
      if (offset === undefined) {
        continue;
      }
      choices.push({ svid: base + offset, owner, slot: stringField(rawDef, 'slot') });
    }
  }

  for (const rawDef of arrayField(equipment, 'globalSvids')) {
    if (!isJsonObject(rawDef)) {
      continue;
    }
    const id = numberField(rawDef, 'id');
    if (id !== undefined) {
      choices.push({ svid: id, owner: 'GLOBAL', slot: stringField(rawDef, 'name') });
    }
  }

  const unique = new Map<number, SvidChoice>();
  for (const choice of choices) {
    if (!unique.has(choice.svid)) {
      unique.set(choice.svid, choice);
    }
  }
  return [...unique.values()].sort((left, right) => left.svid - right.svid);
}

/** 当前配置里的 CEID 候选（解析规则与 collectSvids 相同，含 globalCeids）。 */
export function collectCeids(root: JsonObject): SvidChoice[] {
  const equipment = objectField(root, 'equipment');
  const idSpace = objectField(equipment, 'idSpace');
  const ceidBase = numberField(idSpace, 'ceidBase') ?? 2000;
  const ceidStride = numberField(idSpace, 'ceidStride') ?? 100;

  const choices: SvidChoice[] = [];
  const units = arrayField(equipment, 'units');
  for (const [index, rawUnit] of units.entries()) {
    if (!isJsonObject(rawUnit)) {
      continue;
    }
    const owner = stringField(rawUnit, 'name', `unit${index + 1}`);
    const base = ceidBase + index * ceidStride;
    for (const rawDef of arrayField(rawUnit, 'ceids')) {
      if (!isJsonObject(rawDef)) {
        continue;
      }
      const offset = numberField(rawDef, 'offset');
      if (offset === undefined) {
        continue;
      }
      choices.push({ svid: base + offset, owner, slot: stringField(rawDef, 'slot') });
    }
  }

  for (const rawDef of arrayField(equipment, 'globalCeids')) {
    if (!isJsonObject(rawDef)) {
      continue;
    }
    const id = numberField(rawDef, 'id');
    if (id !== undefined) {
      choices.push({ svid: id, owner: 'GLOBAL', slot: stringField(rawDef, 'name') });
    }
  }

  const unique = new Map<number, SvidChoice>();
  for (const choice of choices) {
    if (!unique.has(choice.svid)) {
      unique.set(choice.svid, choice);
    }
  }
  return [...unique.values()].sort((left, right) => left.svid - right.svid);
}
