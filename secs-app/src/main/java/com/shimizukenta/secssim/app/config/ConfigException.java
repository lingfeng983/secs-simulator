package com.shimizukenta.secssim.app.config;

/**
 * 配置错误，带字段路径。
 */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String path;

    /**
     * 创建异常。
     *
     * @param path    字段路径（如 {@code $.equipment.units[0].svids[1].type}）
     * @param message 说明
     */
    public ConfigException(String path, String message) {
        super(message + "（字段: " + path + "）");
        this.path = path;
    }

    /**
     * 字段路径。
     *
     * @return 路径
     */
    public String path() {
        return this.path;
    }
}
