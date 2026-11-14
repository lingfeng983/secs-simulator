import { isJsonObject, readTemplates, stringField, uniqueId, type JsonObject } from './templateConfig';
import type { ParamTemplate } from '../../api/paramTemplates';

/**
 * 参数模板库的设备侧绑定：把库模板体「复制快照」进设备配置文档。
 *
 * <p>复制语义：模板体（曲线/组合/中间实例 + result 指定的最终输出）整体
 * 拷进设备配置的 templates 段，id 冲突时改写并同步修正实例/表达式引用；
 * 之后修改库不影响已配置设备。落点写回 SVID 定义的直配糖
 * （template / output / params），后端走既有编译路径，schema v6 零改动。</p>
 */

/** 物化后的直配落点（写回 SVID 定义对象）。 */
export interface MaterializedBinding {
  /** 最终模板 id（已复制进设备配置的那份）。 */
  templateId: string;
  /** 最终输出名；组合模板多输出时必填，单曲线省略（运行时自动取唯一输出）。 */
  output?: string;
  /** 结果实例的参数（实例级参数跟随复制）。 */
  params?: JsonObject;
}

/**
 * 单曲线形条目的内联曲线（供「从模板库填入」内联 curve）：
 * 体内恰好一条曲线且没有组合时返回该曲线对象（不含 id），否则 undefined。
 */
export function singleCurveBodyOf(entry: ParamTemplate): JsonObject | undefined {
  const body = entry.body;
  const curves = body.curves;
  const composites = body.composites;
  const compositeCount = Array.isArray(composites) ? composites.length : 0;
  if (!Array.isArray(curves) || curves.length !== 1 || !isJsonObject(curves[0]) || compositeCount > 0) {
    return undefined;
  }
  const curve = { ...curves[0] } as JsonObject;
  delete curve.id;
  return curve;
}

/**
 * 把库模板体复制进 root 的 templates 段（就地追加，写穿一次完成）。
 *
 * <p>必须在同一次 {@code edit} 里调用：追加模板段与回填 SVID 直配字段
 * 是一次原子改动——分两次 edit 会因草稿文本闭包过期而互相覆盖。</p>
 *
 * @param root  配置文档根（edit 回调里的 draft）
 * @param entry 库条目
 * @param svid  目标 SVID（用于审计性提示，不参与复制）
 * @return 直配落点；模板体形状不对返回错误文案
 */
