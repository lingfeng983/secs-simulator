package com.shimizukenta.secssim.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

import org.sqlite.SQLiteDataSource;

/**
 * 双库门面（ADR 0017）：config.db（权威配置）+ runtime.db（运行态）。
 *
 * <p><b>写路径单写者</b>：进程内用一把锁串行化两个库的全部写事务（设计 §2.3.2）；
 * 写事务在锁内 BEGIN IMMEDIATE → 提交才返回，失败整体回滚。<b>读路径</b>每次从
 * {@link SQLiteDataSource} 取独立连接——WAL 下读写不互锁，读者不会拖写者。</p>
 *
 * <p><b>跨进程</b>：同一 {@code --work-dir} 不允许跑两个进程。SQLite 自身的锁只保证
 * "同一时刻一个写事务"，挡不住两个进程各自完整地跑——所以启动时对伴生文件
 * {@code config.db.lock} 加文件锁，抢不到直接失败并给出明确提示。不锁 db 本体：
 * Windows 上整文件锁会与 SQLite 的字节段锁互相干扰。</p>
 *
 * <p>本类不认识任何业务类型：SQL 之外的一切（校验、投影、SSE）由仓储与上层负责。</p>
 */
public final class Db implements AutoCloseable {

    private final Path workDir;
    private final SQLiteDataSource configReader;
    private final SQLiteDataSource runtimeReader;
    private final Connection configWriter;
    private final Connection runtimeWriter;
    private final Object writerLock = new Object();
    private final LockHandle processLock;
    private boolean closed;

    private Db(Path workDir, SQLiteDataSource configReader, SQLiteDataSource runtimeReader,
               Connection configWriter, Connection runtimeWriter, LockHandle processLock) {
        this.workDir = workDir;
        this.configReader = configReader;
        this.runtimeReader = runtimeReader;
        this.configWriter = configWriter;
        this.runtimeWriter = runtimeWriter;
        this.processLock = processLock;
    }

    /**
     * 打开（必要时创建）双库并初始化表结构。
     *
     * @param workDir 工作目录
     * @return 门面
     * @throws StoreException 打开失败（目录不可写、被别的进程占用、磁盘错误）
     */
    public static Db open(Path workDir) {
        Objects.requireNonNull(workDir, "workDir");
        try {
            Files.createDirectories(workDir);
            DbBootstrap.ensureNativeLibrary(workDir);

            LockHandle lock = acquireProcessLock(workDir);
            try {
                Path configDb = workDir.resolve("config.db").toAbsolutePath();
                Path runtimeDb = workDir.resolve("runtime.db").toAbsolutePath();

                Connection configWriter = openConnection(configDb);
                Connection runtimeWriter = openConnection(runtimeDb);
                try {
                    tune(configWriter);
                    tune(runtimeWriter);
                    Schema.apply(configWriter, true);
                    Schema.apply(runtimeWriter, false);
                } catch (SQLException | RuntimeException e) {
                    try {
                        configWriter.close();
                    } catch (SQLException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                    try {
                        runtimeWriter.close();
                    } catch (SQLException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                    throw e;
                }

                return new Db(workDir, reader(configDb), reader(runtimeDb),
                        configWriter, runtimeWriter, lock);
            } catch (SQLException | RuntimeException e) {
                lock.release();
                throw e;
            }
        } catch (SQLException | IOException e) {
            throw new StoreException("打开存储失败（work-dir=" + workDir + "）: " + e.getMessage(), e);
        }
    }

    private static SQLiteDataSource reader(Path db) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db);
        return ds;
    }

