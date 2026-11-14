package com.shimizukenta.secssim.cli;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.shimizukenta.secssim.store.Db;
import com.shimizukenta.secssim.store.PlanExecutionRepository;

/**
 * M0 原生镜像尖峰（3.0 设计 §2.3.1 / ADR 0017）：在真实进程里验证
 * sqlite-jdbc 的 JNI 本地库能在 GraalVM native-image 中加载。
 *
 * <p>M1 起尖峰复用正式的 {@link Db}：本地库释放由 {@code DbBootstrap} 负责、
 * 双库与台账表由 {@code Schema} 建立——尖峰只做三件事：开库、读
 * {@code sqlite_version()}（JNI 链路最直接的证据）、经
 * {@link PlanExecutionRepository} 落一行台账（异步批量写路径原生可用）。
 * 全部成功打印 {@code DBSPIKE ok ...} 后继续正常启动。</p>
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
     */
    public static String[] maybeRun(String[] args) {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        if (!rest.remove("--db-spike")) {
            return args;
        }
        run(workDirOf(rest));
        return rest.toArray(String[]::new);
    }

    /** 跑一次尖峰；失败抛异常，由调用方决定退出码。 */
    static void run(Path workDir) {
        try (Db db = Db.open(workDir)) {
            String version;
            try (var connection = db.readConfig();
                 var st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT sqlite_version()")) {
                if (!rs.next()) {
                    throw new IllegalStateException("sqlite_version() 返回空");
                }
                version = rs.getString(1);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("sqlite_version 查询失败: " + e.getMessage(), e);
            }

            try (PlanExecutionRepository repo = new PlanExecutionRepository(db)) {
                long now = System.currentTimeMillis();
                repo.append(new PlanExecutionRepository.Execution(
                        "spike", "m0-gate", 0, "spike|m0-gate|" + now,
                        false, now, now, "SENT", "[]", "[]", ""));
                /* 批量写线程 50ms 一个窗口：等它落库 */
                for (int waited = 0; waited < 20; waited++) {
                    Thread.sleep(50);
                    if (!repo.query("spike", null, null, true, 10).isEmpty()) {
                        break;
                    }
                }
                if (repo.query("spike", null, null, true, 10).isEmpty()) {
                    throw new IllegalStateException("台账行没有落库");
                }
            }

            System.out.println("DBSPIKE ok sqlite=" + version + " work=" + workDir.toAbsolutePath());
            System.out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("尖峰被中断", e);
        }
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
