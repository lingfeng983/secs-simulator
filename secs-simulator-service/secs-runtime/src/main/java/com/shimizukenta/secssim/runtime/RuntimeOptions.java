package com.shimizukenta.secssim.runtime;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 命令行参数。
 *
 * <p>支持 {@code --key=value} 与 {@code --key value} 两种写法；
 * 未知参数直接报错，避免"拼错了却静默生效默认值"这类现场事故。
 * </p>
 *
 * @param mode        运行形态
 * @param bindHost    绑定地址
 * @param httpPort    HTTP 端口，0 表示由系统分配
 * @param hsmsPort    SECS 端口（仅 probe 模式的监听口）
 * @param deviceId    SECS device-id
 * @param configFile  配置文件路径，{@code null} 表示不用配置
 * @param webRoot     静态前端目录，{@code null} 表示只提供 API
 * @param workDir     工作目录（配置、日志、模板）
 * @param tlsKeystore PKCS#12 keystore，{@code null} 表示明文 HTTP
 * @param tlsPassword keystore 密码
 * @param help           是否只打印帮助
 * @param logLevel       日志级别（error/info/debug）
 * @param messagesRecent 报文日志内存保留条数（HTTP 历史回看窗口）
 * @param auditRecent    审计日志内存保留条数
 * @param exportConfig   导出目录；非空时导出后直接退出（备份通道）
 * @param resetRuntime   启动前删除 runtime.db
 * @param allowSchemaReset 库版本与程序不匹配时，备份后自动重建（默认拒绝启动）
 */
