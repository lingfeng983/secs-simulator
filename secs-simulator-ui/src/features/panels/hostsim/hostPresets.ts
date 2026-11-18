/**
 * 主机方向常用报文模板（3.0 主机域共用）。
 *
 * <p>从 v1 的 HostSimMessagesPanel 里搬出来：面板本身随 v1 一起收敛掉了，
 * 但「主机模板」「主机消息发送」两页还在用它。</p>
 */

/** 主机方向的常用报文模板；只列设备真正实现了的报文。 */
export interface HostPreset {
  label: string;
  group: string;
  /** 一句话说明"这条报文是干什么的" */
  desc: string;
  /** 正常回复的函数号（仅说明，不参与发送） */
  reply: string;
  sml: string;
  /** 常用项直接放按钮，其余收进下拉 */
  quick?: boolean;
}

export const HOST_PRESETS: readonly HostPreset[] = [
  {
    label: 'S1F1 在吗',
    group: '建链与控制',
    desc: '主机探活/心跳：确认设备还在线，设备回 S1F2。',
    reply: 'S1F2',
    sml: 'S1F1 W.',
    quick: true,
  },
  {
    label: 'S1F13 建立通信',
    group: '建链与控制',
    desc: '建立通信：主机请求建链，设备回 S1F14（COMMACK）；之后才能发其它报文。',
    reply: 'S1F14',
    sml: 'S1F13 W.',
    quick: true,
  },
  {
    label: 'S1F17 上线',
    group: '建链与控制',
    desc: '请求设备进入 ONLINE；只有 ONLINE 后设备才主动上报 S6F1 / S6F11。',
    reply: 'S1F18',
    sml: 'S1F17 W.',
    quick: true,
  },
  {
    label: 'S1F15 下线',
    group: '建链与控制',
    desc: '请求设备退出 ONLINE，设备回 S1F16；主动上报随之停止。',
    reply: 'S1F16',
    sml: 'S1F15 W.',
    quick: true,
  },
  {
    label: 'S1F3 读状态变量',
    group: '查询',
    desc: '按 SVID 列表读当前值（这里读 1001/1003），设备回 S1F4。',
    reply: 'S1F4',
    sml: 'S1F3 W\n<L\n  <U4 1001>\n  <U4 1003>\n>.',
    quick: true,
  },
  {
    label: 'S1F11 读变量名',
    group: '查询',
    desc: '查询 SVID 的名称/单位（这里查 1001/1003），设备回 S1F12。',
    reply: 'S1F12',
    sml: 'S1F11 W\n<L\n  <U4 1001>\n  <U4 1003>\n>.',
  },
  {
    label: 'S2F13 读全部常量',
    group: '查询',
    desc: '读设备常量（空列表 = 全部 ECV），设备回 S2F14。',
    reply: 'S2F14',
    sml: 'S2F13 W.',
  },
  {
    label: 'S2F17 读设备时间',
    group: '查询',
    desc: '读设备时钟，设备回 S2F18。',
    reply: 'S2F18',
    sml: 'S2F17 W.',
  },
  {
    label: 'S2F31 对时',
    group: '查询',
    desc: '把设备时间设为 A16 格式（yyyyMMddHHmmsscc），设备回 S2F32。',
    reply: 'S2F32',
    sml: 'S2F31 W\n<A "20261028003000">.',
  },
  {
    label: 'S2F33 定义报告',
    group: '报告与事件',
    desc: '定义报告 RPTID 3001，由 SVID 1001/1004 组成，设备回 S2F34。',
    reply: 'S2F34',
    sml: 'S2F33 W\n<L\n  <U4 0>\n  <L\n    <L\n      <U4 3001>\n      <L\n        <U4 1001>\n        <U4 1004>\n      >\n    >\n  >\n>.',
  },
  {
    /* SECS-II 协议术语 Link（事件↔报告的绑定），不是网络「连接」——故保留「链接」措辞并给出解释。 */
    label: 'S2F35 链接事件',
    group: '报告与事件',
    desc: '协议术语 Link（事件与报告的绑定关系，非网络「连接」）：把 CEID 2001 链接到 RPTID 3001，事件触发时按该报告上报，设备回 S2F36。',
    reply: 'S2F36',
    sml: 'S2F35 W\n<L\n  <L\n    <U4 2001>\n    <L\n      <U4 3001>\n    >\n  >\n>.',
  },
  {
    label: 'S2F37 停用全部事件',
    group: '报告与事件',
    desc: 'CEED=0、CEID 列表为空 = 关闭全部事件上报，设备回 S2F38。',
    reply: 'S2F38',
    sml: 'S2F37 W\n<L\n  <BOOLEAN 0x00>\n  <L>\n>.',
  },
  {
    label: 'S2F37 启用 CEID 2001',
    group: '报告与事件',
    desc: 'CEED=1、CEID 列表为 2001 = 只启用该事件，设备回 S2F38。',
    reply: 'S2F38',
    sml: 'S2F37 W\n<L\n  <BOOLEAN 0x01>\n  <L\n    <U4 2001>\n  >\n>.',
  },
  {
    label: 'S2F23 开始 Trace',
    group: '采集与终端',
    desc: '按 DSPER=1s、REPGSZ=1 采集 1001/1003，设备回 S2F24 后周期推 S6F1。',
    reply: 'S2F24',
    sml: 'S2F23 W\n<L\n  <U4 1>\n  <A "000001">\n  <U4 100>\n  <U4 1>\n  <L\n    <U4 1001>\n    <U4 1003>\n  >\n>.',
    quick: true,
  },
  {
    label: 'S2F23 停止 Trace',
    group: '采集与终端',
    desc: 'TOTSMP=0 = 终止该 TRID 的周期采样，设备回 S2F24。',
    reply: 'S2F24',
    sml: 'S2F23 W\n<L\n  <U4 1>\n  <A "000000">\n  <U4 0>\n  <U4 1>\n  <L>\n>.',
  },
  {
    label: 'S10F3 终端消息',
    group: '采集与终端',
    desc: '向设备操作员显示一行文本（TID=0）；带 W，设备回 S10F4。',
    reply: 'S10F4',
    sml: 'S10F3 W\n<L\n  <B 0x0>\n  <A "Hello from host">\n>.',
  },
];

