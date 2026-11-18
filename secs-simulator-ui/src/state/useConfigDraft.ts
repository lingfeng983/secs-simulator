import { useEffect, useRef, useState } from 'react';
import type { BackendController } from './useBackend';
import type { ConfigSectionEffect, ConfigSectionName } from '../api';
import { parseConfigDocument } from '../features/editors/templateConfig';
import {
  CONFIG_SECTION_NAMES,
  changedSections,
  effectNote,
  sectionLabel,
  sectionLabels,
  sectionPayload,
} from '../shared/lib/configSections';
import {
  getDraftSnapshot,
  loadDraft,
  markAllSaved,
  markSectionsSaved,
  setDraftModifiedMs,
  setDraftText,
  useDraftSnapshot,
} from './configDraftStore';

/** 需要用户点头才能继续的动作（保存前确认 / 丢弃改动前确认）。 */
export interface DraftConfirm {
  kind: 'ecv-overwrite' | 'reload-discard' | 'save-restart';
  title: string;
  message: string;
  confirmLabel: string;
  danger: boolean;
}

/** 「设备启动着，保存会打断运行」的确认文案。 */
export interface SaveRestartConfirm {
  title: string;
  message: string;
  confirmLabel: string;
}

/**
 * 设备配置草稿：读、改、校验、保存、重新载入。
 *
 * <p>草稿本身放在 {@link import('./configDraftStore')} 里、按配置名共享：
 * 切页不再重新读盘，别的页面未保存的改动不会因为切走一次就消失。</p>
 *
 * <p>2026-11-18「按页保存、按段生效」：页面通过 {@code sections} 声明自己拥有
 * 哪几段，保存只提交这些段；脏状态也分两级——本页脏、其它页还有脏。
 * 不传 {@code sections} 时仍是整份保存（「配置原文」页的场景）。</p>
 */
export interface ConfigDraftState {
  configName: string;
  text: string;
  /** 本页拥有并改过的段 */
  dirty: boolean;
  /** 属于别的页面、且尚未保存的段 */
  dirtyElsewhere: readonly ConfigSectionName[];
  busy: boolean;
  problems: string[];
  notice: string;
  /** 非空时页面要弹确认框（常量覆盖、丢弃未保存改动） */
  confirm: DraftConfirm | null;
  /** 本页拥有哪些段（提示用；整份保存时为空） */
  sections: readonly ConfigSectionName[];
  setText: (next: string) => void;
  validate: () => Promise<void>;
  save: (options?: { confirmOverwrite?: boolean; confirmed?: boolean }) => Promise<void>;
  reload: () => Promise<void>;
  /** 确认当前 confirm 并继续 */
  confirmPending: () => Promise<void>;
  cancelPending: () => void;
}

