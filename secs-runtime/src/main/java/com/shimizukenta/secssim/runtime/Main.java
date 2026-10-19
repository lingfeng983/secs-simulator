package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
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
import com.shimizukenta.secssim.app.event.MessageLog;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionState;

/**
 * 程序入口。
 *
 * <p>三种形态共用同一条启动路径，只有绑定地址、探针端口与鉴权策略不同。
 * 这是刻意的：一体化与远程服务端如果走两条代码路径，必然会出现
 * "单机能用、连服务器就不对"的问题。
 * </p>
 */
public final class Main {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long DEMO_SEED = 20240601L;

    private Main() {
    }

    /**
     * 进程入口。
     *
     * @param args 命令行
     */
    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Exception e) {
            /* 单行摘要给桌面壳看；完整栈只在需要时输出，避免刷屏 */
            Log.error("启动失败", e);
            if (System.getenv("SECS_SIM_DEBUG") != null) {
                e.printStackTrace();
            }
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
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

        if (options.mode() == RuntimeMode.SERVER
                && options.token().isBlank() && options.authUsers() == null) {
            Log.error("server 模式必须显式提供 --token 或 --auth-users"
                    + "（不能每次启动随机生成，否则所有客户端都会掉线）", null);
            return 2;
        }

        SSLContext tls = null;
        if (options.tlsKeystore() != null) {
            try {
                tls = TlsSupport.load(options.tlsKeystore(), options.tlsPassword());
            } catch (Exception e) {
                Log.error("加载 TLS keystore 失败: " + options.tlsKeystore(), e);
                return 2;
            }
        }

        Credentials credentials;
        try {
            credentials = resolveCredentials(options);
        } catch (ConfigException | IOException e) {
            Log.error("加载账号文件失败: " + options.authUsers(), e);
            return 2;
        }
        AccessControl access = credentials.access();
        String token = credentials.token();

        boolean utf8Ok = utf8RoundTrip(options.workDir());
        CountDownLatch stopped = new CountDownLatch(1);

        SessionManager sessions = new SessionManager();
        EventBus bus = new EventBus();
        SecsProbe probe = null;

        /*
         * 报文日志是"诊断材料"，不是业务数据：写不进去只少一段历史，不能让设备起不来。
         * 打开失败时 messageLog 保持 null，HTTP 历史接口会如实回 503。
         */
        MessageLog messageLog = null;
        try {
            messageLog = MessageLog.open(options.workDir());
            Log.info("报文日志: " + messageLog.file());
        } catch (IOException e) {
            Log.error("打开报文日志失败（本次不落盘）: " + options.workDir(), e);
        }
        AutoCloseable messageSubscription = messageLog == null ? null : bus.subscribe(messageLog::append);

        AuditLog auditLog = null;
        try {
            auditLog = AuditLog.open(options.workDir());
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

        ApiContext context = new Context(options.mode().id(), BuildInfo.version(),
                extra.toString(), stopped::countDown, sessions, launcher, configStore, messageLog, auditLog);

        HttpApiServer api = HttpApiServer.start(
                new InetSocketAddress(options.bindHost(), options.httpPort()), access, context,
                options.webRoot(), bus, tls, auditSink);

        Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown, "shutdown-hook"));

        System.out.println("READY mode=" + options.mode().id()
                + " http=" + api.port()
                + " token=" + token
                + " accounts=" + access.size()
                + " utf8=" + utf8Ok
                + " tls=" + (tls != null)
                + " sessions=" + sessions.list().size()
                + ((probe == null && !options.demoSession()) ? "" : " hsms=" + options.hsmsPort())
                + " work=" + options.workDir());
        System.out.flush();

        try {
            stopped.await();
        } finally {
            sessions.closeAll();
            if (probe != null) {
                probe.close();
            }
            api.close();
            closeMessageLog(messageSubscription, messageLog);
            closeAuditLog(auditLog);
        }
        return 0;
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

    /**
     * 决定这次启动用哪种凭据。
     *
     * <p>{@code --auth-users} 与 {@code --token} 不是叠加关系，而是<b>二选一</b>：
     * 同时给出时按账号文件走，并把这件事打进日志——
     * 悄悄忽略一个用户明确写了的参数，比报错更难查。
     * </p>
     *
     * <p>单令牌模式下，未显式给 {@code --token} 就随机生成一个。
     * 注意这里必须用生成后的 {@code token} 去建 {@code AccessControl}：
     * {@code options.token()} 此时还是空串，直接拿它建表会得到一个"空口令"账号。
     * </p>
     *
     * @param options 命令行
     * @return 访问控制与 READY 行要打印的 token
     * @throws IOException 账号文件读失败
     */
    static Credentials resolveCredentials(RuntimeOptions options) throws IOException {
        if (options.authUsers() != null) {
            if (!options.token().isBlank()) {
                Log.error("--token 与 --auth-users 同时给出：按 --auth-users 走，--token 被忽略", null);
            }
            /* 凭据在账号文件里，READY 行的 token 留空表示"没有单一令牌" */
            return new Credentials(AccessControl.load(options.authUsers()), "");
        }

        String token = options.token().isBlank() ? newToken() : options.token();
        return new Credentials(AccessControl.singleToken(token), token);
    }

    /**
     * 一次启动的凭据结果。
     *
     * @param access 访问控制
     * @param token  READY 行要打印的 token；账号文件模式下为空串
     */
    record Credentials(AccessControl access, String token) {
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
        launcher.startDemo("demo", options.hsmsPort(), options.deviceId());
        return 0;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
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

    /** 运行时的 HTTP 上下文：只做转发，不含业务逻辑。 */
    private static final class Context implements ApiContext {

        private final String mode;
        private final String version;
        private final String extraHealthJson;
        private final Runnable shutdown;
        private final SessionManager sessions;
        private final SessionLauncher launcher;
        private final ConfigStore configs;
        private final MessageLog messages;
        private final AuditLog audit;

        Context(String mode, String version, String extraHealthJson, Runnable shutdown,
                SessionManager sessions, SessionLauncher launcher, ConfigStore configs,
                MessageLog messages, AuditLog audit) {
            this.mode = mode;
            this.version = version;
            this.extraHealthJson = extraHealthJson;
            this.shutdown = shutdown;
            this.sessions = sessions;
            this.launcher = launcher;
            this.configs = configs;
            this.messages = messages;
            this.audit = audit;
        }

        @Override
        public String mode() {
            return this.mode;
        }

        @Override
        public String version() {
            return this.version;
        }

        @Override
        public String extraHealthJson() {
            return this.extraHealthJson;
        }

        @Override
        public void requestShutdown() {
            this.shutdown.run();
        }

        @Override
        public String sessionsJson() {
            return SessionsJson.render(this.sessions.list());
        }

        @Override
        public String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
            return this.sessions.find(sessionId)
                    .map(session -> session.previewJson(fromMs, toMs, buckets))
                    .orElse(null);
        }

        @Override
        public byte[] previewBinary(String sessionId, long fromMs, long toMs, int buckets) {
            return this.sessions.find(sessionId)
                    .map(session -> session.previewBinary(fromMs, toMs, buckets))
                    .orElse(null);
        }

        @Override
        public TriggerOutcome triggerCeid(String sessionId, int ceid) {
            SessionRuntime session = this.sessions.find(sessionId).orElse(null);

            if (session == null) {
                return TriggerOutcome.UNKNOWN_SESSION;
            }
            if (session.state() != SessionState.OPEN || !session.hasPeer()) {
                return TriggerOutcome.NOT_OPEN;
            }

            try {
                session.emitCeid(ceid);
                return TriggerOutcome.OK;
            } catch (IOException e) {
                Log.error("手动触发 CEID " + ceid + " 失败", e);
                return TriggerOutcome.FAILED;
            }
        }

        @Override
        public TriggerOutcome triggerAlarm(String sessionId, int alid, boolean set, String text, int category) {
            SessionRuntime session = this.sessions.find(sessionId).orElse(null);

            if (session == null) {
                return TriggerOutcome.UNKNOWN_SESSION;
            }
            if (session.state() != SessionState.OPEN || !session.hasPeer()) {
                return TriggerOutcome.NOT_OPEN;
            }

            try {
                session.emitAlarm(alid, set, text, category);
                return TriggerOutcome.OK;
            } catch (IOException e) {
                Log.error("手动触发 ALID " + alid + " 失败", e);
                return TriggerOutcome.FAILED;
            }
        }

        @Override
        public String alarmsJson(String sessionId) {
            return this.sessions.find(sessionId).map(SessionRuntime::alarmsJson).orElse(null);
        }

        @Override
        public String terminalMessagesJson(String sessionId) {
            return this.sessions.find(sessionId).map(SessionRuntime::terminalMessagesJson).orElse(null);
        }

        @Override
        public String catalogJson(String sessionId) {
            return this.sessions.find(sessionId).map(SessionRuntime::catalogJson).orElse(null);
        }

        @Override
        public String eventsJson(String sessionId) {
            return this.sessions.find(sessionId).map(SessionRuntime::eventsJson).orElse(null);
        }

        @Override
        public EventOutcome setEventEnabled(String sessionId, String planId, boolean enabled) {
            SessionRuntime session = this.sessions.find(sessionId).orElse(null);
            if (session == null) {
                return EventOutcome.UNKNOWN_SESSION;
            }
            return session.setEventEnabled(planId, enabled) ? EventOutcome.OK : EventOutcome.UNKNOWN_PLAN;
        }

        @Override
        public String messagesJson(int limit, String sessionId, String direction) {
            return this.messages == null ? null : this.messages.recentJson(limit, sessionId, direction);
        }

        @Override
        public String messagesJsonl(int limit, String sessionId, String direction) {
            return this.messages == null ? null : this.messages.recentJsonl(limit, sessionId, direction);
        }

        @Override
        public String auditJson(int limit) {
            return this.audit == null ? null : this.audit.recentJson(limit);
        }

        @Override
        public String sendSml(String sessionId, String sml) throws IOException {
            SessionRuntime session = this.sessions.find(sessionId).orElse(null);
            return session == null ? null : session.sendSml(sml);
        }

        @Override
        public SessionOutcome createSession(String configName) {
            try {
                String id = this.launcher.create(configName);
                Log.info("新建会话 " + id + "（配置 " + configName + "）");
                return SessionOutcome.OK;
            } catch (ConfigException e) {
                /* 配置名非法、文件读不了、内容编译不过——都是"这台设备起不来" */
                Log.error("新建会话失败（配置）: " + e.getMessage(), null);
                return SessionOutcome.FAILED;
            } catch (IOException e) {
                /* 到这一步配置已经是好的，只剩端口没绑上 */
                Log.error("新建会话失败（端口绑定）: " + e.getMessage(), null);
                return SessionOutcome.FAILED;
            } catch (IllegalArgumentException e) {
                Log.error("新建会话失败（标识冲突）: " + e.getMessage(), null);
                return SessionOutcome.CONFLICT;
            }
        }

        @Override
        public SessionOutcome closeSession(String sessionId) {
            return this.launcher.close(sessionId) ? SessionOutcome.OK : SessionOutcome.UNKNOWN_SESSION;
        }

        @Override
        public SessionOutcome reloadSession(String sessionId) {
            if (this.sessions.find(sessionId).isEmpty()) {
                return SessionOutcome.UNKNOWN_SESSION;
            }
            if (this.launcher.sourceOf(sessionId).isEmpty()) {
                return SessionOutcome.NOT_RELOADABLE;
            }

            try {
                this.launcher.reload(sessionId);
                Log.info("会话 " + sessionId + " 已按配置重载");
                return SessionOutcome.OK;
            } catch (ConfigException e) {
                /* 配置有问题时旧会话原样跑着，这里只是如实报告失败 */
                Log.error("重载失败（配置，旧会话未动）: " + e.getMessage(), null);
                return SessionOutcome.FAILED;
            } catch (IOException e) {
                Log.error("重载失败（端口绑定）: " + e.getMessage(), null);
                return SessionOutcome.FAILED;
            }
        }

        @Override
        public String configsJson() {
            return this.configs.listJson();
        }

        @Override
        public String configText(String name) {
            try {
                return this.configs.read(name);
            } catch (ConfigException e) {
                /* 名字非法与文件不存在都归到"取不到"，界面只需知道这份打不开 */
                return null;
            } catch (IOException e) {
                Log.error("读取配置失败: " + name, e);
                return null;
            }
        }

        @Override
        public List<String> validateConfig(String text) {
            return ConfigStore.validate(text);
        }

        @Override
        public ConfigResult saveConfig(String name, String text) {
            ConfigResult result = this.configs.save(name, text);
            if (result.outcome() == ConfigOutcome.OK) {
                this.warnStaleSessions(name);
            }
            return result;
        }

        @Override
        public ConfigResult createConfig(String name) {
            ConfigResult result = this.configs.create(name);
            if (result.outcome() == ConfigOutcome.OK) {
                Log.info("新建配置 " + name);
            }
            return result;
        }

        /**
         * 提示"存了但没生效"。
         *
         * <p>会话是在启动那一刻读配置读进内存的，之后再改文件它不会自己变。
         * 用户改完配置发现设备行为没变，第一反应是"编辑器根本没存"。
         * 这里直接把还要重载哪个会话说清楚，比让人自己猜要省事。
         * </p>
         */
        private void warnStaleSessions(String name) {
            for (SessionRuntime session : this.sessions.list()) {
                Path source = this.launcher.sourceOf(session.id()).orElse(null);
                if (source != null && source.getFileName().toString().equals(name.trim())) {
                    Log.info("配置已改，但会话 " + session.id() + " 仍在用旧配置，重载后才生效");
                }
            }
        }
    }
}
