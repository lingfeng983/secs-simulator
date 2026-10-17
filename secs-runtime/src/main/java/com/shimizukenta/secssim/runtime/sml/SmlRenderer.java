package com.shimizukenta.secssim.runtime.sml;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;
import com.shimizukenta.secs.secs2.Secs2Item;

/**
 * 把 {@link Secs2} 渲染成 SML 文本。
 *
 * <p>只做渲染，不做解析：本项目的入参都来自协议层已经解析好的 {@code Secs2}。
 * 输出尽量贴近 SEMI E5 的 SML 习惯（{@code <L[n] ...>}、{@code <U4 1001>}、
 * {@code <A "TEXT">}、{@code <B 0x01>}）。</p>
 */
public final class SmlRenderer {

    /** 渲染深度上限，防御异常数据导致栈溢出。 */
    private static final int MAX_DEPTH = 32;

    private SmlRenderer() {
    }

    /**
     * 渲染。
     *
     * @param value 数据项
     * @return SML 文本
     */
    public static String render(Secs2 value) {
        return render(value, 0);
    }

    private static String render(Secs2 value, int depth) {
        if (value == null) {
            return "<>";
        }
        if (depth > MAX_DEPTH) {
            return "<...>";
        }

        Secs2Item item = value.secs2Item();

        if (item == Secs2Item.LIST) {
            StringBuilder sb = new StringBuilder("<L[").append(value.size()).append(']');
            for (int i = 0; i < value.size(); i++) {
                sb.append(' ').append(render(read(value, i), depth + 1));
            }
            return sb.append('>').toString();
        }

        if (item == Secs2Item.ASCII) {
            try {
                return "<A \"" + value.getAscii() + "\">";
            } catch (Secs2Exception e) {
                return "<A ?>";
            }
        }

        StringBuilder sb = new StringBuilder("<").append(tag(item));
        for (int i = 0; i < value.size(); i++) {
            sb.append(' ').append(scalar(item, value, i));
        }
        return sb.append('>').toString();
    }

    private static Secs2 read(Secs2 value, int index) {
        try {
            return value.get(index);
        } catch (Secs2Exception | RuntimeException e) {
            return null;
        }
    }

    private static String scalar(Secs2Item item, Secs2 value, int index) {
        try {
            return switch (item) {
                case FLOAT4 -> String.valueOf(value.getFloat(index));
                case FLOAT8 -> String.valueOf(value.getDouble(index));
                case BINARY -> String.format(java.util.Locale.ROOT, "0x%02X", value.getByte(index) & 0xFF);
                case BOOLEAN -> value.getBoolean(index) ? "0x01" : "0x00";
                default -> String.valueOf(value.getLong(index));
            };
        } catch (Secs2Exception | RuntimeException e) {
            return "?";
        }
    }

    private static String tag(Secs2Item item) {
        return switch (item) {
            case LIST -> "L";
            case ASCII -> "A";
            case BINARY -> "B";
            case BOOLEAN -> "BOOLEAN";
            case INT1 -> "I1";
            case INT2 -> "I2";
            case INT4 -> "I4";
            case INT8 -> "I8";
            case UINT1 -> "U1";
            case UINT2 -> "U2";
            case UINT4 -> "U4";
            case UINT8 -> "U8";
            case FLOAT4 -> "F4";
            case FLOAT8 -> "F8";
            case JIS8 -> "J";
            case UNICODE -> "UNICODE";
            case UNDEFINED -> "?";
        };
    }
}
