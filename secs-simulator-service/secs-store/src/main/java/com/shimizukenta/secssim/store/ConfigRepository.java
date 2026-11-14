package com.shimizukenta.secssim.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 配置仓储：device 实体 + config_document（权威正文）+ config_revision（版本快照）
 * + 投影表（结构化 UI 读的形状）。
 *
 * <p><b>唯一写口</b>（设计 §2.3.2）：所有正文写入都走 {@link #saveDocument} /
 * {@link #createDevice} / {@link #updateConstants}——先比 revision（乐观锁），
 * 再升版本、留快照、整体重投影，任何一步失败整事务回滚。投影表只由
 * {@link DocumentProjector} 从正文重算，禁止单表写入。</p>
 *
 * <p>本模块不解析配置正文：编译校验由调用方（secs-runtime 的 ConfigStore.validate）
 * 在进入本类之前完成；投影由上层注入 {@link DocumentProjector} 实现——
 * 这是「secs-store 不依赖 secs-app」唯一可行的拆法。</p>
 */
public final class ConfigRepository {

    /** 每设备保留的版本快照数（超出按时间裁剪，设计 §2.2.1）。 */
    public static final int REVISION_RETENTION = 200;

    /**
     * 正文 → 投影表。实现由上层给出（用现有的配置解析器写连接规格 / 单元 /
     * SVID / 报告 / 事件 / 模板 / 常量等投影行）；在写事务内被调用。
     */
    public interface DocumentProjector {
        void project(Connection connection, String deviceId, String body) throws SQLException;
    }

    /** 重投影前要清空的投影表（由投影实现负责填业务行）。 */
    private static final String[] PROJECTION_TABLES = {
        "connection_spec", "equipment_spec", "unit_def", "svid_def", "equipment_constant",
        "report_def", "report_svid", "ceid_def", "ceid_report",
        "event_plan", "plan_action", "event_effect",
        "template_curve", "template_composite", "template_instance",
    };

    private final Db db;
    private final DocumentProjector projector;

    /**
     * @param db        双库门面
     * @param projector 正文 → 投影表（上层注入）
     */
    public ConfigRepository(Db db, DocumentProjector projector) {
        this.db = db;
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    /** 设备实体视图（配置与文档 JOIN 后的只读形状）。 */
    public record DeviceView(String id, String legacyId, String name, String templateId,
                             boolean autoStart, String note, long revision,
                             long createdAt, long updatedAt) {
    }

    /** 配置文档视图（正文 + 版本 + 哈希）。 */
    public record DocumentView(String deviceId, String body, long revision, String sha256,
                               long updatedAt, String updatedBy, String changeNote) {
    }

    /** 版本快照视图。 */
    public record RevisionView(long revision, String sha256, long createdAt,
                               String createdBy, String note) {
    }

    /**
     * 新建设备：插入实体 + revision 1 的正文与快照 + 首次投影。
     *
     * @param name       设备名（v1 语义下同时充当 legacy_id = 文件名 stem）
     * @param legacyId   v1 兼容 id；null 表示没有（v2 新建设备）
     * @param templateId 来源模板（仅分组用）；可空
     * @param body       配置正文（调用方已校验）
     * @param autoStart  启动对账时是否自动连接
     * @param actor      操作者
     * @return 新设备 id
     * @throws StoreException 名字冲突（未删除行内唯一）或写库失败
     */
    public String createDevice(String name, String legacyId, String templateId,
                               String body, boolean autoStart, String actor) {
        Objects.requireNonNull(name, "name");
        String id = Ids.nextDevice();
        long now = System.currentTimeMillis();
        String sha = ContentHash.sha256Hex(body);
        return this.db.writeConfig(unchecked(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO device(id, legacy_id, name, template_id, auto_start, note, created_at, updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?)")) {
                st.setString(1, id);
                st.setString(2, legacyId);
                st.setString(3, name);
                st.setString(4, templateId);
                st.setInt(5, autoStart ? 1 : 0);
                st.setString(6, "");
                st.setLong(7, now);
                st.setLong(8, now);
                st.executeUpdate();
            } catch (SQLException e) {
                /* SQLite 的唯一冲突报文不带索引名：按列名判定 */
                if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                    throw StoreException.conflict("设备名或 legacy id 冲突: " + name + " / " + legacyId);
                }
                throw new StoreException("建设备失败: " + e.getMessage(), e);
            }
            insertDocument(connection, id, body, sha, 1, actor, "created");
            insertRevisionSnapshot(connection, id, 1, body, sha, actor, "created");
            this.projector.project(connection, id, body);
            return id;
        }));
    }

    /**
     * 保存正文（唯一写口）：乐观锁 → 升版本 → 快照 → 重投影 → 裁剪。
     *
     * @param key              设备 key（legacy_id 或 id）
     * @param body             新正文（调用方已校验）
     * @param expectedRevision 期望的当前版本；null 表示不检查
     * @param actor            操作者
     * @param note             变更备注
     * @return 新版本号（正文未变化时返回当前版本，幂等）
     * @throws StoreException 设备不存在（notFound）或版本冲突（conflict）
     */
    public long saveDocument(String key, String body, Long expectedRevision, String actor, String note) {
        return this.saveDocument(key, body, expectedRevision, null, actor, note);
    }

    /**
     * 保存正文（带 updated_at 乐观检查）：v1 的 If-Match 用毫秒时间戳当并发基准
     * （设计 §2.5），映射成「编辑会话拿到的 updatedAt 必须仍然等于当前值」。
     *
     * @param key               设备 key
     * @param body              新正文（调用方已校验）
     * @param expectedRevision  期望版本；null 不检查
     * @param expectedUpdatedAt 期望的 updated_at（毫秒）；null 不检查
     * @param actor             操作者
     * @param note              变更备注
     * @return 新版本号（正文未变化时返回当前版本，幂等）
     */
    public long saveDocument(String key, String body, Long expectedRevision,
                             Long expectedUpdatedAt, String actor, String note) {
        Objects.requireNonNull(body, "body");
        String sha = ContentHash.sha256Hex(body);
        return this.db.writeConfig(unchecked(connection -> {
            DeviceRow device = findLiveDevice(connection, key);
            long currentRevision;
            String currentSha;
            long currentUpdatedAt;
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT revision, content_sha256, updated_at FROM config_document WHERE device_id=?")) {
                st.setString(1, device.id());
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw StoreException.notFound("设备没有配置正文: " + key);
                    }
                    currentRevision = rs.getLong(1);
                    currentSha = rs.getString(2);
                    currentUpdatedAt = rs.getLong(3);
                }
            }
            if (expectedRevision != null && expectedRevision != currentRevision) {
                throw StoreException.conflict("配置在编辑期间被修改过（当前 v" + currentRevision
                        + "，请求基于 v" + expectedRevision + "），请重载后再保存");
            }
            if (expectedUpdatedAt != null && expectedUpdatedAt != currentUpdatedAt) {
                throw StoreException.conflict("配置在编辑期间被其他操作修改过，请重新载入后再保存");
            }
            if (currentSha.equals(sha)) {
                return currentRevision;
            }
            long next = currentRevision + 1;
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE config_document SET body=?, schema_version=?, revision=?, content_sha256=?, "
                            + "updated_at=?, updated_by=?, change_note=? WHERE device_id=?")) {
                st.setString(1, body);
                st.setInt(2, Schema.VERSION);
                st.setLong(3, next);
                st.setString(4, sha);
                st.setLong(5, System.currentTimeMillis());
                st.setString(6, actor == null ? "local" : actor);
                st.setString(7, note == null ? "" : note);
                st.setString(8, device.id());
                st.executeUpdate();
            }
            insertRevisionSnapshot(connection, device.id(), next, body, sha, actor, note);
            reproject(connection, device.id(), body);
            trimRevisions(connection, device.id());
            touchDevice(connection, device.id());
            return next;
        }));
    }

    /**
     * S2F15 常量回写的专用写口：升版本 + 重投影，但<b>不写快照</b>，提交后把
     * runtime.db 的 device_link.config_revision 追平——运行版本本来就等于最新版本，
     * 若走 saveDocument 的语义会出现永久「配置待生效」（设计 §2.3.2）。
     *
     * <p>两个库不做跨库事务；两步之间崩溃无害——启动对账一律把设备拉回 STOPPED，
     * 重连按最新正文重建运行态。</p>
     *
     * @param key    设备 key
     * @param newBody 只替换过 constants 子树的新正文
     * @param actor  操作者（恒为设备侧）
     * @return 新版本号
     */
    public long updateConstants(String key, String newBody, String actor) {
        String sha = ContentHash.sha256Hex(newBody);
        String deviceId = this.db.writeConfig(unchecked(connection -> {
            DeviceRow device = findLiveDevice(connection, key);
            long currentRevision;
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT revision FROM config_document WHERE device_id=?")) {
                st.setString(1, device.id());
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw StoreException.notFound("设备没有配置正文: " + key);
                    }
                    currentRevision = rs.getLong(1);
                }
            }
            long next = currentRevision + 1;
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE config_document SET body=?, schema_version=?, revision=?, content_sha256=?, "
                            + "updated_at=?, updated_by=?, change_note='' WHERE device_id=?")) {
                st.setString(1, newBody);
                st.setInt(2, Schema.VERSION);
                st.setLong(3, next);
                st.setString(4, sha);
                st.setLong(5, System.currentTimeMillis());
                st.setString(6, actor == null ? "device" : actor);
                st.setString(7, device.id());
                st.executeUpdate();
            }
            reproject(connection, device.id(), newBody);
            touchDevice(connection, device.id());
            return device.id() + "|" + next;
        }));
        int sep = deviceId.lastIndexOf('|');
        String id = deviceId.substring(0, sep);
        long revision = Long.parseLong(deviceId.substring(sep + 1));
        /* 追平运行版本；没有 device_link 行（设备未连接过）就跳过 */
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE device_link SET config_revision=? WHERE device_id=?")) {
                st.setLong(1, revision);
                st.setString(2, id);
                return st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("追平运行版本失败: " + e.getMessage(), e);
            }
        });
        return revision;
    }

    /** 按 key（legacy_id 优先，其次 id）找未删除设备。 */
    public Optional<DeviceView> findDevice(String key) {
        try (Connection connection = this.db.readConfig()) {
            DeviceRow row = findLiveDevice(connection, key);
            return Optional.of(toView(connection, row));
        } catch (StoreException e) {
            if (e.kind() == StoreException.Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        } catch (SQLException e) {
            throw new StoreException("查设备失败: " + e.getMessage(), e);
        }
    }

    /** 全部未删除设备（按创建时间排序）。 */
    public List<DeviceView> listDevices() {
        try (Connection connection = this.db.readConfig();
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, legacy_id FROM device WHERE deleted_at IS NULL ORDER BY created_at, id")) {
            List<DeviceView> result = new ArrayList<>();
            while (rs.next()) {
                result.add(toView(connection, new DeviceRow(rs.getString(1), rs.getString(2))));
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("列设备失败: " + e.getMessage(), e);
        }
    }

    /** 读当前正文。 */
    public Optional<DocumentView> readDocument(String key) {
        try (Connection connection = this.db.readConfig()) {
            DeviceRow device = findLiveDevice(connection, key);
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT device_id, body, revision, content_sha256, updated_at, updated_by, change_note "
                            + "FROM config_document WHERE device_id=?")) {
                st.setString(1, device.id());
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new DocumentView(rs.getString(1), rs.getString(2),
                            rs.getLong(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                            rs.getString(7)));
                }
            }
        } catch (StoreException e) {
            if (e.kind() == StoreException.Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        } catch (SQLException e) {
            throw new StoreException("读配置正文失败: " + e.getMessage(), e);
        }
    }

    /** 版本历史（新→旧）。 */
    public List<RevisionView> listRevisions(String key, int limit) {
        try (Connection connection = this.db.readConfig()) {
            DeviceRow device = findLiveDevice(connection, key);
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT revision, content_sha256, created_at, created_by, note "
                            + "FROM config_revision WHERE device_id=? ORDER BY revision DESC LIMIT ?")) {
                st.setString(1, device.id());
                st.setInt(2, Math.max(1, limit));
                try (ResultSet rs = st.executeQuery()) {
                    List<RevisionView> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(new RevisionView(rs.getLong(1), rs.getString(2),
                                rs.getLong(3), rs.getString(4), rs.getString(5)));
                    }
                    return result;
                }
            }
        } catch (SQLException e) {
            throw new StoreException("读版本历史失败: " + e.getMessage(), e);
        }
    }

    /** 取某版正文（回滚用）。 */
    public Optional<String> readRevision(String key, long revision) {
        try (Connection connection = this.db.readConfig()) {
            DeviceRow device = findLiveDevice(connection, key);
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT body FROM config_revision WHERE device_id=? AND revision=?")) {
                st.setString(1, device.id());
                st.setLong(2, revision);
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new StoreException("读历史版本失败: " + e.getMessage(), e);
        }
    }

    /** 直接重投影（迁移 / 校验自检用）：整体重建该设备的投影表。 */
    public void reproject(String key) {
        this.db.writeConfig(unchecked(connection -> {
            DeviceRow device = findLiveDevice(connection, key);
            Optional<DocumentView> doc = readDocumentInTx(connection, device.id());
            if (doc.isEmpty()) {
                throw StoreException.notFound("设备没有配置正文: " + key);
            }
            reproject(connection, device.id(), doc.get().body());
            return null;
        }));
    }

    /* ---------- 内部 ---------- */

    /** 允许抛 SQLException 的事务体。 */
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** 把带 SQLException 的写事务体包成 unchecked：SQL 失败统一变 StoreException。 */
    private <T> java.util.function.Function<Connection, T> unchecked(SqlWork<T> work) {
        return connection -> {
            try {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new StoreException("config.db 写入失败: " + e.getMessage(), e);
            }
        };
    }

    private record DeviceRow(String id, String legacyId) {
    }

    /** key 解析：先按 legacy_id 精确命中，再按 id；都未命中抛 notFound。 */
    private static DeviceRow findLiveDevice(Connection connection, String key) throws SQLException {
        String trimmed = key == null ? "" : key.trim();
        try (PreparedStatement st = connection.prepareStatement(
                "SELECT id, legacy_id FROM device WHERE deleted_at IS NULL AND (legacy_id=? OR id=?) LIMIT 2")) {
            st.setString(1, trimmed);
            st.setString(2, trimmed);
            try (ResultSet rs = st.executeQuery()) {
                String id = null;
                String legacy = null;
                while (rs.next()) {
                    String rowId = rs.getString(1);
                    String rowLegacy = rs.getString(2);
                    if (trimmed.equals(rowLegacy)) {
                        return new DeviceRow(rowId, rowLegacy);
                    }
                    if (id == null) {
                        id = rowId;
                        legacy = rowLegacy;
                    }
                }
                if (id != null) {
                    return new DeviceRow(id, legacy);
                }
            }
        }
        throw StoreException.notFound("设备不存在: " + key);
    }

    private DeviceView toView(Connection connection, DeviceRow row) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(
                "SELECT name, template_id, auto_start, note, created_at, updated_at FROM device WHERE id=?")) {
            st.setString(1, row.id());
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    throw StoreException.notFound("设备不存在: " + row.id());
                }
                long revision = 0;
                try (PreparedStatement doc = connection.prepareStatement(
                        "SELECT revision FROM config_document WHERE device_id=?")) {
                    doc.setString(1, row.id());
                    try (ResultSet drs = doc.executeQuery()) {
                        if (drs.next()) {
                            revision = drs.getLong(1);
                        }
                    }
                }
                return new DeviceView(row.id(), row.legacyId(), rs.getString(1), rs.getString(2),
                        rs.getInt(3) == 1, rs.getString(4), revision, rs.getLong(5), rs.getLong(6));
            }
        }
    }

    private static void insertDocument(Connection connection, String deviceId, String body,
                                       String sha, long revision, String actor, String note) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(
                "INSERT INTO config_document(device_id, body, schema_version, revision, content_sha256, "
                        + "updated_at, updated_by, change_note) VALUES(?,?,?,?,?,?,?,?)")) {
            st.setString(1, deviceId);
            st.setString(2, body);
            st.setInt(3, Schema.VERSION);
            st.setLong(4, revision);
            st.setString(5, sha);
            st.setLong(6, System.currentTimeMillis());
            st.setString(7, actor == null ? "local" : actor);
            st.setString(8, note == null ? "" : note);
            st.executeUpdate();
        }
    }

    private static void insertRevisionSnapshot(Connection connection, String deviceId, long revision,
                                               String body, String sha, String actor, String note) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(
                "INSERT INTO config_revision(device_id, revision, body, content_sha256, created_at, created_by, note) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            st.setString(1, deviceId);
            st.setLong(2, revision);
            st.setString(3, body);
            st.setString(4, sha);
            st.setLong(5, System.currentTimeMillis());
            st.setString(6, actor == null ? "local" : actor);
            st.setString(7, note == null ? "" : note);
            st.executeUpdate();
        }
    }

    private void reproject(Connection connection, String deviceId, String body) throws SQLException {
        clearProjections(connection, deviceId);
        this.projector.project(connection, deviceId, body);
    }

    private static void clearProjections(Connection connection, String deviceId) throws SQLException {
        for (String table : PROJECTION_TABLES) {
            try (PreparedStatement st = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE device_id=?")) {
                st.setString(1, deviceId);
                st.executeUpdate();
            }
        }
    }

    /** 快照保留策略：每设备只留最近 REVISION_RETENTION 版，超出删最旧。 */
    private static void trimRevisions(Connection connection, String deviceId) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(
                "DELETE FROM config_revision WHERE device_id=? AND revision < "
                        + "(SELECT revision FROM config_revision WHERE device_id=? "
                        + "ORDER BY revision DESC LIMIT 1 OFFSET ?)")) {
            st.setString(1, deviceId);
            st.setString(2, deviceId);
            st.setInt(3, REVISION_RETENTION - 1);
            st.executeUpdate();
        }
    }

    private static void touchDevice(Connection connection, String deviceId) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(
                "UPDATE device SET updated_at=? WHERE id=?")) {
            st.setLong(1, System.currentTimeMillis());
            st.setString(2, deviceId);
            st.executeUpdate();
        }
    }

    private static Optional<DocumentView> readDocumentInTx(Connection connection, String deviceId) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(
                "SELECT device_id, body, revision, content_sha256, updated_at, updated_by, change_note "
                        + "FROM config_document WHERE device_id=?")) {
            st.setString(1, deviceId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new DocumentView(rs.getString(1), rs.getString(2),
                        rs.getLong(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                        rs.getString(7)));
            }
        }
    }
}
