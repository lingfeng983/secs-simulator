package com.shimizukenta.secssim.core.template;

import com.shimizukenta.secssim.core.curve.Rng;

/**
 * 模板求值时能看到的外部世界。
 */
public interface EvalContext {

    /**
     * 当前时刻（毫秒）。
     *
     * @return 毫秒
     */
    long timeMs();

    /**
     * 当前时刻（秒），供表达式里的 {@code t} 使用。
     *
     * @return 秒
     */
    default double timeSec() {
        return this.timeMs() / 1000.0;
    }

    /**
     * 取参数（已按 5 级覆盖解析）。
     *
     * @param name 参数名
     * @return 值
     */
    double param(String name);

    /**
     * 取其它实例的输出。
     *
     * @param reference 形如 {@code 实例.输出}
     * @return 值
     */
    double input(String reference);

    /**
     * 该实例的确定性噪声源。
     *
     * @return 噪声源
     */
    Rng rng();
}
