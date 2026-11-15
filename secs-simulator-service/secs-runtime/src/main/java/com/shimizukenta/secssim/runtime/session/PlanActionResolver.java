package com.shimizukenta.secssim.runtime.session;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secssim.app.sml.SmlParser;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.event.PlanAction;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 计划动作解析：把编排表里的一条动作组装成可发送报文（设计 §2.7.1 的五种类型）。
 *
 * <p>S6F11 / SML 直接复用组装器与解析器；alarm / terminal / trace 用
 * 与 {@code AlarmReporter} / S10F1 / S6F1 相同的数据结构组装报文体，
 * 参数从 {@code action.params()} 取，缺省值保持现有行为。</p>
 *
 * <p>组装器与值来源都是热可替换的，所以用 Supplier 延迟取值而不是构造时快照。</p>
 */
final class PlanActionResolver {

    /** SML 动作里的运行时占位符：{@code {{svid.1001}}}。 */
    private static final Pattern SVID_PLACEHOLDER = Pattern.compile("\\{\\{\\s*svid\\.(\\d+)\\s*\\}\\}");

    private final Supplier<S6F11Assembler> assembler;
    private final BooleanSupplier s6f11Wbit;
    private final BooleanSupplier s6f1Wbit;
    private final Supplier<ValueSource> values;

    PlanActionResolver(Supplier<S6F11Assembler> assembler,
                       BooleanSupplier s6f11Wbit,
                       BooleanSupplier s6f1Wbit,
                       Supplier<ValueSource> values) {
        this.assembler = assembler;
        this.s6f11Wbit = s6f11Wbit;
        this.s6f1Wbit = s6f1Wbit;
        this.values = values;
    }

    SmlParser.Message resolve(PlanAction action, int ceid, long atMs) throws Exception {
        return switch (action.kind()) {
            case PlanAction.KIND_S6F11 -> new SmlParser.Message(6, 11, this.s6f11Wbit.getAsBoolean(),
                    this.assembler.get().build(ceid, atMs));
            case PlanAction.KIND_SML -> SmlParser.parse(resolvePlaceholders(action.sml(), atMs));
            case PlanAction.KIND_ALARM -> alarmAction(action);
            case PlanAction.KIND_TERMINAL -> terminalAction(action);
            case PlanAction.KIND_TRACE -> traceAction(action, atMs);
            default -> throw new IllegalArgumentException("未知动作类型: " + action.kind());
        };
    }

    private static SmlParser.Message alarmAction(PlanAction action) {
        int alid = actionInt(action, "alid", 1);
        int category = Math.max(1, Math.min(7, actionInt(action, "category", 1)));
        boolean set = !"false".equalsIgnoreCase(action.params().getOrDefault("set", "true"));
        String text = action.params().getOrDefault("text", set ? "ALARM " + alid : "CLEAR " + alid);
        if (!text.chars().allMatch(c -> c < 128)) {
            throw new IllegalArgumentException("ALTX 只能包含 US-ASCII 字符");
        }
        byte alcd = (byte) ((set ? 0x80 : 0x00) | category);
        var b = Secs2.getBuilder();
        Secs2 body = b.list(b.binary(alcd), b.uint4(alid), b.ascii(text));
        /* S5F1 与 AlarmReporter 一致：默认带 W，等 S5F2 */
        return new SmlParser.Message(5, 1, true, body);
    }

    private static SmlParser.Message terminalAction(PlanAction action) {
        int tid = actionInt(action, "tid", 0);
        String text = action.params().getOrDefault("text", "");
        var b = Secs2.getBuilder();
        Secs2 body = b.list(b.binary((byte) tid), b.ascii(text));
        return new SmlParser.Message(10, 1, false, body);
    }

    private SmlParser.Message traceAction(PlanAction action, long atMs) {
        int trid = actionInt(action, "trid", 1);
        List<Secs2> sampled = new ArrayList<>();
        String rawSvids = action.params().get("svids");
        if (rawSvids != null && !rawSvids.isBlank()) {
            for (String piece : rawSvids.split(",")) {
                if (piece.isBlank()) {
                    continue;
                }
                int svid;
                try {
                    svid = Integer.parseInt(piece.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("trace 动作 svids 含非法编号: " + piece.trim());
                }
                double value = this.values.get().valueAt(svid, atMs).orElse(0.0);
                sampled.add(Secs2Mapper.of(ValueType.F8, value));
            }
        }
        var b = Secs2.getBuilder();
        String stime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        Secs2 body = b.list(b.uint4(trid), b.uint4(1L), b.ascii(stime), b.list(sampled));
        return new SmlParser.Message(6, 1, this.s6f1Wbit.getAsBoolean(), body);
    }

    private String resolvePlaceholders(String sml, long atMs) {
        if (sml == null || sml.indexOf('{') < 0) {
            return sml;
        }
        Matcher matcher = SVID_PLACEHOLDER.matcher(sml);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            int svid = Integer.parseInt(matcher.group(1));
            double value = this.values.get().valueAt(svid, atMs).orElse(0.0);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(formatNumber(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value) : Double.toString(value);
    }

    private static int actionInt(PlanAction action, String key, int fallback) {
        String raw = action.params().get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("动作参数 " + key + " 应为整数: " + raw);
        }
    }
}