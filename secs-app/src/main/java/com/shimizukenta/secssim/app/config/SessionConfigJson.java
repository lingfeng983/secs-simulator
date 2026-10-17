package com.shimizukenta.secssim.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.domain.CeidDef;
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
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.template.Expression;
import com.shimizukenta.secssim.core.template.ExpressionException;

/**
 * Schema v4 配置的读写。
 *
 * <p>只做两件事：把 JSON 变成领域模型，把领域模型变回 JSON。
 * <b>不做</b>语义校验（重复 ID、段重叠、跨单元引用……）——那是
 * {@code EquipmentCompiler} 的职责。分层的好处是：语法错误立刻报，
 * 语义错误在编译阶段一次性报全，两者不会互相掩盖。
 * </p>
 */
public final class SessionConfigJson {

    private static final String ROOT = "$";

    private SessionConfigJson() {
    }

    /**
     * 解析 JSON 文本。
     *
     * @param json JSON
     * @return 配置
     * @throws ConfigException 语法或字段错误（带字段路径）
     */
    public static SessionConfig parse(String json) {
        String text = stripBom(json);
        JsonHub root;
        try {
            root = JsonHub.fromJson(text);
        } catch (RuntimeException e) {
            throw new ConfigException(ROOT, "JSON 解析失败: " + e.getMessage());
        }

        int schemaVersion = JsonAccess.integer(root, "schemaVersion", ROOT);
        if (schemaVersion < SessionConfig.MIN_SCHEMA_VERSION || schemaVersion > SessionConfig.SCHEMA_VERSION) {
            throw new ConfigException(ROOT + ".schemaVersion",
                    "不支持的版本: " + schemaVersion + "（本程序支持 " + SessionConfig.MIN_SCHEMA_VERSION
                            + ".." + SessionConfig.SCHEMA_VERSION + "）");
        }

        String sessionPath = ROOT + ".session";
        JsonHub session = JsonAccess.object(root, "session", ROOT);

        String id = JsonAccess.string(session, "id", sessionPath);
        boolean passive = JsonAccess.bool(session, "passive", true, sessionPath);
        String bindHost = JsonAccess.optionalString(session, "bindHost", sessionPath).orElse("127.0.0.1");
        int port = JsonAccess.optionalInt(session, "port", sessionPath).orElse(0);
        int deviceId = JsonAccess.optionalInt(session, "deviceId", sessionPath).orElse(10);
        long seed = session.containsKey("seed") ? JsonAccess.longValue(session, "seed", sessionPath) : 0L;

        EquipmentSpec equipment = parseEquipment(root, deviceId);
        EventSchedule schedule = parseEvents(root);
        int maxPerSec = parseMaxPerSec(root);
        boolean s6f11Wbit = parseS6f11Wbit(root);
        boolean s6f1Wbit = parseS6f1Wbit(root);
        TemplateConfig templates = TemplateConfigJson.parse(root);

        SessionSpec spec = new SessionSpec(id, passive, bindHost, port, deviceId, seed, equipment);
        return new SessionConfig(spec, schedule, maxPerSec, s6f11Wbit, s6f1Wbit, templates);
    }

    /**
     * 去掉 UTF-8 BOM。
     *
     * <p>Windows 的记事本与 PowerShell 的 {@code Set-Content -Encoding utf8} 都会写 BOM，
     * 而 JSON 规范不允许 BOM。不处理的话，用户用记事本改一行配置就会看到
     * "JSON 解析失败"，且错误指向第 1 个字符，极难自行定位。
     * </p>
     */
    private static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    /**
     * 从文件读取。
     *
     * @param file 文件
     * @return 配置
     * @throws IOException 读取失败
     */
    public static SessionConfig read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * 序列化为 JSON。
     *
     * @param config 配置
     * @return JSON
     */
    public static String toJson(SessionConfig config) {
        JsonHubBuilder b = JsonHub.getBuilder();

        List<JsonObjectPair> pairs = new ArrayList<>();
        pairs.add(b.pair("schemaVersion", SessionConfig.SCHEMA_VERSION));
        pairs.add(b.pair("session", sessionJson(config.spec(), b)));
        pairs.add(b.pair("equipment", equipmentJson(config.spec().equipment(), b)));
        pairs.add(b.pair("events", eventsJson(config, b)));
        if (!config.templates().isEmpty()) {
            pairs.add(b.pair("templates", TemplateConfigJson.toJson(config.templates(), b)));
        }

        JsonHub root = b.object(pairs);

        return root.prettyPrint();
    }

