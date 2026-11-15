import { useEffect, useMemo, useRef, useState } from 'react';
import type { ConfigTemplateEntry, SessionBulkResult, SessionSummary } from '../../api';
import { Panel } from '../../shared/ui/Panel';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { Modal } from '../../shared/ui/Modal';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import { formatCount } from '../../shared/lib/format';
import { controlStateLabel } from '../../shared/lib/controlState';
import {
  DEVICE_STATUS_LABEL,
  DEVICE_STATUS_TONE,
  deviceStatus,
  deviceStatusHint,
  type DeviceStatus,
} from '../../shared/lib/deviceStatus';
import type { PanelBaseProps } from './types';
import { saveHint } from '../../shared/lib/saveFile';
import { DEFAULT_REGION } from '../../state/useRegions';

/** 合并行:会话摘要打底,速率/端口/角色来自 /overview,同一台设备只占一行。
 *  configOnly 非空 = 数据库里还没启动的设备（无会话 id），「启动」= 按配置创建会话。 */
interface DeviceRow extends SessionSummary {
  port: number;
  hostSim: boolean;
  msgsPerMin: number;
  s6f11PerMin: number;
  lastEventAt: number;
  activeAlarms: number;
  configOnly?: string;
}

/** 状态筛选下拉的选项顺序：按操作员关心的顺序排（先"能不能用"，再"在不在线"）。 */
const STATE_FILTER_ORDER: readonly DeviceStatus[] = ['started', 'stopped', 'offline', 'online'];

/**
 * 行 → 「打开设备」的导航目标。
 *
 * <p>库里还没启动的设备没有会话 id，路由 key 用配置文件名；已启动的用会话 id。
 * 两者是同一台设备的两种存在方式，落到同一个设备页——不要因为状态不同
 * 就打开不同的东西。</p>
 */
function openTargetOf(row: DeviceRow): string {
  return `device-open:${row.configOnly ?? row.id}`;
}

/** 新增设备弹框的输入：链接信息、设备模板与区域一次填完。 */
interface CreateDeviceDraft {
  name: string;
  host: string;
  port: string;
  deviceId: string;
  passive: string;
  templateId: string;
  region: string;
  newRegion: string;
}

/**
 * 设备与会话(工作台主体)。
 *
 * <p>原来"设备总览"和"会话"是两张表:上面看速率,下面做操作,
 * 同一台设备出现两次,眼睛要在两张表之间对 id。合并后一行一台设备——
 * 状态/速率/计数是这张行的"读数",勾选与右侧动作是"操作",
 * 点行进设备页。批量工具条常驻在表格下方,勾选后才可用。</p>
 */
