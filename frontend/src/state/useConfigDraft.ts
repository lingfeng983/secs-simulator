import { useCallback, useEffect, useRef, useState } from 'react';
import type { BackendController } from './useBackend';
import { createRequestSeq, isFreshResponse } from './requestSeq';

/**
 * 设备配置草稿：读、改、校验、保存、重新载入。
 *
 * <p>「连接」「结构」两个结构化子页共用这份状态机；配置页只编辑原文，
 * 不再各自实现一套保存逻辑。</p>
 */
export interface ConfigDraftState {
  configName: string;
  text: string;
  dirty: boolean;
  busy: boolean;
  problems: string[];
  notice: string;
  setText: (next: string) => void;
  validate: () => Promise<void>;
  save: () => Promise<void>;
  reload: () => Promise<void>;
}

export function useConfigDraft(backend: BackendController, sessionId: string): ConfigDraftState {
  const configName = backend.sessions.find((session) => session.id === sessionId)?.config ?? '';
  const [text, setTextState] = useState('');
  const [baseline, setBaseline] = useState('');
  const [loadedMs, setLoadedMs] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [problems, setProblems] = useState<string[]>([]);
  const [notice, setNotice] = useState('');
  /** 请求序号：切换配置/重新载入时旧响应可能后到，不能让它覆盖新内容。 */
  const requestSeq = useRef(createRequestSeq());
  /* 渲染期同步最新配置名：异步回调里读到的必须是"现在"打开的那份 */
  const configNameRef = useRef(configName);
  configNameRef.current = configName;

  const load = useCallback(async (name: string, message = ''): Promise<void> => {
    const token = requestSeq.current.begin();
    setBusy(true);
    try {
      const snapshot = await backend.readConfig(name);
      /* 期间又发起了新的载入，或配置名已经切走：这是过期响应，直接丢 */
      if (!isFreshResponse(token, requestSeq.current, name, configNameRef.current)) {
        return;
      }
      if (snapshot === null) {
        /* 读不到就把草稿清空：否则切换配置失败后，界面上仍是上一份正文，
           而 configName 已经是新的——保存会把 A 写进 B。 */
        setTextState('');
        setBaseline('');
        setLoadedMs(null);
        setProblems([]);
        setNotice(`读不到 ${name}，请确认文件是否存在`);
        return;
      }
      setTextState(snapshot.text);
      setBaseline(snapshot.text);
      setLoadedMs(snapshot.modifiedMs);
      setProblems([]);
      setNotice(message);
    } finally {
      /* 只有最新一次请求负责收尾忙碌态，过期响应不能把新请求的 loading 清掉 */
      if (requestSeq.current.isCurrent(token)) {
        setBusy(false);
      }
    }
  }, [backend]);

  useEffect(() => {
    if (configName === '') {
      /* 会话被关掉/重载失败时 configName 变为空串：必须把忙碌态一起收掉，
         否则保存中的 finally 因名字已变而不清，按钮会永久变灰。 */
      setTextState('');
      setBaseline('');
      setLoadedMs(null);
      setProblems([]);
      setNotice('');
      setBusy(false);
      return;
    }
    void load(configName);
    // 只在配置名变化时自动打开；编辑中的草稿不被轮询覆盖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [configName]);

  const setText = (next: string) => {
    setTextState(next);
    setProblems([]);
  };

  const validate = async () => {
    if (configName === '') {
      return;
    }
    /* 校验走 60 秒长超时：期间切配置时旧结果不能写到新配置上，
       收尾也只能由最新一次请求负责。 */
    const targetName = configName;
    const token = requestSeq.current.begin();
    setBusy(true);
    try {
      const result = await backend.validateConfig(targetName, text);
      if (!isFreshResponse(token, requestSeq.current, targetName, configNameRef.current)
              || result === null) {
        return;
      }
      setProblems(result.problems);
      setNotice(result.valid ? '校验通过' : '校验未通过');
    } finally {
      if (requestSeq.current.isCurrent(token)) {
        setBusy(false);
      }
    }
  };

  const save = async () => {
    if (configName === '') {
      return;
    }
    /* 保存期间用户可能切到另一份配置：结果只能落到发起时的那一份 */
    const targetName = configName;
    setBusy(true);
    try {
      const result = await backend.saveConfig(targetName, text, loadedMs);
      if (result === null || configNameRef.current !== targetName) {
        return;
      }
      if (result.status === 200) {
        setProblems([]);
        setBaseline(text);
        const fresh = await backend.readConfig(targetName);
        /* 取 modifiedMs 也可能被切配置抢先，回来再确认一次 */
        if (fresh !== null && configNameRef.current === targetName) {
          setLoadedMs(fresh.modifiedMs);
        }
        /* 只说"已保存"。是否已让会话生效由重载那条路径自己报（它会区分
           成功几个、失败几个）——这里再写一句"相关会话已按新配置重载"，
           在重载全失败时就是假话。 */
        setNotice(`已保存 ${targetName}`);
        void backend.refreshConfigs();
      } else if (result.status === 422) {
        setProblems(result.problems);
        setNotice('保存被拒：配置有问题，文件没有改动');
      } else if (result.status === 409) {
        setNotice('保存被拒：配置在编辑期间被其他窗口修改过——请先「重新载入」');
      } else {
        setNotice(`保存失败（HTTP ${result.status}）`);
      }
    } finally {
      /* 切配置后，新配置的载入可能已经在跑；别把它的忙碌态清掉 */
      if (configNameRef.current === targetName) {
        setBusy(false);
      }
    }
  };

  const reload = async () => {
    if (configName === '') {
      return;
    }
    await load(configName, `已重新载入 ${configName}`);
  };

  return {
    configName,
    text,
    dirty: text !== baseline,
    busy,
    problems,
    notice,
    setText,
    validate,
    save,
    reload,
  };
}
