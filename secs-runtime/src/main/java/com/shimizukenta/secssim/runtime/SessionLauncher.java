package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.app.config.SessionConfig;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.config.ValueSources;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.EngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.value.ValueSource;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 会话的创建、关闭与热重载。
 *
 * <p>抽出来的原因很实际：{@code Main} 启动时已经会建会话，HTTP 接口要做的
 * 是<b>同一件事</b>——把构造逻辑写两份，迟早会出现"命令行能起、接口起不来"
 * 这种只在其中一条路径上复现的现场问题。
 * </p>
 *
 * <p>配置文件只允许 {@code --work-dir} 下的<b>文件名</b>，不含任何路径分隔符。
 * 接口能指定配置源，就意味着不能让调用方指到 {@code work-dir} 之外去——
 * 否则一个 API 调用就能把服务器上任意 JSON 当配置读进来试。
 * </p>
 */
public final class SessionLauncher {

    /** 请求里代表"建一个内置演示会话"的关键字。 */
    public static final String DEMO = "demo";

    private static final long DEMO_SEED = 20240601L;
    private static final String DEMO_ID = "demo";

    private final SessionManager sessions;
    private final EventBus bus;
    private final Path workDir;
    /** 配置门面（3.0：正文在 config.db）；create/reload/常量回写都经它。 */
    private ConfigStore configStore;
    private final int defaultHsmsPort;
    /** 状态通知总线(v6);默认 null,可用 {@link #noticeBus(NoticeBus)} 挂上。 */
    private volatile NoticeBus noticeBus;

    /** 会话标识 -> 它的配置来源；演示会话没有来源，故不出现在这里。 */
    private final ConcurrentMap<String, SessionSource> sources = new ConcurrentHashMap<>();

    /**
     * 创建/重载/关闭的串行锁。
     *
     * <p>{@code find → 端口检查 → open → register} 必须是原子的：
     * 两个并发创建会同时通过端口检查，在 Windows 上真的能重复绑定，
     * 然后后注册的那个把先注册的挤掉。锁是全局的，创建是低频操作，
     * 换来的是不会有半初始化的会话漏在注册表外。</p>
     */
    private final java.util.concurrent.locks.ReentrantLock lifecycleLock =
            new java.util.concurrent.locks.ReentrantLock();

    /**
     * 配置来源文件 + 部署期覆盖 + 结构指纹；重载时三者都要原样带上。
     *
     * @param file        配置文件
     * @param overrides   部署期覆盖
     * @param fingerprint session+equipment 的规范 JSON 指纹；null 表示算不了（老来源或解析失败）
     */
    private record SessionSource(String configName, SessionOverrides overrides, String fingerprint, Prepared lastGood) {

        SessionSource withLastGood(Prepared prepared, String fp) {
            return new SessionSource(this.configName, this.overrides, fp, prepared);
        }
    }

    /**
     * 创建启动器。
     *
     * @param sessions        会话注册表
     * @param bus             事件总线
     * @param workDir         配置目录
     * @param defaultHsmsPort 配置未指定端口时使用的 SECS 端口
     */
    public SessionLauncher(SessionManager sessions, EventBus bus, Path workDir, int defaultHsmsPort) {
        this.sessions = sessions;
        this.bus = bus;
        this.workDir = workDir;
        this.defaultHsmsPort = defaultHsmsPort;
    }

    /**
     * 按请求创建一个会话。
     *
     * @param name {@code demo} 或 {@code work-dir} 下的配置文件名
     * @return 新建的会话标识
     * @throws ConfigException 名称非法、配置读不了或编译不过
     * @throws IOException     端口绑定失败
     */
    public String create(String name) throws ConfigException, IOException {
        return this.create(name, SessionOverrides.NONE);
    }

