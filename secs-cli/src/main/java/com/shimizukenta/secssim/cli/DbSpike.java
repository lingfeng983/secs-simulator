package com.shimizukenta.secssim.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * M0 原生镜像尖峰（3.0 设计 §2.3.1 / ADR 0017）：在真实进程里验证
 * sqlite-jdbc 的 JNI 本地库能在 GraalVM native-image 中加载。
 *
 * <p>这是存储地基（M1）的硬门禁：sqlite-jdbc 是本仓库第一个 JNI 依赖，
 * 原生镜像内 classpath 资源无法被 JNI 直接 dlopen，所以按设计的顺序做四件事——</p>
 *
 * <ol>
 *   <li>把 classpath 里的平台本地库释放到 {@code <work-dir>/native/}，
 *       并设 {@code org.sqlite.lib.path} / {@code org.sqlite.lib.name}，
 *       让 sqlite-jdbc 直接加载落盘的那份（不依赖运行时自解压）；</li>
 *   <li>创建/打开 {@code config.db} 与 {@code runtime.db}（WAL）；</li>
 *   <li>{@code SELECT sqlite_version()} 证明 JNI 链路通；</li>
 *   <li>在 runtime.db 建最小 {@code plan_execution} 表并写一行，证明写事务可用。</li>
 * </ol>
 *
 * <p>全部成功打印 {@code DBSPIKE ok ...} 后继续正常启动；任何一步失败进程
 * 以非 0 退出（{@code NativeRuntimeIT} 会据此判红）。M1 落地后，本地库释放
 * 逻辑迁入 secs-store 的 {@code DbBootstrap}，本类退化为冒烟路径。</p>
 */
public final class DbSpike {

    private DbSpike() {
    }

    /**
     * 命令行含 {@code --db-spike} 时先跑尖峰，返回去掉该旗标后的参数；
     * 没有旗标则原样返回，不做任何事。
     *
     * @param args 原始命令行
     * @return 交给 Bootstrap 的命令行
     * @throws IOException 释放本地库失败
     * @throws SQLException 数据库打开/建表/写入失败
     * @throws ClassNotFoundException JDBC 驱动类缺失
     */
    public static String[] maybeRun(String[] args)
            throws IOException, SQLException, ClassNotFoundException {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        if (!rest.remove("--db-spike")) {
            return args;
        }
        Path workDir = workDirOf(rest);
        run(workDir);
        return rest.toArray(String[]::new);
    }

    /** 跑一次尖峰；失败抛异常，由调用方决定退出码。 */
    static void run(Path workDir) throws IOException, SQLException, ClassNotFoundException {
        Files.createDirectories(workDir);

        /* 1. 释放平台本地库：native-image 里 getResourceAsStream 只能读注册过的资源，
              读出来落盘后用 -Dorg.sqlite.lib.path 交给 sqlite-jdbc 直接 System.load。 */
        extractNativeLib(workDir);

        Class.forName("org.sqlite.JDBC");

        /* 2+3. config.db：WAL + 读版本号（JNI 链路通的最直接证据） */
        String version;
        try (Connection config = DriverManager.getConnection(jdbcUrl(workDir.resolve("config.db")))) {
            try (Statement st = config.createStatement()) {
                try (ResultSet rs = st.executeQuery("PRAGMA journal_mode=WAL")) {
                    rs.next();
                }
                try (ResultSet rs = st.executeQuery("SELECT sqlite_version()")) {
                    if (!rs.next()) {
                        throw new SQLException("sqlite_version() 返回空");
                    }
                    version = rs.getString(1);
                }
            }
        }

        /* 4. runtime.db：最小 plan_execution 写一行（执行台账的形态预演） */
        try (Connection runtime = DriverManager.getConnection(jdbcUrl(workDir.resolve("runtime.db")))) {
            try (Statement st = runtime.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS plan_execution ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "device_id TEXT NOT NULL, plan_id TEXT NOT NULL, "
                        + "status TEXT NOT NULL, fired_at INTEGER NOT NULL)");
                st.executeUpdate("INSERT INTO plan_execution(device_id, plan_id, status, fired_at) "
                        + "VALUES ('spike', 'm0-gate', 'SENT', " + System.currentTimeMillis() + ")");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM plan_execution")) {
                    rs.next();
                    if (rs.getLong(1) < 1) {
                        throw new SQLException("台账行没有写进去");
                    }
                }
            }
        }

        System.out.println("DBSPIKE ok sqlite=" + version + " work=" + workDir.toAbsolutePath());
        System.out.flush();
    }

    /**
     * 把当前平台的 sqlite-jdbc 本地库从 classpath 释放到 {@code <work-dir>/native/}。
     *
     * <p>M1 的 {@code DbBootstrap} 会复用这段逻辑；资源路径由 sqlite-jdbc 的
     * {@code org/sqlite/native/<OS>/<arch>/} 布局决定。</p>
     *
     * @param workDir 工作目录
     * @throws IOException 资源缺失或写盘失败（work-dir 只读 / 网络盘 / 杀软拦截都落到这里）
     */
    public static void extractNativeLib(Path workDir) throws IOException {
        String resource = nativeResource();
        Path dir = workDir.resolve("native");
        Files.createDirectories(dir);
        Path target = dir.resolve(Path.of(resource).getFileName());
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            try (InputStream in = DbSpike.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("classpath 里没有 " + resource
                            + "：检查 reachability-metadata.json 的 resources 段与 -H:IncludeResources");
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.setProperty("org.sqlite.lib.path", dir.toAbsolutePath().toString());
        System.setProperty("org.sqlite.lib.name", target.getFileName().toString());
    }

    /** 当前平台的 sqlite-jdbc 本地库 classpath 路径。 */
    private static String nativeResource() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String dir;
        if (os.contains("win")) {
            dir = "Windows";
        } else if (os.contains("linux")) {
            dir = "Linux";
        } else if (os.contains("mac")) {
            dir = "Mac";
        } else {
            throw new IllegalStateException("不支持的操作系统: " + os);
        }
        String archDir = arch.contains("aarch64") || arch.contains("arm") ? "aarch64" : "x86_64";
        String file;
        if (dir.equals("Windows")) {
            file = "sqlitejdbc.dll";
        } else if (dir.equals("Mac")) {
            file = "libsqlitejdbc.jnilib";
        } else {
            file = "libsqlitejdbc.so";
        }
        return "org/sqlite/native/" + dir + "/" + archDir + "/" + file;
    }

    private static String jdbcUrl(Path db) {
        return "jdbc:sqlite:" + db.toAbsolutePath();
    }

    /** 从（已去掉旗标的）命令行里取 --work-dir；尖峰要求显式给出，避免写错目录。 */
    private static Path workDirOf(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if (token.equals("--work-dir") && i + 1 < args.size()) {
                return Path.of(args.get(i + 1));
            }
            if (token.startsWith("--work-dir=")) {
                return Path.of(token.substring("--work-dir=".length()));
            }
        }
        throw new IllegalArgumentException("--db-spike 需要同时给出 --work-dir <path>");
    }
}
