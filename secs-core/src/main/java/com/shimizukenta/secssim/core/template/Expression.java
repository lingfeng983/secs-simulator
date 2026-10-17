package com.shimizukenta.secssim.core.template;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 受限的纯函数表达式。
 *
 * <p>刻意做小：没有赋值、没有语句、没有副作用、不能调用任意代码。
 * 它只回答一个问题——"给定这些变量，这个数是多少"。
 * 需要状态的东西（积分、滞后、计数器）不属于表达式，
 * 必须做成模板或包装（见 ADR 0003）。
 * </p>
 *
 * <p>变量：</p>
 * <ul>
 *   <li>{@code t} —— 当前秒数</li>
 *   <li>{@code p.名字} —— 参数（已按 5 级覆盖解析）</li>
 *   <li>{@code in.实例.输出} —— 其它模板实例的输出</li>
 * </ul>
 *
 * <p>函数：{@code clamp} {@code lerp} {@code min} {@code max} {@code abs}
 * {@code roundTo} {@code sum} {@code avg} {@code select} {@code sin} {@code floor} {@code ceil}。
 * </p>
 */
public final class Expression {

    private final Node root;
    private final String source;
    private final Set<String> variables;

    private Expression(Node root, String source, Set<String> variables) {
        this.root = root;
        this.source = source;
        this.variables = Set.copyOf(variables);
    }

