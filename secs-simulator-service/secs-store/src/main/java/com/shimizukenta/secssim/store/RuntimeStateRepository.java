package com.shimizukenta.secssim.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 运行态仓储（runtime.db）：链路相位、运行态编辑（钉值 / 计划启用态 /
 * 运行时计划正文 / 目录编辑 / 主机动态报告）与链路事件。
 *
 * <p>runtime.db 整库「可随时丢弃重建」；断开保留、重连恢复的语义在
 * 生命周期层（M2），本类只提供行级读写。跨库（config.db）不做事务——
 * 启动对账是唯一的收敛机制（设计 §2.4.4）。</p>
 */
public final class RuntimeStateRepository {

    /** 链路相位（设备 / 主机各有一套枚举值，这里以字符串存取）。 */
    public record LinkRow(String entityId, String phase, long configRevision,
                          String controlState, String lastError,
                          Long connectedAt, Long disconnectedAt) {
    }

    /** 链路事件。 */
    public record LinkEvent(long id, String entity, String entityId,
                            String fromPhase, String toPhase, long at, String detail) {
    }

    /** 计划运行态。 */
    public record PlanStateRow(String planId, boolean enabled, Long startAt, Long nextFireAt,
                               Long lastFireAt, long fireCount, long skippedCount, String lastError) {
    }

    /** 运行时目录/计划编辑。 */
    public record OverrideRow(String key, String op, String bodyJson, long updatedAt) {
    }

    private final Db db;

    /** @param db 双库门面 */
    public RuntimeStateRepository(Db db) {
        this.db = db;
    }

    /* ---------- device_link ---------- */

