package com.shimizukenta.secssim.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * 配置仓储：device 实体 + config_document（权威正文）+ config_revision（版本快照）。
 *
 * <p><b>唯一写口</b>（设计 §2.3.2）：所有正文写入都走 {@link #saveDocument} /
 * {@link #createDevice} / {@link #updateConstants}——先比 revision（乐观锁），
 * 再升版本、留快照，任何一步失败整事务回滚。正文之外不维护任何结构化副本：
 * 查询与展示一律解析正文，避免「一份配置两处写、迟早不一致」。</p>
 *
 * <p>本模块不解析配置正文：编译校验由调用方（secs-runtime 的 ConfigStore.validate）
 * 在进入本类之前完成。</p>
 */
public final class ConfigRepository {

    /** 每设备保留的版本快照数（超出按时间裁剪，设计 §2.2.1）。 */
    public static final int REVISION_RETENTION = 200;

    private final Db db;

    /**
     * @param db 双库门面
     */
    public ConfigRepository(Db db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    /** 设备实体视图（配置与文档 JOIN 后的只读形状）。 */
    public record DeviceView(String id, String name, String templateId,
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
     * 新建设备：插入实体 + revision 1 的正文与快照。
     *
     * <p>设备名同时是配置文件的 stem（{@code <name>.json}），也是 v1 路径里
     * 用来定位设备的自然键；实体 id 是独立的 {@code dev_} ULID，两者不再互为别名。</p>
     *
     * @param name       设备名（= 配置文件名去掉 {@code .json}）
     * @param templateId 来源模板（仅分组用）；可空
     * @param body       配置正文（调用方已校验）
     * @param autoStart  启动对账时是否自动连接
     * @param actor      操作者
     * @return 新设备 id
     * @throws StoreException 名字冲突（未删除行内唯一）或写库失败
     */
    public String createDevice(String name, String templateId,
                               String body, boolean autoStart, String actor) {
        Objects.requireNonNull(name, "name");
        String id = Ids.nextDevice();
        long now = System.currentTimeMillis();
        String sha = ContentHash.sha256Hex(body);
        return this.db.writeConfig(unchecked(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO device(id, name, template_id, auto_start, note, created_at, updated_at) "
                            + "VALUES(?,?,?,?,?,?,?)")) {
                st.setString(1, id);
                st.setString(2, name);
                st.setString(3, templateId);
                st.setInt(4, autoStart ? 1 : 0);
                st.setString(5, "");
                st.setLong(6, now);
                st.setLong(7, now);
                st.executeUpdate();
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    throw StoreException.conflict("设备名冲突: " + name);
                }
                throw new StoreException("建设备失败: " + e.getMessage(), e);
            }
            insertDocument(connection, id, body, sha, 1, actor, "created");
            insertRevisionSnapshot(connection, id, 1, body, sha, actor, "created");
            return id;
        }));
    }

    /**
     * 保存正文（唯一写口）：乐观锁 → 升版本 → 快照 → 裁剪。
     *
     * @param key              设备 key（设备名或设备 id）
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
                            + "updated_at=?, updated_by=?, change_note=? "
                            + "WHERE device_id=? AND revision=?")) {
                st.setString(1, body);
                st.setInt(2, Schema.VERSION);
                st.setLong(3, next);
                st.setString(4, sha);
                st.setLong(5, System.currentTimeMillis());
                st.setString(6, actor == null ? "local" : actor);
                st.setString(7, note == null ? "" : note);
                st.setString(8, device.id());
                /* CAS 条件同时写进 UPDATE：以前只靠上面那次 SELECT 比对，
                   完全依赖「Db 只有一个写者」这个隐含前提。哪天出现第二个写者
                   （连接池、外部进程、只读副本转写），就是标准的读后写丢更新。
                   0 行说明有人在 SELECT 与 UPDATE 之间改过，按冲突返回。 */
                st.setLong(9, currentRevision);
                if (st.executeUpdate() == 0) {
                    throw StoreException.conflict("配置在保存过程中被其他写入修改过，请重新载入后再保存");
                }
            }
            insertRevisionSnapshot(connection, device.id(), next, body, sha, actor, note);
            trimRevisions(connection, device.id());
            touchDevice(connection, device.id());
            return next;
        }));
    }

    /**
     * S2F15 常量回写的专用写口：升版本，但<b>不写快照</b>，提交后把
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
        return this.updateConstants(key, newBody, null, actor);
    }

    /**
     * S2F15 常量回写（带乐观锁）：语义同 {@link #updateConstants(String, String, String)}，
     * 但事务内先比对 revision——「读正文 → 改 constants → 回写」不是原子的，
     * 读与写之间用户 saveDocument 提交的新正文不能被基于旧正文的 newBody 整体覆盖。
     *
     * @param key              设备 key
     * @param newBody          只替换过 constants 子树的新正文
     * @param expectedRevision 读到正文时的版本号；null 表示不校验（兼容旧调用方）
     * @param actor            操作者（恒为设备侧）
     * @return 新版本号
     * @throws StoreException 版本冲突（CONFLICT，调用方应重读后重试）或设备不存在（NOT_FOUND）
     */
    public long updateConstants(String key, String newBody, Long expectedRevision, String actor) {
        String sha = ContentHash.sha256Hex(newBody);
        ConstantsUpdate updated = this.db.writeConfig(unchecked(connection -> {
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
            if (expectedRevision != null && expectedRevision != currentRevision) {
                throw StoreException.conflict("常量回写基于 v" + expectedRevision
                        + "，但配置已被修改到 v" + currentRevision + "，请重读后重试");
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
            touchDevice(connection, device.id());
            return new ConstantsUpdate(device.id(), next);
        }));
        /* 追平运行版本；没有 device_link 行（设备未连接过）就跳过 */
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE device_link SET config_revision=? WHERE device_id=?")) {
                st.setLong(1, updated.revision());
                st.setString(2, updated.deviceId());
                return st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("追平运行版本失败: " + e.getMessage(), e);
            }
        });
        return updated.revision();
    }

    /** 按 key（设备名或设备 id）找未删除设备。 */
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
                     "SELECT id, name FROM device WHERE deleted_at IS NULL ORDER BY created_at, id")) {
            List<DeviceView> result = new ArrayList<>();
            while (rs.next()) {
                result.add(toView(connection, new DeviceRow(rs.getString(1), rs.getString(2))));
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("列设备失败: " + e.getMessage(), e);
        }
    }

    /**
     * 软删设备：列表里不再出现，名字可以重新使用。
     *
     * <p>不物理删除：配置正文、版本历史与运行台账都是排障时要翻的东西，
     * 一台设备从清单里删掉之后，"它上周为什么报警"仍然要查得到。
     * 名字唯一索引只看未删除行（{@code WHERE deleted_at IS NULL}），
     * 所以软删之后同名设备可以直接建回来。</p>
     *
     * @param key 设备 key（设备名或设备 id）
     * @return 是否真的删掉了一台在用的设备（不存在或已删返回 {@code false}）
     */
    public boolean deleteDevice(String key) {
        Objects.requireNonNull(key, "key");
        return this.db.writeConfig(connection -> {
            /* 先按与读路径相同的语义解析出唯一一台设备，再按 id 删。
               原写法是 `WHERE name=? OR id=?` 一次可能命中两行：设备名恰好等于
               另一台设备的 dev_ 前缀 id 时，删一台会连带把另一台也软删掉。 */
            DeviceRow device;
            try {
                device = findLiveDevice(connection, key);
            } catch (StoreException e) {
                if (e.kind() == StoreException.Kind.NOT_FOUND) {
                    return false;
                }
                throw e;
            } catch (SQLException e) {
                throw new StoreException("删除设备失败: " + e.getMessage(), e);
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE device SET deleted_at=?, updated_at=? "
                            + "WHERE deleted_at IS NULL AND id=?")) {
                st.setLong(1, now);
                st.setLong(2, now);
                st.setString(3, device.id());
                return st.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new StoreException("删除设备失败: " + e.getMessage(), e);
            }
        });
    }

    /** 读当前正文。 */
    public Optional<DocumentView> readDocument(String key) {        try (Connection connection = this.db.readConfig()) {
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

    /**
     * 全量导出（备份通道）：每台设备写 {@code <name>.json}。
     *
     * @param dir 目标目录（自动创建；已存在的同名文件覆盖）
     * @return 导出的设备数
     */
    public int exportTo(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new StoreException("导出目录不可用: " + dir + ": " + e.getMessage(), e);
        }
        int count = 0;
        for (DeviceView device : this.listDevices()) {
            Optional<DocumentView> doc = readDocument(device.name());
            if (doc.isEmpty()) {
                continue;
            }
            String fileName = requireExportableName(device.name()) + ".json";
            try {
                Path target = dir.resolve(fileName);
                /* 临时名加随机后缀：固定 ".tmp" 名会让同目录并发导出互踩 */
                Path tmp = dir.resolve(fileName + "."
                        + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong())
                        + ".tmp");
                Files.writeString(tmp, doc.get().body(), StandardCharsets.UTF_8);
                move(tmp, target);
                count++;
            } catch (IOException e) {
                throw new StoreException("导出 " + fileName + " 失败: " + e.getMessage(), e);
            }
        }
        return count;
    }

    /**
     * 导出文件名净化（与上层 ConfigStore.requireFileName 等价，store 层不能反向依赖它）：
     * 设备名要拼进 {@code dir.resolve(...)}，含路径分隔符 / {@code ..} / 盘符的名字
     * 会越界写到目录外——仓储可被绕过上层校验的调用方直接使用，这里必须自己挡。
     */
    private static String requireExportableName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")
                || trimmed.contains("..") || trimmed.contains(":")) {
            throw new StoreException("设备名不能用作导出文件名（不能为空或带路径）: " + name);
        }
        return trimmed;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /* ---------- 内部 ---------- */

    /** 允许抛 SQLException 的事务体。 */
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** SQLITE_CONSTRAINT_UNIQUE（扩展码）。 */
    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;

    /** SQLITE_CONSTRAINT_PRIMARYKEY（扩展码）。 */
    private static final int SQLITE_CONSTRAINT_PRIMARYKEY = 1555;

    /**
     * 唯一/主键约束冲突判定（{@link ParamTemplateRepository} 等共用）。
     *
     * <p>判据是 sqlite-jdbc 的扩展错误码而不是异常报文：
     * {@code SQLException.getErrorCode()} 只给基础码 19（SQLITE_CONSTRAINT），
     * 区分不了 UNIQUE 与 NOT NULL/CHECK，扩展码从
     * {@code SQLiteException.getResultCode()} 取。报文匹配只在拿不到扩展码时兜底
     * （驱动被包装、升级换了文案都会让文本判据失效）。</p>
     */
    static boolean isUniqueViolation(SQLException e) {
        if (e instanceof org.sqlite.SQLiteException sqlite) {
            int extended = sqlite.getResultCode().code;
            if (extended == SQLITE_CONSTRAINT_UNIQUE || extended == SQLITE_CONSTRAINT_PRIMARYKEY) {
                return true;
            }
        }
        String message = e.getMessage();
        return message != null
                && (message.contains("UNIQUE constraint failed") || message.contains("PRIMARY"));
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

    private record DeviceRow(String id, String name) {
    }

    /** updateConstants 跨事务传值：设备 id + 新版本号（record 替代旧的 "|" 字符串打包协议）。 */
    private record ConstantsUpdate(String deviceId, long revision) {
    }

    /** key 解析：先按设备名精确命中，再按 id；都未命中抛 notFound。 */
    private static DeviceRow findLiveDevice(Connection connection, String key) throws SQLException {
        String trimmed = key == null ? "" : key.trim();
        try (PreparedStatement st = connection.prepareStatement(
                "SELECT id, name FROM device WHERE deleted_at IS NULL AND (name=? OR id=?) LIMIT 2")) {
            st.setString(1, trimmed);
            st.setString(2, trimmed);
            try (ResultSet rs = st.executeQuery()) {
                String id = null;
                String name = null;
                while (rs.next()) {
                    String rowId = rs.getString(1);
                    String rowName = rs.getString(2);
                    if (trimmed.equals(rowName)) {
                        return new DeviceRow(rowId, rowName);
                    }
                    if (id == null) {
                        id = rowId;
                        name = rowName;
                    }
                }
                if (id != null) {
                    return new DeviceRow(id, name);
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
                return new DeviceView(row.id(), rs.getString(1), rs.getString(2),
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
}
