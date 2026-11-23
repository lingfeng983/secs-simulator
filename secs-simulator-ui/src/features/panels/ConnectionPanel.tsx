import type { PanelBaseProps } from './types';
import { ConfigEditShell } from './ConfigEditShell';
import { SessionForm } from '../editors/ConfigFormEditor';

/**
 * 设备 · 链接配置：配置里的 session 段。
 *
 * <p>这段只有它能改：端口 / 绑定地址 / Device ID 变化必须断链重建，
 * 是唯一会「全清」的一页（保存成功后会提示主机重连、运行态清空）。
 * 2026-11-20 起顶部不再有校验/重新载入；2026-11-21 起「保存」挪到表单最后，
 * 高级参数（超时 / Trace 容量）不再折叠，直接平铺。</p>
 */
export function ConnectionPanel({ backend, sessionId, configName }: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-connection"
      title="链接配置"
      sections={['session']}
      /* 这段改的是监听口，保存一定重建会话：设备启动着时先问一句 */
      confirmOnSave={{
        title: '保存链接参数并重启会话？',
        message: '端口 / 绑定地址 / Device ID 改完要按新参数重建会话：端口会重新监听，'
          + '当前连接会断开，手动设定的变量、计划启用态、周期进度等运行状态全部清空。',
        confirmLabel: '保存并重启会话',
      }}
    >
      {({ root, edit, save, busy }) => (
        <>
          <SessionForm root={root} edit={edit} />
          {/* 保存按钮在表单最后（2026-11-21 用户要求）：改完一口气按一次 */}
          <div className="editor-foot">
            <span />
            <button
              type="button"
              className="primary"
              disabled={busy}
              title="保存链接参数；设备启动中会先问一次是否按新参数重启会话"
              onClick={() => void save()}
            >
              保存
            </button>
          </div>
        </>
      )}
    </ConfigEditShell>
  );
}
