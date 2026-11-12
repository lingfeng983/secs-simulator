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
import com.shimizukenta.secssim.runtime.session.RuntimeEdits;
import com.shimizukenta.secssim.app.session.SessionState;

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
        /* 内置模板开箱即用；demo 模式推迟到端口/deviceId 校验之后播种
           （见 startDemoSession），越界时不在 workDir 里留下用不上的模板 */
        if (!options.demoSession()) {
            seedDefaultConfigs(options.workDir());
        }

        /* 配置读写与建会话共用同一个目录，"编辑完就能建会话"才成立 */
        ConfigStore configStore = new ConfigStore(options.workDir());

        if (options.configFile() != null) {
            int code = startConfiguredSession(options, launcher);
            if (code != 0) {
                closeMessageLog(messageSubscription, messageLog);
                closeAuditLog(auditLog);
                return code;
            }
        } else if (options.demoSession()) {
            int code = startDemoSession(options, launcher);
            if (code != 0) {
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
                extra.toString(), stopped::countDown, sessions, launcher, configStore, messageLog, auditLog,
                bus);

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

    private static int startDemoSession(RuntimeOptions options, SessionLauncher launcher)
            throws IOException {
        int basePort = options.hsmsPort();
        int baseDeviceId = options.deviceId();
        int lastPort = basePort + DEFAULT_CONFIGS.length - 1;
        int lastDeviceId = baseDeviceId + DEFAULT_CONFIGS.length - 1;
        /* 两台演示会话的端口/deviceId 都要错开；越界时配置编译会失败并留下坏文件，
           这里提前拒绝，避免"演示启动永久失败、只能手工删文件" */
        if (basePort < 1 || lastPort > 65_535 || baseDeviceId < 0 || lastDeviceId > 32_767) {
            Log.error("演示会话参数越界: port " + basePort + ".." + lastPort
                    + "（1..65535）, deviceId " + baseDeviceId + ".." + lastDeviceId + "（0..32767）");
            return 3;
        }

        /* v6:--demo-session 开两台内置演示设备（run() 已为 demo 模式跳过播种，
           这里才是唯一播种点）；分散演示"事件驱动值变化"(etch)与"主机拉取采集"(cvd)。
           播种必须在校验之后：越界时不能往 workDir 里落下两份用不了的模板 */
        seedDefaultConfigs(options.workDir());

        for (int i = 0; i < DEFAULT_CONFIGS.length; i++) {
            String name = DEFAULT_CONFIGS[i];
            try {
                /* 端口/deviceId 由启动参数覆盖（不写回文件）：每次启动都生效，
                   不再依赖"只在首次播种时改写模板文本" */
                launcher.startFromFile(options.workDir().resolve(name), basePort,
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
     * 播种内置默认设备配置（schema v6 的需求:开箱即有两台可用设备）。
     *
     * <p>幂等不覆盖:workDir 里已有同名文件（含用户改过的）一律不动,
     * 删除文件重启即恢复默认。模板原样写入；端口/deviceId 由启动参数
     * 通过 {@link SessionOverrides} 覆盖，不在这里改写文本——
     * 改文本依赖精确格式，模板一美化就静默失效。</p>
     */
    private static void seedDefaultConfigs(java.nio.file.Path workDir) {
        for (String name : DEFAULT_CONFIGS) {
            java.nio.file.Path target = workDir.resolve(name);
            if (java.nio.file.Files.exists(target)) {
                continue;
            }
            String text;
            try (java.io.InputStream in = Bootstrap.class.getResourceAsStream("/defaults/" + name)) {
                if (in == null) {
                    Log.warn("内置默认配置缺失（不播种）: /defaults/" + name);
                    continue;
                }
                text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                Log.error("读取内置默认配置失败: " + name, e);
                continue;
            }

            try {
                java.nio.file.Files.writeString(target, text, java.nio.charset.StandardCharsets.UTF_8);
                Log.info("已播种默认设备配置: " + name);
            } catch (IOException e) {
                Log.error("播种默认配置失败: " + name, e);
            }
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
