package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.secssim.core.domain.CeidDef;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.GlobalCeidDef;
import com.shimizukenta.secssim.core.domain.GlobalSvidDef;
import com.shimizukenta.secssim.core.domain.IdRange;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.core.domain.ReportDef;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitId;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.value.DataSource;

/**
 * {@code equipment} 段（含 {@code units}/{@code svids}/{@code reports}/{@code ceids}/{@code idSpace}）的读写。
 */
final class EquipmentSectionJson {

    private static final String ROOT = "$";

    private EquipmentSectionJson() {
    }

    /* ---------- 解析 ---------- */

    static EquipmentSpec parse(JsonHub root, int deviceId) {
        String path = ROOT + ".equipment";
        JsonHub equipment = JsonAccess.object(root, "equipment", ROOT);

        String name = JsonAccess.string(equipment, "name", path);
        IdSpacePolicy idSpace = parseIdSpace(JsonAccess.object(equipment, "idSpace", path), path + ".idSpace");

        List<GlobalSvidDef> globalSvids = new ArrayList<>();
        if (equipment.containsKey("globalSvids")) {
            List<JsonHub> items = JsonAccess.elements(JsonAccess.array(equipment, "globalSvids", path), path + ".globalSvids");
            for (int i = 0; i < items.size(); i++) {
                String itemPath = path + ".globalSvids[" + i + "]";
                JsonHub item = items.get(i);
                globalSvids.add(new GlobalSvidDef(
                        JsonAccess.integer(item, "id", itemPath),
                        JsonAccess.string(item, "name", itemPath),
                        parseType(JsonAccess.string(item, "type", itemPath), itemPath + ".type"),
                        JsonAccess.optionalDouble(item, "min", itemPath).orElse(Double.NEGATIVE_INFINITY),
                        JsonAccess.optionalDouble(item, "max", itemPath).orElse(Double.POSITIVE_INFINITY)));
            }
        }

        List<GlobalCeidDef> globalCeids = new ArrayList<>();
        if (equipment.containsKey("globalCeids")) {
            List<JsonHub> items = JsonAccess.elements(JsonAccess.array(equipment, "globalCeids", path), path + ".globalCeids");
            for (int i = 0; i < items.size(); i++) {
                String itemPath = path + ".globalCeids[" + i + "]";
                JsonHub item = items.get(i);
                globalCeids.add(new GlobalCeidDef(
                        JsonAccess.integer(item, "id", itemPath),
                        JsonAccess.string(item, "name", itemPath),
                        readStringList(item, "reports", itemPath)));
            }
        }

        /* 设备常量（ECV）：可选段，旧配置没有就当"没有常量"，
           不必升级 schemaVersion——S2F13 回空列表、S2F15 一律拒绝即可。 */
        List<EquipmentConstantDef> constants = new ArrayList<>();
        if (equipment.containsKey("constants")) {
            List<JsonHub> items = JsonAccess.elements(
                    JsonAccess.array(equipment, "constants", path), path + ".constants");
            for (int i = 0; i < items.size(); i++) {
                String itemPath = path + ".constants[" + i + "]";
                JsonHub item = items.get(i);
                constants.add(new EquipmentConstantDef(
                        JsonAccess.integer(item, "id", itemPath),
                        JsonAccess.string(item, "name", itemPath),
                        parseType(JsonAccess.string(item, "type", itemPath), itemPath + ".type"),
                        JsonAccess.optionalDouble(item, "value", itemPath)
                                .orElseThrow(() -> new ConfigException(itemPath + ".value",
                                        "设备常量必须给 value")),
                        JsonAccess.optionalString(item, "unit", itemPath).orElse("")));
            }
        }

        List<JsonHub> unitItems = JsonAccess.elements(
                JsonAccess.array(equipment, "units", path), path + ".units");

        if (unitItems.isEmpty()) {
            throw new ConfigException(path + ".units", "设备至少需要一个单元");
        }

        List<UnitSpec> units = new ArrayList<>(unitItems.size());
        for (int i = 0; i < unitItems.size(); i++) {
            units.add(parseUnit(unitItems.get(i), path + ".units[" + i + "]"));
        }

        return new EquipmentSpec(name, deviceId, units, globalSvids, globalCeids, idSpace, constants);
    }

