package com.shimizukenta.secssim.app.session;

/**
 * HSMS-SS 超时参数（SEMI E5；T1/T2/T4 只用于 SECS-I，这里不暴露）。
 *
 * <p>逐键可选：每个字段为 {@code null} 时用协议层默认值
 * （T3=45s、T5=10s、T6=5s、T7=10s、T8=6s）；写了就必须大于 0。
 * 配置里只写需要的键即可，便于现场一眼看出“哪一条被显式改过”。</p>
 *
 * @param t3  回复超时（秒）
 * @param t5  重连间隔（秒）
 * @param t6  控制事务超时（秒）
 * @param t7  未 SELECT 连接的超时（秒）
 * @param t8  网络字符间超时（秒）
 */
public record HsmsTimeouts(Double t3, Double t5, Double t6, Double t7, Double t8) {

    /** 全默认：协议层内置值。 */
    public static final HsmsTimeouts DEFAULTS = new HsmsTimeouts(null, null, null, null, null);

    /**
     * 校验并补默认。
     *
     * @param t3 T3
     * @param t5 T5
     * @param t6 T6
     * @param t7 T7
     * @param t8 T8
     */
    public HsmsTimeouts {
        if (t3 != null && t3 <= 0) {
            throw new IllegalArgumentException("T3 必须大于 0");
        }
        if (t5 != null && t5 <= 0) {
            throw new IllegalArgumentException("T5 必须大于 0");
        }
        if (t6 != null && t6 <= 0) {
            throw new IllegalArgumentException("T6 必须大于 0");
        }
        if (t7 != null && t7 <= 0) {
            throw new IllegalArgumentException("T7 必须大于 0");
        }
        if (t8 != null && t8 <= 0) {
            throw new IllegalArgumentException("T8 必须大于 0");
        }
    }
}
