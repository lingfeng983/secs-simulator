package com.shimizukenta.secssim.api.http;

/**
 * 一个 API 账号。
 *
 * <p>{@code secret} 是<b>明文口令</b>：服务端要在运行时拿它比对，因此不能只存哈希。
 * 代价是账号文件本身就是凭据文件——它和私钥同级，必须靠文件系统权限保护，
 * 不能进版本库、不能打进分发包。
 * </p>
 *
 * @param name   账号名（用于日志与 {@code /api/v1/health} 的身份回显，不参与鉴权）
 * @param role   角色
 * @param secret 口令
 */
public record ApiAccount(String name, ApiRole role, String secret) {

    /**
     * 校验并规范化。
     *
     * @throws IllegalArgumentException 字段缺失
     */
    public ApiAccount {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("账号名为空");
        }
        if (role == null) {
            throw new IllegalArgumentException("角色为空: " + name);
        }
        if (secret == null || secret.isBlank()) {
            /* 空口令会让"没带凭据"和"带了个空口令"变得无法区分 */
            throw new IllegalArgumentException("口令为空: " + name);
        }
        name = name.trim();
        secret = secret.trim();
    }
}
