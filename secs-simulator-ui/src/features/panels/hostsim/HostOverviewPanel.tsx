import { useEffect, useState } from 'react';
import { Panel } from '../../../shared/ui/Panel';
import type { CollectorState } from '../../../api';
import { parseIdList, useCollectPlans, useTracePlans } from '../../../state/hostPlans';
import type { PanelArgs } from './hostPanelShared';

/**
 * 概览：名称（热生效）+ 连接目标（保存后手动「使设置生效」）+ 采集方案概览。
 *
 * <p>2026-11-18 P3：名称与目标分开保存。名称不参与建链，改完立刻生效、不涨
 * 设置版本；只有目标（地址 / 端口 / Device ID）变了才需要重建链路，那时才挂
 * 「设置待生效」并把「使设置生效」交给操作员点——改个名字不该顺手断一次正在
 * 采样的连接。</p>
 */
export function HostOverviewPanel({ hostId, hosts, readOnly, client }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [name, setName] = useState('');
  const [host_, setHost_] = useState('');
  const [port, setPort] = useState('5000');
  const [deviceId, setDeviceId] = useState('0');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loadedFor, setLoadedFor] = useState('');
  const [tracePlans] = useTracePlans(hostId);
  const [collectPlans] = useCollectPlans(hostId);
  const [collector, setCollector] = useState<CollectorState | null>(null);

  useEffect(() => {
    if (host !== null && loadedFor !== host.id) {
      setLoadedFor(host.id);
      setName(host.name);
      setHost_(host.target.split(':')[0] ?? '');
      setPort(String(host.port));
      setDeviceId(String(host.deviceId));
    }
  }, [host, loadedFor]);

  /* 采集概览：S1F3 采集器的运行状态是活数据，2 秒刷一次。 */
  useEffect(() => {
    if (client === null) {
      return;
    }
    let cancelled = false;
    const load = () => {
      void client.hostInstanceCollector(hostId)
        .then((body) => { if (!cancelled) { setCollector(body); } })
        .catch(() => undefined);
    };
    load();
    const timer = window.setInterval(load, 2_000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [client, hostId]);

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }
  /* 一台主机可以同时挂多份采集器：概览只给份数与合计，明细在「数据采集」页 */
  const collectorPlans = collector?.collectors ?? [];
  const runningPlans = collectorPlans.filter((item) => item.running === true);
  const totalSamples = runningPlans.reduce((sum, item) => sum + (item.samples ?? 0), 0);
  const totalErrors = runningPlans.reduce((sum, item) => sum + (item.decodeErrors ?? 0), 0);
  const totalSvids = collectorPlans.reduce((sum, item) => sum + (item.svids?.length ?? 0), 0);

  /* 目标地址在 host.target 里是 "host:port"，地址部分单独比 */
  const currentHost = host.target.split(':')[0] ?? '';
  const nameDirty = name.trim() !== host.name && name.trim() !== '';
  const targetDirty = host_ !== currentHost
    || Number(port) !== host.port
    || (Number(deviceId) || 0) !== host.deviceId;
  /* 设置版本没追平 = 有目标改动还没生效 */
  const pendingApply = host.settingRevision > host.appliedRevision;

  /**
   * 保存一段。名称段只提交名称（目标原样带回），目标段只提交目标（名称原样带回）——
   * 这样点「保存名称」绝不会顺手把目标也改了。
   */
  const save = (part: 'name' | 'target') => {
    setBusy(true);
    setError('');
    setNotice('');
    const request = part === 'name'
      ? { name: name.trim(), host: currentHost, port: host.port, deviceId: host.deviceId }
      : { name: host.name, host: host_.trim(), port: Number(port), deviceId: Number(deviceId) || 0 };
    hosts.saveSettings(hostId, request)
      .then((result) => {
        if (part === 'name') {
          setNotice('名称已保存（立即生效，连接未中断）');
          return;
        }
        setNotice(result.needsReconnect
          ? '目标已保存，尚未生效：点「使设置生效」重建链路'
          : '目标已保存，下次连接时生效');
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  const applySettings = () => {
    setBusy(true);
    setError('');
    setNotice('');
    hosts.apply(hostId)
      .then(() => setNotice('设置已生效（链路已按新目标重建）'))
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  return (
    <div className="panel-stack">
      {/* 子页签已经说明"这是哪一页"：面板不再单独占一行标题，直接从表单开始 */}
      <Panel id="panel-host-overview" title="主机概览" headless>
        {error !== '' && <p className="inline-note danger">{error}</p>}
        {notice !== '' && <p className="inline-note">{notice}</p>}

        {/* ---- 名称：热生效 ---- */}
        <div className="cfg-section">
          <div className="template-head">
            <h3>名称</h3>
            <span className="hint">不参与建链：保存后立即生效，不会中断连接</span>
          </div>
          <div className="form-row">
            <label className="field">
              名称
              <input value={name} disabled={readOnly}
                onChange={(event) => setName(event.target.value)} />
            </label>
          </div>
          {!readOnly && (
            <div className="editor-foot">
              <span className="hint">当前名称：{host.name}</span>
              <button
                type="button"
                className="primary"
                disabled={busy || !nameDirty}
                onClick={() => save('name')}
              >
                保存名称
              </button>
            </div>
          )}
        </div>

        {/* ---- 连接目标：保存 + 手动使设置生效（两步） ---- */}
        <div className="cfg-section">
          <div className="template-head">
            <h3>连接目标</h3>
            <span className="hint">改目标要重建链路：正在跑的 Trace / 采集会中断</span>
          </div>

          {pendingApply && (
            <div className="pending-banner settings">
              <span className="pending-tag">待生效</span>
              <span>
                设置 v{host.settingRevision} 已保存，当前生效的是 v{host.appliedRevision}。
                {host.open
                  ? '点「使设置生效」按新目标重建链路。'
                  : '主机未连接：下次连接会直接用新目标。'}
              </span>
              {!readOnly && (
                <button
                  type="button"
                  className="primary"
                  disabled={busy || !host.open}
                  title={host.open ? '断开并按新目标重连（Trace/采集会中断，计数保留）' : '主机未连接，无需重建'}
                  onClick={applySettings}
                >
                  使设置生效
                </button>
              )}
            </div>
          )}

          <div className="form-row">
            <label className="field">
              目标地址
              <input value={host_} disabled={readOnly}
                onChange={(event) => setHost_(event.target.value)} />
            </label>
            <label className="field">
              端口
              <input type="number" min={1} max={65535} value={port} disabled={readOnly}
                onChange={(event) => setPort(event.target.value)} />
            </label>
          </div>
          <div className="form-row">
            <label className="field">
              Device ID
              <input type="number" min={0} value={deviceId} disabled={readOnly}
                onChange={(event) => setDeviceId(event.target.value)} />
            </label>
          </div>
          {!readOnly && (
            <div className="editor-foot">
              <span className="hint">
                当前设置 v{host.settingRevision} · {host.open ? `生效 v${host.appliedRevision}` : '离线'}
                {host.autoReconnect ? ' · 自动重连开' : ''}
              </span>
              <button
                type="button"
                className="primary"
                disabled={busy || !targetDirty || host_.trim() === '' || !(Number(port) >= 1 && Number(port) <= 65535)}
                onClick={() => save('target')}
              >
                保存目标
              </button>
            </div>
          )}
        </div>
      </Panel>

      {/* 需求：概览页能一眼看到后面 Trace 采集 / S1F3 采集的配置与运行状态 */}
      <Panel id="panel-host-overview-collect" title="采集概览" headless>
        <div className="overview-grid">
          <div className="overview-card">
            <h3>Trace 采集方案（S2F23 → S6F1）</h3>
            <span className="overview-value">{tracePlans.length}</span>
            <span className="hint-inline"> 份</span>
            {tracePlans.length === 0 ? (
              <p className="hint">还没有保存方案；到「配置区 · Trace 设置」保存后可一键调用。</p>
            ) : (
              <ul>
                {tracePlans.slice(0, 6).map((plan) => (
                  <li key={plan.id}>{plan.name} · TRID {plan.trid} · {parseIdList(plan.svids).length} 个变量</li>
                ))}
                {tracePlans.length > 6 && <li>…另有 {tracePlans.length - 6} 份</li>}
              </ul>
            )}
          </div>
          <div className="overview-card">
            <h3>S1F3 采集方案</h3>
            <span className="overview-value">{collectPlans.length}</span>
            <span className="hint-inline"> 份</span>
            {collectPlans.length === 0 ? (
              <p className="hint">还没有保存方案；到「配置区 · 采集设置」保存后可一键下发。</p>
            ) : (
              <ul>
                {collectPlans.slice(0, 6).map((plan) => (
                  <li key={plan.id}>{plan.name} · CEID {plan.startCeid}/{plan.stopCeid} · {parseIdList(plan.svids).length} 个变量</li>
                ))}
                {collectPlans.length > 6 && <li>…另有 {collectPlans.length - 6} 份</li>}
              </ul>
            )}
          </div>
          <div className="overview-card">
            <h3>S1F3 采集器当前状态</h3>
            <span className={`overview-value${runningPlans.length > 0 ? ' tone-ok' : ''}`}>
              {runningPlans.length > 0
                ? `采集中 ${runningPlans.length} 份`
                : collectorPlans.length > 0 ? '已配置 · 待启动' : '未配置'}
            </span>
            <ul>
              <li>采集计划：{collectorPlans.length} 份{runningPlans.length > 0 ? `（在采 ${runningPlans.length} 份）` : ''}</li>
              <li>样本点：{totalSamples} · 解码错误：{totalErrors}</li>
              <li>采集变量：{totalSvids} 个</li>
            </ul>
          </div>
        </div>
      </Panel>
    </div>
  );
}
