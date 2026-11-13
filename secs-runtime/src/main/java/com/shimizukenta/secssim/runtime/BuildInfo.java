package com.shimizukenta.secssim.runtime;

/**
 * 构建信息。
 *
 * <p>原生镜像里 {@code Package#getImplementationVersion()} 常常为空，
 * 因此保留一个显式常量作为兜底；发版脚本负责让两者一致。
 * </p>
 */
public final class BuildInfo {

    /** 当前版本。 */
    public static final String VERSION = "3.0.1";

    private BuildInfo() {
    }

    /**
     * 读取版本：优先 manifest，回退到常量。
     *
     * @return 版本号
     */
    public static String version() {
        String fromManifest = BuildInfo.class.getPackage().getImplementationVersion();
        return fromManifest == null || fromManifest.isBlank() ? VERSION : fromManifest;
    }
}
