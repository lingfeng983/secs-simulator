package com.shimizukenta.secssim.app.log;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 极简日志：单行、带时间、写 stderr。
 *
 * <p>刻意不引入日志框架：原生镜像体积是硬约束，而这里需要的只是
 * "现场能看懂一行时间 + 级别 + 原因"。stdout 留给 {@code READY} 行，
 * 打包脚本要按正则在里面取端口和 token。
 * </p>
 */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
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
        System.err.println(line("ERROR", message, cause));
    }

    /**
     * 记录一条普通信息。
     *
     * @param message 说明
     */
    public static void info(String message) {
        System.err.println(line("INFO", message, null));
    }

    private static String line(String level, String message, Throwable cause) {
        StringBuilder sb = new StringBuilder(message == null ? "" : message);
        if (cause != null) {
            sb.append(": ").append(cause);
        }
        return "[" + LocalTime.now().format(TIME) + "] " + level + " " + sb;
    }
}
