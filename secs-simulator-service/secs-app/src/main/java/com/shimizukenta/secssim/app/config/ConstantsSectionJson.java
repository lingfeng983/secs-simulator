package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.core.value.DataSource;

/**
 * {@code equipment.constants} 段（设备常量 ECV）的写回，以及数据项 {@code source} 键的写回。
 */
final class ConstantsSectionJson {

    private ConstantsSectionJson() {
    }

    /**
     * 设备常量数组。
     *
     * @param constants 常量（按 id 排序后写出）
     * @param b         构造器
     * @return JSON 数组
     */
    static JsonHub toJson(Collection<EquipmentConstantDef> constants, JsonHubBuilder b) {
        return toJson(constants, Map.of(), b);
    }

    /**
     * 设备常量数组（带值来源）。
     *
     * @param constants 常量
     * @param sources   ECID → 值定义
     * @param b         构造器
     * @return JSON 数组
     */
    static JsonHub toJson(Collection<EquipmentConstantDef> constants,
                          Map<Integer, DataSource> sources, JsonHubBuilder b) {
        List<JsonHub> items = new ArrayList<>();
        List<EquipmentConstantDef> ordered = new ArrayList<>(constants);
        ordered.sort(Comparator.comparingInt(EquipmentConstantDef::id));

        for (EquipmentConstantDef def : ordered) {
            List<JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("id", def.id()));
            pairs.add(b.pair("name", def.name()));
            pairs.add(b.pair("type", def.type().name()));
            pairs.add(b.pair("value", def.value()));
            if (!def.unit().isEmpty()) {
                pairs.add(b.pair("unit", def.unit()));
            }
            addItemSource(pairs, sources.get(def.id()), b);
            items.add(b.object(pairs));
        }
        return b.array(items);
    }

    /**
     * 把一个数据项的值定义写回 JSON（v7）。
     *
     * <p>没有 source 的项不写这个键：旧配置因此保持原样，不会凭空多出字段。</p>
     *
     * @param pairs  该数据项的键值对
     * @param source 值定义（可空）
     * @param b      构造器
     */
    static void addItemSource(List<JsonObjectPair> pairs, DataSource source, JsonHubBuilder b) {
        if (source == null) {
            return;
        }
        pairs.add(b.pair("source", DataSourceJson.toJson(source, b)));
    }
}