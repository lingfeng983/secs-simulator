package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;

/**
 * SECS-II 取值适配。
 *
 * <p>协议层的 {@code getInt(int... indices)} 虽然写成变长参数，但实现里
 * <b>必须至少给一个下标</b>（内部 {@code LinkedList.removeLast()}），
 * 无参调用会抛 {@code NoSuchElementException}——这个异常看起来像"消息为空"，
 * 实际是"少写了 0"。把这条约定收口在这里，避免每个调用点各写一遍魔法下标。
 * </p>
 */
public final class Secs2Read {

    /** 标量数据项在其自身值数组中的下标。 */
    private static final int SCALAR_INDEX = 0;

    private Secs2Read() {
    }

    /**
     * 读取整型。
     *
     * @param item 数据项
     * @return 整型值
     * @throws Secs2Exception 类型不符
     */
    public static int asInt(Secs2 item) throws Secs2Exception {
        return item.getInt(SCALAR_INDEX);
    }

    /**
     * 读取单字节（ACK 码之类）。
     *
     * @param item 数据项
     * @return 字节值
     * @throws Secs2Exception 类型不符
     */
    public static int asByte(Secs2 item) throws Secs2Exception {
        return item.getByte(SCALAR_INDEX) & 0xFF;
    }

    /**
     * 读取浮点。
     *
     * @param item 数据项
     * @return 浮点值
     * @throws Secs2Exception 类型不符
     */
    public static double asDouble(Secs2 item) throws Secs2Exception {
        return item.getDouble(SCALAR_INDEX);
    }
}
