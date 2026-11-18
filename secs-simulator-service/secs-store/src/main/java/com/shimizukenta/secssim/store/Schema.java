package com.shimizukenta.secssim.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 双库表结构（3.0 设计 §2.2.1 / §2.2.2）。
 *
 * <p>两个文件物理隔离：<b>config.db</b> 是权威配置（可备份、有版本、软删除），
 * <b>runtime.db</b> 是可随时丢弃重建的运行态。</p>
 *
 * <p><b>版本策略：先读后判。</b>启动时先读 meta.schema_version，与
 * {@link #VERSION} 不一致就拒绝启动（本版本不提供迁移）；显式加
 * {@code --allow-schema-reset} 时才先备份成 {@code <db>.bak.<时间戳>}、再按
 * {@link ResetStrategy} 重建。绝不「写后覆盖版本号」——那会让旧结构看起来
 * 建表成功，下一版再也判断不出真实版本。</p>
 */
public final class Schema {

    /** 当前实现对应的 schema 版本。结构变更即递增，旧库会被拒绝启动。 */
    public static final int VERSION = 2;

    /**
     * 版本不匹配时的重置策略。
     *
     * <p>当前只有 {@link DropAndRecreate}；将来要换成「导出 → 重建 → 导入」时
     * 只替换实现，{@link #apply} 不动。</p>
     */
    public interface ResetStrategy {
        /**
         * 清除旧结构（建表由 {@link #apply} 统一负责）。
         *
         * @param connection  目标库连接（未处于事务中）
         * @param dbPath      库文件路径
         * @param fromVersion 库里的版本
         * @param toVersion   程序要求的版本
         * @throws SQLException 清除失败
         */
        void reset(Connection connection, Path dbPath, int fromVersion, int toVersion) throws SQLException;
    }

    /** 当前唯一实现：DROP 全部用户表，随后由 {@link #apply} 按当前 DDL 重建。 */
    public static final class DropAndRecreate implements ResetStrategy {

        @Override
        public void reset(Connection connection, Path dbPath, int fromVersion, int toVersion)
                throws SQLException {
            dropAllTables(connection);
        }
    }

    private static final ResetStrategy RESET = new DropAndRecreate();

    private Schema() {
    }

    /** config.db 的建表语句（顺序即外键依赖顺序）。 */
    private static final String[] CONFIG_DDL = {
        """
        CREATE TABLE IF NOT EXISTS meta (
          key   TEXT PRIMARY KEY,
          value TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS device (
          id           TEXT PRIMARY KEY,
          legacy_id    TEXT,
          name         TEXT NOT NULL,
          template_id  TEXT,
          auto_start   INTEGER NOT NULL DEFAULT 1,
          note         TEXT NOT NULL DEFAULT '',
          created_at   INTEGER NOT NULL,
          updated_at   INTEGER NOT NULL,
          deleted_at   INTEGER
        )
        """,
        /* 软删后名字必须能重新使用，唯一性只约束未删除的行 */
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_device_name_live ON device(name) WHERE deleted_at IS NULL",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_device_legacy ON device(legacy_id) WHERE legacy_id IS NOT NULL",
        """
        CREATE TABLE IF NOT EXISTS config_document (
          device_id       TEXT PRIMARY KEY REFERENCES device(id) ON DELETE CASCADE,
          body            TEXT NOT NULL,
          schema_version  INTEGER NOT NULL,
          revision        INTEGER NOT NULL,
          content_sha256  TEXT NOT NULL,
          updated_at      INTEGER NOT NULL,
          updated_by      TEXT NOT NULL DEFAULT 'local',
          change_note     TEXT NOT NULL DEFAULT ''
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS config_revision (
          device_id      TEXT NOT NULL,
          revision       INTEGER NOT NULL,
          body           TEXT NOT NULL,
          content_sha256 TEXT NOT NULL,
          created_at     INTEGER NOT NULL,
          created_by     TEXT NOT NULL,
          note           TEXT NOT NULL DEFAULT '',
          PRIMARY KEY (device_id, revision)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS device_template (
          id TEXT PRIMARY KEY, label TEXT NOT NULL, description TEXT NOT NULL,
          source TEXT NOT NULL CHECK (source IN ('builtin','custom')),
          readonly INTEGER NOT NULL, body TEXT NOT NULL,
          created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS param_template (
          id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
          body TEXT NOT NULL,
          source TEXT NOT NULL CHECK (source IN ('builtin','custom')),
          readonly INTEGER NOT NULL,
          created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS host_template (
          id TEXT PRIMARY KEY,
          name TEXT NOT NULL,
          host TEXT NOT NULL,
          port INTEGER NOT NULL,
          device_id_num INTEGER NOT NULL DEFAULT 0,
          collector_json TEXT NOT NULL DEFAULT '{}',
          note TEXT NOT NULL DEFAULT '',
          created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER,
          CHECK (port BETWEEN 1 AND 65535)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS host (
          id TEXT PRIMARY KEY,
          name TEXT NOT NULL,
          target_host TEXT NOT NULL,
          target_port INTEGER NOT NULL,
          target_device_id_num INTEGER NOT NULL DEFAULT 0,
          timeouts_json TEXT NOT NULL DEFAULT '{}',
          collector_json TEXT NOT NULL DEFAULT '{}',
          variable_catalog_json TEXT NOT NULL DEFAULT '{}',
          auto_reconnect INTEGER NOT NULL DEFAULT 0,
          note TEXT NOT NULL DEFAULT '',
          setting_revision INTEGER NOT NULL DEFAULT 1,
          created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER,
          CHECK (target_port BETWEEN 1 AND 65535)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS host_plan (
          host_id TEXT NOT NULL,
          kind TEXT NOT NULL CHECK (kind IN ('trace','collect')),
          plan_id TEXT NOT NULL,
          body TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          PRIMARY KEY (host_id, kind, plan_id)
        )
        """,
    };

    /** runtime.db 的建表语句。 */
    private static final String[] RUNTIME_DDL = {
        "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        """
        CREATE TABLE IF NOT EXISTS device_link (
          device_id TEXT PRIMARY KEY,
          phase TEXT NOT NULL,
          config_revision INTEGER NOT NULL,
          communicator_state TEXT NOT NULL DEFAULT 'NOT_SELECTED',
          control_state TEXT NOT NULL DEFAULT 'OFFLINE',
          bind_host TEXT, port INTEGER, device_id_num INTEGER,
          connected_at INTEGER, disconnected_at INTEGER,
          last_error TEXT NOT NULL DEFAULT ''
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS host_link (
          host_id TEXT PRIMARY KEY,
          phase TEXT NOT NULL,
          target TEXT NOT NULL DEFAULT '', connected_at INTEGER, disconnected_at INTEGER,
          last_error TEXT NOT NULL DEFAULT '',
          applied_revision INTEGER NOT NULL DEFAULT 1,
          retry_count INTEGER NOT NULL DEFAULT 0,
          next_retry_at INTEGER
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS runtime_plan_state (
          device_id TEXT NOT NULL, plan_id TEXT NOT NULL,
          enabled INTEGER NOT NULL,
          start_at INTEGER,
          next_fire_at INTEGER,
          last_fire_at INTEGER, fire_count INTEGER NOT NULL DEFAULT 0,
          skipped_count INTEGER NOT NULL DEFAULT 0, last_error TEXT NOT NULL DEFAULT '',
          PRIMARY KEY (device_id, plan_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS plan_execution (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          device_id TEXT NOT NULL, plan_id TEXT NOT NULL, ceid INTEGER NOT NULL,
          idempotency_key TEXT NOT NULL UNIQUE,
          dry_run INTEGER NOT NULL DEFAULT 0,
          scheduled_at INTEGER NOT NULL, fired_at INTEGER NOT NULL,
          status TEXT NOT NULL,
          message_json TEXT NOT NULL DEFAULT '[]',
          effect_json  TEXT NOT NULL DEFAULT '[]',
          error TEXT NOT NULL DEFAULT ''
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_exec_time ON plan_execution(device_id, fired_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_exec_plan ON plan_execution(device_id, plan_id, fired_at DESC)",
        """
        CREATE TABLE IF NOT EXISTS runtime_svid_pin (
          device_id TEXT NOT NULL, svid INTEGER NOT NULL, value REAL NOT NULL,
          set_at INTEGER NOT NULL, PRIMARY KEY (device_id, svid)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS runtime_plan_override (
          device_id TEXT NOT NULL, plan_id TEXT NOT NULL,
          op TEXT NOT NULL CHECK (op IN ('add','update','delete')),
          body_json TEXT NOT NULL DEFAULT '{}',
          updated_at INTEGER NOT NULL, PRIMARY KEY (device_id, plan_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS runtime_catalog_edit (
          device_id TEXT NOT NULL,
          kind TEXT NOT NULL CHECK (kind IN ('ceid','report','svid')),
          key INTEGER NOT NULL,
          op TEXT NOT NULL CHECK (op IN ('add','update','delete')),
          body_json TEXT NOT NULL DEFAULT '{}',
          updated_at INTEGER NOT NULL, PRIMARY KEY (device_id, kind, key)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS runtime_host_report (
          device_id TEXT NOT NULL, payload_json TEXT NOT NULL, updated_at INTEGER NOT NULL,
          PRIMARY KEY (device_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS link_event (
          id INTEGER PRIMARY KEY AUTOINCREMENT, entity TEXT NOT NULL, entity_id TEXT NOT NULL,
          from_phase TEXT, to_phase TEXT NOT NULL, at INTEGER NOT NULL, detail TEXT NOT NULL DEFAULT ''
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_link_event ON link_event(entity_id, at DESC)",
    };

    /**
     * 校验版本并按需重建，然后建全部表并写入当前版本。
     *
     * @param connection     目标库连接
     * @param configDatabase true = config.db 的 DDL；false = runtime.db 的 DDL
     * @param dbPath         库文件路径（报错提示与备份文件名都用它）
     * @param allowReset     true = 版本不匹配时备份后重建；false = 直接拒绝
     * @return 本次重建产生的备份文件路径；未发生重建时返回 {@code null}
     * @throws SQLException         建表 / 读写版本失败
     * @throws IllegalStateException 版本不匹配且未授权重建
     */
    public static Path apply(Connection connection, boolean configDatabase, Path dbPath,
                             boolean allowReset) throws SQLException {
        int found = readVersion(connection);
        Path backupPath = null;
        if (found != 0 && found != VERSION) {
            if (!allowReset) {
                throw new IllegalStateException(
                        "数据库版本不匹配：库为 v" + found + "，程序要求 v" + VERSION
                        + "。本版本不提供迁移。请备份后删除 " + dbPath
                        + " 再重启（运行态库可直接删；配置库删除后需重新导入设备配置）。"
                        + " 确认要自动重建请加 --allow-schema-reset。");
            }
            backupPath = backup(connection, dbPath);
            RESET.reset(connection, dbPath, found, VERSION);
        }
        createAll(connection, configDatabase);
        writeVersion(connection, VERSION);
        return backupPath;
    }

    /**
     * 读 schema 版本；meta 表不存在（首次启动）或键不存在都返回 0。
     *
     * @param connection 目标库连接
     * @return 库里的版本号，未初始化返回 0
     * @throws SQLException 查询失败或版本值不是整数
     */
    static int readVersion(Connection connection) throws SQLException {
        if (!tableExists(connection, "meta")) {
            return 0;
        }
        try (PreparedStatement st = connection.prepareStatement(
                "SELECT value FROM meta WHERE key='schema_version'");
             ResultSet rs = st.executeQuery()) {
            if (!rs.next()) {
                return 0;
            }
            String raw = rs.getString(1);
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new SQLException("meta.schema_version 不是整数: " + raw, e);
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            st.setString(1, table);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void createAll(Connection connection, boolean configDatabase) throws SQLException {
        for (String ddl : (configDatabase ? CONFIG_DDL : RUNTIME_DDL)) {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate(ddl);
            }
        }
    }

    private static void writeVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(
                "INSERT INTO meta(key, value) VALUES('schema_version', ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            st.setString(1, String.valueOf(version));
            st.executeUpdate();
        }
    }

    /**
     * 备份库文件；失败直接抛异常，不允许在没备份的情况下继续重建。
     *
     * <p>优先 {@code VACUUM INTO}（WAL 下直接复制主文件会丢未 checkpoint 的写入）；
     * 旧 SQLite 不支持时退化为复制 db + -wal + -shm 三个文件。</p>
     *
     * @return 备份文件路径
     */
    private static Path backup(Connection connection, Path dbPath) {
        Path target = dbPath.resolveSibling(dbPath.getFileName()
                + ".bak." + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
        try (Statement st = connection.createStatement()) {
            st.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
            return target;
        } catch (SQLException e) {
            /* VACUUM INTO 不可用（版本过旧 / 目标已存在）→ 退化为文件复制 */
        }
        try {
            Files.copy(dbPath, target, StandardCopyOption.REPLACE_EXISTING);
            for (String suffix : new String[] {"-wal", "-shm"}) {
                Path side = Path.of(dbPath + suffix);
                if (Files.exists(side)) {
                    Files.copy(side, Path.of(target + suffix), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new StoreException("schema 版本不匹配，备份数据库失败（拒绝继续重建）: "
                    + target + ": " + e.getMessage(), e);
        }
        return target;
    }

    /** 删掉全部用户表（索引随表一起消失；保留 sqlite_ 内部表）。 */
    private static void dropAllTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        /* 关外键再 DROP：开着外键 DROP 父表会触发隐式 DELETE 而撞约束 */
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys=OFF");
        }
        try (Statement st = connection.createStatement()) {
            for (String table : tables) {
                st.executeUpdate("DROP TABLE IF EXISTS \"" + table.replace("\"", "\"\"") + "\"");
            }
        } finally {
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
            } catch (SQLException ignored) {
                /* 恢复失败不掩盖 DROP 的结果；Db 打开连接时会重新设置 */
            }
        }
    }
}