/** 下拉按组归类；保持定义顺序。 */
export const PRESET_GROUPS = [...new Set(HOST_PRESETS.map((preset) => preset.group))].map((group) => ({
  label: group,
  items: HOST_PRESETS.filter((preset) => preset.group === group)
    .map((preset) => ({ label: preset.label, value: preset.sml })),
}));
/* ===== 用户自定义覆盖 ===== */

/** 一条自定义模板；与内置模板同 `label` 时覆盖内置正文。 */
export interface PresetOverride {
  id: string;
  label: string;
  group: string;
  desc: string;
  reply: string;
  sml: string;
}

/**
 * localStorage 里的覆盖存储。
 *
 * <p>内置模板以 `label` 作为稳定键：删除内置只把 label 记进 `deleted`，
 * 不改本文件的常量，所以刷新页面也不会"复活"。</p>
 */
export interface PresetStore {
  /** 被删除的内置模板 label。 */
  deleted: readonly string[];
  custom: readonly PresetOverride[];
}

/** 覆盖存储的 localStorage 键。 */
export const PRESET_STORE_KEY = 'secs.host.presets.v1';

const EMPTY_STORE: PresetStore = { deleted: [], custom: [] };

function isOverride(value: unknown): value is PresetOverride {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const item = value as Record<string, unknown>;
  return ['id', 'label', 'group', 'desc', 'reply', 'sml'].every((key) => typeof item[key] === 'string');
}

/** 读覆盖存储；localStorage 不可用或内容损坏 → 空覆盖（仍展示内置模板）。 */
export function readPresetStore(): PresetStore {
  try {
    const raw = globalThis.localStorage?.getItem(PRESET_STORE_KEY);
    if (raw === null || raw === undefined || raw === '') {
      return EMPTY_STORE;
    }
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) {
      return EMPTY_STORE;
    }
    const record = parsed as Record<string, unknown>;
    const deleted = Array.isArray(record.deleted)
      ? record.deleted.filter((item): item is string => typeof item === 'string')
      : [];
    const custom = Array.isArray(record.custom) ? record.custom.filter(isOverride) : [];
    return { deleted, custom };
  } catch {
    return EMPTY_STORE;
  }
}

/** 写覆盖存储；写失败（隐私模式/配额满）只失效于本次会话，不阻断界面。 */
export function writePresetStore(store: PresetStore): void {
  try {
    globalThis.localStorage?.setItem(
      PRESET_STORE_KEY,
      JSON.stringify({ deleted: store.deleted, custom: store.custom }),
    );
  } catch {
    /* 存不下时保持内存态可用 */
  }
}

function toPreset(item: PresetOverride, base?: HostPreset): HostPreset {
  return {
    label: item.label,
    group: item.group,
    desc: item.desc,
    reply: item.reply,
    sml: item.sml,
    ...(base?.quick === undefined ? {} : { quick: base.quick }),
  };
}

/** 表格行 = 内置（去掉 `deleted` 中出现的）+ `custom`（同 label 覆盖内置）。 */
export function mergePresets(store: PresetStore): HostPreset[] {
  const deleted = new Set(store.deleted);
  const overrides = new Map(store.custom.map((item) => [item.label, item]));
  const rows: HostPreset[] = [];
  for (const preset of HOST_PRESETS) {
    if (deleted.has(preset.label)) {
      continue;
    }
    const override = overrides.get(preset.label);
    rows.push(override === undefined ? preset : toPreset(override, preset));
  }
  const builtinLabels = new Set(HOST_PRESETS.map((preset) => preset.label));
  for (const item of store.custom) {
    if (!builtinLabels.has(item.label)) {
      rows.push(toPreset(item));
    }
  }
  return rows;
}

/** 从 SML 首行取 SxFy（`S1F1 W.` → `S1F1`）；取不到时回退分组名。 */
export function presetTxfy(preset: HostPreset): string {
  const first = preset.sml.trim().split(/\r?\n/, 1)[0] ?? '';
  const match = /^S\d+F\d+/.exec(first);
  return match === null ? preset.group : match[0];
}