export function useConfigDraft(
  backend: BackendController,
  sessionId: string,
  configNameOverride?: string,
  sections?: readonly ConfigSectionName[],
  confirmOnSave?: SaveRestartConfirm,
): ConfigDraftState {
  /* 显式配置名优先：数据库里还没启动的设备没有会话行，设备页直接给它配置文件名。 */
  const configName = configNameOverride ?? backend.sessions.find((session) => session.id === sessionId)?.config ?? '';
  const snapshot = useDraftSnapshot(configName);
  const [busy, setBusy] = useState(false);
  const [problems, setProblems] = useState<string[]>([]);
  const [notice, setNotice] = useState('');
  const [confirm, setConfirm] = useState<DraftConfirm | null>(null);
  /* 渲染期同步最新值：异步回调里读到的必须是"现在"打开的那份 */
  const configNameRef = useRef(configName);
  configNameRef.current = configName;
  const backendRef = useRef(backend);
  backendRef.current = backend;
  const sectionsRef = useRef<readonly ConfigSectionName[]>(sections ?? []);
  sectionsRef.current = sections ?? [];
  const ownedSections = sectionsRef.current;
  /** 保存前确认（连接页：设备启动中保存 = 重建会话）。 */
  const confirmOnSaveRef = useRef<SaveRestartConfirm | undefined>(confirmOnSave);
  confirmOnSaveRef.current = confirmOnSave;
  /** 设备是不是启动着——判据是端口开着，不是 GEM 的 ONLINE。 */
  const sessionStarted = (): boolean => backendRef.current.sessions.some(
    (session) => session.id === sessionId && (session.state === 'OPEN' || session.state === 'OPENING'),
  );

  /* 首次挂载 / 换配置时读一次盘；已经有草稿就沿用（切页不再读盘） */
  useEffect(() => {
    if (configName === '') {
      return;
    }
    void loadDraft(configName, () => backendRef.current.readConfig(configName));
  }, [configName]);

  /* 读盘失败的说明搬到页面提示（仓库里只放事实） */
  useEffect(() => {
    if (snapshot.message !== '') {
      setNotice(snapshot.message);
    }
  }, [snapshot.message]);

  /* 换配置时清掉上一份的临时提示 */
  useEffect(() => {
    setProblems([]);
    setNotice('');
    setConfirm(null);
  }, [configName]);

  const scope = ownedSections.length > 0 ? ownedSections : CONFIG_SECTION_NAMES;
  const dirtyHere = ownedSections.length > 0
    ? snapshot.parseable && changedSections(snapshot.current, snapshot.baseline, scope).length > 0
    : snapshot.text !== snapshot.baselineText;
  const dirtyElsewhere = ownedSections.length > 0
    ? changedSections(
      snapshot.current,
      snapshot.baseline,
      CONFIG_SECTION_NAMES.filter((section) => !ownedSections.includes(section)),
    )
    : [];

  const setText = (next: string) => {
    setDraftText(configName, next);
    setProblems([]);
  };

  const validate = async () => {
    if (configName === '') {
      return;
    }
    const targetName = configName;
    const text = getDraftSnapshot(targetName).text;
    setBusy(true);
    try {
      const result = await backend.validateConfig(targetName, text);
      if (configNameRef.current !== targetName || result === null) {
        return;
      }
      setProblems(result.problems);
      setNotice(result.valid ? '校验通过' : '校验未通过');
    } finally {
      if (configNameRef.current === targetName) {
        setBusy(false);
      }
    }
  };

  /** 保存本页（有 sections 时按段保存，否则整份保存）。 */
  const save = async ({ confirmOverwrite = false, confirmed = false }: { confirmOverwrite?: boolean; confirmed?: boolean } = {}) => {
    if (configName === '') {
      return;
    }
    const targetName = configName;
    const owned = sectionsRef.current;
    /* 设备启动着 + 这页保存会重建会话：先让操作员确认一次（连接页改端口） */
    const restart = confirmOnSaveRef.current;
    if (!confirmed && restart !== undefined && sessionStarted()) {
      setConfirm({
        kind: 'save-restart',
        title: restart.title,
        message: restart.message,
        confirmLabel: restart.confirmLabel,
        danger: true,
      });
      return;
    }
    /* 用仓库里的最新快照：闭包里的 snapshot 可能落后于最后一次输入提交 */
    const snap = getDraftSnapshot(targetName);
    setBusy(true);
    try {
      if (owned.length === 0) {
        await saveWhole(targetName, snap.text, snap.modifiedMs);
        return;
      }

      const parsed = parseConfigDocument(snap.text);
      if (!parsed.ok) {
        setNotice(`配置原文不是合法 JSON，没有保存：${parsed.error}`);
        return;
      }
      /* 本页没有任何实际改动：不发请求，也别推进基线 */
      if (changedSections(snap.current, snap.baseline, owned).length === 0) {
        setNotice('本页没有改动，没有保存');
        return;
      }
      const payload = sectionPayload(owned, parsed.config.root);
      const result = await backend.saveConfigSections(targetName, payload, snap.modifiedMs, confirmOverwrite);
      if (result === null || configNameRef.current !== targetName) {
        return;
      }

      if (result.status === 200) {
        setProblems([]);
        /* 只推进服务端判定为"确实写入了"的那几段，别页的脏保持原样 */
        markSectionsSaved(targetName, result.changed);
        await refreshModifiedMs(targetName);
        setConfirm(null);
        /* 成功不写页面内提示：右下角已经有 toast。下面只在"有东西被重置"时补一句，
           因为那一条必须被看见（周期停了、钉值失效）。 */
        setNotice(await applySavedSections(targetName, result.changed, result.effect));
        return;
      }

      if (result.status === 422) {
        setProblems(result.problems);
        setNotice('保存被拒：配置有问题，文件没有改动');
        return;
      }
      if (result.status === 409) {
        handleGuard(targetName, result.error, result.detail, owned);
        return;
      }
      setNotice(`保存失败（HTTP ${result.status}）${result.detail === '' ? '' : `：${result.detail}`}`);
    } finally {
      if (configNameRef.current === targetName) {
        setBusy(false);
      }
    }
  };

  /** 整份保存（配置原文页）：沿用原来的 PUT + 自动重载。 */
  const saveWhole = async (targetName: string, text: string, modifiedMs: number | null) => {
    const result = await backend.saveConfig(targetName, text, modifiedMs);
    if (result === null || configNameRef.current !== targetName) {
      return;
    }
    if (result.status === 200) {
      setProblems([]);
      markAllSaved(targetName);
      await refreshModifiedMs(targetName);
      setNotice('');
    } else if (result.status === 422) {
      setProblems(result.problems);
      setNotice('保存被拒：配置有问题，文件没有改动');
    } else if (result.status === 409) {
      setNotice('保存被拒：配置在编辑期间被其他窗口修改过——请先「重新载入」');
    } else {
      setNotice(`保存失败（HTTP ${result.status}）`);
    }
  };

  /** 保存成功后重新取一次 modifiedMs：下一次 If-Match 才是新的快照。 */
  const refreshModifiedMs = async (targetName: string) => {
    const fresh = await backend.readConfig(targetName);
    if (fresh !== null) {
      setDraftModifiedMs(targetName, fresh.modifiedMs);
    }
  };

  /** 把刚保存的段应用到设备，返回要显示在页面上的提示（'' = 不用写）。 */
  const applySavedSections = async (
    targetName: string,
    changed: readonly ConfigSectionName[],
    effect: ConfigSectionEffect,
  ): Promise<string> => {
    if (changed.length === 0 || sessionId === '') {
      return '';
    }
    /* 设备还没启动：文件已经保存，但没有运行实例可应用——启动时按新配置装配 */
    if (!backend.sessions.some((session) => session.id === sessionId)) {
      return '已保存：设备未启动，下次启动按新配置生效';
    }
    setNotice(`已保存（${sectionLabels(changed)}），正在应用到设备…`);
    const applied = await backend.applyConfigSections(sessionId, changed);
    if (applied === null || configNameRef.current !== targetName) {
      return '';
    }
    if (applied.status === 200) {
      return effectNote(applied.effect ?? effect, applied.reset, applied.pinsDropped, applied.plansKept);
    }
    /* 文件已经落盘、但设备没生效：这是最容易被误判成"已上线"的状态，必须留在页面上 */
    return `已保存到文件，但设备未生效（${sectionLabelOf(changed)}）：${applied.detail === '' ? applied.error : applied.detail}`;
  };

  /**
   * 保存被守卫拦下：把后端给的原因翻成"该怎么做"。
   *
   * <p>常量覆盖是唯一可以确认后重试的；结构与周期引用必须先改设备状态
   * （下线 / 停止周期），所以只提示、不提供"强行保存"。</p>
   */
  const handleGuard = (targetName: string, error: string, detail: string, owned: readonly ConfigSectionName[]) => {
    if (error === 'ecv-overwrite') {
      setConfirm({
        kind: 'ecv-overwrite',
        title: '覆盖主机写入的常量值？',
        message: detail === '' ? '保存会覆盖主机用 S2F15 写入的运行时值。' : detail,
        confirmLabel: '覆盖并保存',
        danger: true,
      });
      return;
    }
    if (error === 'not-offline') {
      setNotice(`没有保存：${detail === '' ? '设备在线时不能改单元结构，先下线再保存' : detail}`);
      return;
    }
    if (error === 'cycle-running-guard') {
      setNotice(`没有保存：${detail === '' ? '有变量/属性正被运行中的周期引用，先停止周期' : detail}`);
      return;
    }
    if (error === 'revision-conflict') {
      setNotice(`保存被拒：${targetName} 在编辑期间被其他窗口修改过——请先「重新载入」`);
      return;
    }
    setNotice(`没有保存：${detail === '' ? error : detail}（本页：${sectionLabels(owned)}）`);
  };

  const reload = async () => {
    if (configName === '') {
      return;
    }
    /* 有未保存改动时先确认：重新载入会用磁盘内容覆盖整份草稿，且没有撤销 */
    const snap = getDraftSnapshot(configName);
    const anyDirty = CONFIG_SECTION_NAMES.some(
      (section) => snap.current[section] !== snap.baseline[section],
    ) || (!snap.parseable && snap.text !== snap.baselineText);
    if (anyDirty) {
      setConfirm({
        kind: 'reload-discard',
        title: '放弃未保存的改动？',
        message: `「重新载入」会用磁盘上的 ${configName} 覆盖整份草稿，所有页面未保存的改动都会丢失。`,
        confirmLabel: '放弃并重新载入',
        danger: true,
      });
      return;
    }
    await loadDraft(configName, () => backend.readConfig(configName), { force: true });
    setNotice(`已重新载入 ${configName}`);
  };

  const confirmPending = async () => {
    const pending = confirm;
    setConfirm(null);
    if (pending === null) {
      return;
    }
    if (pending.kind === 'ecv-overwrite') {
      await save({ confirmOverwrite: true, confirmed: true });
      return;
    }
    if (pending.kind === 'save-restart') {
      await save({ confirmed: true });
      return;
    }
    await loadDraft(configName, () => backend.readConfig(configName), { force: true });
    setNotice(`已重新载入 ${configName}`);
  };

  return {
    configName,
    text: snapshot.text,
    dirty: dirtyHere,
    dirtyElsewhere,
    busy,
    problems,
    notice,
    confirm,
    sections: ownedSections,
    setText,
    validate,
    save,
    reload,
    confirmPending,
    cancelPending: () => setConfirm(null),
  };
}

/** 段名列表 → 中文列举（提示里用）。 */
function sectionLabelOf(names: readonly ConfigSectionName[]): string {
  return names.map(sectionLabel).join('、');
}
