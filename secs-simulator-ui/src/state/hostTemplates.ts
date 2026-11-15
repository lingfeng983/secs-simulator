import type { HostTemplateRow } from '../api';
import { planId, type CollectPlan, type TracePlan } from './hostPlans';

/**
 * 主机模板 = 一组可复用的 Trace / S1F3 采集方案。
 *
 * <p>目标地址、端口、Device ID 属于「某一台主机实例」的设置，不是模板内容：
 * 同一套方案可以套到任意地址的主机上。模板里只装方案；新建主机时把方案
 * 复制进那台主机的方案库（/api/v2/hosts/{id}/plans），之后各改各的。</p>
 *
 * <p>后端 host_template 目前只有 note 能装自由文本，方案包以带版本标记的
 * JSON 编码进 note；旧数据（纯文本说明）按「说明 + 空方案」解码，不会丢。</p>
 */
export interface HostTemplate {
  id: string;
  name: string;
  /** 模板说明，用户可见。 */
  note: string;
  /** 内置模板只读：编辑后另存为自定义模板，也不能删。 */
  builtin: boolean;
  tracePlans: TracePlan[];
  collectPlans: CollectPlan[];
}

/** note 里的方案包标记。 */
const BUNDLE_VERSION = 1;
/** 后端端点仍要求这三个实例字段，界面不展示、也不让它们影响模板语义。 */
export const HOST_TEMPLATE_PLACEHOLDER = { host: '-', port: 5000, deviceId: 0 } as const;

function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function number(value: unknown, fallback: number): number {
  const parsed = typeof value === 'number' || typeof value === 'string' ? Number(value) : Number.NaN;
  return Number.isFinite(parsed) ? parsed : fallback;
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function normalizeTracePlans(value: unknown): TracePlan[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((item) => {
    const raw = record(item) ?? {};
    return {
      id: text(raw.id) || planId(),
      name: text(raw.name, 'Trace 方案'),
      trid: number(raw.trid, 1),
      dsper: number(raw.dsper, 1000),
      totsmp: number(raw.totsmp, 100),
      repgsz: number(raw.repgsz, 1),
      svids: text(raw.svids, '1001, 1003'),
      createdAt: number(raw.createdAt, Date.now()),
    };
  });
}

function normalizeCollectPlans(value: unknown): CollectPlan[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((item) => {
    const raw = record(item) ?? {};
    return {
      id: text(raw.id) || planId(),
      name: text(raw.name, '采集方案'),
      startCeid: text(raw.startCeid, '2001'),
      stopCeid: text(raw.stopCeid, '2002'),
      intervalMs: text(raw.intervalMs, '1000'),
      svids: text(raw.svids, '1001, 1003'),
      createdAt: number(raw.createdAt, Date.now()),
    };
  });
}

/**
 * 这行是不是新模型的方案包模板。
 *
 * <p>旧数据只填了目标地址/端口/Device ID、note 是纯说明——那是「示例主机实例」
 * 而不是模板，不混入模板列表；用户要复用时新建一份带方案的模板即可。</p>
 */
export function isHostTemplateBundle(row: HostTemplateRow): boolean {
  try {
    const parsed: unknown = JSON.parse(text(row.note));
    const candidate = record(parsed);
    return candidate !== null && number(candidate.v, 0) === BUNDLE_VERSION;
  } catch {
    return false;
  }
}

/** 把后端一行解码成界面用的方案包模板。 */
export function decodeHostTemplate(row: HostTemplateRow): HostTemplate {
  const note = text(row.note);
  let bundle: Record<string, unknown> | null = null;
  try {
    const parsed: unknown = JSON.parse(note);
    const candidate = record(parsed);
    if (candidate !== null && number(candidate.v, 0) === BUNDLE_VERSION) {
      bundle = candidate;
    }
  } catch {
    /* 老模板的 note 是纯说明文本，按说明处理 */
  }
  return {
    id: row.id,
    name: row.name,
    note: bundle === null ? note : text(bundle.description),
    builtin: false,
    tracePlans: bundle === null ? [] : normalizeTracePlans(bundle.tracePlans),
    collectPlans: bundle === null ? [] : normalizeCollectPlans(bundle.collectPlans),
  };
}

/** 把方案包编码进后端 note 字段（界面不再传目标地址等实例设置）。 */
export function encodeHostTemplate(template: Omit<HostTemplate, 'id' | 'builtin'>): string {
  return JSON.stringify({
    v: BUNDLE_VERSION,
    description: template.note,
    tracePlans: template.tracePlans,
    collectPlans: template.collectPlans,
  });
}

/** 方案总数，用于列表与下拉里的一句话说明。 */
export function templatePlanCount(template: HostTemplate): number {
  return template.tracePlans.length + template.collectPlans.length;
}

/** 套用模板：方案逐条换新 id，避免与主机已有方案或另一个模板撞 id。 */
export function instantiateTemplatePlans(template: HostTemplate): {
  trace: TracePlan[];
  collect: CollectPlan[];
} {
  const now = Date.now();
  return {
    trace: template.tracePlans.map((plan) => ({ ...plan, id: planId(), createdAt: now })),
    collect: template.collectPlans.map((plan) => ({ ...plan, id: planId(), createdAt: now })),
  };
}

/** 两份开箱即用的内置模板：覆盖常规联调与高频采样两种节奏。 */
export const BUILTIN_HOST_TEMPLATES: readonly HostTemplate[] = [
  {
    id: 'builtin-standard',
    name: '标准联调',
    note: '1 秒周期、常用状态变量；适合首次建链与日常联调。',
    builtin: true,
    tracePlans: [
      {
        id: 'builtin-standard-trace',
        name: '标准 Trace 1s',
        trid: 1,
        dsper: 1000,
        totsmp: 100,
        repgsz: 1,
        svids: '1001, 1003',
        createdAt: 0,
      },
    ],
    collectPlans: [
      {
        id: 'builtin-standard-collect',
        name: '标准 S1F3 采集',
        startCeid: '2001',
        stopCeid: '2002',
        intervalMs: '1000',
        svids: '1001, 1003',
        createdAt: 0,
      },
    ],
  },
  {
    id: 'builtin-fast',
    name: '高频采样',
    note: '多变量 Trace + 500ms 轮询；适合观察变化过程。',
    builtin: true,
    tracePlans: [
      {
        id: 'builtin-fast-trace',
        name: '多变量 Trace 1s',
        trid: 2,
        dsper: 1000,
        totsmp: 300,
        repgsz: 1,
        svids: '1001, 1003, 1004',
        createdAt: 0,
      },
    ],
    collectPlans: [
      {
        id: 'builtin-fast-collect',
        name: '高频 S1F3 采集',
        startCeid: '2001',
        stopCeid: '2002',
        intervalMs: '500',
        svids: '1001, 1003, 1004',
        createdAt: 0,
      },
    ],
  },
];