    /**
     * 按请求创建一个会话，允许用部署期覆盖替换配置里的连接标识。
     *
     * <p>多台设备复用同一份设备配置时，只有 id / 端口 / device-id 需要逐台给；
     * 覆盖不写回配置文件，重载时会原样再套一遍。</p>
     *
     * @param name      {@code demo} 或 {@code work-dir} 下的配置文件名
     * @param overrides 部署期覆盖；{@code null} 视为不覆盖
     * @return 新建的会话标识
     * @throws ConfigException 名称非法、配置读不了或编译不过
     * @throws IOException     端口绑定失败
     */
    public String create(String name, SessionOverrides overrides) throws ConfigException, IOException {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ConfigException("$.config", "不能为空（填 demo 或 work-dir 下的配置文件名）");
        }
        SessionOverrides safe = overrides == null ? SessionOverrides.NONE : overrides;

        if (DEMO.equalsIgnoreCase(trimmed)) {
            /* 演示会话是内置样品，不参与部署覆盖 */
            return startDemo(DEMO_ID, this.defaultHsmsPort, 10);
        }

        String configName = resolveConfigName(trimmed);
        String body = this.configStore.read(configName);
        return startFromConfig(configName, body, this.defaultHsmsPort, safe).id();
    }

    /**
     * 关闭并移除一个会话。
     *
     * @param id 会话标识
     * @return 会话存在并已关闭返回 {@code true}
     */
    public boolean close(String id) {
        this.lifecycleLock.lock();
        try {
            if (this.sessions.remove(id)) {
                this.sources.remove(id);
                return true;
            }
            return false;
        } finally {
            this.lifecycleLock.unlock();
        }
    }

    /**
     * 重新装载某个会话的配置。
     *
     * <p><b>先编译再动旧会话</b>：配置有错时旧会话原样跑着，
     * 报错返回；反过来先关后编译，一个手滑的逗号就会让设备凭空消失。
     * </p>
     *
     * @param id 会话标识
     * @return 会话不存在、或没有配置来源（演示会话）时返回 {@code false}
     * @throws ConfigException 配置读不了或编译不过
     * @throws IOException     端口绑定失败
     */
    public boolean reload(String id) throws ConfigException, IOException {
        this.lifecycleLock.lock();
        try {
            SessionSource source = this.sources.get(id);
            if (source == null) {
                return false;
            }

            /* 先构造新的：编译不过时旧会话还好端端地跑着。
               部署覆盖与来源配置一起带上——否则重载第 2 台会抢第 1 台的端口。 */
            String body = this.configStore.read(source.configName());
            Prepared prepared = prepare(source.configName(), body, this.defaultHsmsPort, source.overrides());
            String fingerprint = structureFingerprint(body);
            SessionRuntime current = this.sessions.find(id).orElse(null);

            /* 热生效路径：连接标识（session 段）没变——设备模型、编排、模板、
               限流、W 位的任何变化都原地替换，主机连接保持不断。 */
            if (current instanceof HsmsSessionRuntime running
                    && fingerprint != null && fingerprint.equals(source.fingerprint())) {
                running.applyHot(prepared.schedule, prepared.maxPerSec,
                        prepared.s6f11Wbit, prepared.s6f1Wbit,
                        prepared.model, prepared.values);
                this.sources.put(id, source.withLastGood(prepared, fingerprint));
                Log.info("会话 " + id + " 已热生效（设备模型/编排/模板原地替换，主机连接未断）");
                return true;
            }

            /* 重建前先确认端口没被别的会话占着：失败时旧会话还在跑，
               而不是"先把它关了再发现新端口绑不上"。 */
            requirePortFree(id, prepared.spec());

            /* 同端口/同目标的重建不能"先开新再关旧"：旧监听 socket 还占着这个端口，
               Linux 上 bind 必失败（还叠加首次绑定失败静默的旧问题），Windows 上会出现两个监听器。
               先关旧再开新；开不起来时用上一次成功的快照把旧会话拉回来。 */
            if (current != null && sameEndpoint(current, prepared.spec())) {
                return rebuildInPlace(id, source, prepared, fingerprint);
            }

            /* 目标变了（端口/绑定地址）：先 open 新会话再动旧的——新会话绑的是另一个端口，
               失败时旧会话原样跑着。 */
            HsmsSessionRuntime opened = prepared.open(this.bus, this.noticeBus, source.configName());
            try {
                this.close(id);
                this.sessions.register(opened);
            } catch (RuntimeException e) {
                opened.close();
                Log.error("重载会话 " + id + " 失败（新会话已关闭）: " + source.configName(), e);
                throw e;
            }
            this.sources.put(id, source.withLastGood(prepared, fingerprint));
            Log.info("会话 " + id + " 已重建（连接标识变化，主机需重连）");
            return true;
        } finally {
            this.lifecycleLock.unlock();
        }
    }

    /**
     * 同端口/同目标的重建：先关旧，再开新；失败时用上一次成功的快照恢复旧会话。
     *
     * @param id          会话标识
     * @param source      配置来源（含 lastGood 快照）
     * @param prepared    按新配置组装好的会话
     * @param fingerprint 新配置的结构指纹
     * @return 恒为 {@code true}（失败会抛异常）
     * @throws IOException 新会话起不来（旧会话已尝试恢复）
     */
    private boolean rebuildInPlace(String id, SessionSource source, Prepared prepared, String fingerprint)
            throws IOException {
        final Prepared previous = source.lastGood();
        this.close(id);

        try {
            HsmsSessionRuntime opened = prepared.open(this.bus, this.noticeBus, source.configName());
            this.sessions.register(opened);
            this.sources.put(id, source.withLastGood(prepared, fingerprint));
            Log.info("会话 " + id + " 已重建（同端口：先关旧再开新）");
            return true;
        } catch (IOException | RuntimeException failure) {
            restore(id, source, previous, failure);
            throw failure;
        }
    }

    /** 重建失败后的补偿：用旧快照再起一个会话，尽量不让设备凭空消失。 */
    private void restore(String id, SessionSource source, Prepared previous, Exception failure) {
        if (previous == null) {
            Log.error("重载失败，且没有可恢复的旧快照: " + id, failure);
            return;
        }
        try {
            HsmsSessionRuntime restored = previous.open(this.bus, this.noticeBus, source.configName());
            this.sessions.register(restored);
            this.sources.put(id, source);
            Log.warn("重载失败，已用旧配置恢复会话: " + id);
        } catch (IOException | RuntimeException rollback) {
            failure.addSuppressed(rollback);
            Log.error("重载失败，旧会话也无法恢复: " + id, rollback);
        }
    }

    /** 两个会话规格是否指向同一监听目标（端口 + 绑定地址）。 */
    private static boolean sameEndpoint(SessionRuntime current, SessionSpec next) {
        com.shimizukenta.secssim.app.session.HsmsConnectionTarget target = current.hostTarget();
        return target != null
                && target.port() == next.port()
                && java.util.Objects.equals(target.bindHost(), next.bindHost());
    }

    /**
     * 启动进程内置的演示会话。
     *
     * @param id       会话标识
     * @param hsmsPort SECS 端口
     * @param deviceId device-id
     * @return 会话标识
     * @throws IOException 端口绑定失败
     */
    public String startDemo(String id, int hsmsPort, int deviceId) throws IOException {
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", deviceId);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        if (!compiled.ok()) {
            throw new ConfigException("$.demo", "内置演示设备编译失败");
        }

        SessionSpec spec = new SessionSpec(id, true, "127.0.0.1", hsmsPort, deviceId, DEMO_SEED, demo);
        this.lifecycleLock.lock();
        try {
            /* 先判标识冲突：否则同一端口的既有会话会让端口预检先命中，
               把「标识重复」报成「端口被占」，用户会去改一个没问题的端口。
               （startFromFile 一直有这一步，演示会话漏了。） */
            if (this.sessions.find(id).isPresent()) {
                throw new IllegalArgumentException("session already registered: " + id);
            }
            requirePortFree(id, spec);
            HsmsSessionRuntime runtime = new HsmsSessionRuntime(spec, compiled.equipment());
            this.bus(runtime);
            runtime.enableSchedule(new EventSchedule(List.of(
                    new EventPlan("demo-process-start", 2001, new Trigger.Periodic(5_000L, 200L)))), 20);
            runtime.open();
            try {
                this.sessions.register(runtime);
            } catch (RuntimeException e) {
                /* 注册失败（重复 id / 管理器已关闭）时已经 open 的会话必须关掉 */
                runtime.close();
                throw e;
            }
            return id;
        } finally {
            this.lifecycleLock.unlock();
        }
    }

    /**
     * 从配置文件启动一个会话（外部 {@code --config} 场景：文件在 work-dir 之外）。
     *
     * @param file        配置文件
     * @param defaultPort 配置未指定端口时使用的 SECS 端口
     * @return 会话
     * @throws ConfigException 配置读不了或编译不过
     * @throws IOException     端口绑定失败
     */
    public HsmsSessionRuntime startFromFile(Path file, int defaultPort) throws ConfigException, IOException {
        return this.startFromFile(file, defaultPort, SessionOverrides.NONE);
    }

    /**
     * 从配置文件启动一个会话，并记下来源与部署覆盖。
     *
     * <p>3.0 里 work-dir 内的配置存 config.db；这条入口只服务 work-dir 之外的
     * 外部文件（{@code --config}），读进正文后与库内配置走同一条组装路径。</p>
     *
     * @param file        配置文件
     * @param defaultPort 配置未指定端口时使用的 SECS 端口
     * @param overrides   部署期覆盖
     * @return 会话
     * @throws ConfigException 配置读不了或编译不过
     * @throws IOException     端口绑定失败
     */
    public HsmsSessionRuntime startFromFile(Path file, int defaultPort, SessionOverrides overrides)
            throws ConfigException, IOException {
        String body;
        try {
            body = java.nio.file.Files.readString(file);
        } catch (java.io.IOException e) {
            throw new ConfigException("$.config", "读不了配置文件 " + file.getFileName() + ": " + e.getMessage());
        }
        String configName = file.getFileName().toString();
        return startFromConfig(configName, body, defaultPort, overrides);
    }

    /**
     * 从配置正文启动一个会话（库内配置的入口）。
     *
     * @param configName 配置名（文件名，诊断与常量回写定位用）
     * @param body       配置正文
     * @param defaultPort 配置未指定端口时使用的 SECS 端口
     * @param overrides  部署期覆盖
     * @return 会话
     * @throws ConfigException 配置编译不过
     * @throws IOException     端口绑定失败
     */
    public HsmsSessionRuntime startFromConfig(String configName, String body, int defaultPort,
                                              SessionOverrides overrides)
            throws ConfigException, IOException {
        Prepared prepared = prepare(configName, body, defaultPort, overrides);
        this.lifecycleLock.lock();
        try {
            /* 先把"会话 id 已存在"判掉：否则同一端口的既有会话会让端口检查先命中，
               把"标识冲突"报成"端口被占"，用户会去改一个没问题的端口。 */
            if (this.sessions.find(prepared.spec().id()).isPresent()) {
                throw new IllegalArgumentException("session already registered: " + prepared.spec().id());
            }
            requirePortFree(prepared.spec().id(), prepared.spec());
            HsmsSessionRuntime runtime = prepared.open(this.bus, this.noticeBus, configName);
            try {
                this.sessions.register(runtime);
            } catch (RuntimeException e) {
                /* 已经 open 的会话在注册失败时必须 close：否则端口与线程都泄漏 */
                runtime.close();
                throw e;
            }
            /* 命令行启动的会话同样要记下来源，否则只有接口建的会话能重载，
               而"能不能重载"取决于配置从哪来，与会话怎么起来的关系 */
            this.sources.put(runtime.id(), new SessionSource(configName, overrides,
                    structureFingerprint(body), prepared));
            return runtime;
        } finally {
            this.lifecycleLock.unlock();
        }
    }

    /** 读配置、编译、组装好但还没 open。 */
    private Prepared prepare(String configName, String body, int defaultPort, SessionOverrides overrides)
            throws ConfigException {
        SessionConfig config;
        try {
            config = SessionConfigJson.parse(body);
        } catch (RuntimeException e) {
            throw new ConfigException("$.config", "解析不了配置 " + configName + ": " + e.getMessage());
        }

        CompilationResult compiled = config.compile();
        if (!compiled.ok()) {
            throw new ConfigException("$.equipment", "编译失败: "
                    + compiled.errors().stream().map(Object::toString).reduce((a, b) -> a + "; " + b).orElse("未知错误"));
        }

        SessionSpec configured = config.spec();
        String id = overrides.sessionId() != null ? overrides.sessionId() : configured.id();
        String bindHost = overrides.bindHost() != null ? overrides.bindHost() : configured.bindHost();
        int port = overrides.port() != null ? overrides.port()
                : configured.port() == 0 ? defaultPort : configured.port();
        int deviceId = overrides.deviceId() != null ? overrides.deviceId() : configured.deviceId();
        SessionSpec spec = new SessionSpec(id, configured.passive(), bindHost, port, deviceId,
                configured.seed(), configured.equipment(), configured.timeouts());

        /* 值来源统一从这里出：默认曲线 / 模板图 / SVID 直配（v6）的分支只在 ValueSources 里有一份 */
        ValueSource values = ValueSources.of(config, compiled.equipment());

        return new Prepared(spec, compiled.equipment(), values, config.schedule(),
                config.maxEventsPerSec(), config.s6f11Wbit(), config.s6f1Wbit(), constantWriter());
    }

    /** S2F15 的常量回写目标：接到配置门面（正文在 config.db）。 */
    private Prepared.ConstantWriter constantWriter() {
        return this.configStore == null ? null
                : (configName, constants) -> this.configStore.writeConstants(configName, constants);
    }

    /**
     * 挂上配置门面（3.0 必配）：会话的创建/重载/常量回写都读 config.db。
     *
     * @param store 配置门面
     */
    public void configStore(ConfigStore store) {
        this.configStore = store;
    }

    private void bus(HsmsSessionRuntime runtime) {
        if (this.bus != null) {
            runtime.eventBus(this.bus);
        }
        if (this.noticeBus != null) {
            runtime.noticeBus(this.noticeBus);
        }
    }

    /**
     * 被动会话的端口不能和已有会话重复。
     *
     * <p>HSMS 的监听 socket 在 Windows 上可以重复绑定（SO_REUSEADDR），
     * 后建的会话会"看起来启动成功"，但主机连到哪个会话是不确定的。
     * 在启动路径上先挡掉，比现场排查"报文怎么跑到另一台设备去了"便宜得多。</p>
     *
     * @param selfId 正在启动/重建的会话 id（重载时旧实例还在注册表里）
     * @param spec   目标规格
     * @throws IOException 端口已被其它会话占用
     */
    private void requirePortFree(String selfId, SessionSpec spec) throws IOException {
        if (!spec.passive()) {
            return;
        }
        for (SessionRuntime other : this.sessions.list()) {
            if (other.id().equals(selfId)) {
                continue;
            }
            com.shimizukenta.secssim.app.session.HsmsConnectionTarget target = other.hostTarget();
            if (target != null && target.port() == spec.port()) {
                throw new IOException("端口 " + spec.port() + " 已被会话 " + other.id() + " 占用");
            }
        }
    }

    /**
     * 挂上状态通知总线（schema v6）。
     *
     * @param bus 通知总线
     */
    public void noticeBus(NoticeBus bus) {
        this.noticeBus = bus;
    }

    /**
     * 校验配置文件名。
     *
     * <p>规则与 {@link ConfigStore} 完全一致（就调它那份）：
     * 接口建会话和接口保存配置走的是同一个目录，两处各写一份校验，
     * 迟早会出现"这里能读、那里读不了"这种只在一条路径上复现的问题。
     * </p>
     */
    private static String resolveConfigName(String name) throws ConfigException {
        return ConfigStore.requireFileName(name, "$.config");
    }

    /** 已组装好、只差 open 的会话。 */
    private static final class Prepared {

        /** 常量回写目标：配置名 → 门面（S2F15 用）。 */
        interface ConstantWriter {
            void save(String configName, java.util.Map<Integer, com.shimizukenta.secssim.core.domain.EquipmentConstantDef> constants)
                    throws java.io.IOException;
        }

        private final SessionSpec spec;
        private final ResolvedEquipment model;
        private final ValueSource values;
        private final EventSchedule schedule;
        private final int maxPerSec;
        private final boolean s6f11Wbit;
        private final boolean s6f1Wbit;
        private final ConstantWriter constantWriter;

        Prepared(SessionSpec spec, ResolvedEquipment model,
                 ValueSource values, EventSchedule schedule, int maxPerSec,
                 boolean s6f11Wbit, boolean s6f1Wbit, ConstantWriter constantWriter) {
            this.spec = spec;
            this.model = model;
            this.values = values;
            this.schedule = schedule;
            this.maxPerSec = maxPerSec;
            this.s6f11Wbit = s6f11Wbit;
            this.s6f1Wbit = s6f1Wbit;
            this.constantWriter = constantWriter;
        }

        /** 目标规格（端口冲突检查要在 open 之前做）。 */
        SessionSpec spec() {
            return this.spec;
        }

        HsmsSessionRuntime open(EventBus bus) throws IOException {
            return this.open(bus, null, null);
        }

        /**
         * 打开会话，并把常量落盘接到配置门面。
         *
         * @param bus        事件总线
         * @param configName 配置名；{@code null} 表示这个会话没有来源（演示会话），
         *                   常量只留在内存
         * @return 已打开的会话
         * @throws IOException 打开失败
         */
        HsmsSessionRuntime open(EventBus bus, NoticeBus notices, String configName) throws IOException {
            HsmsSessionRuntime runtime = new HsmsSessionRuntime(this.spec, this.model,
                    EngineClock.system(), this.values);
            if (bus != null) {
                runtime.eventBus(bus);
            }
            if (notices != null) {
                runtime.noticeBus(notices);
            }
            if (configName != null && this.constantWriter != null) {
                runtime.constantSaver(constants -> this.constantWriter.save(configName, constants));
            }
            runtime.enableSchedule(this.schedule, this.maxPerSec);
            runtime.collectionEventWbit(this.s6f11Wbit);
            runtime.traceWbit(this.s6f1Wbit);
            runtime.open();
            return runtime;
        }
    }

    /** 取某会话的配置名（诊断用；演示会话为空）。 */
    public Optional<String> sourceOf(String id) {
        return Optional.ofNullable(this.sources.get(id)).map(SessionSource::configName);
    }

    /**
     * 结构指纹：session 段的规范 JSON 文本。
     *
     * <p>session 段决定"会话怎么连"（id/端口/绑定地址/deviceId/超时），
     * 变了必须关旧开新；equipment / events / templates 的变化全部热生效
     * （设备模型、组装器、Trace、值来源整体替换，连接不断）。
     * 解析失败返回 {@code null}，重载一律走重建。</p>
     */
    private static String structureFingerprint(String body) {
        try {
            String text = body;
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1);
            }
            com.shimizukenta.jsonhub.JsonHub root = com.shimizukenta.jsonhub.JsonHub.fromJson(text);
            if (!root.isObject() || !root.containsKey("session")) {
                return null;
            }
            /* 只有 session 段（连接标识/超时）变化才需要重建连接；
               equipment / events / templates 的变化全部走热生效。 */
            StringBuilder sb = new StringBuilder();
            appendCanonical(sb, root.get("session"));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 规范化输出：键排序、数组保序；缺段输出 {@code null}。 */
    private static final String QUOTE = "\"";
    private static final String QUOTE_COLON = "\":";

    private static void appendCanonical(StringBuilder sb, com.shimizukenta.jsonhub.JsonHub node) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.length(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendCanonical(sb, node.get(i));
            }
            sb.append(']');
            return;
        }
        if (node.isObject()) {
            sb.append('{');
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (com.shimizukenta.jsonhub.JsonString key : node.keySet()) {
                keys.add(key.unescaped());
            }
            java.util.Collections.sort(keys);
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(QUOTE).append(key).append(QUOTE_COLON);
                appendCanonical(sb, node.get(key));
            }
            sb.append('}');
            return;
        }
        sb.append(node.toJson());
    }
}