    private static IdSpacePolicy parseIdSpace(JsonHub jh, String path) {
        IdSpacePolicy defaults = IdSpacePolicy.defaults();

        List<IdRange> svidReserves = parseRanges(jh, "reserve", path);
        /* 旧文件只有 reserve：那时三类保留区间共用一份，读回来保持一致 */
        List<IdRange> ceidReserves = jh.containsKey("reserveCeid")
                ? parseRanges(jh, "reserveCeid", path)
                : svidReserves;
        List<IdRange> reportReserves = jh.containsKey("reserveReport")
                ? parseRanges(jh, "reserveReport", path)
                : svidReserves;

        int svidBase = JsonAccess.optionalInt(jh, "svidBase", path).orElse(defaults.svidBase());
        int ceidBase = JsonAccess.optionalInt(jh, "ceidBase", path).orElse(defaults.ceidBase());
        int reportBase = JsonAccess.optionalInt(jh, "reportBase", path).orElse(defaults.reportBase());
        int svidStride = JsonAccess.optionalInt(jh, "svidStride", path).orElse(defaults.svidStride());
        int ceidStride = JsonAccess.optionalInt(jh, "ceidStride", path).orElse(defaults.ceidStride());
        int reportStride = JsonAccess.optionalInt(jh, "reportStride", path).orElse(defaults.reportStride());

        return new IdSpacePolicy(svidBase, ceidBase, reportBase, svidStride, ceidStride, reportStride,
                svidReserves, ceidReserves, reportReserves);
    }

