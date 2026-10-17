package com.shimizukenta.secssim.core.template;

/**
 * 模板图画不出来（或画出来不能用）。
 *
 * <p>{@link #code()} 是稳定的机器可读标识，前端按它高亮具体字段。
 * </p>
 */
public class GraphException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * 创建异常。
     *
     * @param code    稳定代码
     * @param message 说明
     */
    public GraphException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 稳定代码。
     *
     * @return 代码
     */
    public String code() {
        return this.code;
    }
}
