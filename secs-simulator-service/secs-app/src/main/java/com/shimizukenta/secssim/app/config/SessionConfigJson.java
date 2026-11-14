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
import com.shimizukenta.jsonhub.JsonString;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.app.session.HsmsTimeouts;
import com.shimizukenta.secssim.app.session.TraceLimits;
import com.shimizukenta.secssim.core.domain.CeidDef;
import com.shimizukenta.secssim.core.domain.DirectSource;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.GlobalCeidDef;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.core.domain.GlobalSvidDef;
import com.shimizukenta.secssim.core.domain.IdRange;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.core.domain.ReportDef;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitId;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.event.EventEffect;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.PlanAction;
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

        HsmsTimeouts timeouts = parseTimeouts(session, sessionPath);
        TraceLimits traceLimits = parseTraceLimits(session, sessionPath);
        SessionSpec spec = new SessionSpec(id, passive, bindHost, port, deviceId, seed, equipment, timeouts,
                traceLimits);
        return new SessionConfig(spec, schedule, maxPerSec, s6f11Wbit, s6f1Wbit, templates);
    }


    /**
     * 读 session.timeouts 段（可选）：HSMS-SS 超时，秒，必须大于 0。
     *
     * <p>形如 {@code "timeouts": {"t3": 60, "t7": 15}}；没写的键用协议层默认。</p>
     */
    private static HsmsTimeouts parseTimeouts(JsonHub session, String path) {
        if (!session.containsKey("timeouts")) {
            return HsmsTimeouts.DEFAULTS;
        }
        JsonHub timeouts = JsonAccess.object(session, "timeouts", path + ".timeouts");
        Double t3 = optionalPositiveSeconds(timeouts, "t3", path + ".timeouts");
        Double t5 = optionalPositiveSeconds(timeouts, "t5", path + ".timeouts");
        Double t6 = optionalPositiveSeconds(timeouts, "t6", path + ".timeouts");
        Double t7 = optionalPositiveSeconds(timeouts, "t7", path + ".timeouts");
        Double t8 = optionalPositiveSeconds(timeouts, "t8", path + ".timeouts");
        try {
            return new HsmsTimeouts(t3, t5, t6, t7, t8);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path + ".timeouts", e.getMessage());
        }
    }

    private static Double optionalPositiveSeconds(JsonHub timeouts, String key, String path) {
        return JsonAccess.optionalDouble(timeouts, key, path + "." + key)
                .stream()
                .boxed()
                .findFirst()
                .map(value -> {
                    if (value <= 0) {
                        throw new ConfigException(path + "." + key, "必须大于 0（秒）");
                    }
                    return value;
                })
                .orElse(null);
    }

    /**
     * 读 session.trace 段（可选）：Trace（S2F23）容量限制。
     *
     * <p>形如 {@code "trace": {"maxTraces": 4, "maxSvidCount": 32}}；没写的键用
     * 内置默认（同时 1 条、每条 ≤10 个 SVID，与多数真机一致）。</p>
     */
    private static TraceLimits parseTraceLimits(JsonHub session, String path) {
        if (!session.containsKey("trace")) {
            return TraceLimits.DEFAULTS;
        }
        JsonHub trace = JsonAccess.object(session, "trace", path + ".trace");
        Integer maxTraces = optionalPositiveInt(trace, "maxTraces", path + ".trace");
        Integer maxSvidCount = optionalPositiveInt(trace, "maxSvidCount", path + ".trace");
        try {
            return new TraceLimits(maxTraces, maxSvidCount);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path + ".trace", e.getMessage());
        }
    }

    private static Integer optionalPositiveInt(JsonHub trace, String key, String path) {
        return JsonAccess.optionalInt(trace, key, path + "." + key)
                .stream()
                .boxed()
                .findFirst()
                .map(value -> {
                    if (value < 1) {
                        throw new ConfigException(path + "." + key, "必须 ≥ 1");
                    }
                    return value;
                })
                .orElse(null);
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
        List<JsonObjectPair> pairs = new ArrayList<>();
        pairs.add(b.pair("id", spec.id()));
        pairs.add(b.pair("passive", spec.passive()));
        pairs.add(b.pair("bindHost", spec.bindHost()));
        pairs.add(b.pair("port", spec.port()));
        pairs.add(b.pair("deviceId", spec.deviceId()));
        pairs.add(b.pair("seed", spec.seed()));
        addTimeouts(pairs, spec.timeouts(), b);
        addTraceLimits(pairs, spec.traceLimits(), b);
        return b.object(pairs);
    }

    /**
     * 把 HSMS 超时写回 session 段。
     *
     * <p>只写显式给过的键：没写的键在文件里保持"没有"，语义上等于协议层默认。
     * 这段必须参与往返——否则 S2F15 写一次设备常量，配置文件里的超时就会消失。</p>
     */
    private static void addTimeouts(List<JsonObjectPair> pairs, HsmsTimeouts timeouts, JsonHubBuilder b) {
        if (timeouts == null) {
            return;
        }

        List<JsonObjectPair> values = new ArrayList<>();
        if (timeouts.t3() != null) {
            values.add(b.pair("t3", timeouts.t3()));
        }
        if (timeouts.t5() != null) {
            values.add(b.pair("t5", timeouts.t5()));
        }
        if (timeouts.t6() != null) {
            values.add(b.pair("t6", timeouts.t6()));
        }
        if (timeouts.t7() != null) {
            values.add(b.pair("t7", timeouts.t7()));
        }
        if (timeouts.t8() != null) {
            values.add(b.pair("t8", timeouts.t8()));
        }
        if (!values.isEmpty()) {
            pairs.add(b.pair("timeouts", b.object(values)));
        }
    }

    /**
     * 把 Trace 容量限制写回 session 段。
     *
     * <p>只写显式给过的键：没写的键在文件里保持"没有"，语义上等于内置默认。
     * 与 timeouts 一样必须参与往返——否则 S2F15 写一次常量，容量配置就丢了。</p>
     */
    private static void addTraceLimits(List<JsonObjectPair> pairs, TraceLimits traceLimits, JsonHubBuilder b) {
        if (traceLimits == null) {
            return;
        }

        List<JsonObjectPair> values = new ArrayList<>();
        if (traceLimits.maxTraces() != null) {
            values.add(b.pair("maxTraces", traceLimits.maxTraces()));
        }
        if (traceLimits.maxSvidCount() != null) {
            values.add(b.pair("maxSvidCount", traceLimits.maxSvidCount()));
        }
        if (!values.isEmpty()) {
            pairs.add(b.pair("trace", b.object(values)));
        }
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
            addDirectSource(pairs, def.source(), b);
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
        JsonHub constants = constantsJson(equipment.constants(), b);

        return b.object(
                b.pair("name", equipment.name()),
                b.pair("idSpace", idSpaceJson(equipment.idSpace(), b)),
                b.pair("globalSvids", b.array(globals)),
                b.pair("globalCeids", b.array(globalCeids)),
                b.pair("constants", constants),
                b.pair("units", b.array(units)));
    }

    /**
     * 设备常量（ECV）数组。
     *
     * <p>公开给 S2F15 的原文回写复用：两条写盘路径必须用同一种元素形状，
     * 否则"编辑器保存"与"主机写常量"产出的文件会不一致。</p>
     *
     * @param constants 常量（按 id 排序后写出）
     * @param b         JSON builder
     * @return JSON 数组
     */
    public static JsonHub constantsJson(java.util.Collection<EquipmentConstantDef> constants, JsonHubBuilder b) {
        List<JsonHub> items = new ArrayList<>();
        List<EquipmentConstantDef> ordered = new ArrayList<>(constants);
        ordered.sort(java.util.Comparator.comparingInt(EquipmentConstantDef::id));

        for (EquipmentConstantDef def : ordered) {
            List<JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("id", def.id()));
            pairs.add(b.pair("name", def.name()));
            pairs.add(b.pair("type", def.type().name()));
            pairs.add(b.pair("value", def.value()));
            if (!def.unit().isEmpty()) {
                pairs.add(b.pair("unit", def.unit()));
            }
            items.add(b.object(pairs));
        }
        return b.array(items);
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
        addDirectSource(pairs, def.source(), b);

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
            List<com.shimizukenta.jsonhub.JsonObjectPair> planPairs = new ArrayList<>();
            planPairs.add(b.pair("id", plan.id()));
            planPairs.add(b.pair("ceid", plan.ceid()));
            planPairs.add(b.pair("trigger", triggerJson(plan.trigger(), b)));
            if (!plan.effects().isEmpty()) {
                planPairs.add(b.pair("effects", effectsJson(plan.effects(), b)));
            }
            /* 3.0 需求 5：动作与策略按原样回写，配置保存往返不丢字段。
               全缺省的计划不写这些键，保证 v4/v5/v6 旧配置零改动。 */
            if (plan.hasCustomActions()) {
                planPairs.add(b.pair("actions", actionsJson(plan.actions(), b)));
            }
            if (!EventPlan.OFFLINE_SKIP.equals(plan.offlinePolicy())) {
                planPairs.add(b.pair("offlinePolicy", plan.offlinePolicy()));
            }
            if (!EventPlan.FAILURE_KEEP.equals(plan.onSendFailure())) {
                planPairs.add(b.pair("onSendFailure", plan.onSendFailure()));
            }
            plans.add(b.object(planPairs));
        }

        List<com.shimizukenta.jsonhub.JsonObjectPair> pairs = new ArrayList<>();
        pairs.add(b.pair("maxPerSec", config.maxEventsPerSec()));
        pairs.add(b.pair("plans", b.array(plans)));
        /* W 位默认 true：两个字段都显式写回，显式配 false 的配置不会在往返时被默认值翻回去 */
        pairs.add(b.pair("s6f11Wbit", config.s6f11Wbit()));
        pairs.add(b.pair("s6f1Wbit", config.s6f1Wbit()));

        return b.object(pairs);
    }

    /** 计划动作数组（kind/stream/function/wbit/sml/params，全可选的键不写）。 */
    private static JsonHub actionsJson(List<PlanAction> actions, JsonHubBuilder b) {
        List<JsonHub> items = new ArrayList<>(actions.size());
        for (PlanAction action : actions) {
            List<JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("kind", action.kind()));
            if (action.stream() != null) {
                pairs.add(b.pair("stream", action.stream()));
            }
            if (action.function() != null) {
                pairs.add(b.pair("function", action.function()));
            }
            if (action.wbit() != null) {
                pairs.add(b.pair("wbit", action.wbit()));
            }
            if (action.sml() != null) {
                pairs.add(b.pair("sml", action.sml()));
            }
            if (!action.params().isEmpty()) {
                List<JsonObjectPair> params = new ArrayList<>();
                for (Map.Entry<String, String> entry : action.params().entrySet()) {
                    params.add(b.pair(entry.getKey(), entry.getValue()));
                }
                pairs.add(b.pair("params", b.object(params)));
            }
            items.add(b.object(pairs));
        }
        return b.array(items);
    }

    private static JsonHub effectsJson(List<EventEffect> effects, JsonHubBuilder b) {
        List<JsonHub> items = new ArrayList<>(effects.size());
        for (EventEffect effect : effects) {
            List<com.shimizukenta.jsonhub.JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("svid", effect.svid()));
            pairs.add(b.pair("op", effect.op().name().toLowerCase(java.util.Locale.ROOT)));
            if (effect.value() != null) {
                pairs.add(b.pair("value", effect.value()));
            }
            if (effect.delta() != null) {
                pairs.add(b.pair("delta", effect.delta()));
            }
            if (effect.holdMs() != null) {
                pairs.add(b.pair("holdMs", effect.holdMs()));
            }
            if (effect.decayMs() != null) {
                pairs.add(b.pair("decayMs", effect.decayMs()));
            }
            items.add(b.object(pairs));
        }
        return b.array(items);
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

        if (trigger instanceof Trigger.Periodic periodic) {
            return b.object(
                    b.pair("type", "periodic"),
                    b.pair("periodMs", periodic.periodMs()),
                    b.pair("jitterMs", periodic.jitterMs()));
        }

        /* 新增 Trigger 子类型时这里是编译可见的兜底，而不是对 Periodic 的盲转（CCE） */
        throw new IllegalStateException("未支持的触发类型: " + trigger.getClass().getName());
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
                        JsonAccess.optionalDouble(item, "max", itemPath).orElse(Double.POSITIVE_INFINITY),
                        parseDirectSource(item, itemPath)));
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
        java.util.OptionalLong asLong = value.optionalLong();
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

        return new SvidDef(offset, slot, type, unitSymbol, decimals, lower, upper, parseDirectSource(jh, path));
    }

    /**
     * 解析 SVID 上的直配值来源（schema v6，可选）。
     *
     * <p>{@code curve}（内联曲线）与 {@code template}（引用模板，可带
     * {@code output} 与 {@code params}）二选一；都不写就是默认曲线。</p>
     */
    private static DirectSource parseDirectSource(JsonHub jh, String path) {
        boolean hasCurve = jh.containsKey("curve");
        boolean hasTemplate = jh.containsKey("template");

        if (hasCurve && hasTemplate) {
            throw new ConfigException(path, "curve 与 template 只能二选一");
        }
        if (hasTemplate) {
            String template = JsonAccess.string(jh, "template", path);
            String output = jh.containsKey("output")
                    ? JsonAccess.string(jh, "output", path + ".output") : null;
            Map<String, Double> params = new LinkedHashMap<>();
            if (jh.containsKey("params")) {
                JsonHub paramsJson = JsonAccess.object(jh, "params", path + ".params");
                for (JsonString key : paramsJson.keySet()) {
                    String name = key.unescaped();
                    OptionalDouble value = paramsJson.get(name).optionalDouble();
                    if (value.isEmpty()) {
                        throw new ConfigException(path + ".params." + name, "应为数字");
                    }
                    params.put(name, value.getAsDouble());
                }
            }
            return DirectSource.ofTemplate(template, output, params);
        }
        if (hasCurve) {
            if (jh.containsKey("params") || jh.containsKey("output")) {
                throw new ConfigException(path, "output/params 必须与 template 搭配使用");
            }
            return DirectSource.ofCurve(CurveJson.parse(JsonAccess.object(jh, "curve", path), path + ".curve"));
        }
        return null;
    }

    /** 把直配值来源写回 SVID 的 JSON（没配就不写，保持旧配置往返稳定）。 */
    private static void addDirectSource(List<JsonObjectPair> pairs, DirectSource source, JsonHubBuilder b) {
        if (source == null) {
            return;
        }
        if (source.curve() != null) {
            pairs.add(b.pair("curve", b.object(CurveJson.pairs(source.curve(), b))));
            return;
        }
        pairs.add(b.pair("template", source.template()));
        if (source.output() != null) {
            pairs.add(b.pair("output", source.output()));
        }
        if (!source.params().isEmpty()) {
            List<JsonObjectPair> params = new ArrayList<>();
            for (Map.Entry<String, Double> entry : source.params().entrySet()) {
                params.add(b.pair(entry.getKey(), entry.getValue()));
            }
            pairs.add(b.pair("params", b.object(params)));
        }
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

            String offlinePolicy = JsonAccess.optionalString(item, "offlinePolicy", itemPath)
                    .orElse(EventPlan.OFFLINE_SKIP);
            if (!List.of(EventPlan.OFFLINE_SKIP, EventPlan.OFFLINE_QUEUE,
                    EventPlan.OFFLINE_FAIL).contains(offlinePolicy)) {
                throw new ConfigException(itemPath + ".offlinePolicy",
                        "未知离线策略: " + offlinePolicy + "（可选 skip / queue / fail）");
            }
            String onSendFailure = JsonAccess.optionalString(item, "onSendFailure", itemPath)
                    .orElse(EventPlan.FAILURE_KEEP);
            if (!List.of(EventPlan.FAILURE_KEEP, EventPlan.FAILURE_ROLLBACK).contains(onSendFailure)) {
                throw new ConfigException(itemPath + ".onSendFailure",
                        "未知发送失败策略: " + onSendFailure + "（可选 keep / rollback）");
            }
            try {
                plans.add(new EventPlan(
                        JsonAccess.string(item, "id", itemPath),
                        JsonAccess.integer(item, "ceid", itemPath),
                        parseTrigger(JsonAccess.object(item, "trigger", itemPath), itemPath + ".trigger"),
                        parseEffects(item, itemPath),
                        parseActions(item, itemPath),
                        offlinePolicy, onSendFailure));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(itemPath, e.getMessage());
            }
        }

        validateChains(plans, path);
        return new EventSchedule(plans);
    }

    /**
     * 解析计划动作数组（schema v7 / 3.0 需求 5，可选）。
     *
     * <p>缺省 = 一条默认 S6F11，保证旧配置行为不变；字段完整性（kind=sml
     * 要 sml 原文）由 {@link PlanAction} 的构造器把关。</p>
     */
    private static List<PlanAction> parseActions(JsonHub item, String itemPath) {
        if (!item.containsKey("actions")) {
            return List.of();
        }
        String path = itemPath + ".actions";
        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(item, "actions", itemPath), path);
        List<PlanAction> actions = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String at = path + "[" + i + "]";
            JsonHub action = items.get(i);
            OptionalInt stream = JsonAccess.optionalInt(action, "stream", at);
            OptionalInt function = JsonAccess.optionalInt(action, "function", at);
            Boolean wbit = action.containsKey("wbit")
                    ? JsonAccess.bool(action, "wbit", false, at) : null;
            Map<String, String> params = new LinkedHashMap<>();
            if (action.containsKey("params")) {
                JsonHub paramsJson = JsonAccess.object(action, "params", at + ".params");
                for (JsonString key : paramsJson.keySet()) {
                    String name = key.unescaped();
                    String value = paramsJson.get(name).optionalString().orElseThrow(
                            () -> new ConfigException(at + ".params." + name, "应为字符串"));
                    params.put(name, value);
                }
            }
            try {
                actions.add(new PlanAction(
                        JsonAccess.optionalString(action, "kind", at).orElse(PlanAction.KIND_S6F11),
                        stream.isPresent() ? stream.getAsInt() : null,
                        function.isPresent() ? function.getAsInt() : null,
                        wbit,
                        JsonAccess.optionalString(action, "sml", at).orElse(null),
                        params));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(at, e.getMessage());
            }
        }
        return actions;
    }

    /**
     * 解析计划的效果数组（schema v6，可选）。
     *
     * <p>字段完整性（哪个 op 要哪些字段）由 {@link EventEffect} 的构造器把关，
     * 这里只做 JSON → 模型的映射与 op 名的容错。</p>
     */
    private static List<EventEffect> parseEffects(JsonHub item, String itemPath) {
        if (!item.containsKey("effects")) {
            return List.of();
        }

        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(item, "effects", itemPath), itemPath + ".effects");
        List<EventEffect> effects = new ArrayList<>(items.size());

        for (int i = 0; i < items.size(); i++) {
            String path = itemPath + ".effects[" + i + "]";
            JsonHub effect = items.get(i);
            String opName = JsonAccess.string(effect, "op", path).trim().toUpperCase(java.util.Locale.ROOT);

            EventEffect.Op op;
            try {
                op = EventEffect.Op.valueOf(opName);
            } catch (IllegalArgumentException e) {
                throw new ConfigException(path + ".op",
                        "未知效果类型: " + opName + "（可选 set / add / pulse / shift / resume）");
            }

            try {
                effects.add(new EventEffect(
                        JsonAccess.integer(effect, "svid", path),
                        op,
                        boxedOptionalDouble(effect, "value"),
                        boxedOptionalDouble(effect, "delta"),
                        effect.containsKey("holdMs") ? JsonAccess.longValue(effect, "holdMs", path) : null,
                        effect.containsKey("decayMs") ? JsonAccess.longValue(effect, "decayMs", path) : null));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(path, e.getMessage());
            }
        }
        return effects;
    }

    /** OptionalDouble → 装箱可空（JSON 字段缺失时为 {@code null}）。 */
    private static Double boxedOptionalDouble(JsonHub jh, String key) {
        return JsonAccess.optionalDouble(jh, key, ROOT + "." + key)
                .stream()
                .mapToObj(value -> value)
                .findFirst()
                .orElse(null);
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
        return JsonAccess.bool(events, "s6f11Wbit", true, ROOT + ".events");
    }

    private static boolean parseS6f1Wbit(JsonHub root) {
        if (!root.containsKey("events")) {
            return false;
        }
        JsonHub events = JsonAccess.object(root, "events", ROOT);
        return JsonAccess.bool(events, "s6f1Wbit", true, ROOT + ".events");
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
