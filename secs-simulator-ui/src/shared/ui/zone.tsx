/**
 * 设备/主机详情共用的「两段式」骨架（设计 §3.4.1 同构对照表）：
 * 一条操作栏（配置/运行切换 + 动作）+ 子页签。
 * 两个域共用版式与交互语言，数据与路由完全独立（同构但不相交）。
 *
 * <p>页面不再有"实例头"：设备名与主机名在页签、侧栏里已经写着，
 * 状态在各自的清单页里看——详情页从操作栏直接开始。</p>
 */

/**
 * 紧凑的配置区 / 运行区切换：放在操作栏左侧，一行排完。
 */
export function ZoneSwitch({
  zone,
  onChange,
  configNote,
  runtimeNote,
}: {
  zone: 'config' | 'runtime';
  onChange: (zone: 'config' | 'runtime') => void;
  configNote?: string;
  runtimeNote?: string;
}): JSX.Element {
  return (
    <div className="zone-switch" role="tablist" aria-label="配置区 / 运行区">
      <button
        type="button"
        role="tab"
        aria-selected={zone === 'config'}
        className={`zone-switch-btn config${zone === 'config' ? ' active' : ''}`}
        onClick={() => onChange('config')}
        title={configNote ?? '修改实例定义'}
      >
        ▣ 配置区
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={zone === 'runtime'}
        className={`zone-switch-btn runtime${zone === 'runtime' ? ' active' : ''}`}
        onClick={() => onChange('runtime')}
        title={runtimeNote ?? '查看与操作当前运行'}
      >
        ◉ 运行区
      </button>
    </div>
  );
}

/**
 * 实例端点小标签：`地址:端口 | DeviceID`。
 *
 * <p>主机看「目标」（它连向哪里），设备看「监听」（它在哪里等）。
 * 端口未知时只显示地址，别把 `:null` 摆在脸上。</p>
 */
export function EndpointTag({
  label,
  host,
  port,
  deviceId,
  hint,
}: {
  label: string;
  host: string;
  port: number | null;
  deviceId: number | null;
  hint?: string;
}): JSX.Element {
  return (
    <span className={`endpoint-tag${port === null ? ' pending' : ''}`} title={hint ?? `${label}地址：端口 | Device ID`}>
      <span className="endpoint-tag-label">{label}</span>
      <span className="mono">
        {host}
        {port === null ? '' : `:${port}`}
        {deviceId === null ? '' : ` | ${deviceId}`}
      </span>
    </span>
  );
}

/** 两段式内部的子页签。 */
export function SubTabs({
  pages,
  active,
  onSelect,
}: {
  pages: ReadonlyArray<{ id: string; label: string; note?: string }>;
  active: string;
  onSelect: (page: string) => void;
}): JSX.Element {
  return (
    <div className="subtabs" role="tablist">
      {pages.map((page) => (
        <button
          key={page.id}
          type="button"
          role="tab"
          aria-selected={active === page.id}
          className={`subtab${active === page.id ? ' active' : ''}`}
          title={page.note}
          onClick={() => onSelect(page.id)}
        >
          {page.label}
        </button>
      ))}
    </div>
  );
}
