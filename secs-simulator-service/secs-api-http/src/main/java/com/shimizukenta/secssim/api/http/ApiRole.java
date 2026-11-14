package com.shimizukenta.secssim.api.http;

import java.util.Locale;

/**
 * API 角色。
 *
 * <p>枚举常量按能力<b>递增</b>排列，因此 {@link #ordinal()} 本身就是权限大小：
 * 判断"某角色是否至少达到 X"直接比大小，不必再维护一张平行的权限表。
 * 代价是<b>不能随意调整常量顺序</b>，调整等于给所有已部署的账号降级/升级。
 * </p>
 *
 * <p>分级依据是"这个操作会不会改变设备状态"：
 * 读不会，触发 CEID 与报警会，关停会。
 * </p>
 */
public enum ApiRole {

    /** 只读：health 详情、会话列表、报警与终端消息查询、曲线预览、SSE 报文流。 */
    VIEWER,

    /** 可下发：在 viewer 之上，额外允许手动触发 CEID 与置位/清除报警（S5F1）。 */
    OPERATOR,

    /** 可停机：在 operator 之上，额外允许优雅停机。 */
    ADMIN;

    /**
     * 从配置文本解析角色。
     *
     * <p>大小写不敏感：这类文件通常是人手写的，{@code Viewer} 和 {@code viewer}
     * 不该因为大小写不同就被拒。
     * </p>
     *
     * @param id 角色文本
     * @return 角色
     * @throws IllegalArgumentException 文本不识别
     */
    public static ApiRole fromId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("角色为空");
        }
        String normalized = id.trim().toUpperCase(Locale.ROOT);
        for (ApiRole role : values()) {
            if (role.name().equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知角色: " + id);
    }

    /**
     * 是否至少具备 {@code required} 的能力。
     *
     * @param required 需要的最低角色
     * @return {@code true} 表示具备
     */
    public boolean atLeast(ApiRole required) {
        return this.ordinal() >= required.ordinal();
    }
}
