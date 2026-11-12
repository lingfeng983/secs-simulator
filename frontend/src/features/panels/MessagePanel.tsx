import { useMemo } from 'react';
import type { PanelBaseProps } from './types';
import { Panel, PanelFootnote } from '../../shared/ui/Panel';
import { MessageFeed } from '../../shared/ui/MessageFeed';
import { safeFilename } from '../../shared/lib/table';
import { saveHint, saveTextFile } from '../../shared/lib/saveFile';

/**
 * 设备页 · 消息：本设备与主机之间的报文收发。
 *
 * <p>方向以设备为准：<b>收</b> 是主机发来的 primary，<b>发</b> 是设备回给主机
 * 或主动上报。探测/采集/上报通常同时存在，用类别过滤把不关心的先屏蔽。</p>
 */
export function MessagePanel({ backend, messages = [], notifier }: PanelBaseProps): JSX.Element {
  const sessionId = backend.activeSession;
  const own = useMemo(
    () => messages.filter((message) => (
      message.sessionId === sessionId
      /* 远程主机的报文挂在宿主会话名下，但设备侧从没看见过它：
         留在设备页会让人以为是本设备收发的，所以排除（它们只在「消息监控」里）。 */
      && (message.hostId === undefined || message.hostId === '')
    )),
    [messages, sessionId],
  );
  const inbound = own.filter((message) => message.direction === 'in').length;
  const outbound = own.length - inbound;

  /** 只导出本设备的报文（设备视角，方向不翻转）。 */
  const exportDeviceLog = async () => {
    if (sessionId === '' || backend.client === null) {
      return;
    }
    try {
      const text = await backend.client.messagesJsonl(sessionId, '', 5000);
      /* 能弹保存位置就弹，并在提示里说清文件去了哪 */
      const result = await saveTextFile(
        `messages-device-${safeFilename(sessionId)}.jsonl`,
        text,
        'application/jsonl;charset=utf-8',
      );
      const hint = saveHint(result);
      if (hint !== '') {
        notifier.success('已导出本设备报文', hint);
      }
    } catch (error) {
      notifier.error('导出本设备报文失败', (error as Error).message);
    }
  };

  return (
    <Panel
      id="panel-messages"
      title="消息"
      description="本设备与主机之间的报文收发。可以按类别屏蔽探测、采集、上报等消息，只看当前关心的那一类。"
      actions={
        <button
          type="button"
          onClick={() => void exportDeviceLog()}
          disabled={sessionId === '' || backend.client === null}
          title="导出本设备报文（可选择保存位置）"
        >
          导出本设备报文
        </button>
      }
    >
      <MessageFeed
        messages={own}
        perspective="device"
        emptyText="还没有报文。"
        emptyHint="主机连上来并开始发报文后，这里实时滚动；也可以到「发送」页手动发一条。"
      />
      <PanelFootnote
        items={[
          { label: '收（主机→设备）', value: inbound },
          { label: '发（设备→主机）', value: outbound },
          { label: '本设备会话', value: sessionId === '' ? '—' : sessionId },
        ]}
      />
    </Panel>
  );
}
