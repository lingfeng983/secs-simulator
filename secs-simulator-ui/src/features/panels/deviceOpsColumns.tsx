import type { Dispatch, SetStateAction } from 'react';
import type { BackendController } from '../../state/useBackend';
import type { RegionsState } from '../../state/useRegions';
import { DEFAULT_REGION } from '../../state/useRegions';
import type { Column } from '../../shared/ui/DataTable';
import { formatCount } from '../../shared/lib/format';
import { controlStateLabel } from '../../shared/lib/controlState';
import {
  DEVICE_STATUS_LABEL,
  DEVICE_STATUS_TONE,
  deviceStatus,
  deviceStatusHint,
} from '../../shared/lib/deviceStatus';
import { openTargetOf, type DeviceRow } from './deviceOpsRows';

/** 设备表的一行动作落在面板上的两个确认框：停止与删除都不可撤销，先问一次。 */
export interface DeviceOpsColumnsHandlers {
  /** 单行「停止」：交由面板弹确认框。 */
  onStop: (row: DeviceRow) => void;
  /** 单行「删除」：交由面板弹确认框。 */
  onDelete: (row: DeviceRow) => void;
}

/**
 * 设备表的列定义。
 *
 * <p>读数列只展示，操作列与勾选列把动作交回面板（弹确认框），
 * 这样面板仍是唯一持有弹框状态的地方。</p>
 */
export function deviceOpsColumns({
  backend,
  goTo,
  regions,
  visible,
  selected,
  setSelected,
  allSelected,
  someSelected,
  onStop,
  onDelete,
}: {
  backend: BackendController;
  goTo?: (id: string) => void;
  regions?: RegionsState;
  visible: readonly DeviceRow[];
  selected: ReadonlySet<string>;
  setSelected: Dispatch<SetStateAction<ReadonlySet<string>>>;
  allSelected: boolean;
  someSelected: boolean;
} & DeviceOpsColumnsHandlers): Column<DeviceRow>[] {
  return [
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
                onClick={() => onStop(row)}
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
                  onDelete(row);
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
}