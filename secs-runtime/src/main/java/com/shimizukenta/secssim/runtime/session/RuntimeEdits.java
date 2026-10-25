package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.core.compile.ResolvedReport;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.event.EventEffect;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.template.Expression;

/**
 * 运行时编辑请求体的解析（设备页·事件 / 报告 / 状态变量的增删改）。
 *
 * <p>HTTP 层只递 JSON 字符串,解析与校验都收在这里:字段缺省、类型不对、
 * 效果字段与 op 不匹配等一律抛 {@link IllegalArgumentException},由 HTTP 层
 * 转成 400 带原因。错误信息面向界面,要说清哪个字段不合法。</p>
 */
public final class RuntimeEdits {

    private RuntimeEdits() {
    }

    /**
     * 解析编排计划:{"id","ceid","trigger":{"type",...},"effects":[...]}。
     *
     * @param json JSON 文本
     * @return 计划
     */
    public static EventPlan parseEventPlan(String json) {
        JsonHub root = JsonHub.fromJson(json);
        String id = requireString(root, "id");
        int ceid = (int) root.get("ceid").longValue();
        if (!root.containsKey("trigger") || !root.get("trigger").isObject()) {
            throw new IllegalArgumentException("缺少 trigger 对象");
        }
        JsonHub trigger = root.get("trigger");
        String type = requireString(trigger, "type");
        Trigger t = switch (type) {
            case "at" -> new Trigger.At(requireLong(trigger, "atMs"));
            case "periodic" -> new Trigger.Periodic(
                    requireLong(trigger, "periodMs"),
                    trigger.containsKey("jitterMs") ? trigger.get("jitterMs").longValue() : 0L);
            case "onCondition" -> new Trigger.OnCondition(
                    parseExpression(requireString(trigger, "expression")),
                    trigger.containsKey("debounceMs") ? trigger.get("debounceMs").longValue() : 0L);
            case "afterEvent" -> new Trigger.AfterEvent(
                    requireString(trigger, "sourcePlan"),
                    trigger.containsKey("delayMs") ? trigger.get("delayMs").longValue() : 0L);
            default -> throw new IllegalArgumentException("未知触发类型: " + type
                    + "（可选 at / periodic / onCondition / afterEvent）");
        };

        List<EventEffect> effects = new ArrayList<>();
        if (root.containsKey("effects") && root.get("effects").isArray()) {
            for (JsonHub item : root.get("effects")) {
                effects.add(parseEffect(item));
            }
        }
        try {
            return new EventPlan(id, ceid, t, effects);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("计划不合法: " + e.getMessage());
        }
    }

    private static EventEffect parseEffect(JsonHub item) {
        int svid = (int) item.get("svid").longValue();
        String opText = requireString(item, "op");
        final EventEffect.Op op;
        try {
            op = EventEffect.Op.valueOf(opText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知效果类型 op: " + opText);
        }
        Double value = item.containsKey("value") ? item.get("value").doubleValue() : null;
        Double delta = item.containsKey("delta") ? item.get("delta").doubleValue() : null;
        Long holdMs = item.containsKey("holdMs") ? item.get("holdMs").longValue() : null;
        Long decayMs = item.containsKey("decayMs") ? item.get("decayMs").longValue() : null;
        try {
            return new EventEffect(svid, op, value, delta, holdMs, decayMs);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("效果不合法(op=" + opText + "): " + e.getMessage());
        }
    }

    private static Expression parseExpression(String source) {
        try {
            return Expression.parse(source);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("条件表达式无法解析: " + e.getMessage());
        }
    }

    /**
     * 解析报告:{"id","slot","svids":[...],"owner"?}。
     *
     * @param json JSON 文本
     * @return 报告定义
     */
    public static ResolvedReport parseReport(String json) {
        JsonHub root = JsonHub.fromJson(json);
        int id = (int) root.get("id").longValue();
        String slot = requireString(root, "slot");
        String owner = root.containsKey("owner") ? root.get("owner").optionalString().orElse("") : "";
        if (!root.containsKey("svids") || !root.get("svids").isArray()) {
            throw new IllegalArgumentException("报告缺少 svids 数组");
        }
        List<Integer> svids = new ArrayList<>();
        for (JsonHub item : root.get("svids")) {
            svids.add((int) item.longValue());
        }
        return new ResolvedReport(id, owner, slot, svids);
    }

    /**
     * 解析状态变量:{"id","slot","type","owner"?,"unit"?}。
     *
     * @param json JSON 文本
     * @return 变量定义
     */
    public static ResolvedSvid parseSvid(String json) {
        JsonHub root = JsonHub.fromJson(json);
        int id = (int) root.get("id").longValue();
        String slot = requireString(root, "slot");
        String typeText = requireString(root, "type");
        final ValueType type;
        try {
            type = ValueType.valueOf(typeText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知数据类型: " + typeText);
        }
        String owner = root.containsKey("owner") ? root.get("owner").optionalString().orElse("") : "";
        String unit = root.containsKey("unit") ? root.get("unit").optionalString().orElse("") : "";
        return new ResolvedSvid(id, owner, slot, type, unit);
    }

    /**
     * 解析「设定」值:{"value": <number>}。
     *
     * @param json JSON 文本
     * @return 设定值
     */
    public static double parsePinnedValue(String json) {
        JsonHub root = JsonHub.fromJson(json);
        if (!root.containsKey("value")) {
            throw new IllegalArgumentException("缺少 value 字段");
        }
        return root.get("value").doubleValue();
    }

    private static String requireString(JsonHub root, String key) {
        if (!root.containsKey(key)) {
            throw new IllegalArgumentException("缺少字段: " + key);
        }
        String value = root.get(key).optionalString().orElse("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("字段不能为空: " + key);
        }
        return value.trim();
    }

    private static long requireLong(JsonHub root, String key) {
        if (!root.containsKey(key)) {
            throw new IllegalArgumentException("缺少字段: " + key);
        }
        return root.get(key).longValue();
    }
}
