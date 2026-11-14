package com.shimizukenta.secssim.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 双库表结构（3.0 设计 §2.2.1 / §2.2.2）。
 *
 * <p>两个文件物理隔离：<b>config.db</b> 是权威配置（可备份、有版本、软删除），
 * <b>runtime.db</b> 是可随时丢弃重建的运行态。投影表（unit_def / svid_def /
 * report_def / …）只由 {@code ConfigRepository#reproject} 在写事务里整体重建，
 * 任何接口不得单独写这些表。建表全部 {@code IF NOT EXISTS}，schema_version
 * 记在 meta 表，升级迁移按版本号走。</p>
 */
public final class Schema {

    /** 当前实现对应的 schema 版本。 */
    public static final int VERSION = 1;

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
        CREATE TABLE IF NOT EXISTS connection_spec (
          device_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, passive INTEGER NOT NULL,
          bind_host TEXT NOT NULL, port INTEGER NOT NULL, device_id_num INTEGER NOT NULL,
          seed INTEGER NOT NULL, timeouts_json TEXT NOT NULL, trace_json TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS equipment_spec (
          device_id TEXT PRIMARY KEY, name TEXT NOT NULL, id_space_json TEXT NOT NULL,
          global_svids_json TEXT NOT NULL, global_ceids_json TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS unit_def (
          device_id TEXT NOT NULL, unit_no INTEGER NOT NULL, name TEXT NOT NULL,
          inject_unit_id INTEGER NOT NULL, PRIMARY KEY (device_id, unit_no)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS svid_def (
          device_id TEXT NOT NULL, svid INTEGER NOT NULL, owner TEXT NOT NULL, slot TEXT NOT NULL,
          value_type TEXT NOT NULL, unit TEXT, source_kind TEXT NOT NULL, source_json TEXT NOT NULL,
          PRIMARY KEY (device_id, svid)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS equipment_constant (
          device_id TEXT NOT NULL, ecid INTEGER NOT NULL, name TEXT NOT NULL,
          value_type TEXT NOT NULL, value REAL NOT NULL, unit TEXT,
          PRIMARY KEY (device_id, ecid)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS report_def (
          device_id TEXT NOT NULL, rptid INTEGER NOT NULL, owner TEXT NOT NULL, slot TEXT NOT NULL,
          PRIMARY KEY (device_id, rptid)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS report_svid (
          device_id TEXT NOT NULL, rptid INTEGER NOT NULL, ordinal INTEGER NOT NULL, svid INTEGER NOT NULL,
          PRIMARY KEY (device_id, rptid, ordinal)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS ceid_def (
          device_id TEXT NOT NULL, ceid INTEGER NOT NULL, owner TEXT NOT NULL, slot TEXT NOT NULL,
          PRIMARY KEY (device_id, ceid)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS ceid_report (
          device_id TEXT NOT NULL, ceid INTEGER NOT NULL, rptid INTEGER NOT NULL,
          PRIMARY KEY (device_id, ceid, rptid)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS event_plan (
          device_id TEXT NOT NULL, plan_id TEXT NOT NULL, ceid INTEGER NOT NULL,
          trigger_json TEXT NOT NULL, enabled_default INTEGER NOT NULL DEFAULT 1,
          offline_policy TEXT NOT NULL DEFAULT 'skip' CHECK (offline_policy IN ('skip','queue','fail')),
          on_send_failure TEXT NOT NULL DEFAULT 'keep' CHECK (on_send_failure IN ('keep','rollback')),
          ordinal INTEGER NOT NULL,
          PRIMARY KEY (device_id, plan_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS plan_action (
          device_id TEXT NOT NULL, plan_id TEXT NOT NULL, ordinal INTEGER NOT NULL,
          kind TEXT NOT NULL,
          stream INTEGER, function INTEGER, wbit INTEGER,
          sml TEXT,
          params_json TEXT NOT NULL DEFAULT '{}',
          PRIMARY KEY (device_id, plan_id, ordinal)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS event_effect (
          device_id TEXT NOT NULL, plan_id TEXT NOT NULL, ordinal INTEGER NOT NULL,
          svid INTEGER NOT NULL, op TEXT NOT NULL,
          value REAL, delta REAL, hold_ms INTEGER, decay_ms INTEGER,
          PRIMARY KEY (device_id, plan_id, ordinal)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS template_curve (
          device_id TEXT NOT NULL, curve_id TEXT NOT NULL, definition_json TEXT NOT NULL,
          PRIMARY KEY (device_id, curve_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS template_composite (
          device_id TEXT NOT NULL, composite_id TEXT NOT NULL, definition_json TEXT NOT NULL,
          PRIMARY KEY (device_id, composite_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS template_instance (
          device_id TEXT NOT NULL, instance_id TEXT NOT NULL, definition_json TEXT NOT NULL,
          PRIMARY KEY (device_id, instance_id)
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
        "CREATE INDEX IF NOT EXISTS idx_plan_device ON event_plan(device_id, ordinal)",
        "CREATE INDEX IF NOT EXISTS idx_effect_plan ON event_effect(device_id, plan_id)",
        "CREATE INDEX IF NOT EXISTS idx_svid_owner ON svid_def(device_id, owner)",
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
          last_error TEXT NOT NULL DEFAULT '', counters_json TEXT NOT NULL DEFAULT '{}',
          overrides_json TEXT NOT NULL DEFAULT '{}',
          last_good_json TEXT NOT NULL DEFAULT ''
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
     * 在连接上建全部表（幂等）。
     *
     * @param connection    目标库连接
     * @param configDatabase true = config.db 的 DDL；false = runtime.db 的 DDL
     * @throws SQLException 建表失败
     */
    public static void apply(Connection connection, boolean configDatabase) throws SQLException {
        for (String ddl : (configDatabase ? CONFIG_DDL : RUNTIME_DDL)) {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate(ddl);
            }
        }
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("INSERT INTO meta(key, value) VALUES('schema_version', '"
                    + VERSION + "') ON CONFLICT(key) DO UPDATE SET value=excluded.value");
        }
    }
}
