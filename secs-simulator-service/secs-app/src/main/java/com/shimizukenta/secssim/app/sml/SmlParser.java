package com.shimizukenta.secssim.app.sml;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;

/**
 * SML 解析（手写报文用）。
 *
 * <p>项目原本只有 {@link SmlRenderer}，没有反向解析。手动发送需要把
 * 工程师写的 SML 变回 {@link Secs2}，所以这里实现一个<b>受控子集</b>：</p>
 *
 * <ul>
 *   <li>报文头：{@code S1F1}、{@code S1F1 W}、{@code S1F1 W.}、
 *       {@code S6F11 NW}（primary 但不要回复，wbit=false）</li>
 *   <li>列表：{@code <L [n] ...>} / {@code <L ...>}</li>
 *   <li>ASCII：{@code <A "TEXT">}，支持 {@code \\ \" \n \r \t}</li>
 *   <li>二进制：{@code <B 0x81 0x00>}</li>
 *   <li>布尔：{@code <BOOLEAN 0x01>} / {@code TRUE}</li>
 *   <li>整数：{@code <U4 1001>}、{@code <I2 -1>}、{@code <U8 0x10>} 等</li>
 *   <li>浮点：{@code <F4 1.5>}、{@code <F8 2.5>}</li>
 * </ul>
 *
 * <p>不是完整的 SML 实现：JIS8、UNICODE、数组简写等一律报错。
 * 不确定的语法宁可拒绝，也不要悄悄发出一条与工程师预期不同的报文。</p>
 */
public final class SmlParser {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_ITEMS = 10_000;
    private static final int MAX_TEXT_LENGTH = 64 * 1024;
    private static final Secs2Builder BUILDER = Secs2.getBuilder();

    private SmlParser() {
    }

    /**
     * 一条可发送的报文。
     *
     * @param stream   流号
     * @param function 功能号
     * @param wbit     是否带 W 位
     * @param body     SECS-II 数据；没有 body 时为空数据
     */
    public record Message(int stream, int function, boolean wbit, Secs2 body) {
    }

    /**
     * 解析 SML。
     *
     * @param text SML 文本
     * @return 报文
     * @throws SmlParseException 语法错误
     */
    public static Message parse(String text) throws SmlParseException {
        if (text == null) {
            throw new SmlParseException("SML 不能为空");
        }
        /* Windows 编辑器常给 .sml 文件加 BOM；发送模板文件时不该因此报错 */
        String source = !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
        if (source.isBlank()) {
            throw new SmlParseException("SML 不能为空");
        }
        if (source.length() > MAX_TEXT_LENGTH) {
            throw new SmlParseException("SML 超过 " + MAX_TEXT_LENGTH + " 字符上限");
        }
        return new Parser(source).message();
    }

    private static final class Parser {

        private final String src;
        private int pos;
        private int items;

        Parser(String src) {
            this.src = src;
        }

        Message message() throws SmlParseException {
            skipSpaces();
            expectIgnoreCase('S', "报文头应以 S 开头");
            int stream = number("流号");
            expectIgnoreCase('F', "流号后应紧跟 F");
            int function = number("功能号");

            skipSpaces();
            /* 系统默认：primary（奇数 function）带 W 等回复，secondary（偶数）永远不带。
               这是 SECS 的惯例默认值，但并非所有 primary 都需要回复——因此补一个
               显式关闭的写法 {@code NW}（no wait）：S6F11 NW 发出的报文 wbit=false。
               在此之前语法上没有任何方式表达「不带 W 的 primary」，
               手工/计划里写 S6F11 只能被迫等满 T3。 */
            boolean wbit = function % 2 == 1;
            if (matchesIgnoreCase("NW")) {
                if (function % 2 == 0) {
                    throw error("secondary 报文（偶数 function）本来就不带 W 位，不需要 NW");
                }
                wbit = false;
                skipSpaces();
            } else if (peekIgnoreCase('W')) {
                pos++;
                wbit = true;
                if (function % 2 == 0) {
                    throw error("secondary 报文（偶数 function）不能带 W 位");
                }
                skipSpaces();
            }
            if (peek() == '.') {
                pos++;
                skipSpaces();
            }

            if (stream < 1 || stream > 127) {
                throw error("流号必须在 1..127");
            }
            if (function < 0 || function > 255) {
                throw error("功能号必须在 0..255");
            }

            Secs2 body;
            if (atEnd()) {
                /* S1F1 W. 这种 header-only 报文 */
                body = BUILDER.empty();
            } else {
                body = item(0);
                skipSpaces();
                if (peek() == '.') {
                    pos++;
                    skipSpaces();
                }
            }
            if (!atEnd()) {
                throw error("报文末尾有多余内容");
            }
            return new Message(stream, function, wbit, body);
        }