    /** 解析一个保留区间数组；键不存在时返回空列表。 */
    private static List<IdRange> parseRanges(JsonHub jh, String key, String path) {
        List<IdRange> ranges = new ArrayList<>();
        if (!jh.containsKey(key)) {
            return ranges;
        }
        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(jh, key, path), path + "." + key);
        for (int i = 0; i < items.size(); i++) {
            String rangePath = path + "." + key + "[" + i + "]";
            JsonHub range = items.get(i);
            if (range.length() != 2) {
                throw new ConfigException(rangePath, "区间必须是 [from, to]");
            }
            ranges.add(new IdRange(exactInt(range.get(0), rangePath + "[0]"), exactInt(range.get(1), rangePath + "[1]")));
        }
        return ranges;
    }

    /** 保留段的端点必须是整数：{@code intValue()} 会把 0.5 静默截成 0。 */
    private static int exactInt(JsonHub value, String path) {
        OptionalLong asLong = value.optionalLong();
        if (asLong.isEmpty()) {
            throw new ConfigException(path, "应为整数");
        }
        double asDouble = value.optionalDouble().orElse(Double.NaN);
        if (!Double.isFinite(asDouble) || asDouble != (double) asLong.getAsLong()) {
            throw new ConfigException(path, "应为整数，不接受小数: " + value.toJson());
        }
        long l = asLong.getAsLong();
        if (l < 1 || l > Integer.MAX_VALUE) {
            throw new ConfigException(path, "应为 1.." + Integer.MAX_VALUE + " 的整数");
        }
        return (int) l;
    }

    private static UnitSpec parseUnit(JsonHub jh, String path) {
        int unitId = JsonAccess.integer(jh, "unitId", path);
        String name = JsonAccess.string(jh, "name", path);
        boolean injectUnitId = JsonAccess.bool(jh, "injectUnitId", true, path);

        List<SvidDef> svids = new ArrayList<>();
        List<JsonHub> svidItems = JsonAccess.elements(JsonAccess.array(jh, "svids", path), path + ".svids");
        for (int i = 0; i < svidItems.size(); i++) {
            svids.add(parseSvid(svidItems.get(i), path + ".svids[" + i + "]"));
        }

        List<ReportDef> reports = new ArrayList<>();
        if (jh.containsKey("reports")) {
            List<JsonHub> items = JsonAccess.elements(JsonAccess.array(jh, "reports", path), path + ".reports");
            for (int i = 0; i < items.size(); i++) {
                String itemPath = path + ".reports[" + i + "]";
                JsonHub item = items.get(i);
                reports.add(new ReportDef(
                        JsonAccess.integer(item, "offset", itemPath),
                        JsonAccess.string(item, "slot", itemPath),
                        readStringList(item, "svids", itemPath)));
            }
        }

        List<CeidDef> ceids = new ArrayList<>();
        if (jh.containsKey("ceids")) {
            List<JsonHub> items = JsonAccess.elements(JsonAccess.array(jh, "ceids", path), path + ".ceids");
            for (int i = 0; i < items.size(); i++) {
                String itemPath = path + ".ceids[" + i + "]";
                JsonHub item = items.get(i);
                ceids.add(new CeidDef(
                        JsonAccess.integer(item, "offset", itemPath),
                        JsonAccess.string(item, "slot", itemPath),
                        readStringList(item, "reports", itemPath)));
            }
        }

        return new UnitSpec(UnitId.of(unitId), name, svids, reports, ceids, injectUnitId);
    }

    private static SvidDef parseSvid(JsonHub jh, String path) {
        int offset = JsonAccess.integer(jh, "offset", path);
        String slot = JsonAccess.string(jh, "slot", path);
        ValueType type = parseType(JsonAccess.string(jh, "type", path), path + ".type");
        String unitSymbol = JsonAccess.optionalString(jh, "unit", path).orElse("");
        int decimals = JsonAccess.optionalInt(jh, "decimals", path).orElse(0);

        OptionalDouble min = JsonAccess.optionalDouble(jh, "min", path);
        OptionalDouble max = JsonAccess.optionalDouble(jh, "max", path);

        double lower = min.orElse(Double.NEGATIVE_INFINITY);
        double upper = max.orElse(Double.POSITIVE_INFINITY);

        if (lower > upper) {
            throw new ConfigException(path + ".min", "min " + lower + " 大于 max " + upper);
        }

        return new SvidDef(offset, slot, type, unitSymbol, decimals, lower, upper);
    }

    private static ValueType parseType(String name, String path) {
        try {
            return ValueType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path, "未知数据类型: " + name);
        }
    }

    private static List<String> readStringList(JsonHub parent, String key, String path) {
        List<String> values = new ArrayList<>();
        if (!parent.containsKey(key)) {
            return values;
        }

        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(parent, key, path), path + "." + key);
        for (int i = 0; i < items.size(); i++) {
            Optional<String> text = items.get(i).optionalString();
            if (text.isEmpty()) {
                throw new ConfigException(path + "." + key + "[" + i + "]", "应为字符串");
            }
            values.add(text.get());
        }
        return values;
    }

    /**
     * 解析每个 SVID 上的 {@code source}（schema v7），解析成绝对 SVID。
     *
     * @param root      配置根
     * @param equipment 设备定义（用于算绝对 ID）
     * @return 绝对 SVID → 值定义
     */
    static Map<Integer, DataSource> parseSvidSources(JsonHub root, EquipmentSpec equipment) {
        Map<Integer, DataSource> sources = new LinkedHashMap<>();
        List<UnitSpec> units = equipment.units();
        for (int i = 0; i < units.size(); i++) {
            int base = equipment.idSpace().svidBaseFor(i + 1);
            List<JsonHub> svids = svidJsonList(root, i);
            List<SvidDef> defs = units.get(i).svids();
            for (int k = 0; k < svids.size() && k < defs.size(); k++) {
                JsonHub item = svids.get(k);
                if (!item.containsKey("source")) {
                    continue;
                }
                String path = ROOT + ".equipment.units[" + i + "].svids[" + k + "].source";
                sources.put(defs.get(k).resolve(base),
                        DataSourceJson.parse(JsonAccess.object(item, "source", path), path));
            }
        }
        if (root.containsKey("equipment")) {
            JsonHub equipmentJh = JsonAccess.object(root, "equipment", ROOT);
            if (equipmentJh.containsKey("globalSvids")) {
                List<JsonHub> globals = JsonAccess.elements(
                        JsonAccess.array(equipmentJh, "globalSvids", ROOT + ".equipment"),
                        ROOT + ".equipment.globalSvids");
                List<GlobalSvidDef> defs = equipment.globalSvids();
                for (int k = 0; k < globals.size() && k < defs.size(); k++) {
                    JsonHub item = globals.get(k);
                    if (!item.containsKey("source")) {
                        continue;
                    }
                    String path = ROOT + ".equipment.globalSvids[" + k + "].source";
                    sources.put(defs.get(k).id(),
                            DataSourceJson.parse(JsonAccess.object(item, "source", path), path));
                }
            }
        }
        return sources;
    }

    /** 取某个单元的 svids 原始 JSON 列表（与解析出的定义一一对应）。 */
    private static List<JsonHub> svidJsonList(JsonHub root, int unitIndex) {
        JsonHub equipment = JsonAccess.object(root, "equipment", ROOT);
        List<JsonHub> units = JsonAccess.elements(
                JsonAccess.array(equipment, "units", ROOT + ".equipment"), ROOT + ".equipment.units");
        JsonHub unit = units.get(unitIndex);
        if (!unit.containsKey("svids")) {
            return List.of();
        }
        return JsonAccess.elements(JsonAccess.array(unit, "svids", ROOT + ".equipment.units"),
                ROOT + ".equipment.units[" + unitIndex + "].svids");
    }

    /**
     * 解析每个 ECID 上的 {@code source}（schema v7）：时间变化型设备常量。
     *
     * @param root      配置根
     * @param equipment 设备定义
     * @return ECID → 值定义
     */
    static Map<Integer, DataSource> parseEcidSources(JsonHub root, EquipmentSpec equipment) {
        if (!root.containsKey("equipment")) {
            return Map.of();
        }
        JsonHub equipmentJh = JsonAccess.object(root, "equipment", ROOT);
        if (!equipmentJh.containsKey("constants")) {
            return Map.of();
        }
        List<JsonHub> items = JsonAccess.elements(
                JsonAccess.array(equipmentJh, "constants", ROOT + ".equipment"),
                ROOT + ".equipment.constants");
        Map<Integer, DataSource> sources = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            JsonHub item = items.get(i);
            if (!item.containsKey("source")) {
                continue;
            }
            String path = ROOT + ".equipment.constants[" + i + "].source";
            int id = JsonAccess.integer(item, "id", path.substring(0, path.length() - ".source".length()));
            sources.put(id, DataSourceJson.parse(JsonAccess.object(item, "source", path), path));
        }
        return sources;
    }

    /* ---------- 写回 ---------- */

    static JsonHub toJson(EquipmentSpec equipment, JsonHubBuilder b) {
        return toJson(equipment, Map.of(), Map.of(), b);
    }

    static JsonHub toJson(EquipmentSpec equipment, Map<Integer, DataSource> svidSources,
                          Map<Integer, DataSource> ecidSources, JsonHubBuilder b) {
        List<JsonHub> units = new ArrayList<>();
        for (int i = 0; i < equipment.units().size(); i++) {
            units.add(unitJson(equipment.units().get(i),
                    equipment.idSpace().svidBaseFor(i + 1), svidSources, b));
        }

        List<JsonHub> globals = new ArrayList<>();
        for (GlobalSvidDef def : equipment.globalSvids()) {
            List<JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("id", def.id()));
            pairs.add(b.pair("name", def.name()));
            pairs.add(b.pair("type", def.type().name()));
            if (Double.isFinite(def.min())) {
                pairs.add(b.pair("min", def.min()));
            }
            if (Double.isFinite(def.max())) {
                pairs.add(b.pair("max", def.max()));
            }
            ConstantsSectionJson.addItemSource(pairs, svidSources.get(def.id()), b);
            globals.add(b.object(pairs));
        }

        List<JsonHub> globalCeids = new ArrayList<>();
        for (GlobalCeidDef def : equipment.globalCeids()) {
            globalCeids.add(b.object(
                    b.pair("id", def.id()),
                    b.pair("name", def.name()),
                    b.pair("reports", b.array(strings(def.reportNames(), b)))));
        }

        /* 设备常量要原样写回：S2F15 落盘后如果这段没保存，
           "重载后保留"就成了假的——文件里根本没有这些值。 */
        JsonHub constants = ConstantsSectionJson.toJson(equipment.constants(), ecidSources, b);

        return b.object(
                b.pair("name", equipment.name()),
                b.pair("idSpace", idSpaceJson(equipment.idSpace(), b)),
                b.pair("globalSvids", b.array(globals)),
                b.pair("globalCeids", b.array(globalCeids)),
                b.pair("constants", constants),
                b.pair("units", b.array(units)));
    }

    private static JsonHub idSpaceJson(IdSpacePolicy policy, JsonHubBuilder b) {
        /* 三个保留区间分别写：只写 reserve（SVID 的）会让 reservedCeid/report
           在"程序化构造策略 → 保存 → 重读"后丢失 */
        return b.object(
                b.pair("svidBase", policy.svidBase()),
                b.pair("ceidBase", policy.ceidBase()),
                b.pair("reportBase", policy.reportBase()),
                b.pair("svidStride", policy.svidStride()),
                b.pair("ceidStride", policy.ceidStride()),
                b.pair("reportStride", policy.reportStride()),
                b.pair("reserve", rangesJson(policy.reservedSvid(), b)),
                b.pair("reserveCeid", rangesJson(policy.reservedCeid(), b)),
                b.pair("reserveReport", rangesJson(policy.reservedReport(), b)));
    }

    private static JsonHub rangesJson(List<IdRange> ranges, JsonHubBuilder b) {
        List<JsonHub> items = new ArrayList<>();
        for (IdRange range : ranges) {
            items.add(b.array(b.number(range.from()), b.number(range.to())));
        }
        return b.array(items);
    }

    private static JsonHub unitJson(UnitSpec unit, int base, Map<Integer, DataSource> sources,
                                    JsonHubBuilder b) {
        List<JsonHub> svids = new ArrayList<>();
        for (SvidDef def : unit.svids()) {
            svids.add(svidJson(def, base, sources, b));
        }

        List<JsonHub> reports = new ArrayList<>();
        for (ReportDef def : unit.reports()) {
            reports.add(b.object(
                    b.pair("offset", def.offset()),
                    b.pair("slot", def.slot()),
                    b.pair("svids", b.array(strings(def.svidNames(), b)))));
        }

        List<JsonHub> ceids = new ArrayList<>();
        for (CeidDef def : unit.ceids()) {
            ceids.add(b.object(
                    b.pair("offset", def.offset()),
                    b.pair("slot", def.slot()),
                    b.pair("reports", b.array(strings(def.reportNames(), b)))));
        }

        return b.object(
                b.pair("unitId", unit.unitId().value()),
                b.pair("name", unit.name()),
                b.pair("injectUnitId", unit.injectUnitId()),
                b.pair("svids", b.array(svids)),
                b.pair("reports", b.array(reports)),
                b.pair("ceids", b.array(ceids)));
    }

    private static JsonHub svidJson(SvidDef def, int base, Map<Integer, DataSource> sources,
                                    JsonHubBuilder b) {
        List<JsonObjectPair> pairs = new ArrayList<>();
        pairs.add(b.pair("offset", def.offset()));
        pairs.add(b.pair("slot", def.slot()));
        pairs.add(b.pair("type", def.type().name()));
        pairs.add(b.pair("unit", def.unitSymbol()));
        pairs.add(b.pair("decimals", def.decimals()));

        if (Double.isFinite(def.min())) {
            pairs.add(b.pair("min", def.min()));
        }
        if (Double.isFinite(def.max())) {
            pairs.add(b.pair("max", def.max()));
        }
        ConstantsSectionJson.addItemSource(pairs, sources.get(def.resolve(base)), b);

        return b.object(pairs);
    }

    private static List<JsonHub> strings(List<String> values, JsonHubBuilder b) {
        List<JsonHub> items = new ArrayList<>(values.size());
        for (String value : values) {
            items.add(b.string(value));
        }
        return items;
    }
}