import {useEffect, useMemo, useRef, useState} from 'react';
import {useVisibilityInterval} from '../../state/useVisibilityInterval';
import type {PanelBaseProps} from './types';
import {CycleRunCard, type CycleDefinition} from './CyclePanel';
import {PlanExecutionTimeline} from './PlanExecutionTimeline';
import {PreviewCard, cyclePreviewWindows} from './PreviewCard';
import {Panel} from '../../shared/ui/Panel';
import {CycleForm} from '../editors/CycleForm';
import {isJsonObject, type JsonObject} from '../editors/templateConfig';
import type {BackendController} from '../../state/useBackend';

/** 定义摘要的轮询节奏：配置区保存会让会话重载，这里跟着刷新（读文件，很轻）。 */
const DEFINITION_POLL_MS = 5_000;

/** 只读展示不需要写回：给 CycleForm 一个不动的编辑函数。 */
const NOOP_EDIT = () => undefined;

/** 从配置里读出来的周期：定义摘要给预览用，root 给只读配置卡用。 */
interface CycleConfig {
    definition: CycleDefinition | null;
    root: JsonObject | null;
}

/**
 * 设备 · 运行区 · 工艺周期（2026-11-17 运行区补齐）：
 * 运行卡（启停/片号/相位/已发事件）+ <b>周期配置（只读）</b> + 周期预览 + 片内事件台账。
 *
 * <p>节拍参数与片内事件的定义在配置区「工艺周期」页；本页只管这次运行——
 * 启动/停止走 ONLINE 门控（§2.2），全停事件（下线/钉值/S2F15 写常量）会把
 * 周期打回停止，需要手动重新启动。</p>
 *
 * <p>2026-11-18：配置区保存完周期回到这里，页面上必须能看出"周期是哪一个"：
 * 运行卡下面直接把配置里的 cycle 段摆出来（字段与配置区一致，但只读），
 * 再加一张与配置区完全一致的周期预览图（{@link PreviewCard}，数据源换成当前运行）。</p>
 */
export function CycleRuntimePanel({backend, notifier, liveEvents}: PanelBaseProps): JSX.Element {
    const deviceId = backend.activeSession;
    const configName = backend.sessions.find((session) => session.id === deviceId)?.config ?? '';
    const config = useCycleConfig(backend, configName);
    const definition = config.definition;
    const windowOptions = useMemo(() => cyclePreviewWindows(definition?.durationMs), [definition?.durationMs]);

    return (
        <div className="panel-stack">
            {config.root !== null && (
                <Panel id="panel-cycle-config" title="周期配置" headless>
                    <CycleForm root={config.root} edit={NOOP_EDIT} readOnly/>
                </Panel>
            )}
            <CycleRunCard backend={backend} notifier={notifier} deviceId={deviceId}/>
            <PreviewCard
                title="周期预览（当前运行）"
                hint="画的是这台设备最近这段时间的实跑曲线：横轴是距窗口起点的秒数（最右端 = 现在）、纵轴是参数值，竖虚线是片内事件的发送时刻。默认只画第一个参数，需要对比时在「参数」里再勾选；点图例可以隐藏/显示某条曲线或某类事件。按时间窗自动刷新，跟着运行往前走。"
                eventLabel="片内事件"
                scope="cycle"
                relativeTime
                params={backend.catalog?.svids ?? []}
                windows={windowOptions.windows}
                defaultWindowMs={windowOptions.defaultWindowMs}
                emptyParamsText="这台设备还没有可展示的变量（先在配置区定义 SVID，并重启设备）。"
                invalidText="这台设备现在读不到运行数据（可能刚停止或正在重启），稍后会自己出现。"
                source={{
                    key: deviceId,
                    refreshMs: 5_000,
                    fetch: (windowMs) => (deviceId === '' ? Promise.resolve(null) : backend.previewSession(deviceId, windowMs)),
                }}
            />

            {/* 执行明细只在「事件执行」页出全量；这一页只留片内事件这一子集 */}
            <PlanExecutionTimeline
                deviceId={deviceId}
                liveEvents={liveEvents}
                notifier={notifier}
                planId="cycle"
                title="片内事件发送记录"
                sectionId="cycle-executions"
                emptyText="这片还没有片内事件发送记录。启动设备并在线后，周期按配置里的片内事件逐片发送。"
                readOnly
            />
        </div>
    );
}

/**
 * 读设备配置里的 cycle 段：运行卡旁边要显示"这台设备跑的周期长什么样"。
 *
 * <p>运行态接口只给状态（片号/相位/总数），片长、片数、片内事件都只在配置里；
 * 运维现场最常问的恰好是"这片多长时间、片内什么时候发什么事件"。读文件很便宜，
 * 5 秒一次跟着配置区的保存走。</p>
 */
function useCycleConfig(backend: BackendController, configName: string): CycleConfig {
    const [config, setConfig] = useState<CycleConfig>({definition: null, root: null});
    const loadRef = useRef<(() => Promise<void>) | null>(null);

    useEffect(() => {
        if (configName === '') {
            setConfig({definition: null, root: null});
            return;
        }
        let cancelled = false;

        const load = async () => {
            try {
                const snapshot = await backend.readConfig(configName);
                if (cancelled || snapshot === null) {
                    return;
                }
                const root = JSON.parse(snapshot.text) as JsonObject;
                if (!isJsonObject(root.cycle)) {
                    setConfig({definition: null, root});
                    return;
                }
                const durationMs = Number(root.cycle.durationMs ?? 0);
                if (!Number.isFinite(durationMs) || durationMs <= 0) {
                    /* 周期段不完整：只读卡照常摆出来，预览窗口退回默认 */
                    setConfig({definition: null, root});
                    return;
                }
                setConfig({
                    root,
                    definition: {
                        durationMs,
                        jitterMs: Number(root.cycle.jitterMs ?? 0) || 0,
                        count: Number(root.cycle.count ?? 0) || 0,
                        events: Array.isArray(root.cycle.events) ? root.cycle.events.length : 0,
                    },
                });
            } catch {
                /* 配置读不到/不是合法 JSON：宁可不显示，也不让运行卡跟着报错 */
            }
        };

        void load();
        loadRef.current = load;
        return () => {
            cancelled = true;
            loadRef.current = null;
        };
    }, [backend, configName]);

    /* 后台标签页暂停轮询（N6）；恢复可见立即补跑一次 */
    useVisibilityInterval(() => loadRef.current?.(), DEFINITION_POLL_MS, configName !== '');

    return config;
}
