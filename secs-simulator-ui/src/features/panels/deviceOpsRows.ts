import { useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import type { ConfigEntry, OverviewRow, SessionSummary } from '../../api';
import type { BackendController } from '../../state/useBackend';
import { DEFAULT_REGION, type RegionsState } from '../../state/useRegions';
import { deviceStatus, type DeviceStatus } from '../../shared/lib/deviceStatus';

/** 合并行:会话摘要打底,速率/端口/角色来自 /overview,同一台设备只占一行。
 *  configOnly 非空 = 数据库里还没启动的设备（无会话 id），「启动」= 按配置创建会话。 */
export interface DeviceRow extends SessionSummary {
  port: number;
  hostSim: boolean;
  msgsPerMin: number;
  s6f11PerMin: number;
  lastEventAt: number;
  activeAlarms: number;
  configOnly?: string;
}

/** 状态筛选下拉的选项顺序：按操作员关心的顺序排（先"能不能用"，再"在不在线"）。 */
export const STATE_FILTER_ORDER: readonly DeviceStatus[] = ['started', 'stopped', 'offline', 'online'];

/**
 * 行 → 「打开设备」的导航目标。
 *
 * <p>库里还没启动的设备没有会话 id，路由 key 用配置文件名；已启动的用会话 id。
 * 两者是同一台设备的两种存在方式，落到同一个设备页——不要因为状态不同
 * 就打开不同的东西。</p>
 */
export function openTargetOf(row: DeviceRow): string {
  return `device-open:${row.configOnly ?? row.id}`;
}

export interface DeviceRowsInput {
  sessions: SessionSummary[];
  overview: OverviewRow[];
  configList: ConfigEntry[];
  client: BackendController['client'];
  readConfig: BackendController['readConfig'];
  stateFilter: DeviceStatus | '';
  regionFilter: string;
  regions?: RegionsState;
}

export interface DeviceRowsState {
  /** 配置库 + 会话合并后的全量行。 */
  rows: DeviceRow[];
  /** 经状态/区域筛选后真正显示的行。 */
  visible: DeviceRow[];
  /** 配置文件名 → 配置里写的端口（未启动的设备也读得到）。 */
  configPorts: Record<string, number>;
  selected: ReadonlySet<string>;
  setSelected: Dispatch<SetStateAction<ReadonlySet<string>>>;
  selectedRows: DeviceRow[];
  /** 批量重载/停止/同步只对"有会话"的行有意义（未启动的设备没有会话 id）。 */
  selectedSessionIds: string[];
  /** 删除按配置文件名走；没有配置文件的会话（外部 --config 启动）删不了。 */
  selectedConfigs: string[];
  allSelected: boolean;
  someSelected: boolean;
}

/**
 * 设备清单的取数与筛选。
 *
 * <p>端口来自配置原文、运行读数来自 /overview、清单以配置库为准：一台设备一行。
 * 勾选只认"当前看得见的行"——筛选一变，勾中的隐藏行不该继续参与批量动作。</p>
 */
export function useDeviceRows({
  sessions,
  overview,
  configList,
  client,
  readConfig,
  stateFilter,
  regionFilter,
  regions,
}: DeviceRowsInput): DeviceRowsState {
  /* 端口列的默认来源是设备配置里的 session.port，不是运行态总览：
     未启动（还没会话）的设备也要能一眼看到"它启动后会占哪个端口"。
     按 配置文件名 + 修改时间 缓存，配置没变就不重复读原文。 */
  const [configPorts, setConfigPorts] = useState<Record<string, number>>({});
  const configPortCache = useRef<Map<string, { modifiedMs: number; port: number }>>(new Map());
  const configKey = useMemo(
    () => configList.map((entry) => `${entry.name}:${entry.modifiedMs}`).join('|'),
    [configList],
  );
  useEffect(() => {
    if (client === null || client === undefined) {
      return;
    }
    const pending = configList.filter(
      (entry) => configPortCache.current.get(entry.name)?.modifiedMs !== entry.modifiedMs,
    );
    if (pending.length === 0) {
      return;
    }
    let cancelled = false;
    void Promise.all(pending.map(async (entry) => {
      try {
        const snapshot = await readConfig(entry.name);
        const parsed = snapshot === null
          ? null
          : JSON.parse(snapshot.text) as { session?: { port?: number } };
        const raw = parsed?.session?.port;
        return {
          name: entry.name,
          modifiedMs: entry.modifiedMs,
          port: typeof raw === 'number' && raw > 0 && raw <= 65535 ? raw : 0,
        };
      } catch {
        /* 配置原文坏了/读不到：端口退回运行态数据，不拖垮整张表 */
        return { name: entry.name, modifiedMs: entry.modifiedMs, port: 0 };
      }
    })).then((results) => {
      if (cancelled) {
        return;
      }
      for (const item of results) {
        configPortCache.current.set(item.name, { modifiedMs: item.modifiedMs, port: item.port });
      }
      setConfigPorts(Object.fromEntries(
        [...configPortCache.current].map(([name, value]) => [name, value.port]),
      ));
    });
    return () => { cancelled = true; };
    // configKey 覆盖 configList 的内容变化；readConfig 由 useCallback 固定
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [configKey, readConfig, client]);

  /* /overview 按 id 并进会话行:计数以会话为准,速率/端口/角色以总览为准 */
  const overviewById = useMemo(() => new Map((overview ?? []).map((row) => [row.id, row])), [overview]);
  const rows = useMemo<DeviceRow[]>(() => {
    /* 清单以配置库为准：一台设备一行（按配置文件名去重），运行状态并入会话数据。
       会话没带 config（外部 --config 场景）的行单独排在后面，不参与按配置合并。 */
    const byConfig = new Map<string, DeviceRow>();
    const others: DeviceRow[] = [];
    for (const session of sessions) {
      const info = overviewById.get(session.id);
      const row: DeviceRow = {
        ...session,
        controlState: session.controlState ?? info?.controlState,
        device: info?.device ?? session.device,
        units: info?.units ?? session.units,
        /* 配置里写的端口是"这台设备定义要监听哪个端口"的默认答案；
           只有配置读不到时才退回总览里的运行态端口。 */
        port: (session.config !== undefined ? configPorts[session.config] : 0) || info?.port || 0,
        hostSim: info?.hostSim ?? false,
        msgsPerMin: info?.msgsPerMin ?? 0,
        s6f11PerMin: info?.s6f11PerMin ?? 0,
        lastEventAt: info?.lastEventAt ?? 0,
        activeAlarms: info?.counters.activeAlarms ?? 0,
      };
      if (session.config !== undefined && session.config !== '') {
        byConfig.set(session.config, row);
      } else {
        others.push(row);
      }
    }
    /* 配置库里有、还没有会话行的设备：显示为「停止」，点「启动」才创建会话 */
    for (const entry of configList) {
      if (!byConfig.has(entry.name)) {
        byConfig.set(entry.name, {
          id: `config:${entry.name}`,
          state: 'CLOSED',
          device: entry.name.replace(/\.json$/i, ''),
          config: entry.name,
          units: 0,
          lastError: '',
          counters: {},
          port: configPorts[entry.name] ?? 0,
          configOnly: entry.name,
        } as DeviceRow);
      }
    }
    return [...others, ...[...byConfig.values()]];
  }, [sessions, overviewById, configList, configPorts]);

  /* 四态筛选：判据与「状态」列、设备页头、侧栏完全同源（deviceStatus），
     不再用"是否在线"这种二分法——那样「启动」「离线」两档就筛不出来。 */
  const visible = useMemo(() => rows.filter((row) => {
    if (stateFilter !== '' && deviceStatus(row) !== stateFilter) {
      return false;
    }
    if (regionFilter !== '' && (regions?.deviceRegion(row.config ?? row.id) ?? DEFAULT_REGION) !== regionFilter) {
      return false;
    }
    return true;
  }), [rows, stateFilter, regionFilter, regions]);

  /* 勾选只认"当前看得见的行"：筛选一变，勾中的隐藏行不该继续参与批量动作。
     未启动的设备（configOnly）也能勾——它照样是一台设备，能删、能启动。 */
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const selectedRows = useMemo(
    () => visible.filter((row) => selected.has(row.id)),
    [visible, selected],
  );
  const selectedSessionIds = useMemo(
    () => selectedRows.filter((row) => row.configOnly === undefined).map((row) => row.id),
    [selectedRows],
  );
  const selectedConfigs = useMemo(
    () => selectedRows
      .map((row) => row.config ?? '')
      .filter((name) => name !== ''),
    [selectedRows],
  );
  const allSelected = visible.length > 0 && selectedRows.length === visible.length;
  const someSelected = selectedRows.length > 0 && !allSelected;

  return {
    rows,
    visible,
    configPorts,
    selected,
    setSelected,
    selectedRows,
    selectedSessionIds,
    selectedConfigs,
    allSelected,
    someSelected,
  };
}