import type { HostSimCommands, HostSimView } from '../../../state/useHostSims';
import type { Notifier } from '../../../state/useToasts';
import type { PanelBaseProps } from '../types';
import { Modal } from '../../../shared/ui/Modal';
import { useState } from 'react';

/**
 * 主机实例选择条。
 *
 * <p>四个子页都要"当前操作的是哪个主机"，所以这个条共用：
 * 左边是实例下拉，右边是新建主机（连本机设备 / 连远程 SECS）。
 * 远程主机弹窗也由它自己管，各页不必再各放一个（避免同一个按钮出现两次）。</p>
 */
export function HostTargetPicker({
  backend,
  notifier,
  hostSims,
  allowCreate = true,
}: {
  backend: PanelBaseProps['backend'];
  notifier: Notifier;
  hostSims: HostSimCommands;
  /** 只有「主机实例」页允许新建；其它页只选已有实例。 */
  allowCreate?: boolean;
}): JSX.Element {
  const [session, setSession] = useState('');
  const [busy, setBusy] = useState(false);
  const [remoteOpen, setRemoteOpen] = useState(false);

  const devices = backend.sessions;
  /* 一台设备只允许一个本机主机：已有连接的选择项标出来并禁用 */
  const busySessions = new Set(
    hostSims.hosts.filter((host) => host.open && !host.remote).map((host) => host.session),
  );

  return (
    <>
      <div className="host-picker">
        <label className="field">
          当前主机
          <select
            value={hostSims.selectedId}
            onChange={(event) => hostSims.select(event.target.value)}
            aria-label="当前主机实例"
          >
            {hostSims.hosts.length === 0 && <option value="">（还没有主机）</option>}
            {hostSims.hosts.map((host) => (
              <option key={host.id} value={host.id}>
                {host.id} · {host.remote ? `远程 ${host.target}` : host.session}
                {host.open ? '' : '（已断开）'}
              </option>
            ))}
          </select>
        </label>

        {!allowCreate && (
          <>
            <span className="spacer" />
            <span className="hint-inline">主机的新建 / 断开在「主机实例」页；这里只选择操作对象。</span>
          </>
        )}

        {allowCreate && (
          <>
            <span className="spacer" />

            <label className="field">
              连本机设备
              <select
                value={session}
                onChange={(event) => setSession(event.target.value)}
                aria-label="选择本机设备"
              >
                <option value="">（选择设备）</option>
                {devices.map((item) => {
                  const occupied = busySessions.has(item.id);
                  return (
                    <option key={item.id} value={item.id} disabled={occupied}>
                      {item.device}（{item.id}）{occupied ? ' · 已有主机' : ''}
                    </option>
                  );
                })}
              </select>
            </label>
            <button
              type="button"
              disabled={busy || session === ''}
              onClick={() => {
                setBusy(true);
                void hostSims
                  .create({ session })
                  .then((ok) => {
                    if (ok) {
                      notifier.success(`主机已连接 ${session}`);
                    }
                  })
                  .catch((error: Error) => notifier.error('新建主机失败', error.message))
                  .finally(() => setBusy(false));
              }}
              title="以主机角色连这台设备（一台设备只允许一个主机，已有连接时会被拒绝）"
            >
              {busy ? '连接中…' : '新建本机主机'}
            </button>
            <button type="button" onClick={() => setRemoteOpen(true)} title="连远程 SECS（host / port / device-id 手填）">
              新建远程主机
            </button>
          </>
        )}
      </div>

      {allowCreate && (
        /* 远程实例也要挂在一个宿主会话上（后端按会话取本地模型与调度），
           优先用上面选的本机设备，其次用当前正在看的设备。 */
        <RemoteHostDialog
          open={remoteOpen}
          notifier={notifier}
          session={session !== '' ? session : backend.activeSession}
          hostSims={hostSims}
          onClose={() => setRemoteOpen(false)}
        />
      )}
    </>
  );
}

/** 远程主机弹窗：host / port / device-id。 */
export function RemoteHostDialog({
  open,
  notifier,
  session,
  hostSims,
  onClose,
}: {
  open: boolean;
  notifier: Notifier;
  /** 宿主会话：后端用它取本地模型/登记实例；空串时不允许连接。 */
  session: string;
  hostSims: HostSimCommands;
  onClose: () => void;
}): JSX.Element | null {
  const [host, setHost] = useState('');
  const [port, setPort] = useState('5000');
  const [deviceId, setDeviceId] = useState('0');
  const [busy, setBusy] = useState(false);

  const valid = session !== '' && host.trim() !== '' && Number(port) >= 1 && Number(port) <= 65535;

  return (
    <Modal
      open={open}
      title="新建远程主机"
      width="min(34rem, 96vw)"
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button
            type="button"
            className="primary"
            disabled={busy || !valid}
            onClick={() => {
              setBusy(true);
              void hostSims
                .create({
                  session,
                  host: host.trim(),
                  port: Number(port),
                  deviceId: Number(deviceId),
                })
                .then(() => {
                  notifier.success(`已连接远程 SECS ${host.trim()}:${port}`);
                  onClose();
                })
                .catch((error: Error) => {
                  /* 连接失败要把弹窗留着让用户改地址，而不是静默关掉 */
                  notifier.error('新建远程主机失败', error.message);
                })
                .finally(() => setBusy(false));
            }}
          >
            {busy ? '连接中…' : '连接'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <label className="field field-wide">
          主机地址
          <input
            value={host}
            onChange={(event) => setHost(event.target.value)}
            placeholder="如 192.168.1.50"
          />
        </label>
        <label className="field">
          端口
          <input
            type="number"
            min={1}
            max={65535}
            value={port}
            onChange={(event) => setPort(event.target.value)}
          />
        </label>
        <label className="field">
          Device ID
          <input
            type="number"
            min={0}
            value={deviceId}
            onChange={(event) => setDeviceId(event.target.value)}
          />
        </label>
      </div>
      <p className="hint">
        远程模式没有本机 catalog：S2F33 / S2F37 里的 SVID 与 CEID 要按对端设备手填。
        主机实例与本机设备的主机互不影响，可以同时开着。
      </p>
      <p className="hint">
        宿主会话：{session === '' ? '（无，先在左侧选一台本机设备）' : <code>{session}</code>}
      </p>
    </Modal>
  );
}
