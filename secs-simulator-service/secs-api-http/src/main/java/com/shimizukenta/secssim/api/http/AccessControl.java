package com.shimizukenta.secssim.api.http;

/**
 * 访问控制：<b>内部工具，不做鉴权</b>。
 *
 * <p>本项目是车间/实验室内部使用的模拟器，远程访问一律直连：没有令牌、
 * 没有账号文件、没有口令比对。历史上存在过的单令牌模式与账号文件模式
 * 已经移除，避免"要连服务器还得先配一个口令"这类与使用场景不符的门槛。</p>
 *
 * <p>保留 {@link #open()} 与 {@link Principal} 是为了让各处理器里的
 * {@code requireRole(...)} 保持"恒为管理员、直接放行"的语义——将来如果
 * 需要重新接入鉴权，只改这个类，不用回头改每个 handler。</p>
 */
public final class AccessControl {

    /** 直连访问下唯一的身份。 */
    private static final Principal LOCAL = new Principal("local", ApiRole.ADMIN);

    /** 无状态单例：本工具不保存任何凭据。 */
    private static final AccessControl OPEN = new AccessControl();

    private AccessControl() {
    }

    /**
     * 直连访问：任何请求都视为本机管理员。
     *
     * @return 访问控制
     */
    public static AccessControl open() {
        return OPEN;
    }

    /**
     * 认证。直连访问下没有凭据可校验。
     *
     * @return 恒为 {@code local/ADMIN}
     */
    public Principal authenticate() {
        return LOCAL;
    }

    /**
     * 鉴权通过的调用方。
     *
     * @param name 账号名
     * @param role 角色
     */
    public record Principal(String name, ApiRole role) {
    }
}
