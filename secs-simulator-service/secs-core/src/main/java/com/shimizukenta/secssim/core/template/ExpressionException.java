package com.shimizukenta.secssim.core.template;

/**
 * 表达式解析或求值失败。
 */
public class ExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 创建异常。
     *
     * @param message 说明
     */
    public ExpressionException(String message) {
        super(message);
    }

    /**
     * 创建带原因的异常。
     *
     * @param message 说明
     * @param cause   原因
     */
    public ExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
