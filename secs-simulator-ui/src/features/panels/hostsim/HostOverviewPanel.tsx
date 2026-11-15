import { useEffect, useState } from 'react';
import { Panel } from '../../../shared/ui/Panel';
import type { CollectorState } from '../../../api';
import { parseIdList, useCollectPlans, useTracePlans } from '../../../state/hostPlans';
import type { PanelArgs } from './hostPanelShared';

/** 概览:名称/目标编辑 + 保存(设置版本 +1,需「使设置生效」) + 采集方案概览。 */
export function HostOverviewPanel({ hostId, hosts, readOnly, client }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [name, setName] = useState('');
  const [host_, setHost_] = useState('');
  const [port, setPort] = useState('5000');
  const [deviceId, setDeviceId] = useState('0');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
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
  const dirty = name !== host.name
    || host_ !== host.target.split(':')[0]
    || Number(port) !== host.port
    || Number(deviceId) !== host.deviceId;

  return (
    <div className="panel-stack">
      {/* 子页签已经说明"这是哪一页"：面板不再单独占一行标题，直接从表单开始 */}
      <Panel id="panel-host-overview" title="主机概览" headless>
        {error !== '' && <p className="inline-note danger">{error}</p>}
        <div className="form-row">
          <label className="field">
            名称
            <input value={name} disabled={readOnly}
              onChange={(event) => setName(event.target.value)} />
          </label>
          <label className="field">
            目标地址
            <input value={host_} disabled={readOnly}
              onChange={(event) => setHost_(event.target.value)} />
          </label>
        </div>
        <div className="form-row">
          <label className="field">
            端口
            <input type="number" min={1} max={65535} value={port} disabled={readOnly}
              onChange={(event) => setPort(event.target.value)} />
          </label>
          <label className="field">
            Device ID
            <input type="number" min={0} value={deviceId} disabled={readOnly}
              onChange={(event) => setDeviceId(event.target.value)} />
          </label>
        </div>
        <div className="editor-foot">
          <span className="hint">
            当前设置 v{host.settingRevision} · {host.open ? `生效 v${host.appliedRevision}` : '离线'}
            {host.autoReconnect ? ' · 自动重连开' : ''}
          </span>
          {!readOnly && (
            <button
              type="button"
              className="primary"
              disabled={busy || !dirty || host_.trim() === '' || !(Number(port) >= 1 && Number(port) <= 65535)}
              onClick={() => {
                setBusy(true);
                setError('');
                hosts.saveSettings(hostId, {
                  name: name.trim(), host: host_.trim(), port: Number(port), deviceId: Number(deviceId) || 0,
                })
                  .catch((e: Error) => setError(e.message))
                  .finally(() => setBusy(false));
              }}
            >
              保存设置
            </button>
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
            <span className={`overview-value${collector?.running ? ' tone-ok' : ''}`}>
              {collector?.running ? '采集中' : collector?.configured ? '已配置 · 待启动' : '未配置'}
            </span>
            <ul>
              <li>启动 / 停止 CEID：{collector?.startCeid ?? '—'} / {collector?.stopCeid ?? '—'}</li>
              <li>采样周期：{collector?.intervalMs ?? '—'} ms</li>
              <li>样本点：{collector?.samples ?? 0} · 解码错误：{collector?.decodeErrors ?? 0}</li>
              <li>采集变量：{collector?.svids?.length ?? 0} 个</li>
            </ul>
          </div>
        </div>
      </Panel>
    </div>
  );
}