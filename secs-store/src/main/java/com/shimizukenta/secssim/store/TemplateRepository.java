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
 * 设备模板仓储（config.db 的 device_template）：内置（只读种子）+ 自定义统一进库。
 *
 * <p>取代 {@code templates/<id>.json} 文件存储（设计 §2.2.1）；迁移时自定义模板
 * 原文导入、内置模板按 id 播种（已存在则跳过，不覆盖用户可能的同名自定义）。</p>
 */
public final class TemplateRepository {

    /** 模板条目（与 v1 的 TemplateStore.Entry 对齐，便于门面直通）。 */
    public record TemplateView(String id, String label, String description,
                               String source, boolean readonly, String body,
                               long createdAt, long updatedAt) {
    }

    private final Db db;

    /** @param db 双库门面 */
    public TemplateRepository(Db db) {
        this.db = db;
    }

    /** 全部未删除模板（内置在前）。 */
    public List<TemplateView> list() {
        try (Connection connection = this.db.readConfig();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT id, label, description, source, readonly, body, created_at, updated_at "
                             + "FROM device_template WHERE deleted_at IS NULL "
                             + "ORDER BY source DESC, id")) {
            try (ResultSet rs = st.executeQuery()) {
                List<TemplateView> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toView(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new StoreException("列模板失败: " + e.getMessage(), e);
        }
    }

    /** 读模板原文。 */
    public Optional<String> body(String id) {
        try (Connection connection = this.db.readConfig();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT body FROM device_template WHERE id=? AND deleted_at IS NULL")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("读模板失败: " + e.getMessage(), e);
        }
    }

    /** 按模板 id 取元信息。 */
    public Optional<TemplateView> find(String id) {
        try (Connection connection = this.db.readConfig();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT id, label, description, source, readonly, body, created_at, updated_at "
                             + "FROM device_template WHERE id=? AND deleted_at IS NULL")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(toView(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("查模板失败: " + e.getMessage(), e);
        }
    }

    /** 新建自定义模板（body 由调用方校验过；同 id 冲突回 CONFLICT）。 */
    public void createCustom(String id, String label, String description, String body) {
        Objects.requireNonNull(id, "id");
        long now = System.currentTimeMillis();
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO device_template(id, label, description, source, readonly, body, "
                            + "created_at, updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
                st.setString(1, id);
                st.setString(2, label);
                st.setString(3, description);
                st.setString(4, "custom");
                st.setInt(5, 0);
                st.setString(6, body);
                st.setLong(7, now);
                st.setLong(8, now);
                st.executeUpdate();
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().contains("PRIMARY")) {
                    throw StoreException.conflict("已经有同 id 的模板: " + id);
                }
                throw new StoreException("新建模板失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 保存自定义模板正文；内置模板（readonly）拒绝。 */
    public void saveCustom(String id, String body) {
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT readonly FROM device_template WHERE id=? AND deleted_at IS NULL")) {
                st.setString(1, id);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw StoreException.notFound("模板不存在: " + id);
                    }
                    if (rs.getInt(1) == 1) {
                        throw StoreException.conflict("内置模板只读，请另存为新 id: " + id);
                    }
                }
            } catch (SQLException e) {
                throw new StoreException("读模板失败: " + e.getMessage(), e);
            }
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE device_template SET body=?, updated_at=? WHERE id=?")) {
                st.setString(1, body);
                st.setLong(2, System.currentTimeMillis());
                st.setString(3, id);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("保存模板失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 删除自定义模板；内置拒绝。 */
    public void deleteCustom(String id) {
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT readonly FROM device_template WHERE id=? AND deleted_at IS NULL")) {
                st.setString(1, id);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw StoreException.notFound("模板不存在: " + id);
                    }
                    if (rs.getInt(1) == 1) {
                        throw StoreException.conflict("内置模板不能删除: " + id);
                    }
                }
            } catch (SQLException e) {
                throw new StoreException("读模板失败: " + e.getMessage(), e);
            }
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE device_template SET deleted_at=? WHERE id=?")) {
                st.setLong(1, System.currentTimeMillis());
                st.setString(2, id);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("删除模板失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /**
     * 播种一枚模板（迁移 / 启动用）：已存在同 id 的行直接跳过。
     *
     * @param id          模板 id
     * @param label       名称
     * @param description 说明
     * @param builtin     是否内置（只读）
     * @param body        正文
     * @return true = 本次播种；false = 已存在被跳过
     */
    public boolean seed(String id, String label, String description, boolean builtin, String body) {
        long now = System.currentTimeMillis();
        return this.db.writeConfig(connection -> {
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT 1 FROM device_template WHERE id=? AND deleted_at IS NULL")) {
                check.setString(1, id);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            } catch (SQLException e) {
                throw new StoreException("查模板失败: " + e.getMessage(), e);
            }
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO device_template(id, label, description, source, readonly, body, "
                            + "created_at, updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
                st.setString(1, id);
                st.setString(2, label);
                st.setString(3, description);
                st.setString(4, builtin ? "builtin" : "custom");
                st.setInt(5, builtin ? 1 : 0);
                st.setString(6, body);
                st.setLong(7, now);
                st.setLong(8, now);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("播种模板失败: " + e.getMessage(), e);
            }
            return true;
        });
    }

    private static TemplateView toView(ResultSet rs) throws SQLException {
        return new TemplateView(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5) == 1, rs.getString(6), rs.getLong(7), rs.getLong(8));
    }
}
