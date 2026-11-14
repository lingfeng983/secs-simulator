package com.shimizukenta.secssim.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 主机实体仓储（config.db 的 host 表，第三轮修订 12 的 host_template 一并提供）。
 *
 * <p>主机与设备之间<b>无任何引用</b>（设计 P6）：没有指向 device 的列或外键，
 * 连接目标只是一个普通 HSMS 地址。设置保存走 {@link #save}，每次保存
 * {@code setting_revision} +1（「设置待生效」横幅的依据）。</p>
 */
public final class HostRepository {

    /** 主机实体视图。 */
    public record HostView(String id, String name, String targetHost, int targetPort,
                           int targetDeviceIdNum, String timeoutsJson, String collectorJson,
                           String variableCatalogJson, boolean autoReconnect, String note,
                           long settingRevision, long createdAt, long updatedAt) {
    }

    /** 主机模板视图。 */
    public record HostTemplateView(String id, String name, String host, int port,
                                   int deviceIdNum, String collectorJson, String note,
                                   long createdAt, long updatedAt) {
    }

    private final Db db;

    /** @param db 双库门面 */
    public HostRepository(Db db) {
        this.db = db;
    }

    /* ---------- host ---------- */

    /**
     * 新建主机。
     *
     * @param name           显示名
     * @param targetHost     目标地址（任意可达地址，不查询设备）
     * @param targetPort     目标端口
     * @param deviceIdNum    SECS 报文头 Device ID 号
     * @param collectorJson  采集配置（可空 JSON）
     * @param autoReconnect  失败后是否自动重连
     * @param note           备注
     * @return 新主机 id（host_01J…，持久稳定）
     */
    public String create(String name, String targetHost, int targetPort, int deviceIdNum,
                         String collectorJson, boolean autoReconnect, String note) {
        String id = Ids.nextHost();
        long now = System.currentTimeMillis();
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO host(id, name, target_host, target_port, target_device_id_num, "
                            + "collector_json, auto_reconnect, note, created_at, updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                st.setString(1, id);
                st.setString(2, name);
                st.setString(3, targetHost);
                st.setInt(4, targetPort);
                st.setInt(5, deviceIdNum);
                st.setString(6, collectorJson == null ? "{}" : collectorJson);
                st.setInt(7, autoReconnect ? 1 : 0);
                st.setString(8, note == null ? "" : note);
                st.setLong(9, now);
                st.setLong(10, now);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("新建主机失败: " + e.getMessage(), e);
            }
            return null;
        });
        return id;
    }

    /** 按 id 找主机（未删除）。 */
    public Optional<HostView> find(String hostId) {
        try (Connection connection = this.db.readConfig();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT id, name, target_host, target_port, target_device_id_num, timeouts_json, "
                             + "collector_json, variable_catalog_json, auto_reconnect, note, "
                             + "setting_revision, created_at, updated_at FROM host "
                             + "WHERE id=? AND deleted_at IS NULL")) {
            st.setString(1, hostId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(toView(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("查主机失败: " + e.getMessage(), e);
        }
    }

    /** 全部未删除主机（按创建顺序）。 */
    public List<HostView> list() {
        try (Connection connection = this.db.readConfig();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT id, name, target_host, target_port, target_device_id_num, timeouts_json, "
                             + "collector_json, variable_catalog_json, auto_reconnect, note, "
                             + "setting_revision, created_at, updated_at FROM host "
                             + "WHERE deleted_at IS NULL ORDER BY created_at, id")) {
            try (ResultSet rs = st.executeQuery()) {
                List<HostView> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toView(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new StoreException("列主机失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存主机设置：target / 采集 / 自动重连 / 名称 / 备注；setting_revision +1。
     *
     * @param hostId        主机 id
     * @param name          显示名
     * @param targetHost    目标地址
     * @param targetPort    目标端口
     * @param deviceIdNum   Device ID 号
     * @param collectorJson 采集配置
     * @param autoReconnect 自动重连
     * @param note          备注
     * @return 新的 setting_revision
     */
    public long save(String hostId, String name, String targetHost, int targetPort,
                     int deviceIdNum, String collectorJson, boolean autoReconnect, String note) {
        Objects.requireNonNull(name, "name");
        return this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE host SET name=?, target_host=?, target_port=?, target_device_id_num=?, "
                            + "collector_json=?, auto_reconnect=?, note=?, setting_revision=setting_revision+1, "
                            + "updated_at=? WHERE id=? AND deleted_at IS NULL")) {
                st.setString(1, name);
                st.setString(2, targetHost);
                st.setInt(3, targetPort);
                st.setInt(4, deviceIdNum);
                st.setString(5, collectorJson == null ? "{}" : collectorJson);
                st.setInt(6, autoReconnect ? 1 : 0);
                st.setString(7, note == null ? "" : note);
                st.setLong(8, System.currentTimeMillis());
                st.setString(9, hostId);
                if (st.executeUpdate() == 0) {
                    throw StoreException.notFound("主机不存在: " + hostId);
                }
            } catch (SQLException e) {
                throw new StoreException("保存主机失败: " + e.getMessage(), e);
            }
            Long revision = null;
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT setting_revision FROM host WHERE id=?")) {
                st.setString(1, hostId);
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) {
                        revision = rs.getLong(1);
                    }
                }
            } catch (SQLException e) {
                throw new StoreException("读设置版本失败: " + e.getMessage(), e);
            }
            return revision == null ? 0L : revision;
        });
    }

    /** 保存变量目录（S1F11 刷新或手动填写都走这里，不产生设置漂移）。 */
    public void saveVariableCatalog(String hostId, String catalogJson) {
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE host SET variable_catalog_json=?, updated_at=? WHERE id=? AND deleted_at IS NULL")) {
                st.setString(1, catalogJson == null ? "{}" : catalogJson);
                st.setLong(2, System.currentTimeMillis());
                st.setString(3, hostId);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("保存变量目录失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 删除主机（软删墓碑；断开态才允许的检查在生命周期层）。 */
    public void delete(String hostId) {
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE host SET deleted_at=? WHERE id=? AND deleted_at IS NULL")) {
                st.setLong(1, System.currentTimeMillis());
                st.setString(2, hostId);
                if (st.executeUpdate() == 0) {
                    throw StoreException.notFound("主机不存在: " + hostId);
                }
            } catch (SQLException e) {
                throw new StoreException("删除主机失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    private static HostView toView(ResultSet rs) throws SQLException {
        return new HostView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getInt(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getInt(9) == 1, rs.getString(10), rs.getLong(11), rs.getLong(12), rs.getLong(13));
    }

    /* ---------- host_template（第三轮修订 12） ---------- */

    /**
     * 新建主机模板。
     *
     * @param name          模板名
     * @param host          目标地址
     * @param port          端口
     * @param deviceIdNum   Device ID 号
     * @param collectorJson 采集配置（可空）
     * @param note          备注
     * @return 模板 id（ht_01J…）
     */
    public String createTemplate(String name, String host, int port, int deviceIdNum,
                                 String collectorJson, String note) {
        String id = Ids.nextHostTemplate();
        long now = System.currentTimeMillis();
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO host_template(id, name, host, port, device_id_num, collector_json, "
                            + "note, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                st.setString(1, id);
                st.setString(2, name);
                st.setString(3, host);
                st.setInt(4, port);
                st.setInt(5, deviceIdNum);
                st.setString(6, collectorJson == null ? "{}" : collectorJson);
                st.setString(7, note == null ? "" : note);
                st.setLong(8, now);
                st.setLong(9, now);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("新建主机模板失败: " + e.getMessage(), e);
            }
            return null;
        });
        return id;
    }

    /** 全部未删除主机模板。 */
    public List<HostTemplateView> listTemplates() {
        try (Connection connection = this.db.readConfig();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT id, name, host, port, device_id_num, collector_json, note, created_at, updated_at "
                             + "FROM host_template WHERE deleted_at IS NULL ORDER BY created_at, id")) {
            try (ResultSet rs = st.executeQuery()) {
                List<HostTemplateView> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new HostTemplateView(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getString(7),
                            rs.getLong(8), rs.getLong(9)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new StoreException("列主机模板失败: " + e.getMessage(), e);
        }
    }

    /** 全部未删除主机模板的 JSON 数组。 */
    public String listTemplatesJson() {
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        List<com.shimizukenta.jsonhub.JsonHub> items = new ArrayList<>();
        for (HostTemplateView view : listTemplates()) {
            items.add(b.object(
                    b.pair("id", view.id()),
                    b.pair("name", view.name()),
                    b.pair("host", view.host()),
                    b.pair("port", view.port()),
                    b.pair("deviceId", view.deviceIdNum()),
                    b.pair("note", view.note())));
        }
        return b.object(b.pair("templates", b.array(items))).toJson();
    }

    /** 更新主机模板。 */
    public void saveTemplate(String id, String name, String host, int port,
                             int deviceIdNum, String collectorJson, String note) {
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE host_template SET name=?, host=?, port=?, device_id_num=?, "
                            + "collector_json=?, note=?, updated_at=? WHERE id=? AND deleted_at IS NULL")) {
                st.setString(1, name);
                st.setString(2, host);
                st.setInt(3, port);
                st.setInt(4, deviceIdNum);
                st.setString(5, collectorJson == null ? "{}" : collectorJson);
                st.setString(6, note == null ? "" : note);
                st.setLong(7, System.currentTimeMillis());
                st.setString(8, id);
                if (st.executeUpdate() == 0) {
                    throw StoreException.notFound("主机模板不存在: " + id);
                }
            } catch (SQLException e) {
                throw new StoreException("保存主机模板失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 删除主机模板（软删）。 */
    public void deleteTemplate(String id) {
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE host_template SET deleted_at=? WHERE id=? AND deleted_at IS NULL")) {
                st.setLong(1, System.currentTimeMillis());
                st.setString(2, id);
                if (st.executeUpdate() == 0) {
                    throw StoreException.notFound("主机模板不存在: " + id);
                }
            } catch (SQLException e) {
                throw new StoreException("删除主机模板失败: " + e.getMessage(), e);
            }
            return null;
        });
    }
}
