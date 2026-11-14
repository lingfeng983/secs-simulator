package com.shimizukenta.secssim.core.compile;

/**
 * 一条配置问题。
 *
 * @param severity 级别
 * @param code     机器可读的稳定代码（用于测试与前端高亮）
 * @param message  人可读的说明
 */
public record Issue(Severity severity, String code, String message) {

    /**
     * 创建一条错误。
     *
     * @param code    代码
     * @param message 说明
     * @return 问题
     */
    public static Issue error(String code, String message) {
        return new Issue(Severity.ERROR, code, message);
    }

    /**
     * 创建一条提示。
     *
     * @param code    代码
     * @param message 说明
     * @return 问题
     */
    public static Issue warning(String code, String message) {
        return new Issue(Severity.WARNING, code, message);
    }

    @Override
    public String toString() {
        return this.severity + " [" + this.code + "] " + this.message;
    }
}
