package com.shimizukenta.secssim.core.compile;

import com.shimizukenta.secssim.core.domain.ValueType;

/**
 * 已解析出绝对 ID 的 SVID。
 *
 * @param id    绝对 SVID
 * @param owner 归属单元名，设备级为 {@code GLOBAL}
 * @param slot  符号名
 * @param type  类型
 */
public record ResolvedSvid(int id, String owner, String slot, ValueType type) {

    /** 设备级共享项的所有者标记。 */
    public static final String GLOBAL_OWNER = "GLOBAL";
}
