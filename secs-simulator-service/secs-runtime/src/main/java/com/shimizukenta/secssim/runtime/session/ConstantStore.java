package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;

/**
 * 设备常量表：ECID → 常量。
 *
 * <p>主机用 S2F15 改常量，设备这边要留住改后的值，也要在会话重载后
 * 写回配置文件。落盘由上层回调完成，这里只保证传出的是一份快照。</p>
 */
final class ConstantStore {

    /** 当前常量表。 */
    private final Map<Integer, EquipmentConstantDef> constants;

    /** 落盘回调。 */
    private volatile HsmsSessionRuntime.ConstantSaver saver;

    ConstantStore(Map<Integer, EquipmentConstantDef> constants) {
        this.constants = constants;
    }

    /**
     * 设置常量落盘回调（由 {@code SessionLauncher} 在启动会话时接入）。
     *
     * @param saver 回调；{@code null} 表示不落盘
     */
    void saver(HsmsSessionRuntime.ConstantSaver saver) {
        this.saver = saver;
    }

    /** 落盘回调（可能为 {@code null}）。 */
    HsmsSessionRuntime.ConstantSaver saver() {
        return this.saver;
    }

    /**
     * 当前设备常量快照（按 ECID 升序）。
     *
     * @return 常量列表
     */
    List<EquipmentConstantDef> snapshot() {
        List<EquipmentConstantDef> list;
        synchronized (this.constants) {
            list = new ArrayList<>(this.constants.values());
        }
        list.sort(Comparator.comparingInt(EquipmentConstantDef::id));
        return list;
    }

    /**
     * 把当前常量表交给上层写回配置文件。
     *
     * @throws IOException 写盘失败
     */
    void persist() throws IOException {
        HsmsSessionRuntime.ConstantSaver current = this.saver;
        if (current != null) {
            current.save(this.snapshotMap());
        }
    }

    /** 常量表快照（ECID → 常量）。 */
    Map<Integer, EquipmentConstantDef> snapshotMap() {
        synchronized (this.constants) {
            return new java.util.LinkedHashMap<>(this.constants);
        }
    }
}