    /**
     * 写入文件（UTF-8）。
     *
     * @param file   文件
     * @param config 配置
     * @throws IOException 写入失败
     */
    public static void write(Path file, SessionConfig config) throws IOException {
        Files.writeString(file, toJson(config), StandardCharsets.UTF_8);
    }

    private static JsonHub sessionJson(SessionSpec spec, JsonHubBuilder b) {
        return b.object(
                b.pair("id", spec.id()),
                b.pair("passive", spec.passive()),
                b.pair("bindHost", spec.bindHost()),
                b.pair("port", spec.port()),
                b.pair("deviceId", spec.deviceId()),
                b.pair("seed", spec.seed()));
    }

    private static JsonHub equipmentJson(EquipmentSpec equipment, JsonHubBuilder b) {
        List<JsonHub> units = new ArrayList<>();
        for (UnitSpec unit : equipment.units()) {
            units.add(unitJson(unit, b));
        }

        List<JsonHub> globals = new ArrayList<>();
        for (GlobalSvidDef def : equipment.globalSvids()) {
            List<com.shimizukenta.jsonhub.JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("id", def.id()));
            pairs.add(b.pair("name", def.name()));
            pairs.add(b.pair("type", def.type().name()));
            if (Double.isFinite(def.min())) {
                pairs.add(b.pair("min", def.min()));
            }
            if (Double.isFinite(def.max())) {
                pairs.add(b.pair("max", def.max()));
            }
            globals.add(b.object(pairs));
        }

        List<JsonHub> globalCeids = new ArrayList<>();
        for (GlobalCeidDef def : equipment.globalCeids()) {
            globalCeids.add(b.object(
                    b.pair("id", def.id()),
                    b.pair("name", def.name()),
                    b.pair("reports", b.array(strings(def.reportNames(), b)))));
        }