        private Secs2 item(int depth) throws SmlParseException {
            if (depth > MAX_DEPTH) {
                throw error("嵌套超过 " + MAX_DEPTH + " 层");
            }
            if (++this.items > MAX_ITEMS) {
                throw error("数据项超过 " + MAX_ITEMS + " 个");
            }

            skipSpaces();
            expect('<', "数据项应以 < 开头");
            skipSpaces();
            String type = readType();
            if (type.isEmpty()) {
                throw error("缺少数据类型（L/A/B/U4 等）");
            }
            skipSpaces();

            /* [n] 只是提示；以实际数据为准。仍然要求语法完整。 */
            if (peek() == '[') {
                pos++;
                skipSpaces();
                number("数据项个数");
                skipSpaces();
                expect(']', "数据项个数缺少 ]");
                skipSpaces();
            }

            Secs2 value = switch (type) {
                case "L", "LIST" -> itemList(depth);
                case "A", "ASCII" -> itemAscii();
                case "B", "BINARY" -> itemBinary();
                case "BOOLEAN", "BOOL" -> itemBoolean();
                case "I1" -> BUILDER.int1(integerValues("I1"));
                case "I2" -> BUILDER.int2(integerValues("I2"));
                case "I4" -> BUILDER.int4(integerValues("I4"));
                case "I8" -> BUILDER.int8(integerValues("I8"));
                case "U1" -> BUILDER.uint1(unsignedValues("U1"));
                case "U2" -> BUILDER.uint2(unsignedValues("U2"));
                case "U4" -> BUILDER.uint4(unsignedValues("U4"));
                case "U8" -> BUILDER.uint8(unsignedValues("U8"));
                case "F4" -> BUILDER.float4(floatValues());
                case "F8" -> BUILDER.float8(doubleValues());
                default -> throw error("不支持的 SML 类型: " + type);
            };
            skipSpaces();
            expect('>', "数据项缺少 >");
            return value;
        }

        private Secs2 itemList(int depth) throws SmlParseException {
            List<Secs2> children = new ArrayList<>();
            for (;;) {
                skipSpaces();
                if (atEnd()) {
                    throw error("列表缺少 >");
                }
                if (peek() == '>') {
                    return BUILDER.list(children);
                }
                children.add(item(depth + 1));
            }
        }