export function DeviceOpsPanel({ backend, notifier, goTo, regions }: PanelBaseProps): JSX.Element {
  /** 状态筛选：四态（启动/停止/离线/在线）+ 空串看全部。 */
  const [stateFilter, setStateFilter] = useState<DeviceStatus | ''>('');
  /** 区域筛选：空串看全部，其余只看该区域的设备。 */
  const [regionFilter, setRegionFilter] = useState('');
  /** 新增设备弹框：链接信息 + 模板 + 区域都在这一个框里填。 */
  const [createOpen, setCreateOpen] = useState(false);
  const [createError, setCreateError] = useState('');
  const [createDraft, setCreateDraft] = useState<CreateDeviceDraft>({
    name: '', host: '0.0.0.0', port: '5010', deviceId: '0',
    passive: 'true', templateId: 'etch', region: DEFAULT_REGION, newRegion: '',
  });
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkConfig, setBulkConfig] = useState('');
  const [bulkCount, setBulkCount] = useState(5);
  const [bulkPrefix, setBulkPrefix] = useState('dev-');
  const [bulkPortBase, setBulkPortBase] = useState(5100);
  const [bulkDeviceBase, setBulkDeviceBase] = useState(0);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchProgress, setBatchProgress] = useState<string>('');
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkResult, setBulkResult] = useState<SessionBulkResult | null>(null);
  const [configTemplates, setConfigTemplates] = useState<ConfigTemplateEntry[]>([]);
  const [bulkTplId, setBulkTplId] = useState('etch');
  const [bulkTplName, setBulkTplName] = useState('');
  /** 待确认的断开目标:null = 不弹;label 是确认文案里的人话描述。 */
  const [closeTarget, setCloseTarget] = useState<{ ids: readonly string[]; label: string } | null>(null);
  /** 待确认的删除目标：配置文件名 + 人话描述。删设备是不可撤销的，一律先问一次。 */
  const [deleteTarget, setDeleteTarget] = useState<{ configs: readonly string[]; label: string } | null>(null);
  const [bulkTplBusy, setBulkTplBusy] = useState(false);
  const [syncOpen, setSyncOpen] = useState(false);
  const [syncPortBase, setSyncPortBase] = useState(5100);
  const [syncDeviceBase, setSyncDeviceBase] = useState(0);
  const [newTemplateId, setNewTemplateId] = useState('bare');
  const [templateList, setTemplateList] = useState<ConfigTemplateEntry[]>([]);
  /* 连续新建时轮询还没刷新，用本地已分配端口兜底，避免两台设备抢同一端口 */
  const allocatedPorts = useRef<Set<number>>(new Set());
  const { sessions, overview, configList, readConfig, bulkCreateSessions, batchReload, batchClose } = backend;

  /* 模板目录：工作台新建设备只选模板，不再手填配置名 */
  useEffect(() => {
    if (backend.client === null || backend.client === undefined) {
      return;
    }
    void backend.client.templates().then((list) => {
      setTemplateList(list);
      if (list.length > 0) {
        setNewTemplateId((previous) => (list.some((item) => item.id === previous) ? previous : list[0].id));
      }
    });
  }, [backend.client]);

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
    if (backend.client === null || backend.client === undefined) {
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
  }, [configKey, readConfig, backend.client]);

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
  const selectedRows = useMemo(
    () => visible.filter((row) => selected.has(row.id)),
    [visible, selected],
  );
  /** 批量重载/停止/同步只对"有会话"的行有意义（未启动的设备没有会话 id）。 */
  const selectedSessionIds = useMemo(
    () => selectedRows.filter((row) => row.configOnly === undefined).map((row) => row.id),
    [selectedRows],
  );
  /** 删除按配置文件名走；没有配置文件的会话（外部 --config 启动）删不了。 */
  const selectedConfigs = useMemo(
    () => selectedRows
      .map((row) => row.config ?? '')
      .filter((name) => name !== ''),
    [selectedRows],
  );
  const allSelected = visible.length > 0 && selectedRows.length === visible.length;
  const someSelected = selectedRows.length > 0 && !allSelected;

  /* 批量新建预检:会话 id 冲突可以提前算——这是最常见的重复创建问题 */
  const bulkIds = new Set(sessions.map((item) => item.id));
  const plannedIds = Array.from(
    { length: Math.max(0, Math.min(100, Math.round(bulkCount))) },
    (_, i) => `${bulkPrefix.trim() === '' ? 'dev-' : bulkPrefix.trim()}${i + 1}`,
  );
  const idConflicts = plannedIds.filter((id) => bulkIds.has(id));

  const columns: Column<DeviceRow>[] = [
    {
      key: 'select',
      header: '选择',
      width: '46px',
      className: 'select-column',
      /* 表头本身就是"全选"：勾上=全选当前筛选结果，再点=清空。
         原来工具条上的「全选 / 清空选择」两个按钮因此可以撤掉。 */
      headerContent: (
        <input
          type="checkbox"
          checked={allSelected}
          ref={(element) => {
            if (element !== null) {
              /* 部分勾选时画成"半选"，一眼能看出还没全选 */
              element.indeterminate = someSelected;
            }
          }}
          onChange={() => setSelected(allSelected ? new Set() : new Set(visible.map((row) => row.id)))}
          onClick={(event) => event.stopPropagation()}
          aria-label="全选 / 清空"
          title="勾选=全选当前筛选结果，再点=清空"
        />
      ),
      render: (row) => (
        <input
          type="checkbox"
          checked={selected.has(row.id)}
          onClick={(event) => event.stopPropagation()}
          onChange={(event) => {
            const next = new Set(selected);
            if (event.target.checked) {
              next.add(row.id);
            } else {
              next.delete(row.id);
            }
            setSelected(next);
          }}
          aria-label={`选择 ${row.device}`}
        />
      ),
    },
    {
      key: 'device',
      header: '设备',
      sortValue: (row) => row.device,
      render: (row) => (
        <button
          type="button"
          className="link"
          onClick={(event) => {
            event.stopPropagation();
            goTo?.(openTargetOf(row));
          }}
          title={row.configOnly === undefined ? '进入设备页' : '打开这台设备（还没启动，运行数据等启动后才有）'}
        >
          {row.device}
        </button>
      ),
    },
    {
      key: 'port',
      header: '端口',
      sortValue: (row) => row.port,
      /* 端口列只放数字：冒号是"host:port"写法的一部分，单独一列时是噪音。
         数字优先取设备配置里的 session.port（未启动的设备也读得到）。 */
      render: (row) => <span className="mono">{row.port}</span>,
    },
    {
      key: 'region',
      header: '区域',
      sortValue: (row) => regions?.deviceRegion(row.config ?? row.id) ?? DEFAULT_REGION,
      render: (row) => {
        const key = row.config ?? row.id;
        if (regions === undefined) {
          return <span className="hint-inline">{DEFAULT_REGION}</span>;
        }
        return (
          <select
            className="region-cell"
            value={regions.deviceRegion(key)}
            aria-label={`${row.device} 的区域`}
            title="修改这台设备所属的区域，左侧菜单会跟着重新归类"
            onClick={(event) => event.stopPropagation()}
            onChange={(event) => regions.setDeviceRegion(key, event.target.value)}
          >
            {regions.names.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
        );
      },
    },
    {
      key: 'control',
      header: '状态',
      sortValue: (row) => deviceStatus(row),
      render: (row) => {
        const status = deviceStatus(row);
        return (
          <span
            className={`badge tone-${DEVICE_STATUS_TONE[status]}`}
            title={`${deviceStatusHint(status)}（${row.state}${row.controlState === undefined ? '' : ` · ${controlStateLabel(row.controlState)}`}）`}
          >
            {DEVICE_STATUS_LABEL[status]}
          </span>
        );
      },
    },
    {
      key: 'role',
      header: '角色',
      sortValue: (row) => (row.hostSim ? '主机模拟' : '设备'),
      render: (row) => (row.hostSim ? <span className="badge tone-info">主机模拟</span> : <span className="hint-inline">设备</span>),
    },
    {
      key: 'msgs',
      header: '报文/分',
      sortValue: (row) => row.msgsPerMin,
      render: (row) => (row.msgsPerMin > 0 ? <strong>{row.msgsPerMin}</strong> : <span className="hint-inline">0</span>),
    },
    {
      key: 'events',
      header: '事件/分',
      sortValue: (row) => row.s6f11PerMin,
      render: (row) => (row.s6f11PerMin > 0 ? <strong>{row.s6f11PerMin}</strong> : <span className="hint-inline">0</span>),
    },
    {
      key: 'emitted',
      header: '已上报',
      sortValue: (row) => row.counters.emitted ?? 0,
      render: (row) => formatCount(row.counters.emitted),
    },
    {
      key: 'alarms',
      header: '活动报警',
      sortValue: (row) => row.activeAlarms,
      render: (row) => (row.activeAlarms > 0
        ? <span className="badge tone-danger">{row.activeAlarms}</span>
        : <span className="hint-inline">0</span>),
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => {
        /* 三个动作对应三件事，互不替代：
           「重启」= 按配置文件重新装载（等价于"重载"）；
           「启动/停止」= 端口监听开关（启动后主机才连得上，停止后端口释放）；
           「上线/下线」= GEM 控制状态（等价主机发 S1F17 / S1F15）——只有链路
           建起来（离线/在线）才出现：没连上主机时没有"上线"可言。
           「重启」常驻第一格：没启动的设备留一个灰掉的按钮，
           位置不变，操作员不用去找"这台怎么没有重启"。 */
        const status = deviceStatus(row);
        const started = row.configOnly === undefined && status !== 'stopped';
        return (
          <div className="row-actions" onClick={(event) => event.stopPropagation()}>
            <button
              type="button"
              disabled={!started}
              onClick={() => void backend.reloadSession(row.id)}
              title={started
                ? '重启：按配置文件重新装载，配置有问题时旧会话不受影响'
                : '设备没启动，先点「启动」；启动后这里就能重启'}
            >
              重启
            </button>
            {row.configOnly !== undefined || status === 'stopped' ? (
              /* 库里还没启动的设备 / 已停止的会话：一个「启动」= 建链并开始监听 */
              <button
                type="button"
                className="primary"
                onClick={() => void (row.configOnly !== undefined
                  ? backend.createSession(row.configOnly)
                  : backend.connectSession(row.id))}
                title={row.configOnly !== undefined
                  ? '按已保存的设备配置创建会话并启动端口监听'
                  : '启动端口监听（设备开始等主机连接）'}
              >
                启动
              </button>
            ) : (
              <button
                type="button"
                className="danger"
                onClick={() => setCloseTarget({ ids: [row.id], label: row.id })}
                title="停止端口监听：链路断开、端口立即释放，设备保留在清单里"
              >
                停止
              </button>
            )}
            {status === 'offline' && (
              <button
                type="button"
                className="primary"
                onClick={() => void backend.setDeviceOnline(row.id, true)}
                title="设备上线（等价主机发 S1F17）：上线后事件才按规则上报"
              >
                上线
              </button>
            )}
            {status === 'online' && (
              <button
                type="button"
                onClick={() => void backend.setDeviceOnline(row.id, false)}
                title="设备下线（等价主机发 S1F15）：主动上报立即停止"
              >
                下线
              </button>
            )}
            {/* 删除这台设备：先停会话再软删配置（历史留着）。没有配置文件的外部会话删不了 */}
            <button
              type="button"
              className="danger"
              disabled={row.config === undefined || row.config === ''}
              onClick={() => {
                if (row.config !== undefined && row.config !== '') {
                  setDeleteTarget({ configs: [row.config], label: `设备 ${row.device}` });
                }
              }}
              title={row.config === undefined || row.config === ''
                ? '这条会话没有配置文件（外部启动），删不了设备'
                : '删除这台设备：先停止端口监听，再从清单里删掉；配置历史仍留在库里'}
            >
              删除
            </button>
          </div>
        );
      },
    },
  ];

  /** 以第一个选中的设备为源，把其余选中设备的配置同步成一致；
      只保留各自的会话 id、端口、设备号与设备名，其余段原样复制。 */
  const syncSelected = async () => {
    const ids = selectedSessionIds;
    if (ids.length < 2) {
      notifier.error('至少选择两台已启动的设备');
      return;
    }
    const source = sessions.find((item) => item.id === ids[0]);
    const sourceConfig = source?.config ?? '';
    if (source === undefined || sourceConfig === '') {
      notifier.error('第一台设备没有配置文件');
      return;
    }
    const snapshot = await backend.readConfig(sourceConfig);
    if (snapshot === null) {
      notifier.error('读取源配置失败');
      return;
    }
    setBatchBusy(true);
    let done = 0;
    try {
      for (let index = 1; index < ids.length; index += 1) {
        const target = sessions.find((item) => item.id === ids[index]);
        const targetConfig = target?.config ?? '';
        if (target === undefined || targetConfig === '' || targetConfig === sourceConfig) {
          continue;
        }
        setBatchProgress(`同步 ${index}/${ids.length - 1}`);
        try {
          const root = JSON.parse(snapshot.text) as Record<string, unknown>;
          const session = root.session !== null && typeof root.session === 'object'
            ? root.session as Record<string, unknown>
            : {};
          session.id = target.id;
          session.port = syncPortBase + index;
          session.deviceId = syncDeviceBase + index;
          root.session = session;
          const equipment = root.equipment !== null && typeof root.equipment === 'object'
            ? root.equipment as Record<string, unknown>
            : {};
          equipment.name = target.device !== '' ? target.device : target.id;
          root.equipment = equipment;
          const text = JSON.stringify(root, null, 2);
          const validation = await backend.validateConfig(targetConfig, text);
          if (validation === null || !validation.valid) {
            continue;
          }
          const saved = await backend.saveConfig(targetConfig, text, null);
          if (saved !== null && saved.status === 200) {
            done += 1;
          }
        } catch {
          /* 单台失败继续下一台 */
        }
      }
      notifier.success(`已同步 ${done} 台设备`, `源配置：${source.id}；只改了会话 id/端口/设备号/名称`);
      setSyncOpen(false);
    } finally {
      setBatchBusy(false);
      setBatchProgress('');
    }
  };

  /** 打开新增弹框时给一组可用的默认值：端口避开已占用，模板取目录里第一份。 */
  const openCreate = () => {
    const used = new Set<number>([
      ...overview.map((row) => row.port),
      ...Object.values(configPorts),
      ...allocatedPorts.current,
    ]);
    let port = 5010;
    while (used.has(port)) {
      port += 1;
    }
    setCreateError('');
    setCreateDraft({
      name: '',
      host: '0.0.0.0',
      port: String(port),
      deviceId: '0',
      passive: 'true',
      templateId: newTemplateId,
      region: DEFAULT_REGION,
      newRegion: '',
    });
    setCreateOpen(true);
  };

  /**
   * 删设备：逐台走 DELETE /configs/{name}。
   *
   * <p>后端在删之前会先停掉这台设备的会话（配置没了端口还占着，
   * 清单里就查不到是谁占的了）。删完清空勾选——勾着的行已经不在了。</p>
   */
  const removeDevices = async (configs: readonly string[]) => {
    if (configs.length === 0) {
      return;
    }
    setBatchBusy(true);
    let done = 0;
    let missing = 0;
    try {
      for (let index = 0; index < configs.length; index += 1) {
        setBatchProgress(`删除 ${index + 1}/${configs.length}`);
        const status = await backend.deleteConfig(configs[index]);
        if (status === 200) {
          done += 1;
        } else if (status === 404) {
          missing += 1;
        }
      }
      const tail = missing > 0 ? `${missing} 台本来就不在库里` : '';
      if (done === 0) {
        notifier.warn('没有删掉任何设备', tail);
      } else {
        notifier.success(`已删除 ${done} 台设备`, tail);
      }
      setSelected(new Set());
    } finally {
      setBatchBusy(false);
      setBatchProgress('');
    }
  };

  /** 按弹框里填的链接信息 + 模板建设备：配置文件名取设备名，其余写进 session 段。 */
  const createFromTemplate = async (draft: CreateDeviceDraft) => {
    if (backend.client === null || backend.client === undefined) {
      return;
    }
    const label = draft.name.trim();
    if (label === '') {
      setCreateError('请填写设备名称');
      return;
    }
    if (draft.templateId === '') {
      setCreateError('请选择设备模板');
      return;
    }
    const sessionId = label.replace(/[^A-Za-z0-9._-]/g, '-');
    const name = `${sessionId}.json`;
    const port = Number(draft.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      setCreateError('端口需要是 1–65535 之间的整数');
      return;
    }
    const deviceId = Number(draft.deviceId) || 0;
    const freshConfigs = await backend.client.configs();
    const existing = new Set([
      ...freshConfigs.map((entry) => entry.name),
      ...configList.map((entry) => entry.name),
      ...sessions.map((session) => session.config),
    ]);
    if (existing.has(name)) {
      setCreateError(`设备 ${sessionId} 已存在，换一个名称`);
      return;
    }
    const usedPorts = new Set<number>([
      ...overview.map((row) => row.port),
      ...Object.values(configPorts),
      ...allocatedPorts.current,
    ]);
    for (const entry of freshConfigs) {
      try {
        const snapshot = await backend.readConfig(entry.name);
        if (snapshot !== null) {
          const config = JSON.parse(snapshot.text) as { session?: { port?: number } };
          if (typeof config.session?.port === 'number') {
            usedPorts.add(config.session.port);
          }
        }
      } catch {
        /* 坏配置跳过 */
      }
    }
    if (usedPorts.has(port)) {
      setCreateError(`端口 ${port} 已被占用，请换一个端口`);
      return;
    }
    allocatedPorts.current.add(port);
    setCreateError('');
    setBatchBusy(true);
    setBatchProgress('新建设备…');
    try {
      const status = await backend.client.createConfig(name, draft.templateId);
      if (status !== 201 && status !== 200) {
        setCreateError(`按模板创建设备失败（HTTP ${status}）`);
        return;
      }
      const snapshot = await backend.readConfig(name);
      if (snapshot !== null) {
        try {
          const root = JSON.parse(snapshot.text) as Record<string, unknown>;
          const session = root.session !== null && typeof root.session === 'object'
            ? root.session as Record<string, unknown>
            : {};
          session.id = sessionId;
          session.passive = draft.passive === 'true';
          session.bindHost = draft.host.trim() === '' ? '0.0.0.0' : draft.host.trim();
          session.port = port;
          session.deviceId = deviceId;
          root.session = session;
          const equipment = root.equipment !== null && typeof root.equipment === 'object'
            ? root.equipment as Record<string, unknown>
            : {};
          equipment.name = label;
          root.equipment = equipment;
          await backend.saveConfig(name, JSON.stringify(root, null, 2), null);
        } catch {
          /* 模板原文不是合法 JSON 时由后续启动报错 */
        }
      }
      await backend.createSession(name);
      await backend.refreshConfigs();
      const region = draft.newRegion.trim() === '' ? draft.region : draft.newRegion.trim();
      if (region !== '') {
        regions?.addRegion(region);
        regions?.setDeviceRegion(name, region);
      }
      setCreateOpen(false);
      notifier.success(`已新建设备 ${sessionId}`, `端口 ${port}，Device ID ${deviceId}`);
    } finally {
      setBatchBusy(false);
      setBatchProgress('');
    }
  };

  return (
    /* 标题行已删：新建/批量这些页面级动作统一落在表格工具条上，一页只有"一行操作 + 一张表" */
    <Panel id="panel-sessions" title="设备与会话" headless>
      <DataTable
        tableKey="sessions"
        columns={columns}
        rows={visible}
        rowKey={(row) => (row.configOnly !== undefined ? `config:${row.configOnly}` : row.id)}
        searchPlaceholder="搜索设备名 / 会话 id / 状态"
        /* 会话列已从表格里去掉，但仍要能按会话 id 搜到这台设备：
           搜索文本自己拼，顺带把中文状态（在线/离线…）也纳进来。 */
        searchText={(row) => [
          row.device,
          row.id,
          row.config ?? '',
          String(row.port),
          DEVICE_STATUS_LABEL[deviceStatus(row)],
          row.hostSim ? '主机模拟' : '设备',
          regions?.deviceRegion(row.config ?? row.id) ?? DEFAULT_REGION,
        ].join(' ')}
        exportName="sessions"
        onExported={(result) => { const hint = saveHint(result); if (hint !== '') { notifier.success('已导出', hint); } }}
        onImport={async (imported) => {
          /* `backend.sessions` 是本次导入开始前的快照，且 createSession 内部把
             「配置不存在/端口占用」都当提示发出去后正常返回——所以既不能用闭包
             里的列表判重，也不能用"没抛异常"当成功。两条都改掉：本地维护一份
             已占用集合，成功与否只看 201。 */
          const taken = new Set(backend.sessions.map((session) => session.config));
          let done = 0;
          let skipped = 0;
          let failed = 0;
          for (const row of imported) {
            const config = (row['配置'] ?? '').trim();
            if (config === '' || taken.has(config)) {
              skipped += 1;
              continue;
            }
            const status = await backend.createSession(config);
            if (status === 201) {
              taken.add(config);
              done += 1;
            } else {
              failed += 1;
            }
          }
          const tail = [
            failed > 0 ? `${failed} 行失败` : '',
            skipped > 0 ? `${skipped} 行跳过` : '',
          ].filter((part) => part !== '').join('，');
          if (done === 0 && failed > 0) {
            notifier.error('导入没有创建任何会话', tail);
          } else if (done === 0) {
            /* 全被跳过（没有「配置」列、或同名配置已存在）也不能报"已导入 0 个"，
               那读起来像成功 */
            notifier.warn('没有可导入的行', tail === '' ? '每行都需要一列「配置」' : tail);
          } else {
            notifier.success(`已按配置导入 ${done} 个会话`, tail);
          }
        }}
        emptyText="暂无设备:点「新建设备」创建;已保存的设备会显示为「停止」状态,点「启动」开始"
        defaultSort={{ key: 'device', direction: 'asc' }}
        toolbar={
          <>
            <label className="field region-filter">
              <span>状态</span>
              <select
                value={stateFilter}
                aria-label="按状态筛选设备"
                onChange={(event) => setStateFilter(event.target.value as DeviceStatus | '')}
              >
                <option value="">全部</option>
                {STATE_FILTER_ORDER.map((status) => (
                  <option key={status} value={status}>{DEVICE_STATUS_LABEL[status]}</option>
                ))}
              </select>
            </label>
            <label className="field region-filter">
              <span>区域</span>
              <select
                value={regionFilter}
                aria-label="按区域筛选设备"
                onChange={(event) => {
                  const value = event.target.value;
                  if (value === '__new__') {
                    const name = window.prompt('新区域名称')?.trim();
                    if (name !== undefined && name !== '') {
                      regions?.addRegion(name);
                      setRegionFilter(name);
                    }
                    return;
                  }
                  setRegionFilter(value);
                }}
              >
                <option value="">全部区域</option>
                {(regions?.names ?? [DEFAULT_REGION]).map((name) => (
                  <option key={name} value={name}>{name}</option>
                ))}
                <option value="__new__">＋ 新建区域</option>
              </select>
            </label>
          </>
        }
        /* 动作靠右：筛选是"改看什么"，按钮是"改做什么"，两件事分开站 */
        toolbarEnd={
          <>
            <button
              type="button"
              className="primary"
              onClick={openCreate}
              disabled={batchBusy}
              title="新增设备：填写链接信息并选择设备模板"
            >
              新建设备
            </button>
            <button
              type="button"
              disabled={selectedSessionIds.length < 2 || batchBusy}
              title="以选中的第一台为原始配置，把其余设备的配置同步为一致，只改会话 id/端口/设备号/名称"
              onClick={() => setSyncOpen(true)}
            >
              批量同步配置
            </button>
            <button
              type="button"
              disabled={selectedSessionIds.length === 0 || batchBusy}
              title="对选中的设备逐台重启（按各自配置重新装载；配置有问题时旧会话不受影响，主机会断连）"
              onClick={() => {
                setBatchBusy(true);
                void batchReload(new Set(selectedSessionIds), (done, total) => setBatchProgress(`重启 ${done}/${total}`))
                  .finally(() => { setBatchBusy(false); setBatchProgress(''); });
              }}
            >
              批量重启
            </button>
            <button
              type="button"
              className="danger"
              disabled={selectedSessionIds.length === 0 || batchBusy}
              title="停止选中设备的端口监听（链路断开、端口立即释放；设备保留，可再启动）"
              onClick={() => setCloseTarget({
                ids: selectedSessionIds,
                label: `选中的 ${selectedSessionIds.length} 台设备`,
              })}
            >
              批量停止
            </button>
            <button
              type="button"
              className="danger"
              disabled={selectedConfigs.length === 0 || batchBusy}
              title="从清单里删掉选中的设备：先停端口监听再软删配置，配置历史仍留在库里"
              onClick={() => setDeleteTarget({
                configs: selectedConfigs,
                label: `选中的 ${selectedConfigs.length} 台设备`,
              })}
            >
              批量删除
            </button>
            {/* 选中数量与批量进度：只在有勾选或正在批量时出现，不占常驻版面 */}
            {batchBusy && batchProgress !== '' ? (
              <span className="table-selection">{batchProgress}</span>
            ) : selectedRows.length > 0 ? (
              <span className="table-selection">已选 {selectedRows.length} 台</span>
            ) : null}
          </>
        }
        exportColumns={[
          { header: '会话', value: (row) => row.id },
          { header: '设备', value: (row) => row.device },
          { header: '配置', value: (row) => row.config },
          { header: '区域', value: (row) => regions?.deviceRegion(row.config ?? row.id) ?? DEFAULT_REGION },
          { header: '端口', value: (row) => row.port },
          { header: '状态', value: (row) => DEVICE_STATUS_LABEL[deviceStatus(row)] },
          { header: '会话状态', value: (row) => row.state },
          { header: '控制状态', value: (row) => controlStateLabel(row.controlState) },
          { header: '角色', value: (row) => (row.hostSim ? '主机模拟' : '设备') },
          { header: '单元', value: (row) => row.units },
          { header: '报文/分钟', value: (row) => row.msgsPerMin },
          { header: '事件/分钟', value: (row) => row.s6f11PerMin },
          { header: '已上报', value: (row) => row.counters.emitted ?? 0 },
          { header: '限流丢弃', value: (row) => row.counters.rejected ?? 0 },
          { header: '禁用跳过', value: (row) => row.counters.skipped ?? 0 },
          { header: 'S6F1', value: (row) => row.counters.traceFrames ?? 0 },
          { header: 'S9Fx', value: (row) => row.counters.s9Replies ?? 0 },
          { header: '活动报警', value: (row) => row.activeAlarms },
        ]}
        onRowClick={(row) => goTo?.(openTargetOf(row))}
      />

      <Modal
        open={syncOpen}
        title="批量同步配置"
        onClose={() => setSyncOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setSyncOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={batchBusy} onClick={() => void syncSelected()}>
              {batchBusy ? '同步中…' : '开始同步'}
            </button>
          </>
        }
      >
        <p className="hint">
          以选中的第一台为原始配置，把设备/单元/SVID/报告/事件/模板复制到其余选中设备；
          只改各自的会话 id、端口、设备号与设备名，其余配置保持一致。
        </p>
        <p className="hint">
          原始设备：<code>{selectedSessionIds[0] ?? '—'}</code>；目标 {Math.max(0, selectedSessionIds.length - 1)} 台。
        </p>
        <div className="form-row">
          <label className="field">
            起始端口
            <input
              type="number"
              min={1}
              value={syncPortBase}
              onChange={(event) => setSyncPortBase(Number(event.target.value) || 5100)}
            />
          </label>
          <label className="field">
            起始 Device ID
            <input
              type="number"
              min={0}
              value={syncDeviceBase}
              onChange={(event) => setSyncDeviceBase(Number(event.target.value) || 0)}
            />
          </label>
        </div>
      </Modal>

      <CreateDeviceModal
        open={createOpen}
        draft={createDraft}
        templates={templateList}
        regionNames={regions?.names ?? [DEFAULT_REGION]}
        busy={batchBusy}
        error={createError}
        onChange={setCreateDraft}
        onClose={() => setCreateOpen(false)}
        onCreate={(draft) => void createFromTemplate(draft)}
      />

      <ConfirmModal
        open={closeTarget !== null}
        title="停止设备"
        danger
        confirmLabel="停止"
        message={closeTarget === null ? '' : `确定停止${closeTarget.label}？端口会立即释放、与主机的链路断开，设备仍保留在清单里，可随时再启动。`}
        onClose={() => setCloseTarget(null)}
        onConfirm={() => {
          if (closeTarget === null) {
            return;
          }
          const ids = closeTarget.ids;
          setCloseTarget(null);
          if (ids.length === 1) {
            void backend.closeSession(ids[0]);
            return;
          }
          setBatchBusy(true);
          void batchClose(new Set(ids), (done, total) => setBatchProgress(`断开 ${done}/${total}`))
            .finally(() => {
              setSelected(new Set());
              setBatchBusy(false);
              setBatchProgress('');
            });
        }}
      />
      {/* 删除确认：删设备不可撤销，一律先问一次 */}
      <ConfirmModal
        open={deleteTarget !== null}
        title="删除设备"
        danger
        confirmLabel="删除"
        message={deleteTarget === null ? '' : `确定删除${deleteTarget.label}？端口监听会立即停止，设备从清单里消失；配置正文与版本历史仍留在库里，但界面上不再显示。`}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => {
          if (deleteTarget === null) {
            return;
          }
          const configs = deleteTarget.configs;
          setDeleteTarget(null);
          void removeDevices(configs);
        }}
      />

      <Modal
        open={bulkOpen}
        title="批量新建设备"
        onClose={() => setBulkOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setBulkOpen(false)}>
              关闭
            </button>
            <button
              type="button"
              className="primary"
              disabled={bulkBusy || bulkConfig === '' || bulkCount < 1 || idConflicts.length > 0}
              onClick={() => {
                setBulkBusy(true);
                setBulkResult(null);
                void backend
                  .bulkCreateSessions({
                    config: bulkConfig,
                    count: Math.min(100, Math.max(1, Math.round(bulkCount))),
                    sessionIdPrefix: bulkPrefix.trim() === '' ? 'dev-' : bulkPrefix.trim(),
                    portBase: Math.max(1, Math.round(bulkPortBase)),
                    deviceIdBase: Math.max(0, Math.round(bulkDeviceBase)),
                  })
                  .then((result) => {
                    setBulkResult(result);
                    setBulkBusy(false);
                  });
              }}
            >
              {bulkBusy ? '创建中…' : '开始创建'}
            </button>
          </>
        }
      >
        <div className="form-row">
          <label className="field">
            设备配置模板
            <select value={bulkConfig} onChange={(event) => setBulkConfig(event.target.value)}>
              <option value="">（选择配置）</option>
              {configList.map((entry) => (
                <option key={entry.name} value={entry.name}>
                  {entry.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            数量（1–100）
            <NumberField value={bulkCount} integer onCommit={setBulkCount} />
          </label>
          <label className="field">
            会话 id 前缀
            <TextField value={bulkPrefix} onCommit={setBulkPrefix} />
          </label>
          <label className="field">
            起始端口
            <NumberField value={bulkPortBase} integer onCommit={(next) => setBulkPortBase(Math.max(1, next))} />
          </label>
          <label className="field">
            device-id 起始
            <NumberField value={bulkDeviceBase} integer onCommit={setBulkDeviceBase} />
          </label>
        </div>
        <div className="form-row">
          <label className="field">
            从内置设备模板建一份
            <select value={bulkTplId} onChange={(event) => setBulkTplId(event.target.value)}>
              {configTemplates.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            新配置名
            <TextField value={bulkTplName} onCommit={setBulkTplName} placeholder="如 etch-line.json" />
          </label>
          <button
            type="button"
            disabled={bulkTplBusy || bulkTplName.trim() === '' || !bulkTplName.trim().toLowerCase().endsWith('.json')}
            title="新建一份配置文件并选为下面的设备配置模板"
            onClick={() => {
              setBulkTplBusy(true);
              void backend
                .createConfig(bulkTplName.trim(), bulkTplId)
                .then((status) => {
                  if (status === 201) {
                    setBulkConfig(bulkTplName.trim());
                    setBulkTplName('');
                  }
                })
                .finally(() => setBulkTplBusy(false));
            }}
          >
            {bulkTplBusy ? '创建中…' : '建并选用'}
          </button>
        </div>
        <p className="hint">
          第 i 台:会话 id = 前缀 + i,端口 = 起始端口 + i − 1,device-id = 起始 + i − 1。
          设备本体(单元、变量、事件、模板)全部来自所选配置模板;这些连接标识只在运行期覆盖,
          不写回模板文件。起始端口必填(≥1),每台递增;模板里写了端口的也可留作单一设备使用。
        </p>
        {idConflicts.length > 0 && bulkResult === null && (
          <p className="problems">
            会话 id 已存在:{idConflicts.slice(0, 5).join('、')}
            {idConflicts.length > 5 ? ` 等 ${idConflicts.length} 个` : ''}(先关闭或换前缀)
          </p>
        )}
        {bulkResult !== null && (
          <div className="template-card">
            <strong>已创建 {bulkResult.created.length} 台</strong>
            {bulkResult.created.length > 0 && (
              <p className="hint mono">{bulkResult.created.join('、')}</p>
            )}
            {bulkResult.failed.length > 0 && (
              <ul className="problems">
                {bulkResult.failed.map((item) => (
                  <li key={item.index}>
                    第 {item.index} 台:{item.error === 'conflict' ? '会话 id 已存在' : item.error === 'failed' ? '端口被占或配置编译失败' : item.error}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </Modal>
    </Panel>
  );
}

/** 新增设备弹框：链接信息 + 设备模板 + 区域，一次填完再建。 */
function CreateDeviceModal({
  open,
  draft,
  templates,
  regionNames,
  busy,
  error,
  onChange,
  onClose,
  onCreate,
}: {
  open: boolean;
  draft: CreateDeviceDraft;
  templates: readonly ConfigTemplateEntry[];
  regionNames: readonly string[];
  busy: boolean;
  error: string;
  onChange: (draft: CreateDeviceDraft) => void;
  onClose: () => void;
  onCreate: (draft: CreateDeviceDraft) => void;
}): JSX.Element | null {
  const patch = (part: Partial<CreateDeviceDraft>) => onChange({ ...draft, ...part });
  const valid = draft.name.trim() !== '' && draft.templateId !== '' && templates.length > 0;
  return (
    <Modal
      open={open}
      title="新增设备"
      width="min(620px, 92vw)"
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button type="button" className="primary" disabled={busy || !valid} onClick={() => onCreate(draft)}>
            {busy ? '创建中…' : '创建设备'}
          </button>
        </>
      }
    >
      {error !== '' && <p className="problems">{error}</p>}
      <div className="form-row">
        <label className="field">
          设备名称
          <input
            value={draft.name}
            onChange={(event) => patch({ name: event.target.value })}
            placeholder="如 etch-line-a"
            aria-label="设备名称"
          />
        </label>
        <label className="field">
          设备模板
          <select
            value={draft.templateId}
            onChange={(event) => patch({ templateId: event.target.value })}
            aria-label="新建设备使用的模板"
          >
            {templates.length === 0 && <option value="">（还没有模板）</option>}
            {templates.map((item) => (
              <option key={item.id} value={item.id}>
                {item.label}（{item.id}）{item.source === 'custom' ? ' · 自定义' : ''}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="section-head"><h3>链接信息</h3></div>
      <div className="form-row">
        <label className="field">
          监听地址
          <input
            value={draft.host}
            onChange={(event) => patch({ host: event.target.value })}
            placeholder="0.0.0.0"
            aria-label="监听地址"
          />
        </label>
        <label className="field">
          端口
          <input
            type="number"
            min={1}
            max={65535}
            value={draft.port}
            onChange={(event) => patch({ port: event.target.value })}
            aria-label="端口"
          />
        </label>
        <label className="field">
          Device ID
          <input
            type="number"
            min={0}
            max={32767}
            value={draft.deviceId}
            onChange={(event) => patch({ deviceId: event.target.value })}
            aria-label="Device ID"
          />
        </label>
        <label className="field">
          连接模式
          <select
            value={draft.passive}
            onChange={(event) => patch({ passive: event.target.value })}
            aria-label="连接模式"
          >
            <option value="true">被动监听（等主机连入）</option>
            <option value="false">主动连接（连目标主机）</option>
          </select>
        </label>
      </div>

      <div className="section-head"><h3>区域分组</h3></div>
      <div className="form-row">
        <label className="field">
          所属区域
          <select
            value={draft.region}
            onChange={(event) => patch({ region: event.target.value })}
            aria-label="所属区域"
          >
            {regionNames.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
        </label>
        <label className="field">
          新建区域（可选）
          <input
            value={draft.newRegion}
            onChange={(event) => patch({ newRegion: event.target.value })}
            placeholder="填了就建新区域并归入"
            aria-label="新建区域"
          />
        </label>
      </div>
      <p className="hint">名称同时用作配置文件名与会话 id；端口不能与已有设备重复。</p>
    </Modal>
  );
}