        return b.object(
                b.pair("name", equipment.name()),
                b.pair("idSpace", idSpaceJson(equipment.idSpace(), b)),
                b.pair("globalSvids", b.array(globals)),
                b.pair("globalCeids", b.array(globalCeids)),
                b.pair("units", b.array(units)));
    }

    private static JsonHub idSpaceJson(IdSpacePolicy policy, JsonHubBuilder b) {
        List<JsonHub> reserves = new ArrayList<>();
        for (IdRange range : policy.reservedSvid()) {
            reserves.add(b.array(b.number(range.from()), b.number(range.to())));
        }

        return b.object(
                b.pair("svidBase", policy.svidBase()),
                b.pair("ceidBase", policy.ceidBase()),
                b.pair("reportBase", policy.reportBase()),
                b.pair("svidStride", policy.svidStride()),
                b.pair("ceidStride", policy.ceidStride()),
                b.pair("reportStride", policy.reportStride()),
                b.pair("reserve", b.array(reserves)));
    }

    private static JsonHub unitJson(UnitSpec unit, JsonHubBuilder b) {
        List<JsonHub> svids = new ArrayList<>();
        for (SvidDef def : unit.svids()) {
            svids.add(svidJson(def, b));
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

    private static JsonHub svidJson(SvidDef def, JsonHubBuilder b) {
        List<com.shimizukenta.jsonhub.JsonObjectPair> pairs = new ArrayList<>();
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

        return b.object(pairs);
    }

    private static List<JsonHub> strings(List<String> values, JsonHubBuilder b) {
        List<JsonHub> items = new ArrayList<>(values.size());
        for (String value : values) {
            items.add(b.string(value));
        }
        return items;
    }

    private static JsonHub eventsJson(SessionConfig config, JsonHubBuilder b) {
        List<JsonHub> plans = new ArrayList<>();

        for (EventPlan plan : config.schedule().plans()) {
            plans.add(b.object(
                    b.pair("id", plan.id()),
                    b.pair("ceid", plan.ceid()),
                    b.pair("trigger", triggerJson(plan.trigger(), b))));
        }

        List<com.shimizukenta.jsonhub.JsonObjectPair> pairs = new ArrayList<>();
        pairs.add(b.pair("maxPerSec", config.maxEventsPerSec()));
        pairs.add(b.pair("plans", b.array(plans)));
        if (config.s6f11Wbit()) {
            /* 默认 false 不写回，保持旧配置的 JSON 往返稳定 */
            pairs.add(b.pair("s6f11Wbit", true));
        }
        if (config.s6f1Wbit()) {
            pairs.add(b.pair("s6f1Wbit", true));
        }

        return b.object(pairs);
    }

    private static JsonHub triggerJson(Trigger trigger, JsonHubBuilder b) {
        if (trigger instanceof Trigger.At at) {
            return b.object(
                    b.pair("type", "at"),
                    b.pair("atMs", at.atMs()));
        }

        if (trigger instanceof Trigger.OnCondition condition) {
            return b.object(
                    b.pair("type", "onCondition"),
                    b.pair("expression", condition.expression().source()),
                    b.pair("debounceMs", condition.debounceMs()));
        }

        if (trigger instanceof Trigger.AfterEvent after) {
            return b.object(
                    b.pair("type", "afterEvent"),
                    b.pair("sourcePlan", after.sourcePlan()),
                    b.pair("delayMs", after.delayMs()));
        }

        Trigger.Periodic periodic = (Trigger.Periodic) trigger;
        return b.object(
                b.pair("type", "periodic"),
                b.pair("periodMs", periodic.periodMs()),
                b.pair("jitterMs", periodic.jitterMs()));
    }

    private static EquipmentSpec parseEquipment(JsonHub root, int deviceId) {
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

        List<JsonHub> unitItems = JsonAccess.elements(
                JsonAccess.array(equipment, "units", path), path + ".units");

        if (unitItems.isEmpty()) {
            throw new ConfigException(path + ".units", "设备至少需要一个单元");
        }

        List<UnitSpec> units = new ArrayList<>(unitItems.size());
        for (int i = 0; i < unitItems.size(); i++) {
            units.add(parseUnit(unitItems.get(i), path + ".units[" + i + "]"));
        }

        return new EquipmentSpec(name, deviceId, units, globalSvids, globalCeids, idSpace);
    }

    private static IdSpacePolicy parseIdSpace(JsonHub jh, String path) {
        IdSpacePolicy defaults = IdSpacePolicy.defaults();

        List<IdRange> reserves = new ArrayList<>();
        if (jh.containsKey("reserve")) {
            List<JsonHub> ranges = JsonAccess.elements(JsonAccess.array(jh, "reserve", path), path + ".reserve");
            for (int i = 0; i < ranges.size(); i++) {
                String rangePath = path + ".reserve[" + i + "]";
                JsonHub range = ranges.get(i);
                if (range.length() != 2) {
                    throw new ConfigException(rangePath, "区间必须是 [from, to]");
                }
                reserves.add(new IdRange(range.get(0).intValue(), range.get(1).intValue()));
            }
        }

        int svidBase = JsonAccess.optionalInt(jh, "svidBase", path).orElse(defaults.svidBase());
        int ceidBase = JsonAccess.optionalInt(jh, "ceidBase", path).orElse(defaults.ceidBase());
        int reportBase = JsonAccess.optionalInt(jh, "reportBase", path).orElse(defaults.reportBase());
        int svidStride = JsonAccess.optionalInt(jh, "svidStride", path).orElse(defaults.svidStride());
        int ceidStride = JsonAccess.optionalInt(jh, "ceidStride", path).orElse(defaults.ceidStride());
        int reportStride = JsonAccess.optionalInt(jh, "reportStride", path).orElse(defaults.reportStride());

        return new IdSpacePolicy(svidBase, ceidBase, reportBase, svidStride, ceidStride, reportStride,
                reserves, reserves, reserves);
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

    private static EventSchedule parseEvents(JsonHub root) {
        if (!root.containsKey("events")) {
            return EventSchedule.empty();
        }

        String path = ROOT + ".events";
        JsonHub events = JsonAccess.object(root, "events", ROOT);

        if (!events.containsKey("plans")) {
            return EventSchedule.empty();
        }

        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(events, "plans", path), path + ".plans");
        List<EventPlan> plans = new ArrayList<>(items.size());

        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + ".plans[" + i + "]";
            JsonHub item = items.get(i);

            plans.add(new EventPlan(
                    JsonAccess.string(item, "id", itemPath),
                    JsonAccess.integer(item, "ceid", itemPath),
                    parseTrigger(JsonAccess.object(item, "trigger", itemPath), itemPath + ".trigger")));
        }

        validateChains(plans, path);
        return new EventSchedule(plans);
    }

    /** 校验 AfterEvent 引用存在且无环；区间/条件触发不参与图。 */
    private static void validateChains(List<EventPlan> plans, String path) {
        Map<String, EventPlan> byId = new LinkedHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            EventPlan plan = plans.get(i);
            if (byId.putIfAbsent(plan.id(), plan) != null) {
                throw new ConfigException(path + ".plans[" + i + "].id", "计划 id 重复: " + plan.id());
            }
        }

        Map<String, Integer> state = new HashMap<>();
        for (String id : byId.keySet()) {
            visitChain(id, byId, state, path);
        }
    }

    private static void visitChain(String id, Map<String, EventPlan> byId, Map<String, Integer> state, String path) {
        Integer status = state.get(id);
        if (status != null && status == 1) {
            throw new ConfigException(path + ".plans", "AfterEvent 存在环: " + id);
        }
        if (status != null && status == 2) {
            return;
        }

        state.put(id, 1);
        EventPlan plan = byId.get(id);
        if (plan.trigger() instanceof Trigger.AfterEvent after) {
            if (!byId.containsKey(after.sourcePlan())) {
                throw new ConfigException(path + ".plans", "AfterEvent 引用了不存在的计划: " + after.sourcePlan());
            }
            visitChain(after.sourcePlan(), byId, state, path);
        }
        state.put(id, 2);
    }

    private static Trigger parseTrigger(JsonHub jh, String path) {
        String type = JsonAccess.string(jh, "type", path);

        return switch (type) {
            case "at" -> new Trigger.At(JsonAccess.longValue(jh, "atMs", path));
            case "periodic" -> new Trigger.Periodic(
                    JsonAccess.longValue(jh, "periodMs", path),
                    jh.containsKey("jitterMs") ? JsonAccess.longValue(jh, "jitterMs", path) : 0L);
            case "onCondition" -> {
                String source = JsonAccess.string(jh, "expression", path);
                Expression expression;
                try {
                    expression = Expression.parse(source);
                } catch (ExpressionException e) {
                    throw new ConfigException(path + ".expression", e.getMessage());
                }
                long debounceMs = jh.containsKey("debounceMs")
                        ? JsonAccess.longValue(jh, "debounceMs", path) : 0L;
                if (debounceMs < 0L) {
                    throw new ConfigException(path + ".debounceMs", "debounceMs 不能为负数");
                }
                yield new Trigger.OnCondition(expression, debounceMs);
            }
            case "afterEvent" -> {
                String sourcePlan = JsonAccess.string(jh, "sourcePlan", path);
                long delayMs = jh.containsKey("delayMs")
                        ? JsonAccess.longValue(jh, "delayMs", path) : 0L;
                if (delayMs < 0L) {
                    throw new ConfigException(path + ".delayMs", "delayMs 不能为负数");
                }
                yield new Trigger.AfterEvent(sourcePlan, delayMs);
            }
            default -> throw new ConfigException(path + ".type", "未知触发类型: " + type
                    + "（可选 at / periodic / onCondition / afterEvent）");
        };
    }

    private static boolean parseS6f11Wbit(JsonHub root) {
        if (!root.containsKey("events")) {
            return false;
        }
        JsonHub events = JsonAccess.object(root, "events", ROOT);
        return JsonAccess.bool(events, "s6f11Wbit", false, ROOT + ".events");
    }

    private static boolean parseS6f1Wbit(JsonHub root) {
        if (!root.containsKey("events")) {
            return false;
        }
        JsonHub events = JsonAccess.object(root, "events", ROOT);
        return JsonAccess.bool(events, "s6f1Wbit", false, ROOT + ".events");
    }

    private static int parseMaxPerSec(JsonHub root) {
        if (!root.containsKey("events")) {
            return 50;
        }
        JsonHub events = JsonAccess.object(root, "events", ROOT);
        OptionalInt value = JsonAccess.optionalInt(events, "maxPerSec", ROOT + ".events");
        return value.orElse(50);
    }
}
