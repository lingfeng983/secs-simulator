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
 * <p><b>写路径单写者</b>：进程内每个库各一把写锁串行化写事务（设计 §2.3.2）；
 * 两个库物理独立、没有跨库事务，所以不共用一把锁。写事务在锁内
 * BEGIN IMMEDIATE → 提交才返回，失败整体回滚。<b>读路径</b>每次从
 * {@link SQLiteDataSource} 取独立连接——WAL 下读写不互锁，读者不会拖写者。</p>
 *
 * <p><b>跨进程</b>：同一 {@code --work-dir} 不允许跑两个进程。SQLite 自身的锁只保证
 * "同一时刻一个写事务"，挡不住两个进程各自完整地跑——所以启动时对伴生文件
 * {@code config.db.lock} 加文件锁，抢不到直接失败并给出明确提示。不锁 db 本体：
 * Windows 上整文件锁会与 SQLite 的字节段锁互相干扰。</p>
 *
 * <p>本类不认识任何业务类型：SQL 之外的一切（校验、SSE）由仓储与上层负责。</p>
 */
public final class Db implements AutoCloseable {

    private final Path workDir;
    private final SQLiteDataSource configReader;
    private final SQLiteDataSource runtimeReader;
    private final Connection configWriter;
    private final Connection runtimeWriter;
    /* 两个库各一把锁：物理独立、无跨库事务，串行化没必要也不该互相拖慢 */
    private final Object configWriterLock = new Object();
    private final Object runtimeWriterLock = new Object();
    private final LockHandle processLock;
    private final java.util.List<Path> schemaBackups;
    /* volatile：读路径不持写锁，也要能看到 close 置位（写路径在各自写锁内检查） */
    private volatile boolean closed;

    private Db(Path workDir, SQLiteDataSource configReader, SQLiteDataSource runtimeReader,
               Connection configWriter, Connection runtimeWriter, LockHandle processLock,
               java.util.List<Path> schemaBackups) {
        this.workDir = workDir;
        this.configReader = configReader;
        this.runtimeReader = runtimeReader;
        this.configWriter = configWriter;
        this.runtimeWriter = runtimeWriter;
        this.processLock = processLock;
        this.schemaBackups = java.util.List.copyOf(schemaBackups);
    }

    /**
     * 打开（必要时创建）双库并初始化表结构；版本不匹配时拒绝启动。
     *
     * @param workDir 工作目录
     * @return 门面
     * @throws StoreException 打开失败（目录不可写、被别的进程占用、磁盘错误）
     */
    public static Db open(Path workDir) {
        return open(workDir, false);
    }

    /**
     * 打开（必要时创建）双库并初始化表结构。
     *
     * @param workDir         工作目录
     * @param allowSchemaReset 版本不匹配时是否允许备份后重建（{@code --allow-schema-reset}）
     * @return 门面
     * @throws StoreException 打开失败（目录不可写、被别的进程占用、磁盘错误）
     * @throws IllegalStateException 版本不匹配且未授权重建
     */
    public static Db open(Path workDir, boolean allowSchemaReset) {
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
                    java.util.List<Path> backups = new java.util.ArrayList<>();
                    Path configBackup = Schema.apply(configWriter, true, configDb, allowSchemaReset);
                    if (configBackup != null) {
                        backups.add(configBackup);
                    }
                    Path runtimeBackup = Schema.apply(runtimeWriter, false, runtimeDb, allowSchemaReset);
                    if (runtimeBackup != null) {
                        backups.add(runtimeBackup);
                    }
                    return new Db(workDir, reader(configDb), reader(runtimeDb),
                            configWriter, runtimeWriter, lock, backups);
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
            /* synchronous=NORMAL 的取舍：WAL 下掉电/系统崩溃可能丢最近几个已提交事务
               （库本身不会损坏）；FULL 每次提交都 fsync WAL，会拖慢单写者的台账批写。
               配置丢失可由用户重导、运行态本就可丢弃重建，故接受该风险。 */
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

    /**
     * config.db 的读连接（用完必须 close；WAL 下与写者不互锁）。
     *
     * <p>读连接每次新建、每次跑一遍 {@link #tune}：SQLite 的
     * {@code foreign_keys} / {@code busy_timeout} / {@code synchronous} 是
     * <b>连接级</b>属性，不复用连接就必须逐条设置。{@code journal_mode=WAL}
     * 是库级持久属性，重复设置只是读回当前值，没有写放大。做连接池才有意义
     * 省掉这几条 PRAGMA，但会引入连接生命周期与跨线程归还问题——目前读写都短、
     * 数量可控，保持现状更安全。</p>
     */
    public Connection readConfig() {
        ensureOpen();
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
        ensureOpen();
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
        synchronized (this.configWriterLock) {
            ensureOpen();
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
        synchronized (this.runtimeWriterLock) {
            ensureOpen();
            return write(this.runtimeWriter, work, "runtime.db");
        }
    }

    /** closed 后读写一律快速失败：给明确异常，而不是连接被抽走后的混乱 SQLException。 */
    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("存储已关闭（work-dir=" + this.workDir + "），不能再读写");
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

    /**
     * 本次启动因 schema 版本不匹配而重建时产生的备份文件（可能 0～2 个）。
     *
     * <p>调用方（启动器）负责把路径打到日志里：{@code Schema} 位于 store 层，
     * 不能依赖上层的日志门面，所以这里只把事实带出来，不做输出。</p>
     *
     * @return 备份文件路径，按 config.db、runtime.db 顺序；无重建时为空列表
     */
    public java.util.List<Path> schemaBackups() {
        return this.schemaBackups;
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

    /**
     * 关闭双库并释放进程锁。
     *
     * <p>先依次拿 config、runtime 两把写锁再关连接：与在途写事务互斥，
     * 不会把写连接从事务脚下抽走。写路径各自只持一把写锁、从不嵌套获取
     * 另一把（跨库两步写在两个独立事务里），所以这里同时持两把不会死锁。
     * 拿锁顺序固定 config → runtime，与任何可能的多锁路径保持一致。
     * closed 置位后 write/read 快速失败（见 {@link #ensureOpen}）。</p>
     */
    @Override
    public void close() {
        synchronized (this.configWriterLock) {
            synchronized (this.runtimeWriterLock) {
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
    }
}
