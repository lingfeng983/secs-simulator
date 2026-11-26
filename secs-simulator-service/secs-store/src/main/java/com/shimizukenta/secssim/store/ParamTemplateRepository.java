package com.shimizukenta.secssim.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 参数模板仓储（config.db 的 param_template）：所有设备共享的「参数变化逻辑」库。
 *
 * <p>模板体是自包含的变化逻辑（单曲线或 曲线+组合表达式，不绑定 SVID）；
 * 设备配置时把模板体复制进自己的配置文档（复制快照语义），之后修改库
 * 不影响已配置设备。存储模式与 {@link TemplateRepository} 一致：
 * 内置（只读种子）+ 自定义统一进库，软删。</p>
 */
public final class ParamTemplateRepository {

    /** 参数模板条目。 */
    public record ParamTemplateView(String id, String name, String description,
                                    String source, boolean readonly, String body,
                                    long createdAt, long updatedAt) {
    }

    private final Db db;

    /** @param db 双库门面 */
    public ParamTemplateRepository(Db db) {
        this.db = db;
    }

    /** 全部未删除模板（内置在前，再按更新时间倒序）。 */
    public List<ParamTemplateView> list() {
        try (Connection connection = this.db.readConfig();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT id, name, description, source, readonly, body, created_at, updated_at "
                             + "FROM param_template WHERE deleted_at IS NULL "
                             + "ORDER BY source DESC, updated_at DESC, id")) {
            try (ResultSet rs = st.executeQuery()) {
                List<ParamTemplateView> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toView(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new StoreException("列参数模板失败: " + e.getMessage(), e);
        }
    }

    /** 按 id 取模板。 */
    public Optional<ParamTemplateView> find(String id) {
        try (Connection connection = this.db.readConfig();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT id, name, description, source, readonly, body, created_at, updated_at "
                             + "FROM param_template WHERE id=? AND deleted_at IS NULL")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(toView(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("查参数模板失败: " + e.getMessage(), e);
        }
    }

    /** 读模板体原文。 */
    public Optional<String> body(String id) {
        return this.find(id).map(ParamTemplateView::body);
    }

    /**
     * 新建自定义模板（body 由调用方校验过；同 id 冲突回 CONFLICT）。
     */
    public void createCustom(String id, String name, String description, String body) {
        Objects.requireNonNull(id, "id");
        long now = System.currentTimeMillis();
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO param_template(id, name, description, body, source, readonly, "
                            + "created_at, updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
                st.setString(1, id);
                st.setString(2, name);
                st.setString(3, description);
                st.setString(4, body);
                st.setString(5, "custom");
                st.setInt(6, 0);
                st.setLong(7, now);
                st.setLong(8, now);
                st.executeUpdate();
            } catch (SQLException e) {
                if (ConfigRepository.isUniqueViolation(e)) {
                    throw StoreException.conflict("已经有同 id 的参数模板: " + id);
                }
                throw new StoreException("新建参数模板失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 保存自定义模板（名称/说明/体都可改）；内置模板（readonly）拒绝。 */
    public void saveCustom(String id, String name, String description, String body) {
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT readonly FROM param_template WHERE id=? AND deleted_at IS NULL")) {
                st.setString(1, id);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw StoreException.notFound("参数模板不存在: " + id);
                    }
                    if (rs.getInt(1) == 1) {
                        throw StoreException.conflict("内置参数模板只读，请另存为新模板: " + id);
                    }
                }
            } catch (SQLException e) {
                throw new StoreException("查参数模板失败: " + e.getMessage(), e);
            }
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE param_template SET name=?, description=?, body=?, updated_at=? WHERE id=?")) {
                st.setString(1, name);
                st.setString(2, description);
                st.setString(3, body);
                st.setLong(4, System.currentTimeMillis());
                st.setString(5, id);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("保存参数模板失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** 删除自定义模板；内置拒绝。 */
    public void deleteCustom(String id) {
        this.db.writeConfig(connection -> {
            try (PreparedStatement st = connection.prepareStatement(
                    "SELECT readonly FROM param_template WHERE id=? AND deleted_at IS NULL")) {
                st.setString(1, id);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw StoreException.notFound("参数模板不存在: " + id);
                    }
                    if (rs.getInt(1) == 1) {
                        throw StoreException.conflict("内置参数模板不能删除: " + id);
                    }
                }
            } catch (SQLException e) {
                throw new StoreException("查参数模板失败: " + e.getMessage(), e);
            }
            try (PreparedStatement st = connection.prepareStatement(
                    "UPDATE param_template SET deleted_at=? WHERE id=?")) {
                st.setLong(1, System.currentTimeMillis());
                st.setString(2, id);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("删除参数模板失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    /**
     * 播种一枚内置模板（启动用）：已存在同 id 的行直接跳过。
     *
     * <p>存在性检查不带软删过滤，理由同 {@link TemplateRepository#seed}：
     * 软删同 id 行若被检查放行，INSERT 必然撞全表主键。</p>
     *
     * @return true = 本次播种；false = 已存在（含软删）被跳过
     */
    public boolean seed(String id, String name, String description, String body) {
        long now = System.currentTimeMillis();
        return this.db.writeConfig(connection -> {
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT 1 FROM param_template WHERE id=?")) {
                check.setString(1, id);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            } catch (SQLException e) {
                throw new StoreException("查参数模板失败: " + e.getMessage(), e);
            }
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO param_template(id, name, description, body, source, readonly, "
                            + "created_at, updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
                st.setString(1, id);
                st.setString(2, name);
                st.setString(3, description);
                st.setString(4, body);
                st.setString(5, "builtin");
                st.setInt(6, 1);
                st.setLong(7, now);
                st.setLong(8, now);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("播种参数模板失败: " + e.getMessage(), e);
            }
            return true;
        });
    }

    private static ParamTemplateView toView(ResultSet rs) throws SQLException {
        return new ParamTemplateView(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5) == 1, rs.getString(6), rs.getLong(7), rs.getLong(8));
    }

    /**
     * 下线已从内置目录里移除的内置模板（软删）。
     *
     * <p>只动 {@code readonly=1} 的行：自定义模板、以及设备配置里已经复制走的
     * 模板体快照都不受影响——快照是复制语义，本来就不依赖库里的那一行。</p>
     *
     * @param keepIds 当前内置目录的 id 集合
     * @return 本次下线的条数
     */
    public int pruneBuiltin(Collection<String> keepIds) {
        Objects.requireNonNull(keepIds, "keepIds");
        long now = System.currentTimeMillis();
        return this.db.writeConfig(connection -> {
            StringBuilder sql = new StringBuilder(
                    "UPDATE param_template SET deleted_at=? WHERE deleted_at IS NULL AND readonly=1");
            if (!keepIds.isEmpty()) {
                sql.append(" AND id NOT IN (")
                        .append(String.join(",", Collections.nCopies(keepIds.size(), "?")))
                        .append(')');
            }
            try (PreparedStatement st = connection.prepareStatement(sql.toString())) {
                st.setLong(1, now);
                int index = 2;
                for (String id : keepIds) {
                    st.setString(index++, id);
                }
                return st.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("下线旧内置参数模板失败: " + e.getMessage(), e);
            }
        });
    }
}