    /** 建立/重置设备链路行（启动或首次连接）。 */
    public void upsertDeviceLink(String deviceId, String phase, long configRevision) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO device_link(device_id, phase, config_revision) VALUES(?,?,?) "
                            + "ON CONFLICT(device_id) DO UPDATE SET phase=excluded.phase, "
                            + "config_revision=excluded.config_revision")) {
                st.setString(1, deviceId);
                st.setString(2, phase);
                st.setLong(3, configRevision);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("写 device_link 失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 更新链路相位与错误。 */
    public void updateDevicePhase(String deviceId, String phase, String lastError) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE device_link SET phase=?, last_error=?, "
                            + "connected_at=CASE WHEN ?='CONNECTED' THEN ? ELSE connected_at END, "
                            + "disconnected_at=CASE WHEN ? IN ('DISCONNECTED','STOPPED','FAILED') THEN ? ELSE disconnected_at END "
                            + "WHERE device_id=?")) {
                st.setString(1, phase);
                st.setString(2, lastError == null ? "" : lastError);
                st.setString(3, phase);
                st.setLong(4, System.currentTimeMillis());
                st.setString(5, phase);
                st.setLong(6, System.currentTimeMillis());
                st.setString(7, deviceId);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("更新 device_link 相位失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /**
     * 启动对账：把上次进程留下的「还连着」的相位改成已断开。
     *
     * <p>进程重启后没有任何链路是活的，但 {@code device_link} 里仍写着
     * {@code CONNECTED}／{@code LISTENING}／{@code STARTING}。界面上这叫
     * 「显示连着的死设备」，重启恢复要先把它们收敛成 {@code DISCONNECTED}
     * （保留行，断开展示与重连恢复都靠它）。</p>
     *
     * @return 改写的行数
     */
    public long resetStaleDevicePhases() {
        return this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE device_link SET phase='DISCONNECTED', disconnected_at=? "
                            + "WHERE phase IN ('CONNECTED','LISTENING','STARTING')")) {
                st.setLong(1, System.currentTimeMillis());
                return (long) st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("重置设备链路相位失败: " + e.getMessage(), e);
            }
        });
    }

    /** 读设备链路行。 */
    public Optional<LinkRow> deviceLink(String deviceId) {
        try (Connection connection = this.db.readRuntime();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT device_id, phase, config_revision, control_state, last_error, "
                             + "connected_at, disconnected_at "
                             + "FROM device_link WHERE device_id=?")) {
            st.setString(1, deviceId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LinkRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5),
                        longOrNull(rs, 6), longOrNull(rs, 7)));
            }
        } catch (SQLException e) {
            throw new StoreException("读 device_link 失败: " + e.getMessage(), e);
        }
    }

    /* ---------- host_link ---------- */

    /** 建立/重置主机链路行。 */
    public void upsertHostLink(String hostId, String phase, String target, long appliedRevision) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO host_link(host_id, phase, target, applied_revision) VALUES(?,?,?,?) "
                            + "ON CONFLICT(host_id) DO UPDATE SET phase=excluded.phase, "
                            + "target=excluded.target, applied_revision=excluded.applied_revision")) {
                st.setString(1, hostId);
                st.setString(2, phase);
                st.setString(3, target);
                st.setLong(4, appliedRevision);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("写 host_link 失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 更新主机链路相位 / 错误 / 重试计数。 */
    public void updateHostPhase(String hostId, String phase, String lastError,
                                Long retryCount, Long nextRetryAt) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE host_link SET phase=?, last_error=?, "
                            + "retry_count=COALESCE(?, retry_count), next_retry_at=?, "
                            + "connected_at=CASE WHEN ?='CONNECTED' THEN ? ELSE connected_at END, "
                            + "disconnected_at=CASE WHEN ? IN ('DISCONNECTED','FAILED') THEN ? ELSE disconnected_at END "
                            + "WHERE host_id=?")) {
                st.setString(1, phase);
                st.setString(2, lastError == null ? "" : lastError);
                if (retryCount == null) {
                    st.setNull(3, java.sql.Types.INTEGER);
                } else {
                    st.setLong(3, retryCount);
                }
                if (nextRetryAt == null) {
                    st.setNull(4, java.sql.Types.INTEGER);
                } else {
                    st.setLong(4, nextRetryAt);
                }
                st.setString(5, phase);
                st.setLong(6, System.currentTimeMillis());
                st.setString(7, phase);
                st.setLong(8, System.currentTimeMillis());
                st.setString(9, hostId);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("更新 host_link 相位失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 读主机链路行。 */
    public Optional<LinkRow> hostLink(String hostId) {
        try (Connection connection = this.db.readRuntime();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT host_id, phase, applied_revision, '', last_error, connected_at, disconnected_at "
                             + "FROM host_link WHERE host_id=?")) {
            st.setString(1, hostId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LinkRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5),
                        longOrNull(rs, 6), longOrNull(rs, 7)));
            }
        } catch (SQLException e) {
            throw new StoreException("读 host_link 失败: " + e.getMessage(), e);
        }
    }

    /* ---------- 钉值 / 计划 / 目录 / 主机报告（断开保留，reload 清空） ---------- */

    /** 写一条 SVID 钉值。 */
    public void pinSvid(String deviceId, long svid, double value) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO runtime_svid_pin(device_id, svid, value, set_at) VALUES(?,?,?,?) "
                            + "ON CONFLICT(device_id, svid) DO UPDATE SET value=excluded.value, set_at=excluded.set_at")) {
                st.setString(1, deviceId);
                st.setLong(2, svid);
                st.setDouble(3, value);
                st.setLong(4, System.currentTimeMillis());
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("写钉值失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 取消一条钉值。 */
    public void unpinSvid(String deviceId, long svid) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "DELETE FROM runtime_svid_pin WHERE device_id=? AND svid=?")) {
                st.setString(1, deviceId);
                st.setLong(2, svid);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("删钉值失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 设备全部钉值：{svid: value}。 */
    public java.util.Map<Long, Double> pinnedSvids(String deviceId) {
        try (Connection connection = this.db.readRuntime();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT svid, value FROM runtime_svid_pin WHERE device_id=?")) {
            st.setString(1, deviceId);
            java.util.Map<Long, Double> result = new java.util.HashMap<>();
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getLong(1), rs.getDouble(2));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("读钉值失败: " + e.getMessage(), e);
        }
    }

    /** 写计划启用态。 */
    public void savePlanState(String deviceId, String planId, boolean enabled,
                              Long nextFireAt, String lastError) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO runtime_plan_state(device_id, plan_id, enabled, start_at, next_fire_at, last_error) "
                            + "VALUES(?,?,?,?,?,?) "
                            + "ON CONFLICT(device_id, plan_id) DO UPDATE SET enabled=excluded.enabled, "
                            + "start_at=CASE WHEN excluded.enabled=1 AND runtime_plan_state.enabled=0 THEN excluded.start_at "
                            + "ELSE runtime_plan_state.start_at END, "
                            + "next_fire_at=excluded.next_fire_at, last_error=excluded.last_error")) {
                st.setString(1, deviceId);
                st.setString(2, planId);
                st.setInt(3, enabled ? 1 : 0);
                st.setLong(4, System.currentTimeMillis());
                if (nextFireAt == null) {
                    st.setNull(5, java.sql.Types.INTEGER);
                } else {
                    st.setLong(5, nextFireAt);
                }
                st.setString(6, lastError == null ? "" : lastError);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("写计划启用态失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /**
     * 可空整数列 → Long。
     *
     * <p>SQLite 的 JDBC 驱动对小整数返回 {@link Integer}，直接 {@code (Long)}
     * 会在「计划启动过一次、把统计落了库」之后抛 ClassCastException——
     * 只有真正跑起来才会踩到。NULL 仍然返回 {@code null}。</p>
     */
    private static Long longOrNull(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : Long.valueOf(value);
    }

    /** 全部计划运行态。 */
    public List<PlanStateRow> planStates(String deviceId) {
        try (Connection connection = this.db.readRuntime();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT plan_id, enabled, start_at, next_fire_at, last_fire_at, fire_count, "
                             + "skipped_count, last_error FROM runtime_plan_state WHERE device_id=?")) {
            st.setString(1, deviceId);
            try (ResultSet rs = st.executeQuery()) {
                List<PlanStateRow> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new PlanStateRow(rs.getString(1), rs.getInt(2) == 1,
                            longOrNull(rs, 3), longOrNull(rs, 4), longOrNull(rs, 5),
                            rs.getLong(6), rs.getLong(7), rs.getString(8)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new StoreException("读计划运行态失败: " + e.getMessage(), e);
        }
    }

    /** 写运行时计划正文覆盖（add/update/delete）。 */
    public void savePlanOverride(String deviceId, String planId, String op, String bodyJson) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO runtime_plan_override(device_id, plan_id, op, body_json, updated_at) "
                            + "VALUES(?,?,?,?,?) ON CONFLICT(device_id, plan_id) DO UPDATE SET "
                            + "op=excluded.op, body_json=excluded.body_json, updated_at=excluded.updated_at")) {
                st.setString(1, deviceId);
                st.setString(2, planId);
                st.setString(3, op);
                st.setString(4, bodyJson);
                st.setLong(5, System.currentTimeMillis());
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("写计划覆盖失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 运行时计划覆盖列表。 */
    public List<OverrideRow> planOverrides(String deviceId) {
        return overrides(deviceId, "runtime_plan_override", "plan_id");
    }

    /** 写运行时目录编辑（ceid/report/svid）。 */
    public void saveCatalogEdit(String deviceId, String kind, long key, String op, String bodyJson) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO runtime_catalog_edit(device_id, kind, key, op, body_json, updated_at) "
                            + "VALUES(?,?,?,?,?,?) ON CONFLICT(device_id, kind, key) DO UPDATE SET "
                            + "op=excluded.op, body_json=excluded.body_json, updated_at=excluded.updated_at")) {
                st.setString(1, deviceId);
                st.setString(2, kind);
                st.setLong(3, key);
                st.setString(4, op);
                st.setString(5, bodyJson);
                st.setLong(6, System.currentTimeMillis());
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("写目录编辑失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 运行时目录编辑列表。 */
    public List<OverrideRow> catalogEdits(String deviceId) {
        return overrides(deviceId, "runtime_catalog_edit", "kind||':'||key");
    }

    private List<OverrideRow> overrides(String deviceId, String table, String keyExpr) {
        try (Connection connection = this.db.readRuntime();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT " + keyExpr + ", op, body_json, updated_at FROM " + table + " WHERE device_id=?")) {
            st.setString(1, deviceId);
            try (ResultSet rs = st.executeQuery()) {
                List<OverrideRow> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new OverrideRow(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getLong(4)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new StoreException("读运行时编辑失败: " + e.getMessage(), e);
        }
    }

    /** 保存主机动态报告负载（含 S2F37 启用态，重连后恢复用）。 */
    public void saveHostReport(String deviceId, String payloadJson) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO runtime_host_report(device_id, payload_json, updated_at) VALUES(?,?,?) "
                            + "ON CONFLICT(device_id) DO UPDATE SET payload_json=excluded.payload_json, "
                            + "updated_at=excluded.updated_at")) {
                st.setString(1, deviceId);
                st.setString(2, payloadJson);
                st.setLong(3, System.currentTimeMillis());
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("写主机动态报告失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 读主机动态报告负载。 */
    public Optional<String> hostReport(String deviceId) {
        try (Connection connection = this.db.readRuntime();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT payload_json FROM runtime_host_report WHERE device_id=?")) {
            st.setString(1, deviceId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("读主机动态报告失败: " + e.getMessage(), e);
        }
    }

    /* ---------- link_event ---------- */

    /** 每个实体保留的链路事件行数。 */
    private static final int LINK_RETENTION_ROWS = 2_000;

    /** 链路事件的保留时长。 */
    private static final long LINK_RETENTION_MS = 7L * 24L * 3600L * 1000L;

    /**
     * 裁剪链路事件：每实体保留最近 {@value #LINK_RETENTION_ROWS} 条，且不超过 7 天。
     *
     * <p>{@code link_event} 以前完全没有裁剪路径——只在启动时清了一次没人管它，
     * 一台设备反复重连、跑上几周就能把 runtime.db 撑到磁盘写满，
     * 之后所有写事务（含配置保存）都会失败。由 {@code Bootstrap} 的小时级任务调用。</p>
     *
     * @return 删除行数
     */
    public int trimLinkEvents() {
        return this.db.writeRuntime(connection -> {
            try (var st = connection.createStatement()) {
                /* 与 plan_execution 用同一种写法：子查询里先算行号，避免把多行结果当标量 */
                int byRows = st.executeUpdate("DELETE FROM link_event WHERE id IN ("
                        + "SELECT id FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY entity_id "
                        + "ORDER BY id DESC) AS rn FROM link_event) WHERE rn > " + LINK_RETENTION_ROWS + ")");
                int byAge = st.executeUpdate("DELETE FROM link_event WHERE at < "
                        + (System.currentTimeMillis() - LINK_RETENTION_MS));
                return byRows + byAge;
            } catch (SQLException e) {
                throw new StoreException("链路事件裁剪失败: " + e.getMessage(), e);
            }
        });
    }

    /** 追加一条链路事件。 */
    public void appendLinkEvent(String entity, String entityId, String fromPhase,
                                String toPhase, String detail) {
        this.db.writeRuntime(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO link_event(entity, entity_id, from_phase, to_phase, at, detail) "
                            + "VALUES(?,?,?,?,?,?)")) {
                st.setString(1, entity);
                st.setString(2, entityId);
                st.setString(3, fromPhase);
                st.setString(4, toPhase);
                st.setLong(5, System.currentTimeMillis());
                st.setString(6, detail == null ? "" : detail);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("写链路事件失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 某实体最近的链路事件（新→旧）。 */
    public List<LinkEvent> linkEvents(String entityId, int limit) {
        try (Connection connection = this.db.readRuntime();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT id, entity, entity_id, from_phase, to_phase, at, detail "
                             + "FROM link_event WHERE entity_id=? ORDER BY id DESC LIMIT ?")) {
            st.setString(1, entityId);
            st.setInt(2, Math.max(1, limit));
            try (ResultSet rs = st.executeQuery()) {
                List<LinkEvent> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new LinkEvent(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new StoreException("读链路事件失败: " + e.getMessage(), e);
        }
    }

    /* ---------- 启动对账 ---------- */

    /**
     * 清掉不属于任何存活设备的运行态残留（软删 / 迁移后消失的设备）。
     *
     * <p><b>过渡期逻辑</b>：按 device_id 白名单删除时，分不清「设备真被删了」
     * 与「行是旧版本用 legacy 文件名当键写出来的」——两者都只是不在白名单里。
     * 身份统一（运行态只用 config.db 的 ULID 作键）落地后，这类孤儿应由外键
     * 约束挡在写入侧，本方法整体移除。</p>
     *
     * @param liveDeviceIds 存活设备 id 集合
     * @return 清理的行数
     */
    public long cleanupOrphans(Collection<String> liveDeviceIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(
                Math.max(1, liveDeviceIds.size()), "?"));
        /* 七张表合到一个写事务：中途失败不再留下"清了一半"的状态（原本靠下次启动重跑收敛） */
        return this.db.writeRuntime(connection -> {
            long total = 0;
            for (String table : new String[] {
                    "device_link", "runtime_svid_pin", "runtime_plan_state", "runtime_plan_override",
                    "runtime_catalog_edit", "runtime_host_report", "plan_execution" }) {
                String sql = liveDeviceIds.isEmpty()
                        ? "DELETE FROM " + table
                        : "DELETE FROM " + table + " WHERE device_id NOT IN (" + placeholders + ")";
                try (PreparedStatement st = connection.prepareStatement(sql)) {
                    int i = 1;
                    for (String id : liveDeviceIds) {
                        st.setString(i++, id);
                    }
                    total += st.executeUpdate();
                } catch (SQLException e) {
                    throw new StoreException("清理 " + table + " 残留失败: " + e.getMessage(), e);
                }
            }
            return total;
        });
    }

    /** 清空某设备的运行态编辑（reload 语义；M2 接线）。 */
    public void clearRuntimeEdits(String deviceId) {
        this.db.writeRuntime(connection -> {
            for (String table : new String[] {
                    "runtime_svid_pin", "runtime_plan_override", "runtime_catalog_edit" }) {
                try (PreparedStatement st = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE device_id=?")) {
                    st.setString(1, deviceId);
                    st.executeUpdate();
                } catch (SQLException e) {
                    throw new StoreException("清空运行态编辑失败: " + e.getMessage(), e);
                }
            }
            return null;
        });
    }
}
