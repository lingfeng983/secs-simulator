package com.shimizukenta.secssim.app.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 极简日志：单行、带时间、写 stderr，可选追加到文件。
 *
 * <p>刻意不引入日志框架：原生镜像体积是硬约束，而这里需要的只是
 * "现场能看懂一行时间 + 级别 + 原因"。stdout 留给 {@code READY} 行，
 * 打包脚本要按正则在里面取端口和 token。
 * </p>
 *
 * <p><b>为什么默认同时写文件</b>：进程一关，stderr 的内容就没了。
 * 现场反馈"设备突然不发事件了"时，最需要的是<b>当时</b>的日志，
 * 而 stderr 只有在有人盯着终端时才留得下来。</p>
 *
 * <p><b>三个约束</b>：</p>
 * <ul>
 *   <li>写盘失败不能影响业务：一次 IO 异常只丢掉那条日志，不能抛到调用方；</li>
 *   <li>写盘不能卡住事件线程：调用方在 tick 里打日志，这里用单条 transferTo 之外
 *       的简单追加，不做 fsync——崩溃时最多丢最后几行，比拖慢 100ms 心跳划算；</li>
 *   <li>文件可以无限长：按大小轮转，保留 {@value #KEEP_ROTATED} 份历史。</li>
 * </ul>
 */
public final class Log {

    /** 带日期：只有 HH:mm:ss 的话，跨天之后根本对不上。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 单个日志文件的上限。超过就轮转，避免长期运行的设备把磁盘写满。 */
    private static final long MAX_BYTES = 8L * 1024L * 1024L;

    /** 保留的历史份数（app.log.1 … app.log.N）。 */
    private static final int KEEP_ROTATED = 3;

    /** 日志文件；{@code null} 表示只写 stderr。 */
    private static volatile Path file;

    /**
     * 常开的写句柄。
     *
     * <p>以前每行 {@code Files.writeString}（open+write+close 三次系统调用），
     * 且全程持有类锁：日志一多就把事件线程与看门狗一起拖慢。改成常开 + 每行 flush：
     * 崩溃丢行上限仍是一行，但系统调用少了一个数量级。</p>
     */
    private static BufferedWriter writer;

    /** 当前文件字节数（按 UTF-8 计）；用于轮转判断，避免每行 Files.size。 */
    private static long bytes;

    /** 当前级别；低于它的日志直接丢弃。ERROR=0 … DEBUG=3。 */
    private static volatile Level level = Level.INFO;

    /** 日志级别。顺序即严重性递减，序号用于比较。 */
    public enum Level {
        /** 只记错误。 */
        ERROR(0),
        /** 错误 + 警告 + 信息（默认）。 */
        INFO(1),
        /** 再加调试细节（每个报文的去向、计划求值等）。 */
        DEBUG(2);

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
            for (Level value : values()) {
                if (value.name().equalsIgnoreCase(name)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("未知日志级别: " + name + "（可选 error/info/debug）");
        }
    }

    private Log() {
    }

    /**
     * 把日志同时追加到指定文件（并启用轮转）。
     *
     * <p>只在启动时调用一次。目录不存在会尝试创建；创建失败则退化为只写 stderr，
     * 并把原因打到 stderr——不能让"日志不可用"变成"程序起不来"。</p>
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
            if (! Files.exists(path)) {
                Files.createFile(path);
            }
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            bytes = Files.size(path);
            file = path;
        } catch (IOException e) {
            System.err.println("[LOG] 无法启用文件日志 " + path + "：" + e
                    + "（继续使用 stderr）");
            file = null;
            closeWriter();
        }
    }

    /** 关闭写句柄（测试 reset 与重新 toFile 用）。 */
    private static void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignore) {
                /* 关不上没有更安全的去处 */
            }
            writer = null;
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
     * 记录一条调试信息：只在 DEBUG 级别才输出。
     *
     * @param message 说明
     */
    public static void debug(String message) {
        if (level.rank >= Level.DEBUG.rank) {
            write("DEBUG", message, null);
        }
    }

    /**
     * 关闭文件日志（测试用；正常进程退出时无需调用）。
     */
    public static synchronized void reset() {
        closeWriter();
        file = null;
        level = Level.INFO;
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
     * 记录一条错误，不带异常。
     *
     * @param message 说明
     */
    public static void error(String message) {
        error(message, null);
    }

    /**
     * 记录一条错误。
     *
     * @param message 说明
     * @param cause   异常，可为 {@code null}
     */
    public static void error(String message, Throwable cause) {
        write("ERROR", message, cause);
    }

    /**
     * 记录一条普通信息。
     *
     * @param message 说明
     */
    public static void info(String message) {
        write("INFO", message, null);
    }

    /**
     * 记录一条警告。
     *
     * @param message 说明
     */
    public static void warn(String message) {
        write("WARN", message, null);
    }

    private static void write(String level, String message, Throwable cause) {
        if (severity(level) > level().rank) {
            return;
        }
        String text = line(level, message, cause);
        System.err.println(text);

        Path target = file;
        if (target == null) {
            return;
        }

        synchronized (Log.class) {
            if (writer == null) {
                return;
            }
            try {
                long length = text.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (bytes + length > MAX_BYTES) {
                    rotate(target);
                }
                writer.write(text);
                writer.newLine();
                /* 每行 flush：崩溃最多丢最后一行，测试也能立刻读到 */
                writer.flush();
                bytes += length;
            } catch (IOException e) {
                /* 日志写不进去不是业务错误：只在 stderr 提一次，不要把异常抛给调用方 */
                System.err.println("[LOG] 写日志失败：" + e);
            }
        }
    }

    /**
     * 超过上限就轮转：app.log → app.log.1，app.log.1 → app.log.2 …
     *
     * <p>从最老的开始挪，避免覆盖。只在写之前检查一次，不用后台线程。</p>
     */
    private static void rotate(Path target) throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
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
        } finally {
            /* 无论轮转是否成功都必须把句柄重新打开：否则后续日志静默停写 */
            writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            bytes = 0L;
        }
    }

    /** 级别名 → 严重性序号：ERROR=0、WARN/INFO=1、DEBUG=2。 */
    private static int severity(String level) {
        return switch (level) {
            case "ERROR" -> Level.ERROR.rank;
            case "DEBUG" -> Level.DEBUG.rank;
            default -> Level.INFO.rank;
        };
    }

    private static String line(String level, String message, Throwable cause) {
        StringBuilder sb = new StringBuilder(message == null ? "" : message);
        if (cause != null) {
            sb.append(": ").append(cause);
        }
        return "[" + LocalDateTime.now().format(TIME) + "] " + level + " " + sb;
    }
}
