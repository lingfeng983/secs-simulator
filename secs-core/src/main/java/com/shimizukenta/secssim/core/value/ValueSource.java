package com.shimizukenta.secssim.core.value;

import java.util.OptionalDouble;
import java.util.Set;

/**
 * 值的来源。
 *
 * <p>把"谁产生数据"抽象出来，让会话既能被单条曲线驱动（M2），
 * 也能被模板 DAG 驱动（M3），而上层报文逻辑完全不需要改。
 * </p>
 */
public interface ValueSource {

    /**
     * 求某一时刻某个 SVID 的值。
     *
     * @param svid 绝对 SVID
     * @param tMs  时刻
     * @return 值
     */
    OptionalDouble valueAt(int svid, long tMs);

    /**
     * 能提供值的 SVID 集合。
     *
     * @return SVID 集合
     */
    Set<Integer> svids();
}