    /**
     * 解析表达式。
     *
     * @param source 源码
     * @return 表达式
     * @throws ExpressionException 语法错误
     */
    public static Expression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new ExpressionException("表达式不能为空");
        }
        Parser parser = new Parser(source);
        Node node = parser.parseExpression();
        parser.expectEnd();
        return new Expression(node, source, parser.variables());
    }

    /**
     * 求值。
     *
     * @param env 变量环境
     * @return 结果
     */
    public double evaluate(Env env) {
        return this.root.eval(env);
    }

    /**
     * 表达式里出现的全部变量名。
     *
     * @return 变量名
     */
    public Set<String> variables() {
        return this.variables;
    }

    /**
     * 原始源码。
     *
     * @return 源码
     */
    public String source() {
        return this.source;
    }

    @Override
    public String toString() {
        return this.source;
    }

    /**
     * 变量环境。
     */
    @FunctionalInterface
    public interface Env {

        /**
         * 取变量值。
         *
         * @param name 变量名（如 {@code p.gain}、{@code in.ch1.PV}、{@code t}）
         * @return 值
         */
        double variable(String name);
    }

    /** AST 节点。 */
    sealed interface Node permits Const, Var, Neg, Binary, Call {

        double eval(Env env);
    }

    record Const(double value) implements Node {
        @Override
        public double eval(Env env) {
            return this.value;
        }
    }

    record Var(String name) implements Node {
        @Override
        public double eval(Env env) {
            return env.variable(this.name);
        }
    }

    record Neg(Node operand) implements Node {
        @Override
        public double eval(Env env) {
            return -this.operand.eval(env);
        }
    }

    record Binary(char op, Node left, Node right) implements Node {
        @Override
        public double eval(Env env) {
            double a = this.left.eval(env);
            double b = this.right.eval(env);
            return switch (this.op) {
                case '+' -> a + b;
                case '-' -> a - b;
                case '*' -> a * b;
                case '/' -> a / b;
                default -> throw new ExpressionException("未知运算符: " + this.op);
            };
        }
    }

    record Call(String function, List<Node> args) implements Node {
        @Override
        public double eval(Env env) {
            double[] v = new double[this.args.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = this.args.get(i).eval(env);
            }
            return apply(this.function, v);
        }

        private static double apply(String fn, double[] v) {
            return switch (fn) {
                case "clamp" -> Math.min(v[2], Math.max(v[1], v[0]));
                case "lerp" -> v[0] + (v[1] - v[0]) * v[2];
                case "min" -> java.util.Arrays.stream(v).min().orElseThrow();
                case "max" -> java.util.Arrays.stream(v).max().orElseThrow();
                case "abs" -> Math.abs(v[0]);
                case "roundTo" -> v[1] == 0.0 ? v[0] : Math.round(v[0] / v[1]) * v[1];
                case "sum" -> java.util.Arrays.stream(v).sum();
                case "avg" -> java.util.Arrays.stream(v).average().orElseThrow();
                case "select" -> v[0] != 0.0 ? v[1] : v[2];
                case "sin" -> Math.sin(v[0]);
                case "floor" -> Math.floor(v[0]);
                case "ceil" -> Math.ceil(v[0]);
                default -> throw new ExpressionException("未知函数: " + fn);
            };
        }
    }

    /** 递归下降解析器。 */
    private static final class Parser {

        private final String src;
        private final Set<String> vars = new LinkedHashSet<>();
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        Set<String> variables() {
            return this.vars;
        }

        Node parseExpression() {
            Node left = this.parseTerm();
            for (;;) {
                this.skipSpaces();
                if (this.peek() == '+' || this.peek() == '-') {
                    char op = this.src.charAt(this.pos++);
                    left = new Binary(op, left, this.parseTerm());
                } else {
                    return left;
                }
            }
        }

        private Node parseTerm() {
            Node left = this.parseUnary();
            for (;;) {
                this.skipSpaces();
                if (this.peek() == '*' || this.peek() == '/') {
                    char op = this.src.charAt(this.pos++);
                    left = new Binary(op, left, this.parseUnary());
                } else {
                    return left;
                }
            }
        }

        private Node parseUnary() {
            this.skipSpaces();
            if (this.peek() == '-') {
                this.pos++;
                return new Neg(this.parseUnary());
            }
            if (this.peek() == '+') {
                this.pos++;
                return this.parseUnary();
            }
            return this.parsePrimary();
        }

        private Node parsePrimary() {
            this.skipSpaces();
            char c = this.peek();

            if (c == '(') {
                this.pos++;
                Node inner = this.parseExpression();
                this.skipSpaces();
                if (this.peek() != ')') {
                    throw this.error("缺少右括号");
                }
                this.pos++;
                return inner;
            }

            if (Character.isDigit(c) || c == '.') {
                return this.parseNumber();
            }

            if (Character.isLetter(c) || c == '_') {
                return this.parseIdentifier();
            }

            throw this.error("无法识别的字符 '" + c + "'");
        }

        private Node parseNumber() {
            int start = this.pos;
            while (this.pos < this.src.length()
                    && (Character.isDigit(this.src.charAt(this.pos)) || this.src.charAt(this.pos) == '.')) {
                this.pos++;
            }
            String text = this.src.substring(start, this.pos);
            try {
                return new Const(Double.parseDouble(text));
            } catch (NumberFormatException e) {
                throw this.error("非法数字: " + text);
            }
        }

        private Node parseIdentifier() {
            int start = this.pos;
            while (this.pos < this.src.length()) {
                char c = this.src.charAt(this.pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                    this.pos++;
                } else {
                    break;
                }
            }
            String name = this.src.substring(start, this.pos);
            this.skipSpaces();

            if (this.peek() != '(') {
                this.vars.add(name);
                return new Var(name);
            }

            this.pos++;
            List<Node> args = new ArrayList<>();
            this.skipSpaces();
            if (this.peek() != ')') {
                for (;;) {
                    args.add(this.parseExpression());
                    this.skipSpaces();
                    if (this.peek() == ',') {
                        this.pos++;
                    } else {
                        break;
                    }
                }
            }
            this.skipSpaces();
            if (this.peek() != ')') {
                throw this.error("函数 " + name + " 缺少右括号");
            }
            this.pos++;

            validateArity(name, args.size());
            return new Call(name, List.copyOf(args));
        }

        private static void validateArity(String name, int count) {
            switch (name) {
                case "clamp" -> {
                    if (count != 3) {
                        throw new ExpressionException("clamp 需要 3 个参数，实际 " + count);
                    }
                }
                case "lerp" -> {
                    if (count != 3) {
                        throw new ExpressionException("lerp 需要 3 个参数，实际 " + count);
                    }
                }
                case "select" -> {
                    if (count != 3) {
                        throw new ExpressionException("select 需要 3 个参数，实际 " + count);
                    }
                }
                case "roundTo" -> {
                    if (count != 2) {
                        throw new ExpressionException("roundTo 需要 2 个参数，实际 " + count);
                    }
                }
                case "abs", "sin", "floor", "ceil" -> {
                    if (count != 1) {
                        throw new ExpressionException(name + " 需要 1 个参数，实际 " + count);
                    }
                }
                case "min", "max", "sum", "avg" -> {
                    if (count < 1) {
                        throw new ExpressionException(name + " 至少需要 1 个参数");
                    }
                }
                default -> throw new ExpressionException("未知函数: " + name);
            }
        }

        void expectEnd() {
            this.skipSpaces();
            if (this.pos != this.src.length()) {
                throw this.error("表达式尾部有多余内容: '" + this.src.substring(this.pos) + "'");
            }
        }

        private char peek() {
            return this.pos < this.src.length() ? this.src.charAt(this.pos) : '\0';
        }

        private void skipSpaces() {
            while (this.pos < this.src.length() && Character.isWhitespace(this.src.charAt(this.pos))) {
                this.pos++;
            }
        }

        private ExpressionException error(String message) {
            return new ExpressionException(message + "（位置 " + this.pos + "，源码: " + this.src + "）");
        }
    }
}
