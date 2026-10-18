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
 * @param token       访问令牌，空串表示自动生成
 * @param hsmsPort    SECS 端口（探针或演示会话）
 * @param deviceId    SECS device-id
 * @param demoSession 是否开一个演示会话（两腔体，应答 S1F3）
 * @param configFile  配置文件路径，{@code null} 表示不用配置
 * @param webRoot     静态前端目录，{@code null} 表示只提供 API
 * @param workDir     工作目录（配置、日志、模板）
 * @param tlsKeystore PKCS#12 keystore，{@code null} 表示明文 HTTP
 * @param tlsPassword keystore 密码
 * @param authUsers   多账号文件（带角色），{@code null} 表示只用单 {@code --token}
 * @param help        是否只打印帮助
 */
public record RuntimeOptions(RuntimeMode mode, String bindHost, int httpPort, String token,
                             int hsmsPort, int deviceId, boolean demoSession,
                             Path configFile, Path webRoot, Path workDir,
                             Path tlsKeystore, String tlsPassword, Path authUsers, boolean help) {

    /**
     * 校验并创建。
     *
     * @param mode        形态
     * @param bindHost    绑定地址
     * @param httpPort    HTTP 端口
     * @param token       令牌
     * @param hsmsPort    SECS 端口
     * @param deviceId    device-id
     * @param demoSession 是否演示会话
     * @param configFile  配置文件
     * @param webRoot     静态前端目录
     * @param workDir     工作目录
     * @param tlsKeystore PKCS#12 keystore
     * @param tlsPassword keystore 密码
     * @param authUsers   多账号文件（带角色）
     * @param help        帮助标记
     */
    public RuntimeOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(bindHost, "bindHost");
        Objects.requireNonNull(token, "token");
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

        Set<String> known = Set.of("mode", "bind", "port", "token", "hsms-port",
                "device-id", "demo-session", "config", "web-root", "work-dir",
                "tls-keystore", "tls-password", "auth-users", "help");
        for (String key : values.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException("未知参数 --" + key);
            }
        }

        RuntimeMode mode = RuntimeMode.fromId(values.getOrDefault("mode", RuntimeMode.INTEGRATED.id()));

        return new RuntimeOptions(
                mode,
                values.getOrDefault("bind", mode.loopbackOnly() ? "127.0.0.1" : "0.0.0.0"),
                intValue(values, "port", 0),
                values.getOrDefault("token", ""),
                intValue(values, "hsms-port", 5000),
                intValue(values, "device-id", 10),
                Boolean.parseBoolean(values.getOrDefault("demo-session", "false")),
                values.containsKey("config") ? Path.of(values.get("config")) : null,
                values.containsKey("web-root") ? Path.of(values.get("web-root")) : null,
                Path.of(values.getOrDefault("work-dir", defaultWorkDir())),
                values.containsKey("tls-keystore") ? Path.of(values.get("tls-keystore")) : null,
                values.getOrDefault("tls-password", ""),
                values.containsKey("auth-users") ? Path.of(values.get("auth-users")) : null,
                Boolean.parseBoolean(values.getOrDefault("help", "false")));
    }

    /**
     * 帮助文本。
     *
     * @return 用法说明
     */
    public static String usage() {
        return """
                用法: secs-sim [选项]

                  --mode <integrated|server|probe>  运行形态，默认 integrated
                  --bind <host>                     绑定地址，默认 127.0.0.1（server 为 0.0.0.0）
                  --port <n>                        HTTP 端口，默认 0（系统分配）
                  --token <s>                       访问令牌，留空自动生成
                  --hsms-port <n>                   SECS 端口，默认 5000
                  --device-id <n>                   SECS device-id，默认 10
                  --demo-session                    开一个两腔体演示会话（应答 S1F1 / S1F3）
                  --config <path>                   从 Schema v5 JSON 装载设备与会话配置（v4 兼容）
                  --web-root <dir>                  托管静态前端（与 API 同源，浏览器直接打开）
                  --work-dir <path>                 工作目录
                  --tls-keystore <path>             PKCS#12 keystore；提供时 API 走 HTTPS
                  --tls-password <s>                keystore 密码（默认空）
                  --auth-users <path>               多账号文件（带角色）；提供时取代 --token
                  --help                            打印本帮助

                鉴权：默认单 --token（等价于 admin）。--auth-users 的 JSON 形如
                  {"users":[{"name":"guest","role":"viewer","secret":"..."},
                            {"name":"line1","role":"operator","secret":"..."}]}
                角色 viewer 只读 / operator 可触发 CEID 与报警 / admin 额外可停机。
                server 模式必须显式给 --token 或 --auth-users。

                启动后会向 stdout 打印一行 READY，供桌面壳与测试脚本握手。
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