public record RuntimeOptions(RuntimeMode mode, String bindHost, int httpPort,
                             int hsmsPort, int deviceId,
                             Path configFile, Path webRoot, Path workDir,
                             Path tlsKeystore, String tlsPassword, boolean help,
                             com.shimizukenta.secssim.app.log.Log.Level logLevel,
                             int messagesRecent, int auditRecent,
                             Path exportConfig, boolean resetRuntime, boolean allowSchemaReset) {

    /** 报文日志内存保留条数默认值（与 MessageLog.DEFAULT_RECENT 对齐）。 */
    public static final int DEFAULT_MESSAGES_RECENT = 5_000;
    /** 审计日志内存保留条数默认值（与 AuditLog.DEFAULT_RECENT 对齐）。 */
    public static final int DEFAULT_AUDIT_RECENT = 1_000;

    /**
     * 校验并创建。
     *
     * @param mode        形态
     * @param bindHost    绑定地址
     * @param httpPort    HTTP 端口
     * @param hsmsPort    SECS 端口
     * @param deviceId    device-id
     * @param configFile  配置文件
     * @param webRoot     静态前端目录
     * @param workDir     工作目录
     * @param tlsKeystore PKCS#12 keystore
     * @param tlsPassword keystore 密码
     * @param help        帮助标记
     */
    public RuntimeOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(bindHost, "bindHost");
        Objects.requireNonNull(workDir, "workDir");
        if (httpPort < 0 || httpPort > 65535) {
            throw new IllegalArgumentException("http port out of range: " + httpPort);
        }
        if (hsmsPort < 0 || hsmsPort > 65535) {
            throw new IllegalArgumentException("hsms port out of range: " + hsmsPort);
        }
        if (deviceId < 0 || deviceId > 32767) {
            throw new IllegalArgumentException("deviceId out of range: " + deviceId);
        }
        if (messagesRecent < 1 || messagesRecent > 1_000_000) {
            throw new IllegalArgumentException("messages-recent out of range: " + messagesRecent);
        }
        if (auditRecent < 1 || auditRecent > 1_000_000) {
            throw new IllegalArgumentException("audit-recent out of range: " + auditRecent);
        }
    }

    /**
     * 解析命令行。
     *
     * @param args 原始参数
     * @return 选项
     */
    public static RuntimeOptions parse(String[] args) {
        Map<String, String> values = new HashMap<>();

        for (int i = 0; i < args.length; i++) {
            String raw = args[i];
            if ("--help".equals(raw) || "-h".equals(raw)) {
                values.put("help", "true");
                continue;
            }
            if (!raw.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数: " + raw);
            }

            int eq = raw.indexOf('=');
            if (eq > 0) {
                values.put(raw.substring(2, eq), raw.substring(eq + 1));
            } else {
                String key = raw.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    values.put(key, args[++i]);
                } else {
                    values.put(key, "true");
                }
            }
        }

        Set<String> known = Set.of("mode", "bind", "port", "hsms-port",
                "device-id", "config", "web-root", "work-dir",
                "tls-keystore", "tls-password", "help", "log-level",
                "messages-recent", "audit-recent",
                "export-config", "reset-runtime", "allow-schema-reset");
        for (String key : values.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException("未知参数 --" + key);
            }
        }

        RuntimeMode mode = RuntimeMode.fromId(values.getOrDefault("mode", RuntimeMode.INTEGRATED.id()));

        return new RuntimeOptions(
                mode,
                /* 内网工具：默认监听所有网卡，需要收窄时显式 --bind */
                values.getOrDefault("bind", "0.0.0.0"),
                intValue(values, "port", 0),
                /* 仅 probe 模式的监听口；设备会话的 SECS 口一律来自各自配置（数据库） */
                intValue(values, "hsms-port", 5000),
                intValue(values, "device-id", 10),
                values.containsKey("config") ? Path.of(values.get("config")) : null,
                values.containsKey("web-root") ? Path.of(values.get("web-root")) : null,
                Path.of(values.getOrDefault("work-dir", defaultWorkDir())),
                values.containsKey("tls-keystore") ? Path.of(values.get("tls-keystore")) : null,
                values.getOrDefault("tls-password", ""),
                Boolean.parseBoolean(values.getOrDefault("help", "false")),
                com.shimizukenta.secssim.app.log.Log.Level.parse(
                        values.getOrDefault("log-level", "INFO")),
                intValue(values, "messages-recent", DEFAULT_MESSAGES_RECENT),
                intValue(values, "audit-recent", DEFAULT_AUDIT_RECENT),
                values.containsKey("export-config") ? Path.of(values.get("export-config")) : null,
                Boolean.parseBoolean(values.getOrDefault("reset-runtime", "false")),
                Boolean.parseBoolean(values.getOrDefault("allow-schema-reset", "false")));
    }

    /**
     * 帮助文本。
     *
     * @return 用法说明
     */
    public static String usage() {
        return """
                用法: secs-sim [选项]

                  --log-level <error|info|debug>     日志级别，默认 info（debug 输出报文级细节）
                  --mode <integrated|server|probe>  运行形态，默认 integrated
                  --bind <host>                     绑定地址，默认 0.0.0.0（需要收窄时显式指定）
                  --port <n>                        HTTP 端口，默认 0（系统分配）
                  --hsms-port <n>                   probe 模式的 SECS 监听口，默认 5000
                                                  （设备会话的 SECS 口以各自配置为准，无兜底）
                  --device-id <n>                   SECS device-id，默认 10
                  --config <path>                   把一份设备配置导入数据库并立即启动（显式动作）
                  --web-root <dir>                  托管静态前端（与 API 同源，浏览器直接打开）
                  --work-dir <path>                 工作目录
                  --tls-keystore <path>             PKCS#12 keystore；提供时 API 走 HTTPS
                  --tls-password <s>                keystore 密码（默认空）
                  --messages-recent <n>             报文历史内存保留条数，默认 5000（界面上能回看的窗口）
                  --audit-recent <n>                审计历史内存保留条数，默认 1000（重启后也从文件恢复这么多）
                  --export-config <dir>             把库里的全部设备导出为 <dir>/<name>.json 后退出（备份通道）
                  --reset-runtime                   启动前删除 runtime.db（运行态可随时丢弃重建）
                  --allow-schema-reset              库结构版本与程序不一致时，备份后自动重建；默认拒绝启动
                  --help                            打印本帮助

                访问：内部工具，不做鉴权——浏览器/脚本直连地址即可，没有令牌或账号。
                默认监听 0.0.0.0（所有网卡）；需要收窄时显式 --bind 127.0.0.1。

                设备模型：设备存于数据库（config.db），启动后界面里显示为「停止」状态，
                由用户点击「启动」才建立会话并监听 HSMS；--config 是显式的导入并启动动作。

                启动后：stdout 打印一行 READY 供桌面壳握手；同时把 http/端口写进
                <work-dir>/ready.txt（权限 600），供部署脚本读取。

                """;
    }

    private static int intValue(Map<String, String> values, String key, int fallback) {
        String raw = values.get(key);
        return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
    }

    private static String defaultWorkDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "SecsSimulator").toString();
        }
        return Path.of(System.getProperty("user.home"), ".secs-simulator").toString();
    }
}
