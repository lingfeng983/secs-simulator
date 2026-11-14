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
    label: 'S2F35 链接事件',
    group: '报告与事件',
    desc: '把 CEID 2001 链接到 RPTID 3001，事件触发时按该报告上报，设备回 S2F36。',
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
