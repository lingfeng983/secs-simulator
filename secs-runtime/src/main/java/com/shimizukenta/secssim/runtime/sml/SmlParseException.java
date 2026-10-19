package com.shimizukenta.secssim.runtime.sml;

/**
 * SML 解析错误。
 *
 * <p>带位置信息：手写报文最容易错在少一个 {@code >} 或引号，
 * 只说"格式错误"等于没说。</p>
 */
public final class SmlParseException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * 创建解析错误。
     *
     * @param message 说明
     */
    public SmlParseException(String message) {
        super(message);
    }
}
