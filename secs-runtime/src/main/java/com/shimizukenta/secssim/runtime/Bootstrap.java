package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.SSLContext;

import com.shimizukenta.secssim.api.http.AccessControl;
import com.shimizukenta.secssim.api.http.ApiContext;
import com.shimizukenta.secssim.api.http.AuditSink;
import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.api.http.EventOutcome;
import com.shimizukenta.secssim.api.http.HttpApiServer;
import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.api.http.SessionsJson;
import com.shimizukenta.secssim.api.http.TriggerOutcome;
import com.shimizukenta.secssim.app.audit.AuditLog;
import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.event.MessageLog;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.PlanExecutor;
import com.shimizukenta.secssim.runtime.session.RuntimeEdits;
import com.shimizukenta.secssim.runtime.store.ConfigProjector;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.store.ConfigMigrator;
import com.shimizukenta.secssim.store.ConfigRepository;
import com.shimizukenta.secssim.store.Db;
import com.shimizukenta.secssim.store.HostRepository;
import com.shimizukenta.secssim.store.PlanExecutionRepository;
import com.shimizukenta.secssim.store.RuntimeStateRepository;
import com.shimizukenta.secssim.store.TemplateRepository;

/**
 * 启动装配（不负责进程退出码）。
 *
 * <p>三种形态共用同一条启动路径，只有绑定地址、探针端口与鉴权策略不同。
 * 这是刻意的：一体化与远程服务端如果走两条代码路径，必然会出现
 * "单机能用、连服务器就不对"的问题。
 * </p>
 *
 * <p>JVM 入口（{@code main} + {@code System.exit}）在 {@code secs-cli} 的
 * {@code CliMain}：装配是运行时职责，进程退出码是命令行职责，两者分开后
 * 本模块可以只被当作库启动。</p>
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    /**
     * 启动并阻塞到停机信号，返回进程退出码。
     *
     * @param args 命令行
     * @return 0 正常停机；2 参数 / TLS 错误；3 配置错误；其他为启动失败
     * @throws Exception 启动过程中的未预期异常（由调用方决定如何退出）
     */
    public static int run(String[] args) throws Exception {
        RuntimeOptions options;
        try {
            options = RuntimeOptions.parse(args);
        } catch (IllegalArgumentException e) {
            Log.error("参数错误", e);
            System.err.print(RuntimeOptions.usage());
            return 2;
        }

        if (options.help()) {
            System.out.print(RuntimeOptions.usage());
            return 0;
        }

        /* 先开文件日志再做任何会失败的事：进程一关 stderr 就没了，
           而现场最需要的恰恰是"启动失败那一次"的原因。 */
        Log.setLevel(options.logLevel());
        Log.toFile(options.workDir().resolve("logs").resolve("app.log"));

        SSLContext tls = null;
        if (options.tlsKeystore() != null) {
            try {
                tls = TlsSupport.load(options.tlsKeystore(), options.tlsPassword());
            } catch (Exception e) {
                Log.error("加载 TLS keystore 失败: " + options.tlsKeystore(), e);
                return 2;
            }
        }

        /* 内部工具：不做鉴权，浏览器/脚本直连即可（没有令牌、账号、角色门槛） */
        AccessControl access = AccessControl.open();

        boolean utf8Ok = utf8RoundTrip(options.workDir());
        CountDownLatch stopped = new CountDownLatch(1);

        SessionManager sessions = new SessionManager();
        EventBus bus = new EventBus();
        NoticeBus notices = new NoticeBus();
        SecsProbe probe = null;

        /*
         * 报文日志是"诊断材料"，不是业务数据：写不进去只少一段历史，不能让设备起不来。
         * 打开失败时 messageLog 保持 null，HTTP 历史接口会如实回 503。
         */
        MessageLog messageLog = null;
        try {
            messageLog = MessageLog.open(options.workDir(), options.messagesRecent());
            Log.info("报文日志: " + messageLog.file());
        } catch (IOException e) {
            Log.error("打开报文日志失败（本次不落盘）: " + options.workDir(), e);
        }
        AutoCloseable messageSubscription = messageLog == null ? null : bus.subscribe(messageLog::append);

        AuditLog auditLog = null;
        try {
            auditLog = AuditLog.open(options.workDir(), options.auditRecent());
            Log.info("审计日志: " + auditLog.file());
        } catch (IOException e) {
            Log.error("打开审计日志失败（本次不审计）: " + options.workDir(), e);
        }
        AuditSink auditSink = auditLog == null ? null : auditLog::record;

        /*
         * 整个进程只有一个启动器：它记着"哪个会话的配置从哪来"，
         * 热重载全靠这份记忆。每次调用现造一个的话，重载接口会永远说"没有配置来源"。
         */
        SessionLauncher launcher = new SessionLauncher(sessions, bus, options.workDir(), options.hsmsPort());
        launcher.noticeBus(notices);

        /*
         * 3.0 存储（ADR 0017）：打开双库、按需迁移、播种，然后建门面。
         * config.db 是权威配置；work-dir 下的 JSON 在迁移后归档进 legacy/。
         * 运行态编辑表随库建立，M2 起写入。
         */
        if (options.resetRuntime()) {
            deleteRuntimeDb(options.workDir());
        }
        Db db = Db.open(options.workDir());
        ConfigRepository configRepo = new ConfigRepository(db, new ConfigProjector());
        TemplateRepository templateRepo = new TemplateRepository(db);
        PlanExecutionRepository planExecutions = new PlanExecutionRepository(db);
        ConfigMigrator migrator = new ConfigMigrator(db, configRepo, templateRepo);

        if (options.migrateConfig() || migrator.needed(options.workDir())) {
            ConfigMigrator.Report report = migrator.migrate(options.workDir(), builtinTemplateBodies());
            Log.info("配置迁移完成: devices=" + report.devices() + " templates=" + report.templates()
                    + (report.archived() ? "（原文件已归档到 legacy/）" : "")
                    + (report.problems().isEmpty() ? "" : " problems=" + report.problems()));
        }
        seedTemplates(templateRepo);
        seedDefaultDevices(configRepo);

        ConfigStore configStore = new ConfigStore(configRepo, templateRepo, options.workDir());
        TemplateStore templates = new TemplateStore(options.workDir(), templateRepo);
        launcher.configStore(configStore);
        HostRepository hostRepo = new HostRepository(db);
        RuntimeStateRepository linkState = new RuntimeStateRepository(db);
        /* 主机实例在 RuntimeContext 构造时从配置库装回内存（注册表在那儿建） */

        /*
         * 3.0 M4（设计 §2.7.2）：会话一注册就接编排执行器。
         * 注册观察者覆盖 CLI 启动、接口创建与重载重建的全部路径——
         * 在 SessionLauncher 里逐条挂总会漏掉重建/回滚恢复出来的那个会话。
         * 台账与触发计数都只进内存队列，由 plan-execution-writer 批量落库。
         */
        PlanFiredThrottle planFired = new PlanFiredThrottle(notices);
        sessions.onRegister(runtime -> {
            if (!(runtime instanceof HsmsSessionRuntime hsms)) {
                return;
            }
            /* 台账归属用 config.db 的 ULID，不是 v1 的 legacy stem（设计 §2.5） */
            ConfigRepository.DeviceView device = configRepo.findDevice(runtime.id()).orElse(null);
            String deviceId = device == null ? runtime.id() : device.id();
            hsms.planExecutor(new PlanExecutor(deviceId,
                    planSink(deviceId, planExecutions, planFired)));
            /* device_link 行：会话一生效就有相位可查（断开展示、重连恢复都读它） */
            linkState.upsertDeviceLink(deviceId, RuntimeApi.phaseName(hsms.linkPhase()),
                    device == null ? 0L : device.revision());
            /* 相位每次变化都落库 + 推 SSE（event: device-link）：界面上「连上/断开」
               要立刻变；顺带留一条链路事件，排障时能回答"什么时候断的"。 */
            hsms.onLinkPhase((sessionId, phase) -> {
                String phaseName = RuntimeApi.phaseName(phase);
                linkState.updateDevicePhase(deviceId, phaseName, null);
                linkState.appendLinkEvent("device", deviceId, "", phaseName, "");
                notices.publish(new com.shimizukenta.secssim.app.event.NoticeEvent(
                        sessionId, com.shimizukenta.secssim.app.event.NoticeEvent.DEVICE_LINK,
                        "{\"deviceId\":\"" + deviceId + "\",\"phase\":\"" + phaseName + "\"}",
                        System.currentTimeMillis()));
            });
            /* 「断开保留、重启恢复」：上一次进程留下的钉值/计划启用态/计划正文/目录编辑，
               在新会话上原样重放。重载路径会先清库再注册，所以不会把旧编辑装回来。 */
            restoreRuntimeEdits(hsms, deviceId, linkState);
        });

        if (options.exportConfig() != null) {
            int exported = migrator.exportTo(options.exportConfig());
            Log.info("已导出 " + exported + " 台设备到 " + options.exportConfig());
            System.out.println("EXPORT ok devices=" + exported + " dir=" + options.exportConfig().toAbsolutePath());
            planFired.close();
            closeStorage(db, planExecutions);
            return 0;
        }

        /* 启动对账（设计 §2.4.4 的存储侧）：清掉不属于存活设备的运行态残留、裁剪台账。
           设备/主机的连接恢复在 M2 接线；现在只做数据收敛。 */
        List<String> liveDeviceIds = configRepo.listDevices().stream()
                .map(ConfigRepository.DeviceView::id).toList();
        long removed = linkState.cleanupOrphans(liveDeviceIds);
        long reset = linkState.resetStaleDevicePhases();
        planExecutions.trim();
        if (removed > 0) {
            Log.info("启动对账清理运行态残留 " + removed + " 行");
        }
        if (reset > 0) {
            Log.info("启动对账把 " + reset + " 台设备的链路相位收敛为已断开");
        }

        /* 配置读写与建会话共用同一个目录，"编辑完就能建会话"才成立 */

        if (options.configFile() != null) {
            int code = startConfiguredSession(options, launcher);
            if (code != 0) {
                planFired.close();
                closeStorage(db, planExecutions);
                closeMessageLog(messageSubscription, messageLog);
                closeAuditLog(auditLog);
                return code;
            }
        } else if (options.demoSession()) {
            int code = startDemoSession(options, launcher, configStore);
            if (code != 0) {
                planFired.close();
                closeStorage(db, planExecutions);
                closeMessageLog(messageSubscription, messageLog);
                closeAuditLog(auditLog);
                return code;
            }
        } else if (options.mode() == RuntimeMode.PROBE) {
            probe = SecsProbe.open(options.hsmsPort());
        }

        StringBuilder extra = new StringBuilder();
        extra.append(",\"vm\":\"").append(vmKind()).append('"')
                .append(",\"utf8\":").append(utf8Ok)
                .append(",\"sessions\":").append(sessions.list().size());
        if (probe != null) {
            extra.append(",\"hsmsPort\":").append(options.hsmsPort());
        }

        RuntimeContext runtimeContext = new RuntimeContext(options.mode().id(), BuildInfo.version(),
                extra.toString(), stopped::countDown, sessions, launcher, configStore, templates,
                messageLog, auditLog, bus, notices, hostRepo, linkState, planExecutions, configRepo);

        HttpApiServer api = HttpApiServer.start(
                new InetSocketAddress(options.bindHost(), options.httpPort()), access, runtimeContext,
                options.webRoot(), bus, tls, auditSink);
        api.subscribeNotices(notices);

        /* 停机清理只执行一次：hook 里做真正的关闭，主线程被唤醒后也调它。
           原来 hook 只 countDown，清理留在 main 的 finally——JVM 在 hook 结束后
           就 halt，磁盘 I/O 没写完的审计/报文尾行会被截断。 */
        final MessageLog readyMessageLog = messageLog;
        final AutoCloseable readySubscription = messageSubscription;
        final AuditLog readyAuditLog = auditLog;
        final SecsProbe readyProbe = probe;
        final java.util.concurrent.atomic.AtomicBoolean cleaned = new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            try {
                sessions.closeAll();
            } catch (RuntimeException e) {
                Log.error("关闭会话失败（停机继续）", e);
            }
            if (readyProbe != null) {
                try {
                    readyProbe.close();
                } catch (RuntimeException e) {
                    Log.error("关闭探针失败（停机继续）", e);
                }
            }
            try {
                /* 先收会话/主机模拟器/压测线程，再关 HTTP：
                   host-sim 的连接与采集线程不属于任何会话，必须由上下文自己关。 */
                runtimeContext.close();
            } catch (RuntimeException e) {
                Log.error("关闭运行时上下文失败（停机继续）", e);
            }
            try {
                api.close();
            } catch (RuntimeException e) {
                Log.error("关闭 HTTP 服务失败（停机继续）", e);
            }
            closeMessageLog(readySubscription, readyMessageLog);
            closeAuditLog(readyAuditLog);
            planFired.close();
            closeStorage(db, planExecutions);
        };

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopped.countDown();
            cleanup.run();
        }, "shutdown-hook"));

        /* 握手文件给部署脚本读：stdout 经常被 systemd/包装脚本吞掉 */
        writeReadyFile(options.workDir().resolve("ready.txt"), options, api.port());

        System.out.println("READY mode=" + options.mode().id()
                + " http=" + api.port()
                + " utf8=" + utf8Ok
                + " tls=" + (tls != null)
                + " sessions=" + sessions.list().size()
                + ((probe == null && !options.demoSession()) ? "" : " hsms=" + options.hsmsPort())
                + " work=" + options.workDir());
        System.out.flush();

        try {
            stopped.await();
        } finally {
            cleanup.run();
        }
        return 0;
    }

    /**
     * 会话注册时把 runtime.db 里的运行态编辑重放到新会话上（设计 §2.4.4）。
     *
     * <p>顺序按依赖排：目录编辑先落（它会重建模型）→ 计划正文覆盖（计划要先存在）
     * → 钉值（变量要先存在）→ 计划启用态（计划要先存在）。反过来就会互相打架：
     * 先恢复钉值的话，运行时新增的 SVID 还没进模型，那次钉值就被丢掉了。</p>
     *
     * <p>单条失败（配置改过、SVID 被删、JSON 坏了）只跳过这一条，不整体放弃：
     * 恢复是尽力而为，宁可少恢复一项，也不能因为一条脏数据让设备起不来。</p>
     *
     * @param hsms     新注册的会话
     * @param deviceId config.db 的 ULID
     * @param state    运行态仓储
     */
    static void restoreRuntimeEdits(HsmsSessionRuntime hsms, String deviceId,
                                    RuntimeStateRepository state) {
        try {
            for (RuntimeStateRepository.OverrideRow row : state.catalogEdits(deviceId)) {
                String raw = row.key();
                int colon = raw.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String kind = raw.substring(0, colon);
                int key = Integer.parseInt(raw.substring(colon + 1));
                restoreCatalogEdit(hsms, kind, key, row.op(), row.bodyJson());
            }
        } catch (RuntimeException e) {
            Log.error("恢复目录编辑失败（跳过）: " + deviceId + " " + e.getMessage(), e);
        }
        try {
            for (RuntimeStateRepository.OverrideRow row : state.planOverrides(deviceId)) {
                switch (row.op()) {
                    case "add" -> hsms.addEventPlan(RuntimeEdits.parseEventPlan(row.bodyJson()));
                    case "update" -> hsms.updateEventPlan(row.key(),
                            RuntimeEdits.parseEventPlan(row.bodyJson()));
                    case "remove" -> hsms.removeEventPlan(row.key());
                    default -> Log.error("忽略未知计划覆盖 op=" + row.op() + "(" + row.key() + ")", null);
                }
            }
        } catch (RuntimeException e) {
            Log.error("恢复计划覆盖失败（跳过）: " + deviceId + " " + e.getMessage(), e);
        }
        try {
            state.pinnedSvids(deviceId).forEach((svid, value) ->
                    hsms.pinSvidValue(svid.intValue(), value));
        } catch (RuntimeException e) {
            Log.error("恢复钉值失败（跳过）: " + deviceId + " " + e.getMessage(), e);
        }
        try {
            /* 停用也要恢复：计划加载时一律默认禁用，只有这里回放用户上次的选择，
               否则重启后用户手动启动过的计划会全部退回停止 */
            for (RuntimeStateRepository.PlanStateRow row : state.planStates(deviceId)) {
                hsms.setEventEnabled(row.planId(), row.enabled());
            }
        } catch (RuntimeException e) {
            Log.error("恢复计划启用态失败（跳过）: " + deviceId + " " + e.getMessage(), e);
        }
    }

    private static void restoreCatalogEdit(HsmsSessionRuntime hsms, String kind, int key,
                                           String op, String bodyJson) {
        try {
            switch (kind) {
                case "ceid" -> {
                    switch (op) {
                        case "add" -> hsms.addCeid(RuntimeEdits.parseCeid(bodyJson));
                        case "update" -> hsms.updateCeid(RuntimeEdits.parseCeid(bodyJson));
                        case "remove" -> hsms.removeCeid(key);
                        default -> { }
                    }
                }
                case "report" -> {
                    switch (op) {
                        case "add" -> hsms.addReport(RuntimeEdits.parseReport(bodyJson));
                        case "update" -> hsms.updateReport(RuntimeEdits.parseReport(bodyJson));
                        case "remove" -> hsms.removeReport(key);
                        default -> { }
                    }
                }
                case "svid" -> {
                    switch (op) {
                        case "add" -> hsms.addSvid(RuntimeEdits.parseSvid(bodyJson));
                        case "update" -> hsms.updateSvid(RuntimeEdits.parseSvid(bodyJson));
                        case "remove" -> hsms.removeSvid(key);
                        default -> { }
                    }
                }
                default -> Log.error("忽略未知目录编辑 kind=" + kind + " key=" + key, null);
            }
        } catch (RuntimeException e) {
            Log.error("恢复目录编辑失败（跳过）: " + kind + ":" + key + " op=" + op
                    + " " + e.getMessage(), e);
        }
    }

    /**
     * 把握手信息写进 {@code <work-dir>/ready.txt}。
     *
     * <p>给部署脚本用：stdout 会被 systemd/包装脚本吞掉。文件只写当前用户可读
     * （POSIX 600），内容为 {@code key=value} 每行一条；不含任何凭据。</p>
     *
     * @param file   目标文件
     * @param options 启动参数
     * @param port   HTTP 端口
     */
    private static void writeReadyFile(Path file, RuntimeOptions options, int port) {
        String text = "mode=" + options.mode().id() + "\n"
                + "http=" + port + "\n"
                + "bind=" + options.bindHost() + "\n"
                + "hsms=" + options.hsmsPort() + "\n"
                + "work=" + options.workDir() + "\n";
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling("ready.txt.tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(temp,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignore) {
                /* Windows 没有 POSIX 权限；默认 ACL 已限定当前用户 */
            }
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            /* 握手文件写不出来不影响服务本身，但要让运维知道 */
            Log.error("写握手文件失败（服务继续运行）: " + file, e);
        }
    }

    /** 先退订再关闭；任何一步失败都只记录，不影响停机。 */
    private static void closeMessageLog(AutoCloseable subscription, MessageLog log) {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception e) {
                Log.error("取消报文日志订阅失败（已忽略）", e);
            }
        }
        if (log != null) {
            log.close();
        }
    }

    /** 审计日志关闭失败只记录，不影响停机。 */
    private static void closeAuditLog(AuditLog log) {
        if (log != null) {
            log.close();
        }
    }

    private static int startConfiguredSession(RuntimeOptions options, SessionLauncher launcher)
            throws IOException {
        try {
            launcher.startFromFile(options.configFile(), options.hsmsPort());
            return 0;
        } catch (ConfigException e) {
            Log.error("配置错误", e);
            return 3;
        }
    }

    private static int startDemoSession(RuntimeOptions options, SessionLauncher launcher,
                                        ConfigStore configStore) throws IOException {
        int basePort = options.hsmsPort();
        int baseDeviceId = options.deviceId();
        int lastPort = basePort + DEFAULT_CONFIGS.length - 1;
        int lastDeviceId = baseDeviceId + DEFAULT_CONFIGS.length - 1;
        /* 两台演示会话的端口/deviceId 都要错开；越界时配置编译会失败并留下坏数据，
           这里提前拒绝 */
        if (basePort < 1 || lastPort > 65_535 || baseDeviceId < 0 || lastDeviceId > 32_767) {
            Log.error("演示会话参数越界: port " + basePort + ".." + lastPort
                    + "（1..65535）, deviceId " + baseDeviceId + ".." + lastDeviceId + "（0..32767）");
            return 3;
        }

        for (int i = 0; i < DEFAULT_CONFIGS.length; i++) {
            String name = DEFAULT_CONFIGS[i];
            try {
                /* 端口/deviceId 由启动参数覆盖（不写回配置）：每次启动都生效 */
                launcher.startFromConfig(name, configStore.read(name), basePort,
                        new SessionOverrides(null, null, basePort + i, baseDeviceId + i));
            } catch (com.shimizukenta.secssim.app.config.ConfigException e) {
                Log.error("内置演示配置编译失败: " + name, e);
                return 3;
            }
        }
        return 0;
    }

    /** 内置默认配置文件名（播种与 --demo-session 共用）。 */
    private static final String[] DEFAULT_CONFIGS = {"demo-etch.json", "demo-cvd.json"};

    /**
     * 播种内置默认设备（schema v6 的需求:开箱即有两台可用设备）。
     *
     * <p>3.0 起播种进 config.db（不再写 work-dir 文件）：幂等不覆盖——库里已有
     * 同名 legacy（含迁移进来的、用户改过的）一律不动；要恢复默认就删除该设备。</p>
     */
    private static void seedDefaultDevices(ConfigRepository configs) {
        for (String name : DEFAULT_CONFIGS) {
            String stem = name.substring(0, name.length() - ".json".length());
            if (configs.findDevice(stem).isPresent()) {
                continue;
            }
            String text = classpathDefaults(name);
            if (text == null) {
                continue;
            }
            try {
                configs.createDevice(stem, stem, "demo", text, true, "seed");
                Log.info("已播种默认设备: " + name);
            } catch (RuntimeException e) {
                Log.error("播种默认设备失败: " + name, e);
            }
        }
    }

    /** 读 classpath 里的内置默认配置；缺失返回 null（只警告一次）。 */
    private static String classpathDefaults(String name) {
        try (java.io.InputStream in = Bootstrap.class.getResourceAsStream("/defaults/" + name)) {
            if (in == null) {
                Log.warn("内置默认配置缺失（不播种）: /defaults/" + name);
                return null;
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error("读取内置默认配置失败: " + name, e);
            return null;
        }
    }

    /** 播种内置设备模板（只填缺失的行；自定义模板由迁移导入）。 */
    private static void seedTemplates(TemplateRepository templates) {
        for (var entry : builtinTemplateBodies().entrySet()) {
            DeviceTemplates.Entry meta = DeviceTemplates.catalog().stream()
                    .filter((t) -> t.id().equals(entry.getKey()))
                    .findFirst().orElse(null);
            templates.seed(entry.getKey(),
                    meta == null ? entry.getKey() : meta.label(),
                    meta == null ? "内置模板" : meta.description(),
                    true, entry.getValue());
        }
    }

    /** 内置模板 id → 正文（迁移播种与模板表播种共用）。 */
    private static java.util.Map<String, String> builtinTemplateBodies() {
        java.util.Map<String, String> bodies = new java.util.LinkedHashMap<>();
        for (DeviceTemplates.Entry entry : DeviceTemplates.catalog()) {
            DeviceTemplates.json(entry.id(), "device.json")
                    .ifPresent((body) -> bodies.put(entry.id(), body));
        }
        return bodies;
    }

    /** --reset-runtime：删掉 runtime.db 及其 WAL/SHM（配置库不动）。 */
    private static void deleteRuntimeDb(Path workDir) {
        for (String suffix : new String[] {"runtime.db", "runtime.db-wal", "runtime.db-shm"}) {
            try {
                Files.deleteIfExists(workDir.resolve(suffix));
            } catch (IOException e) {
                Log.error("删除运行态库失败: " + suffix, e);
            }
        }
    }

    /**
     * 执行台账落地（3.0 M4）：台账行与 fire 计数都只入异步批量写队列，
     * SSE 摘要经节流器合并，发送线程不做任何同步 DB 写或网络推送。
     *
     * @param deviceId   设备 ULID
     * @param executions 台账仓储（自带写线程）
     * @param throttle   plan-fired 节流器
     * @return 执行器 sink
     */
    private static PlanExecutor.Sink planSink(String deviceId,
                                              PlanExecutionRepository executions,
                                              PlanFiredThrottle throttle) {
        return new PlanExecutor.Sink() {
            @Override
            public void commit(PlanExecutionRepository.Execution execution) {
                executions.append(execution);
            }

            @Override
            public void recordFire(String planId, boolean succeeded, String error) {
                /* runtime_plan_state 的计数随台账同批 upsert，不在这里直接写盘 */
                executions.appendFire(new PlanExecutionRepository.FireRecord(
                        deviceId, planId, succeeded, error == null ? "" : error));
            }

            @Override
            public void publish(String payloadJson, boolean immediate) {
                throttle.publish(deviceId, payloadJson, immediate);
            }
        };
    }

    /** 关闭存储（写连接、锁、台账写线程）。 */
    private static void closeStorage(Db db, PlanExecutionRepository planExecutions) {
        if (planExecutions != null) {
            planExecutions.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private static String vmKind() {
        return System.getProperty("org.graalvm.nativeimage.imagecode", "jvm");
    }

    /**
     * 检查 workDir 下的中文文件名能否 UTF-8 往返。
     *
     * <p>检查完就把测试文件和空目录删掉：这是启动自检，不该在用户的目录里留垃圾。
     * 目录只在确实为空时才删除，绝不会带走用户自己的文件。
     * </p>
     */
    private static boolean utf8RoundTrip(Path workDir) {
        Path dir = null;
        Path file = null;

        try {
            dir = Files.createDirectories(workDir.resolve("腔体配置"));
            file = dir.resolve("模板-温度.json");
            String content = "{\"单元\":\"CH1\",\"名称\":\"温度设置值\",\"值\":180.5}";
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return content.equals(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.error("UTF-8 往返检查失败", e);
            return false;
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    Log.error("清理 UTF-8 自检文件失败", e);
                }
            }
            if (dir != null) {
                try {
                    Files.delete(dir);
                } catch (IOException ignored) {
                    /* 目录非空说明用户在里面放了东西，保留 */
                }
            }
        }
    }

}
