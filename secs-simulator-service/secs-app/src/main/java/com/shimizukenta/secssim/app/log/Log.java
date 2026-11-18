package com.shimizukenta.secssim.app.log;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 极简日志：单行、带时间与来源、写 stderr，可选追加到文件。
 *
 * <p>刻意不引入日志框架：原生镜像体积是硬约束，而这里需要的只是
 * "现场能看懂一行时间 + 级别 + 谁 + 原因"。stdout 留给 {@code READY} 行，
 * 打包脚本要按正则在里面取端口和 token。</p>
 *
 * <p><b>行格式</b>：{@code [yyyy-MM-dd HH:mm:ss.SSS] LEVEL [ctx] source message}。
 * {@code ctx} 是可选上下文（设备 id / 请求 id），为空时整段省略；{@code source}
 * 是来源的短类名，由 {@link #of(Class)} 绑定。</p>
 *
 * <p><b>为什么默认同时写文件</b>：进程一关，stderr 的内容就没了。
 * 现场反馈"设备突然不发事件了"时，最需要的是<b>当时</b>的日志，
 * 而 stderr 只有在有人盯着终端时才留得下来。</p>
 *
 * <p><b>约束</b>：</p>
 * <ul>
 *   <li>写盘失败不能影响业务：一次 IO 异常只丢掉那条日志，不能抛到调用方；</li>
 *   <li>写盘不能卡住事件线程：常开句柄 + 每行 flush，不做 fsync——崩溃时最多丢最后一行；</li>
 *   <li>文件可以无限长：按大小轮转，保留 {@value #KEEP_ROTATED} 份历史；</li>
 *   <li>stderr 与文件的顺序必须一致：两者在同一个类锁内写出。</li>
 * </ul>
 *
 * <p><b>来源标识</b>：新代码用 {@code private static final Log.Facade LOG = Log.of(Foo.class);}
 * 拿到带类名的 logger；旧调用点（{@code Log.info(String)} 等）保持原签名，
 * 来源记为 {@code app}。</p>
 */
public final class Log {

    /** 带日期：只有 HH:mm:ss 的话，跨天之后根本对不上。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 单个日志文件的上限。超过就轮转，避免长期运行的设备把磁盘写满。 */
    private static final long MAX_BYTES = 8L * 1024L * 1024L;

    /** 保留的历史份数（app.log.1 … app.log.N）。 */
    private static final int KEEP_ROTATED = 3;

    /** 单条消息上限（字符）。超长的 SML / JSON 会撑爆一行，也拖慢终端。 */
    private static final int MAX_MESSAGE_CHARS = 2_048;

    /** 堆栈每行缩进，跟正文区分开。 */
    private static final String STACK_INDENT = "        ";

    /** 级别列宽：最长是 {@code ERROR}/{@code DEBUG}/{@code TRACE} 五个字符。 */
    private static final int LEVEL_WIDTH = 5;

    /** 来源列宽：短类名对齐，便于人眼扫。 */
    private static final int SOURCE_WIDTH = 8;

    /** 日志文件；{@code null} 表示只写 stderr。 */
    private static volatile Path file;

    /**
     * 常开的写句柄（带缓冲）。
     *
     * <p>以前每行 {@code Files.writeString}（open+write+close 三次系统调用），
     * 且全程持有类锁：日志一多就把事件线程与看门狗一起拖慢。改成常开 + 每行 flush：
     * 崩溃丢行上限仍是一行，但系统调用少了一个数量级。</p>
     */
    private static OutputStream out;

    /** 当前文件字节数（按 UTF-8 计）；用于轮转判断，避免每行 Files.size。 */
    private static long bytes;

    /** 当前级别；高于它的日志直接丢弃。ERROR=0 … TRACE=4。 */
    private static volatile Level level = Level.INFO;

    /**
     * 当前线程的上下文（设备 id / 请求 id）。
     *
     * <p>{@link ThreadLocal} 而不是全局变量：虚拟线程里一次请求一个线程，
     * 上下文天然隔离；用全局变量会在并发请求间串台。</p>
     */
    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    /** 日志级别。顺序即严重性递减，序号用于比较（越小越严重）。 */
    public enum Level {
        /** 需要人介入的失败：功能没做成。 */
        ERROR(0),
        /** 异常但已降级/已忽略：功能继续，只是不完美。 */
        WARN(1),
        /** 关键业务节点（默认）。 */
        INFO(2),
        /** 排障细节（每个报文的去向、计划求值等）。 */
        DEBUG(3),
        /** 逐字节级别的追踪，只在深挖协议时打开。 */
        TRACE(4);

        /** 严重性序号，越大越啰嗦。 */
        final int rank;

        Level(int rank) {
            this.rank = rank;
        }

        /**
         * 按名字解析（不区分大小写），用于命令行与接口。
         *
         * @param name 级别名
         * @return 解析结果；不认识时抛 {@link IllegalArgumentException}
         */
        public static Level parse(String name) {
            if (name != null) {
                for (Level value : values()) {
                    if (value.name().equalsIgnoreCase(name.trim())) {
                        return value;
                    }
                }
            }
            throw new IllegalArgumentException("未知日志级别: " + name + "（可选 error/warn/info/debug/trace）");
        }
    }

    /**
     * 来源绑定后的轻量 logger。
     *
     * <p>只持有一个短类名，可安全地放在 {@code static final} 字段里。
     * 与静态方法分开是因为 Java 不允许同签名的静态/实例方法共存，
     * 而旧的 {@code Log.info(String)} 必须保留（全仓 150+ 调用点）。</p>
     */
    public static final class Facade {

        private final String source;

        private Facade(String source) {
            this.source = source;
        }

        /** 绑定的来源名。 */
        public String source() {
            return this.source;
        }

        /** 该级别当前是否会输出（避免调用方白拼参数）。 */
        public boolean isEnabled(Level lvl) {
            return lvl != null && rankOf(lvl) <= rankOf(level);
        }

        public void error(String message) {
            emit(Level.ERROR, null, this.source, message, null, false);
        }

        public void error(String message, Object... args) {
            emitArgs(Level.ERROR, this.source, message, args);
        }

        public void error(String message, Throwable cause) {
            emit(Level.ERROR, null, this.source, message, cause, false);
        }

        public void error(String message, Object[] args, Throwable cause) {
            emit(Level.ERROR, null, this.source, format(message, args), cause, false);
        }

        public void warn(String message) {
            emit(Level.WARN, null, this.source, message, null, false);
        }

        public void warn(String message, Object... args) {
            emitArgs(Level.WARN, this.source, message, args);
        }

        public void warn(String message, Throwable cause) {
            emit(Level.WARN, null, this.source, message, cause, false);
        }

        public void warn(String message, Object[] args, Throwable cause) {
            emit(Level.WARN, null, this.source, format(message, args), cause, false);
        }

        public void info(String message) {
            emit(Level.INFO, null, this.source, message, null, false);
        }

        public void info(String message, Object... args) {
            emitArgs(Level.INFO, this.source, message, args);
        }

        public void info(String message, Throwable cause) {
            emit(Level.INFO, null, this.source, message, cause, false);
        }

        public void info(String message, Object[] args, Throwable cause) {
            emit(Level.INFO, null, this.source, format(message, args), cause, false);
        }

        public void debug(String message) {
            emit(Level.DEBUG, null, this.source, message, null, false);
        }

        public void debug(String message, Object... args) {
            emitArgs(Level.DEBUG, this.source, message, args);
        }

        public void debug(String message, Throwable cause) {
            emit(Level.DEBUG, null, this.source, message, cause, false);
        }

        public void debug(String message, Object[] args, Throwable cause) {
            emit(Level.DEBUG, null, this.source, format(message, args), cause, false);
        }

        public void trace(String message) {
            emit(Level.TRACE, null, this.source, message, null, false);
        }

        public void trace(String message, Object... args) {
            emitArgs(Level.TRACE, this.source, message, args);
        }

        public void trace(String message, Throwable cause) {
            emit(Level.TRACE, null, this.source, message, cause, false);
        }

        public void trace(String message, Object[] args, Throwable cause) {
            emit(Level.TRACE, null, this.source, format(message, args), cause, false);
        }

        /**
         * {@code Object...} 形态：末位是 {@link Throwable} 时当作 cause，
         * 不参与 {@code {}} 占位替换。
         */
        private void emitArgs(Level lvl, String source, String message, Object[] args) {
            if (rankOf(lvl) > rankOf(level)) {
                return;
            }
            Throwable cause = null;
            Object[] params = args;
            if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable t) {
                cause = t;
                params = new Object[args.length - 1];
                System.arraycopy(args, 0, params, 0, params.length);
            }
            emit(lvl, null, source, format(message, params), cause, false);
        }
    }

    /**
     * 绑定来源的 logger。
     *
     * @param owner 来源类；{@code null} 时记为 {@code app}
     * @return 可缓存在静态字段里的 facade
     */
    public static Facade of(Class<?> owner) {
        String name = owner == null ? "" : owner.getSimpleName();
        return new Facade(name.isEmpty() ? "app" : name);
    }

    /**
     * 上下文作用域：{@code try (var ignored = Log.scope(deviceId)) { ... }}。
     *
     * <p>块内的日志行会带上 {@code [deviceId]}；支持嵌套，退出时恢复外层值。</p>
     */
    public static final class Scope implements AutoCloseable {

        private final String previous;
        private boolean closed;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            if (this.previous == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(this.previous);
            }
        }
    }

    /**
     * 打开一个上下文作用域。
     *
     * @param ctx 设备 id / 请求 id；{@code null} 或空串表示清空
     * @return 需在 finally / try-with-resources 中关闭
     */
    public static Scope scope(String ctx) {
        String previous = CONTEXT.get();
        if (ctx == null || ctx.isEmpty()) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(ctx);
        }
        return new Scope(previous);
    }

    private Log() {
    }

    /**
     * 把日志同时追加到指定文件（并启用轮转）。
     *
     * <p>只在启动时调用一次。目录不存在会尝试创建；创建失败则退化为只写 stderr，
     * 并把原因打到 stderr——不能让"日志不可用"变成"程序起不来"。</p>
     *
     * <p>新建的文件尽量设成 {@code 0600}：日志里有设备地址与报文内容，
     * 同机其他账号没有理由能读。非 POSIX 文件系统（Windows）上忽略。</p>
     *
     * @param path 日志文件路径
     */
    public static synchronized void toFile(Path path) {
        closeWriter();
        try {
            Path dir = path.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            createOwnerOnly(path);
            openWriter(path);
            file = path;
        } catch (IOException e) {
            System.err.println("[LOG] 无法启用文件日志 " + path + "：" + e
                    + "（继续使用 stderr）");
            file = null;
            closeWriter();
        }
    }

    private static void createOwnerOnly(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        try {
            Files.createFile(path,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            /* Windows / 非 POSIX 文件系统：没有权限位可设，退回普通创建 */
            Files.createFile(path);
        }
    }

    /** 打开（或重新打开）写句柄，并把 {@link #bytes} 对齐到实际文件大小。 */
    private static void openWriter(Path path) throws IOException {
        out = new BufferedOutputStream(Files.newOutputStream(path,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND), 8 * 1024);
        bytes = Files.exists(path) ? Files.size(path) : 0L;
    }

    /** 关闭写句柄（测试 reset 与重新 toFile 用）。 */
    private static void closeWriter() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignore) {
                /* 关不上没有更安全的去处 */
            }
            out = null;
        }
        bytes = 0L;
    }

    /**
     * 设置日志级别（运行时随时可调）。
     *
     * @param next 新级别
     */
    public static void setLevel(Level next) {
        level = next == null ? Level.INFO : next;
    }

    /**
     * 当前日志级别。
     *
     * @return 级别
     */
    public static Level level() {
        return level;
    }

    /**
     * 当前是否启用了文件日志。
     *
     * @return 启用返回 {@code true}
     */
    public static boolean fileEnabled() {
        return file != null;
    }

    /**
     * 关闭文件日志（测试用；正常进程退出时无需调用）。
     */
    public static synchronized void reset() {
        closeWriter();
        file = null;
        level = Level.INFO;
        CONTEXT.remove();
    }

    /* ---------- 旧签名（保留：全仓 150+ 调用点，来源记为 app） ---------- */

    /**
     * 记录一条错误，不带异常。
     *
     * @param message 说明
     */
    public static void error(String message) {
        emit(Level.ERROR, null, "app", message, null, false);
    }

    /**
     * 记录一条错误。
     *
     * @param message 说明
     * @param cause   异常，可为 {@code null}
     */
    public static void error(String message, Throwable cause) {
        emit(Level.ERROR, null, "app", message, cause, false);
    }

    /**
     * 记录一条普通信息。
     *
     * @param message 说明
     */
    public static void info(String message) {
        emit(Level.INFO, null, "app", message, null, false);
    }

    /**
     * 记录一条警告。
     *
     * @param message 说明
     */
    public static void warn(String message) {
        emit(Level.WARN, null, "app", message, null, false);
    }

    /**
     * 记录一条调试信息：只在 DEBUG 及以上级别才输出。
     *
     * @param message 说明
     */
    public static void debug(String message) {
        emit(Level.DEBUG, null, "app", message, null, false);
    }

    /**
     * 启动横幅：固定 7 行，让现场第一眼就能确认"用的是哪个版本、哪个库、听哪个端口"。
     *
     * <p>不受当前级别影响——启动信息本身就是为了在级别配错时也能看到。
     * 配置库 / 运行态库的 schema 版本拿不到时不打印括号部分。</p>
     *
     * @param version   程序版本，如 {@code 3.2.0}
     * @param workDir   工作目录
     * @param configDb  配置库路径
     * @param runtimeDb 运行态库路径
     * @param bindAddr  监听地址
     * @param port      监听端口
     * @param logFile   日志文件
     * @param level     生效的日志级别
     */
    public static void banner(String version, Path workDir, Path configDb, Path runtimeDb,
                              String bindAddr, int port, Path logFile, Level level) {
        Level lvl = level == null ? Log.level : level;
        emit(Level.INFO, "main", "app", "SECS 模拟器 v" + version + " 启动", null, true);
        emit(Level.INFO, "main", "app", "工作目录  " + absolute(workDir), null, true);
        emit(Level.INFO, "main", "app", "配置库    " + absolute(configDb), null, true);
        emit(Level.INFO, "main", "app", "运行态库  " + absolute(runtimeDb), null, true);
        emit(Level.INFO, "main", "http", "监听      http://" + bindAddr + ":" + port, null, true);
        emit(Level.INFO, "main", "app", "日志级别  " + lvl.name() + " → "
                + absolute(logFile) + " (" + (MAX_BYTES / (1024 * 1024)) + "MB × " + KEEP_ROTATED + ")", null, true);
        emit(Level.WARN, "main", "http", "CORS 白名单未配置，仅允许本机来源", null, true);
    }

    private static String absolute(Path path) {
        return path == null ? "-" : path.toAbsolutePath().normalize().toString();
    }

    /* ---------- 核心 ---------- */

    /**
     * 写一行日志。
     *
     * @param ctxOverride 非 {@code null} 时覆盖线程上下文（横幅用）
     * @param force       {@code true} 时无视级别阈值
     */
    private static void emit(Level lvl, String ctxOverride, String source, String message,
                             Throwable cause, boolean force) {
        if (!force && rankOf(lvl) > rankOf(level)) {
            return;
        }
        String ctx = ctxOverride != null ? ctxOverride : CONTEXT.get();
        String text = compose(lvl, ctx, source, truncate(message), cause);

        /*
         * stderr 与文件必须在同一把锁里：否则多线程下终端看到的顺序和文件里的
         * 顺序会不一致，排障时"谁先谁后"直接失真。
         */
        synchronized (Log.class) {
            System.err.println(text);
            writeToFile(text);
        }
    }

    private static String compose(Level lvl, String ctx, String source, String message, Throwable cause) {
        StringBuilder sb = new StringBuilder(64 + message.length());
        sb.append('[').append(LocalDateTime.now().format(TIME)).append("] ");
        sb.append(pad(lvl.name(), LEVEL_WIDTH)).append(' ');
        if (ctx != null && !ctx.isEmpty()) {
            sb.append('[').append(ctx).append("] ");
        }
        sb.append(pad(source, SOURCE_WIDTH)).append(' ');
        sb.append(message);
        if (cause != null) {
            String stack = stack(cause, lvl);
            if (stack.isEmpty()) {
                /* 该级别不打堆栈：至少留一句异常类型，否则这条日志没有信息量 */
                sb.append(": ").append(cause);
            } else {
                sb.append(stack);
            }
        }
        return sb.toString();
    }

    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * 堆栈策略：ERROR 打完整链，WARN 只打类型 + 首个帧，其余不打。
     *
     * <p>WARN 的调用点都是"已降级、继续运行"，完整堆栈只会把日志刷爆；
     * 但至少要给出一行 {@code at}，否则无法判断是哪个文件。</p>
     */
    private static String stack(Throwable cause, Level lvl) {
        if (rankOf(lvl) >= rankOf(Level.INFO)) {
            return "";
        }
        StringWriter sw = new StringWriter(512);
        cause.printStackTrace(new PrintWriter(sw));
        String[] lines = sw.toString().split("\\R");
        int count = lvl == Level.ERROR ? lines.length : Math.min(lines.length, 2);
        StringBuilder sb = new StringBuilder(64 * count);
        for (int i = 0; i < count; i++) {
            sb.append('\n').append(STACK_INDENT).append(lines[i]);
        }
        return sb.toString();
    }

    /** 单条消息超长时截断，并把原始长度带上——否则看不出"少了一截"。 */
    private static String truncate(String message) {
        String text = message == null ? "" : message;
        if (text.length() <= MAX_MESSAGE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_MESSAGE_CHARS) + "…(truncated, total=" + text.length() + ")";
    }

    /**
     * {@code {}} 占位替换（按出现顺序）。级别关闭时调用方已提前返回，
     * 这里只处理真正要输出的那条。
     */
    private static String format(String pattern, Object[] args) {
        if (pattern == null) {
            return "";
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        StringBuilder sb = new StringBuilder(pattern.length() + 32);
        int arg = 0;
        int from = 0;
        while (from < pattern.length()) {
            int at = pattern.indexOf("{}", from);
            if (at < 0 || arg >= args.length) {
                break;
            }
            sb.append(pattern, from, at).append(stringify(args[arg++]));
            from = at + 2;
        }
        sb.append(pattern, from, pattern.length());
        return sb.toString();
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Throwable t) {
            return t.toString();
        }
        return String.valueOf(value);
    }

    /** 调用方必须已持有 {@code Log.class} 锁。 */
    private static void writeToFile(String text) {
        OutputStream target = out;
        if (target == null) {
            return;
        }
        /* 只编码一次：长度判断与写盘用同一份 byte[] */
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        long length = raw.length + 1L;
        try {
            if (bytes + length > MAX_BYTES) {
                rotate(file);
                target = out;
                if (target == null) {
                    return;
                }
            }
            target.write(raw);
            target.write('\n');
            /* 每行 flush：崩溃最多丢最后一行，测试也能立刻读到 */
            target.flush();
            bytes += length;
        } catch (IOException e) {
            /* 日志写不进去不是业务错误：只在 stderr 提一次，不要把异常抛给调用方 */
            System.err.println("[LOG] 写日志失败：" + e);
        }
    }

    /**
     * 超过上限就轮转：app.log → app.log.1，app.log.1 → app.log.2 …
     *
     * <p>从最老的开始挪，避免覆盖。只在写之前检查一次，不用后台线程。</p>
     */
    private static void rotate(Path target) {
        if (target == null) {
            return;
        }
        closeWriter();
        try {
            for (int i = KEEP_ROTATED - 1; i >= 1; i--) {
                Path older = target.resolveSibling(target.getFileName() + "." + i);
                if (Files.exists(older)) {
                    Files.move(older, target.resolveSibling(target.getFileName() + "." + (i + 1)),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.move(target, target.resolveSibling(target.getFileName() + ".1"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[LOG] 轮转日志失败（继续追加当前文件）：" + e);
        } finally {
            /* 无论轮转是否成功都必须把句柄重新打开：否则后续日志静默停写 */
            try {
                openWriter(target);
            } catch (IOException e) {
                System.err.println("[LOG] 重新打开日志失败（后续日志只写 stderr）：" + e);
                out = null;
            }
        }
    }

    /**
     * 级别 → 严重性序号。
     *
     * <p>刻意用完整枚举 switch 且不写 default：将来新增级别时这里会编译失败，
     * 而不是悄悄落到 INFO 档（旧实现就是这么让 WARN 与 INFO 同档的）。</p>
     */
    static int rankOf(Level lvl) {
        return switch (lvl) {
            case ERROR -> 0;
            case WARN -> 1;
            case INFO -> 2;
            case DEBUG -> 3;
            case TRACE -> 4;
        };
    }
}