        private Secs2 itemAscii() throws SmlParseException {
            skipSpaces();
            expect('"', "ASCII 数据应以双引号开始");
            StringBuilder sb = new StringBuilder();
            for (;;) {
                if (atEnd()) {
                    throw error("ASCII 字符串缺少结束引号");
                }
                char c = this.src.charAt(this.pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw error("ASCII 字符串以反斜杠结尾");
                    }
                    char escaped = this.src.charAt(this.pos++);
                    switch (escaped) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append(escaped);
                    }
                } else {
                    sb.append(c);
                }
            }
            skipSpaces();
            return BUILDER.ascii(sb);
        }

        private Secs2 itemBinary() throws SmlParseException {
            List<String> tokens = scalarTokens();
            byte[] bytes = new byte[tokens.size()];
            for (int i = 0; i < tokens.size(); i++) {
                BigInteger value = parseInteger(tokens.get(i), "二进制值");
                if (value.signum() < 0 || value.bitLength() > 8) {
                    throw error("二进制值必须在 0..255: " + tokens.get(i));
                }
                bytes[i] = value.byteValue();
            }
            return BUILDER.binary(bytes);
        }

        private Secs2 itemBoolean() throws SmlParseException {
            List<String> tokens = scalarTokens();
            boolean[] values = new boolean[tokens.size()];
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if ("TRUE".equalsIgnoreCase(token) || "1".equals(token)
                        || "0X01".equalsIgnoreCase(token)) {
                    values[i] = true;
                } else if ("FALSE".equalsIgnoreCase(token) || "0".equals(token)
                        || "0X00".equalsIgnoreCase(token)) {
                    values[i] = false;
                } else {
                    throw error("布尔值只能是 0x00/0x01/TRUE/FALSE: " + token);
                }
            }
            return BUILDER.bool(values);
        }

        private List<BigInteger> integerValues(String type) throws SmlParseException {
            List<String> tokens = scalarTokens();
            List<BigInteger> values = new ArrayList<>(tokens.size());
            final int bits = signedBits(type);
            final BigInteger min = BigInteger.ONE.shiftLeft(bits - 1).negate();
            final BigInteger max = BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE);
            for (String token : tokens) {
                BigInteger value = parseInteger(token, type);
                if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                    throw error(type + " 超出值域 " + min + ".." + max + ": " + token);
                }
                values.add(value);
            }
            return values;
        }

        /**
         * 无符号整型：负数与上界都在<b>解析期</b>拒绝。
         *
         * <p>只查负数的话，{@code <U1 300>} 会一路走到协议层编码时才抛
         * {@code Secs2ValueOutOfRangeException}——用户看到的是 500，而不是"U1 只能是 0..255"。</p>
         */
        private List<BigInteger> unsignedValues(String type) throws SmlParseException {
            List<String> tokens = scalarTokens();
            List<BigInteger> values = new ArrayList<>(tokens.size());
            final BigInteger max = BigInteger.ONE.shiftLeft(unsignedBits(type)).subtract(BigInteger.ONE);
            for (String token : tokens) {
                BigInteger value = parseInteger(token, type);
                if (value.signum() < 0 || value.compareTo(max) > 0) {
                    throw error(type + " 超出值域 0.." + max + ": " + token);
                }
                values.add(value);
            }
            return values;
        }

        private static int signedBits(String type) {
            return switch (type) {
                case "I1" -> 8;
                case "I2" -> 16;
                case "I4" -> 32;
                default -> 64;
            };
        }

        private static int unsignedBits(String type) {
            return switch (type) {
                case "U1" -> 8;
                case "U2" -> 16;
                case "U4" -> 32;
                default -> 64;
            };
        }

        private List<Float> floatValues() throws SmlParseException {
            List<String> tokens = scalarTokens();
            List<Float> values = new ArrayList<>(tokens.size());
            for (String token : tokens) {
                try {
                    values.add(Float.parseFloat(token));
                } catch (NumberFormatException e) {
                    throw error("非法 F4 数值: " + token);
                }
            }
            return values;
        }

        private List<Double> doubleValues() throws SmlParseException {
            List<String> tokens = scalarTokens();
            List<Double> values = new ArrayList<>(tokens.size());
            for (String token : tokens) {
                try {
                    values.add(Double.parseDouble(token));
                } catch (NumberFormatException e) {
                    throw error("非法 F8 数值: " + token);
                }
            }
            return values;
        }

        private List<String> scalarTokens() throws SmlParseException {
            List<String> tokens = new ArrayList<>();
            for (;;) {
                skipSpaces();
                if (atEnd()) {
                    throw error("数据项缺少 >");
                }
                if (peek() == '>') {
                    return tokens;
                }
                tokens.add(readValueToken());
            }
        }

        private String readValueToken() throws SmlParseException {
            int start = pos;
            while (!atEnd()) {
                char c = this.src.charAt(pos);
                if (Character.isWhitespace(c) || c == '>') {
                    break;
                }
                pos++;
            }
            if (start == pos) {
                throw error("缺少数据值");
            }
            return this.src.substring(start, pos);
        }

        private String readType() throws SmlParseException {
            int start = pos;
            while (!atEnd()) {
                char c = this.src.charAt(pos);
                if (Character.isLetterOrDigit(c)) {
                    pos++;
                } else {
                    break;
                }
            }
            return this.src.substring(start, pos).toUpperCase(java.util.Locale.ROOT);
        }

        private BigInteger parseInteger(String token, String label) throws SmlParseException {
            try {
                if (token.startsWith("0x") || token.startsWith("0X")) {
                    return new BigInteger(token.substring(2), 16);
                }
                return new BigInteger(token);
            } catch (NumberFormatException e) {
                throw error("非法" + label + ": " + token);
            }
        }

        private int number(String label) throws SmlParseException {
            int start = pos;
            while (!atEnd() && Character.isDigit(this.src.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw error("缺少" + label);
            }
            try {
                return Integer.parseInt(this.src.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error(label + "超出范围");
            }
        }

        private void skipSpaces() {
            while (!atEnd() && Character.isWhitespace(this.src.charAt(pos))) {
                pos++;
            }
        }

        private boolean atEnd() {
            return pos >= this.src.length();
        }

        private char peek() {
            return atEnd() ? '\0' : this.src.charAt(pos);
        }

        private boolean peekIgnoreCase(char expected) {
            return !atEnd() && Character.toUpperCase(this.src.charAt(pos)) == Character.toUpperCase(expected);
        }

        /**
         * 大小写不敏感地匹配并吃掉一个关键字（必须整词，后面不能紧跟字母数字）。
         *
         * @param keyword 关键字
         * @return 是否匹配并已推进 pos
         */
        private boolean matchesIgnoreCase(String keyword) {
            int end = pos + keyword.length();
            if (end > this.src.length()) {
                return false;
            }
            if (!this.src.regionMatches(true, pos, keyword, 0, keyword.length())) {
                return false;
            }
            /* 整词匹配：避免把 S6F11NWXYZ 吃成 S6F11NW */
            if (end < this.src.length()) {
                char next = this.src.charAt(end);
                if (Character.isLetterOrDigit(next) || next == '_') {
                    return false;
                }
            }
            pos = end;
            return true;
        }

        private void expect(char expected, String message) throws SmlParseException {
            if (atEnd() || this.src.charAt(pos) != expected) {
                throw error(message);
            }
            pos++;
        }

        private void expectIgnoreCase(char expected, String message) throws SmlParseException {
            if (!peekIgnoreCase(expected)) {
                throw error(message);
            }
            pos++;
        }

        private SmlParseException error(String message) {
            return new SmlParseException(message + "（位置 " + pos + "）");
        }
    }
}
