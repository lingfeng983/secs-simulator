package com.shimizukenta.secssim.runtime;

import java.util.ArrayList;
import java.util.List;

import com.shimizukenta.secssim.runtime.session.SectionApplyOutcome;

/**
 * 分段保存/生效响应的 JSON 拼装（从 RuntimeApi 抽出，评审 2026-11-24 A2 千行拆分）。
 * 纯静态工具，输出与拆分前逐字节一致。
 */
final class SectionsJson {

    private SectionsJson() {
        /* 纯静态工具 */
    }

    /** 这次被重置的运行态项（界面提示用）。 */
    static String resetJson(com.shimizukenta.secssim.runtime.session.SectionApplyOutcome outcome) {
        List<String> items = new ArrayList<>();
        /* 只有结构变了才会丢视图与目录编辑；变量/报告/事件的改动是换视图、不动运行态 */
        if (outcome.modelChanged()) {
            items.add("model");
            items.add("trace");
            items.add("edits");
        }
        if (outcome.valuesChanged()) {
            items.add("values");
        }
        /* 计划只有在"结构变更 → 全停装载"时才真被重置；
           目录变更走保留态换表，哪怕当前 0 条启用也不能说成"被重置" */
        if (outcome.scheduleChanged() && outcome.modelChanged()) {
            items.add("plans");
        }
        if (outcome.cycleChanged()) {
            items.add("cycle");
        }
        return jsonStrings(items);
    }

    /** 这次被保留的运行态项。 */
    static String preservedJson(com.shimizukenta.secssim.runtime.session.SectionApplyOutcome outcome) {
        List<String> items = new ArrayList<>();
        /* 计划只在结构变更时全停；其余情况换表都保留启用态与锚点 */
        if (!outcome.modelChanged()) {
            items.add("plans");
        }
        if (!outcome.valuesChanged()) {
            items.add("pins");
        }
        if (!outcome.cycleChanged()) {
            items.add("cycle");
        }
        items.add("counters");
        return jsonStrings(items);
    }


    static String problemsJson(List<String> problems) {
        StringBuilder sb = new StringBuilder("{\"ok\":false,\"error\":\"config-invalid\",\"problems\":[");
        for (int i = 0; i < problems.size(); i += 1) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(problems.get(i))).append('"');
        }
        return sb.append("]}").toString();
    }

    static String sectionsError(String code, String detail) {
        return "{\"ok\":false,\"error\":\"" + code + "\",\"detail\":\"" + escape(detail) + "\"}";
    }

    static String conflictsJson(List<ConfigSectionGuards.Conflict> conflicts) {
        StringBuilder sb = new StringBuilder("{\"ok\":false,\"error\":\"cycle-running-guard\",\"detail\":[");
        boolean first = true;
        for (ConfigSectionGuards.Conflict conflict : conflicts) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"kind\":\"").append(conflict.kind())
                    .append("\",\"name\":\"").append(escape(conflict.name()))
                    .append("\",\"ceids\":[");
            for (int i = 0; i < conflict.ceids().size(); i += 1) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(conflict.ceids().get(i));
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    static String overwritesJson(List<ConfigSectionGuards.ConstantOverwrite> overwrites) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ConfigSectionGuards.ConstantOverwrite item : overwrites) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"ecid\":").append(item.ecid())
                    .append(",\"runtime\":").append(item.runtime())
                    .append(",\"config\":").append(item.config()).append('}');
        }
        return sb.append(']').toString();
    }

    /** 一组字符串 → JSON 数组（reset / preserved 用）。 */
    static String jsonStrings(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i += 1) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(items.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    static String sectionsJson(List<ConfigSection> sections) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ConfigSection section : sections) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(section.wire()).append('"');
        }
        return sb.append(']').toString();
    }

    static String number(Long value) {
        return value == null ? "0" : String.valueOf(value);
    }

    /** JSON 字符串转义（本类只拼小对象，不引第三方）。 */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i += 1) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

}
