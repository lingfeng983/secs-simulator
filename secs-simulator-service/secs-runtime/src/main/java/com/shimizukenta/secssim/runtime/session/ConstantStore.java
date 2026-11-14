package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;

/**
 * 设备常量表：ECID → 常量。
 *
 * <p>主机用 S2F15 改常量，设备这边要留住改后的值；落盘回调直接接在
 * 协作者（HostCommandHandler）上，这里只负责快照读取。</p>
 */
final class ConstantStore {

    /** 当前常量表。 */
    private final Map<Integer, EquipmentConstantDef> constants;

    ConstantStore(Map<Integer, EquipmentConstantDef> constants) {
        this.constants = constants;
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
}