    /** 打开一条连接；驱动可加载性由 DbBootstrap 保证。 */
    private static Connection openConnection(Path db) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new StoreException("找不到 sqlite-jdbc 驱动", e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + db);
    }

    /** 每条连接的统一参数：WAL、外键、忙等待 5s（WAL 是库级持久属性，重复设置无副作用）。 */
    private static void tune(Connection connection) throws SQLException {
        try (var st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA synchronous=NORMAL");
        }
    }

    /** 抢进程锁；抢不到（别的进程占用，或本进程重复打开）一律给同一条提示。 */
    private static LockHandle acquireProcessLock(Path workDir) {
        Path lockFile = workDir.resolve("config.db.lock");
        try {
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
            java.nio.channels.FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw lockConflict(lockFile);
            }
            return new LockHandle(channel, lock);
        } catch (OverlappingFileLockException e) {
            throw lockConflict(lockFile);
        } catch (IOException e) {
            throw new StoreException("无法创建进程锁文件 " + lockFile + ": " + e.getMessage(), e);
        }
    }

    private static StoreException lockConflict(Path lockFile) {
        return new StoreException("工作目录已被另一个进程使用（" + lockFile
                + " 被占用）。同一个 work-dir 不允许同时跑两个进程。");
    }

    /** 文件锁句柄；随 Db 关闭而释放。 */
    private record LockHandle(FileChannel channel, java.nio.channels.FileLock lock) {
        void release() {
            try {
                if (this.lock != null && this.lock.isValid()) {
                    this.lock.release();
                }
            } catch (IOException e) {
                /* 释放失败只影响并发打开的检测，不影响数据 */
            }
            try {
                this.channel.close();
            } catch (IOException e) {
                /* 同上 */
            }
        }
    }

    /** config.db 的读连接（用完必须 close；WAL 下与写者不互锁）。 */
    public Connection readConfig() {
        try {
            Connection connection = this.configReader.getConnection();
            tune(connection);
            return connection;
        } catch (SQLException e) {
            throw new StoreException("取 config.db 读连接失败: " + e.getMessage(), e);
        }
    }

    /** runtime.db 的读连接（用完必须 close）。 */
    public Connection readRuntime() {
        try {
            Connection connection = this.runtimeReader.getConnection();
            tune(connection);
            return connection;
        } catch (SQLException e) {
            throw new StoreException("取 runtime.db 读连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在 config.db 上执行一个写事务：锁内 BEGIN IMMEDIATE → body → COMMIT，
     * 异常整体 ROLLBACK。
     *
     * @param <T>  返回类型
     * @param work 事务体；连接已开启事务，直接执行 SQL
     * @return 事务体的返回值
     */
    public <T> T writeConfig(Function<Connection, T> work) {
        synchronized (this.writerLock) {
            return write(this.configWriter, work, "config.db");
        }
    }

    /**
     * 在 runtime.db 上执行一个写事务（语义同 {@link #writeConfig}）。
     *
     * @param <T>  返回类型
     * @param work 事务体
     * @return 事务体的返回值
     */
    public <T> T writeRuntime(Function<Connection, T> work) {
        synchronized (this.writerLock) {
            return write(this.runtimeWriter, work, "runtime.db");
        }
    }

    private static <T> T write(Connection writer, Function<Connection, T> work, String name) {
        /* 事务用裸 SQL 管理：sqlite-jdbc 的 setAutoCommit(false) 自带隐式 BEGIN，
           再发 BEGIN IMMEDIATE 会撞 "within a transaction"；裸 SQL 才能指定 IMMEDIATE。 */
        try {
            exec(writer, "BEGIN IMMEDIATE");
            T result = work.apply(writer);
            exec(writer, "COMMIT");
            return result;
        } catch (SQLException e) {
            rollbackQuietly(writer, e);
            throw new StoreException(name + " 写事务失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollbackQuietly(writer, e);
            throw e;
        }
    }

    private static void exec(Connection connection, String sql) throws SQLException {
        try (var st = connection.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /** 回滚（尽力而为：失败挂在主异常上，不掩盖原始错误）。 */
    private static void rollbackQuietly(Connection writer, Exception primary) {
        try {
            exec(writer, "ROLLBACK");
        } catch (SQLException suppressed) {
            primary.addSuppressed(suppressed);
        }
    }

    /** work-dir。 */
    public Path workDir() {
        return this.workDir;
    }

    /** meta 表读值（database = "config" | "runtime"）；不存在返回 null。 */
    public String meta(String database, String key) {
        boolean config = "config".equals(database);
        try (Connection connection = config ? this.readConfig() : this.readRuntime();
             var st = connection.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            st.setString(1, key);
            try (var rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new StoreException("读 meta 失败: " + e.getMessage(), e);
        }
    }

    /** meta 表写值（不存在则插入）。 */
    public void putMeta(String database, String key, String value) {
        String sql = "INSERT INTO meta(key, value) VALUES(?,?) "
                + "ON CONFLICT(key) DO UPDATE SET value=excluded.value";
        if ("config".equals(database)) {
            this.writeConfig(connection -> {
                try (var st = connection.prepareStatement(sql)) {
                    st.setString(1, key);
                    st.setString(2, value);
                    st.executeUpdate();
                } catch (SQLException e) {
                    throw new StoreException("写 meta 失败: " + e.getMessage(), e);
                }
                return null;
            });
        } else {
            this.writeRuntime(connection -> {
                try (var st = connection.prepareStatement(sql)) {
                    st.setString(1, key);
                    st.setString(2, value);
                    st.executeUpdate();
                } catch (SQLException e) {
                    throw new StoreException("写 meta 失败: " + e.getMessage(), e);
                }
                return null;
            });
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            this.configWriter.close();
        } catch (SQLException e) {
            /* 关闭失败不影响停机 */
        }
        try {
            this.runtimeWriter.close();
        } catch (SQLException e) {
            /* 同上 */
        }
        this.processLock.release();
    }
}