export function materializeIntoRoot(
  root: JsonObject,
  entry: ParamTemplate,
  svid: number,
): MaterializedBinding | string {
  const body = entry.body;
  const curves = readBodyArray(body, 'curves');
  const composites = readBodyArray(body, 'composites');
  const instances = readBodyArray(body, 'instances');
  if (typeof curves === 'string') {
    return curves;
  }
  if (typeof composites === 'string') {
    return composites;
  }
  if (typeof instances === 'string') {
    return instances;
  }
  const result = typeof body.result === 'string' ? body.result : '';
  const dot = result.lastIndexOf('.');
  if (result === '' || dot <= 0 || dot === result.length - 1) {
    return `模板 ${entry.name} 的 result 形状不对（应为 实例.输出）: ${result}`;
  }
  const resultInstanceId = result.slice(0, dot);
  const resultOutput = result.slice(dot + 1);

  /* 现有 id：新 id 以 模板id-原id 为基底，冲突时由 uniqueId 顺延 */
  const current = readTemplates(root);
  if (!current.ok) {
    return `配置文档的 templates 段有问题，先修好原文: ${current.error}`;
  }
  const existingTemplateIds = [...current.templates.curves, ...current.templates.composites]
    .map((item) => stringField(item, 'id'));
  const existingInstanceIds = current.templates.instances.map((item) => stringField(item, 'id'));

  const templateIdMap = new Map<string, string>();
  const instanceIdMap = new Map<string, string>();
  for (const curve of curves) {
    const oldId = stringField(curve, 'id');
    const newId = uniqueId(existingTemplateIds, `${entry.id}-${oldId}`);
    existingTemplateIds.push(newId);
    templateIdMap.set(oldId, newId);
  }
  for (const composite of composites) {
    const oldId = stringField(composite, 'id');
    const newId = uniqueId(existingTemplateIds, `${entry.id}-${oldId}`);
    existingTemplateIds.push(newId);
    templateIdMap.set(oldId, newId);
  }
  for (const instance of instances) {
    const oldId = stringField(instance, 'id');
    const newId = uniqueId(existingInstanceIds, `${entry.id}-${oldId}`);
    existingInstanceIds.push(newId);
    instanceIdMap.set(oldId, newId);
  }

  /* 组合表达式里的 in.<实例>.<输出> 引用跟着实例 id 改写 */
  const rewrittenComposites = composites.map((composite) => {
    const copy: JsonObject = { ...composite, id: templateIdMap.get(stringField(composite, 'id')) };
    const outputs = isJsonObject(composite.outputs) ? composite.outputs : {};
    const rewritten: JsonObject = {};
    for (const [name, expression] of Object.entries(outputs)) {
      rewritten[name] = typeof expression === 'string'
        ? rewriteInstanceRefs(expression, instanceIdMap)
        : expression;
    }
    copy.outputs = rewritten;
    return copy;
  });
  const rewrittenCurves = curves.map((curve) => ({
    ...curve,
    id: templateIdMap.get(stringField(curve, 'id')),
  }));
  const rewrittenInstances = instances.map((instance) => {
    const copy: JsonObject = { ...instance };
    copy.id = instanceIdMap.get(stringField(instance, 'id'));
    copy.template = templateIdMap.get(stringField(instance, 'template')) ?? stringField(instance, 'template');
    /* 中间实例不绑 SVID（后端已放宽；库体里 svids 恒为空） */
    copy.svids = {};
    return copy;
  });

  /* 直配落点：result 指向的实例 → 它引用的模板 + 输出。
     result 直接指向模板 id（未归一化的简形）时自动补一枚空绑定实例，与后端归一化同规则。
     全部校验在落盘之前：错误时不能往配置里追加半份拷贝。 */
  let resultInstance = instances.find((item) => stringField(item, 'id') === resultInstanceId);
  let resultInstanceCopy: JsonObject | undefined = resultInstance === undefined
    ? undefined
    : rewrittenInstances[instances.indexOf(resultInstance)];
  if (resultInstance === undefined) {
    const mappedTemplate = templateIdMap.get(resultInstanceId);
    if (mappedTemplate === undefined) {
      return `模板 ${entry.name} 的 result 指向不存在的实例或模板: ${resultInstanceId}`;
    }
    resultInstance = { id: resultInstanceId, template: resultInstanceId, svids: {}, params: {} };
    resultInstanceCopy = { id: uniqueId(existingInstanceIds, `${entry.id}-${resultInstanceId}`), template: mappedTemplate, svids: {} };
    rewrittenInstances.push(resultInstanceCopy);
  }
  const finalTemplateId = templateIdMap.get(stringField(resultInstance, 'template'));
  if (finalTemplateId === undefined) {
    return `模板 ${entry.name} 的结果实例引用了未知模板: ${stringField(resultInstance, 'template')}`;
  }

  const binding: MaterializedBinding = { templateId: finalTemplateId };
  /* 组合模板多输出时必须带 output；单曲线/单输出组合运行时自动取唯一输出 */
  const finalComposite = composites.find(
    (item) => stringField(item, 'id') === stringField(resultInstance, 'template'));
  if (finalComposite !== undefined && isJsonObject(finalComposite.outputs)
      && Object.keys(finalComposite.outputs).length > 1) {
    binding.output = resultOutput;
  }
  const params = isJsonObject(resultInstance.params) ? resultInstance.params : undefined;
  if (params !== undefined && Object.keys(params).length > 0) {
    binding.params = { ...params };
  }

  /* 校验全过，落盘：确保 templates 段与三个数组都在文档里，再追加复制体 */
  const section = ensureTemplatesSection(root);
  section.curves.push(...rewrittenCurves);
  section.composites.push(...rewrittenComposites);
  section.instances.push(...rewrittenInstances);
  void svid;
  return binding;
}

/** 读库体里的对象数组；形状不对返回错误文案。 */
function readBodyArray(body: Record<string, unknown>, key: string): JsonObject[] | string {
  const value = body[key];
  if (value === undefined) {
    return [];
  }
  if (!Array.isArray(value) || value.some((item) => !isJsonObject(item))) {
    return `模板体 ${key} 必须是对象数组`;
  }
  return value as JsonObject[];
}

/** 表达式里的 in.<实例>.<输出> 引用按实例 id 映射改写。 */
function rewriteInstanceRefs(expression: string, map: Map<string, string>): string {
  let rewritten = expression;
  for (const [oldId, newId] of map) {
    rewritten = rewritten.split(`in.${oldId}.`).join(`in.${newId}.`);
  }
  return rewritten;
}

/** 确保 root.templates 与三个数组键存在且是活引用（与 editTemplates 同纪律）。 */
function ensureTemplatesSection(root: JsonObject): {
  curves: JsonObject[];
  composites: JsonObject[];
  instances: JsonObject[];
} {
  const existing = root.templates;
  const section: JsonObject = isJsonObject(existing) ? existing : {};
  if (!isJsonObject(existing)) {
    root.templates = section;
  }
  for (const key of ['curves', 'composites', 'instances'] as const) {
    if (!Array.isArray(section[key])) {
      section[key] = [];
    }
  }
  return {
    curves: section.curves as JsonObject[],
    composites: section.composites as JsonObject[],
    instances: section.instances as JsonObject[],
  };
}
