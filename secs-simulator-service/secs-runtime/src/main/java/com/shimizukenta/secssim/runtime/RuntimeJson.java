package com.shimizukenta.secssim.runtime;

/**
 * 运行时上下文手工拼 JSON 时用的转义与小工具。
 *
 * <p>报文、台账与链路 JSON 都是直接用 {@code StringBuilder} 拼的，
 * 转义规则必须在所有拼装点保持一致，所以集中在这里。</p>
 */
final class RuntimeJson {

    private RuntimeJson() {
    }

    /** JSON 字符串转义（设备 id / 计划 id / 错误文本可能带引号或换行）。 */
    static String quote(String text) {
        if (text == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** 拼接进 JSON 字符串值内部的转义（调用方负责加引号）。 */
    static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append('\\').append('n');
                case '\r' -> sb.append('\\').append('r');
                case '\t' -> sb.append('\\').append('t');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String safeArray(String json) {
        return json == null || json.isBlank() ? "[]" : json;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}