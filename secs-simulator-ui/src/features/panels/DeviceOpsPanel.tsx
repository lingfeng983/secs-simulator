import { useEffect, useRef, useState } from 'react';
import type { ConfigTemplateEntry } from '../../api';
import { Panel } from '../../shared/ui/Panel';
import { DataTable } from '../../shared/ui/DataTable';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { saveHint } from '../../shared/lib/saveFile';
import { DEVICE_STATUS_LABEL, deviceStatus, type DeviceStatus } from '../../shared/lib/deviceStatus';
import { controlStateLabel } from '../../shared/lib/controlState';
import type { PanelBaseProps } from './types';
import { DEFAULT_REGION } from '../../state/useRegions';
import { STATE_FILTER_ORDER, openTargetOf, useDeviceRows } from './deviceOpsRows';
import { deviceOpsColumns } from './deviceOpsColumns';
import { CreateDeviceModal, parseBatchNumbers, type CreateDeviceDraft } from './CreateDeviceModal';
import { SyncConfigModal } from './SyncConfigModal';

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
    startNow: true,
    batch: false, count: '5', prefix: 'dev-', portBase: '5100', deviceIdBase: '0',
  });
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchProgress, setBatchProgress] = useState<string>('');
  /** 待确认的断开目标:null = 不弹;label 是确认文案里的人话描述。 */
  const [closeTarget, setCloseTarget] = useState<{ ids: readonly string[]; label: string } | null>(null);
  /** 待确认的删除目标：配置文件名 + 人话描述。删设备是不可撤销的，一律先问一次。 */
  const [deleteTarget, setDeleteTarget] = useState<{ configs: readonly string[]; label: string } | null>(null);
  const [syncOpen, setSyncOpen] = useState(false);
  const [syncPortBase, setSyncPortBase] = useState(5100);
  const [syncDeviceBase, setSyncDeviceBase] = useState(0);
  const [newTemplateId, setNewTemplateId] = useState('bare');
  const [templateList, setTemplateList] = useState<ConfigTemplateEntry[]>([]);
  /* 连续新建时轮询还没刷新，用本地已分配端口兜底，避免两台设备抢同一端口 */
  const allocatedPorts = useRef<Set<number>>(new Set());
  const { sessions, overview, configList, readConfig, batchReload, batchClose } = backend;

  /* 模板目录：工作台新建设备只选模板，不再手填配置名 */
  useEffect(() => {
    if (backend.client === null || backend.client === undefined) {
      return;
    }
    let cancelled = false;
    void backend.client.templates()
      .then((list) => {
        if (cancelled) {
          return;
        }
        setTemplateList(list);
        if (list.length > 0) {
          setNewTemplateId((previous) => (list.some((item) => item.id === previous) ? previous : list[0].id));
        }
      })
      .catch((error: unknown) => {
        /* 后端不可达时下拉框会静默为空，至少要在提示里说清原因 */
        if (!cancelled) {
          notifier.error('读取模板清单失败', (error as Error).message);
        }
      });
    return () => { cancelled = true; };
  }, [backend.client, notifier]);

  const {
    visible,
    configPorts,
    selected,
    setSelected,
    selectedRows,
    selectedSessionIds,
    selectedConfigs,
    allSelected,
    someSelected,
  } = useDeviceRows({
    sessions,
    overview,
    configList,
    client: backend.client,
    readConfig,
    stateFilter,
    regionFilter,
    regions,
  });

  const columns = deviceOpsColumns({
    backend,
    goTo,
    regions,
    visible,
    selected,
    setSelected,
    allSelected,
    someSelected,
    onStop: (row) => setCloseTarget({ ids: [row.id], label: row.id }),
    onDelete: (row) => setDeleteTarget({ configs: [row.config ?? ''], label: `设备 ${row.device}` }),
  });

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
      startNow: true,
      /* 批量的起始端口跟着单台端口一起避开已占用段 */
      batch: false,
      count: '5',
      prefix: 'dev-',
      portBase: String(port),
      deviceIdBase: '0',
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

  /**
   * 批量新建设备：按模板建（或复用）一份基础配置，再走 /sessions/bulk 逐台递增开多台。
   *
   * <p>第 i 台的会话 id/端口/Device ID 是运行期覆盖，不写回配置文件——
   * 一批设备共用一份配置本体（单元、变量、事件都一致）。基础配置名带模板 id，
   * 同「前缀+模板」的批次复用同一份，不同模板互不覆盖。</p>
   */
  const createBatchDevices = async (draft: CreateDeviceDraft) => {
    if (backend.client === null || backend.client === undefined) {
      return;
    }
    const numbers = parseBatchNumbers(draft);
    if (numbers === null) {
      setCreateError('数量、起始端口、Device ID 起始都需要是整数');
      return;
    }
    const { count, prefix, portBase, deviceIdBase } = numbers;
    if (count < 1 || count > 100) {
      setCreateError('数量需要是 1–100 之间的整数');
      return;
    }
    if (prefix === '' || /[/\\"\s]/.test(prefix)) {
      setCreateError('会话 id 前缀不能为空，且不能含空白、斜杠或引号');
      return;
    }
    if (portBase < 1 || portBase + count - 1 > 65535) {
      setCreateError(`起始端口需要在 1–${65536 - count} 之间（${count} 台逐台递增）`);
      return;
    }
    if (deviceIdBase < 0 || deviceIdBase + count - 1 > 32767) {
      setCreateError(`Device ID 起始需要在 0–${32768 - count} 之间（${count} 台逐台递增）`);
      return;
    }
    if (draft.templateId === '') {
      setCreateError('请选择设备模板');
      return;
    }
    /* 会话 id 冲突预检：这是批量重建最常见的坑，提前算比逐台失败友好 */
    const takenIds = new Set(sessions.map((item) => item.id));
    const conflicts = Array.from({ length: count }, (_, i) => `${prefix}${i + 1}`)
      .filter((id) => takenIds.has(id));
    if (conflicts.length > 0) {
      setCreateError(
        `会话 id 已存在：${conflicts.slice(0, 5).join('、')}${conflicts.length > 5 ? ` 等 ${conflicts.length} 个` : ''}；换个前缀，或先删除同名设备`,
      );
      return;
    }
    /* 基础配置名带模板 id：同模板+同前缀的批次复用同一份，不同模板互不覆盖 */
    const stem = prefix.replace(/[^\w.-]+/g, '-').replace(/[._-]+$/, '');
    const baseName = `${stem === '' ? 'batch' : stem}-${draft.templateId}-base.json`;
    const bindHost = draft.host.trim() === '' ? '0.0.0.0' : draft.host.trim();
    setCreateError('');
    setBatchBusy(true);
    setBatchProgress('批量新建…');
    try {
      const freshConfigs = await backend.client.configs();
      const exists = freshConfigs.some((entry) => entry.name === baseName)
        || configList.some((entry) => entry.name === baseName);
      if (!exists) {
        const status = await backend.client.createConfig(baseName, draft.templateId);
        if (status !== 201 && status !== 200) {
          setCreateError(`按模板创建基础配置失败（HTTP ${status}）`);
          return;
        }
        /* 被动模式与监听地址写进基础配置；端口/Device ID 由批量接口逐台覆盖 */
        const snapshot = await backend.readConfig(baseName);
        if (snapshot !== null) {
          try {
            const root = JSON.parse(snapshot.text) as Record<string, unknown>;
            const session = root.session !== null && typeof root.session === 'object'
              ? root.session as Record<string, unknown>
              : {};
            session.passive = draft.passive === 'true';
            session.bindHost = bindHost;
            root.session = session;
            await backend.saveConfig(baseName, JSON.stringify(root, null, 2), null);
          } catch {
            /* 模板原文不是合法 JSON 时由后续创建报错 */
          }
        }
      }
      const result = await backend.bulkCreateSessions({
        config: baseName,
        count,
        sessionIdPrefix: prefix,
        portBase,
        deviceIdBase,
        bindHost,
      });
      await backend.refreshConfigs();
      if (result === null) {
        /* 失败详情已由 bulkCreateSessions 自己提示；弹框留着改参数重试 */
        return;
      }
      const region = draft.newRegion.trim() === '' ? draft.region : draft.newRegion.trim();
      if (region !== '') {
        regions?.addRegion(region);
        for (const id of result.created) {
          regions?.setDeviceRegion(id, region);
        }
      }
      setCreateOpen(false);
      const failedNote = result.failed.length > 0 ? `，${result.failed.length} 台失败` : '';
      notifier.success(
        `已批量新建设备 ${result.created.length} 台${failedNote}`,
        `会话 id ${prefix}1 起，配置 ${baseName}`,
      );
    } finally {
      setBatchBusy(false);
      setBatchProgress('');
    }
  };

  /** 按弹框里填的链接信息 + 模板建设备：配置文件名取设备名，其余写进 session 段；批量模式走批量路径。 */
  const createFromTemplate = async (draft: CreateDeviceDraft) => {
    if (draft.batch) {
      await createBatchDevices(draft);
      return;
    }
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
      /* 「立即启动」开关：关掉则只保存设备，清单里以停止态出现，由人点「启动」 */
      if (draft.startNow) {
        await backend.createSession(name);
      }
      await backend.refreshConfigs();
      const region = draft.newRegion.trim() === '' ? draft.region : draft.newRegion.trim();
      if (region !== '') {
        regions?.addRegion(region);
        regions?.setDeviceRegion(name, region);
      }
      setCreateOpen(false);
      notifier.success(
        `已新建设备 ${sessionId}`,
        draft.startNow
          ? `端口 ${port}，Device ID ${deviceId}，已启动`
          : `端口 ${port}，Device ID ${deviceId}；未启动，可在清单点「启动」`,
      );
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

      <SyncConfigModal
        open={syncOpen}
        sessionIds={selectedSessionIds}
        portBase={syncPortBase}
        deviceBase={syncDeviceBase}
        busy={batchBusy}
        onPortBaseChange={setSyncPortBase}
        onDeviceBaseChange={setSyncDeviceBase}
        onClose={() => setSyncOpen(false)}
        onConfirm={() => void syncSelected()}
      />

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

    </Panel>
  );
}