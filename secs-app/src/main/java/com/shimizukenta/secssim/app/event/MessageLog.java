package com.shimizukenta.secssim.app.event;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.secssim.app.log.Log;

/**
 * 报文落盘与最近历史。
 *
 * <p>SSE 只负责“现在”。现场排障最常问的是“刚才那条 S6F11 到底发了没有”，
 * 所以每条报文同时追加到 {@code <work-dir>/logs/messages.jsonl}，
 * 并保留最近 {@value #DEFAULT_RECENT} 条在内存里给 HTTP 历史接口用。</p>
 *
 * <p>为什么不是数据库：这是诊断材料，不是业务数据。JSONL 能直接用
 * {@code Select-String} / {@code jq} / Excel 打开，断线后也能手工 grep。
 * 写日志失败不能影响设备运行——{@link #append(MessageEvent)} 自己吞掉 IOException，
 * 只记录一次错误。</p>
 */
public final class MessageLog implements AutoCloseable {

    /** 内存里保留的最近条数，也是 HTTP 历史接口的默认上限。 */
    public static final int DEFAULT_RECENT = 5_000;

    /** 重启时最多从文件尾部读这么多字节，避免日志很大时启动变慢。 */
    private static final long MAX_TAIL_BYTES = 4L * 1024L * 1024L;

    private final Path file;
    private final BufferedWriter writer;
    private final int capacity;
    private final Deque<MessageEvent> recent = new ArrayDeque<>();
    private boolean writeFailed;

    /**
     * 打开（或创建）workDir 下的报文日志。
     *
     * @param workDir 工作目录；日志写到 {@code workDir/logs/messages.jsonl}
     * @return 已加载最近历史的日志
     * @throws IOException 目录或文件创建失败
     */
    public static MessageLog open(Path workDir) throws IOException {
        return open(workDir, DEFAULT_RECENT);
    }

    /**
     * 打开（或创建）报文日志,内存保留条数可配。
     *
     * @param workDir 工作目录；日志写到 {@code workDir/logs/messages.jsonl}
     * @param recent  内存里保留的最近条数(HTTP 历史接口的回看窗口)
     * @return 已加载最近历史的日志
     * @throws IOException 目录或文件创建失败
     */
    public static MessageLog open(Path workDir, int recent) throws IOException {
        Path dir = workDir.resolve("logs");
        Files.createDirectories(dir);
        Path file = dir.resolve("messages.jsonl");
        ensureTrailingNewline(file);
        MessageLog log = new MessageLog(file, Math.max(1, recent));
        log.loadRecent();
        return log;
    }

    /**
     * 进程可能崩在写一行的中途。若文件末尾没有换行，下一条新报文会接到半截行后面，
     * 两条都变成坏行；补一个换行，让新报文从干净的一行开始。
     */
    private static void ensureTrailingNewline(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        long size = Files.size(file);
        if (size <= 0L) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.read(last, size - 1L);
            if (last.get(0) != '\n') {
                channel.write(ByteBuffer.wrap(new byte[] {'\n'}), size);
            }
        }
    }

    private MessageLog(Path file, int capacity) throws IOException {
        this.file = file;
        this.capacity = capacity;
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * 追加一条报文。
     *
     * <p>订阅者回调里调用，不能向外抛异常：一条日志写不进去不该让会话线程崩掉。</p>
     *
     * @param event 报文事件
     */
    public synchronized void append(MessageEvent event) {
        addRecent(event);
        try {
            this.writer.write(toJson(event));
            this.writer.newLine();
            this.writer.flush();
            this.writeFailed = false;
        } catch (IOException e) {
            if (!this.writeFailed) {
                this.writeFailed = true;
                Log.error("写报文日志失败（后续仍继续运行）: " + this.file, e);
            }
        }
    }

    /**
     * 最近历史（JSON）。
     *
     * @param limit     最多返回条数
     * @param sessionId 只取这个会话；空串表示全部
     * @param direction {@code in} / {@code out}；空串表示全部
     * @return JSON 文本
     */
    public synchronized String recentJson(int limit, String sessionId, String direction) {
        return this.recentJson(limit, sessionId, direction, 0L, null, null);
    }

    /**
     * 最近历史（JSON,带游标与报文类型过滤）。
     *
     * @param limit     最多返回条数
     * @param sessionId 只取这个会话；空串表示全部
     * @param direction {@code in} / {@code out}；空串表示全部
     * @param beforeMs  只取时间早于该值的消息（翻旧账的游标）；0 表示不限制
     * @param stream    只取这个 Stream；null 表示全部
     * @param function  只取这个 Function；null 表示全部
     * @return JSON 文本
     */
    public synchronized String recentJson(int limit, String sessionId, String direction,
                                          long beforeMs, Integer stream, Integer function) {
        Snapshot snapshot = select(limit, sessionId, direction, beforeMs, stream, function);
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> messages = new ArrayList<>(snapshot.messages().size());
        for (MessageEvent event : snapshot.messages()) {
            messages.add(toJsonHub(event, b));
        }
        return b.object(
                b.pair("messages", b.array(messages)),
                b.pair("total", snapshot.total()),
                b.pair("limit", limit),
                b.pair("truncated", snapshot.total() > snapshot.messages().size()))
                .toJson();
    }

    /**
     * 最近历史（JSON Lines），供导出与离线分析。
     *
     * @param limit     最多返回条数
     * @param sessionId 只取这个会话；空串表示全部
     * @param direction {@code in} / {@code out}；空串表示全部
     * @return 每行一个 JSON 对象
     */
    public synchronized String recentJsonl(int limit, String sessionId, String direction) {
        return this.recentJsonl(limit, sessionId, direction, 0L, null, null);
    }

    /**
     * 最近历史（JSON Lines,带游标与报文类型过滤）。
     *
     * @param limit     最多返回条数
     * @param sessionId 只取这个会话；空串表示全部
     * @param direction {@code in} / {@code out}；空串表示全部
     * @param beforeMs  只取时间早于该值的消息；0 表示不限制
     * @param stream    只取这个 Stream；null 表示全部
     * @param function  只取这个 Function；null 表示全部
     * @return 每行一个 JSON 对象
     */
    public synchronized String recentJsonl(int limit, String sessionId, String direction,
                                           long beforeMs, Integer stream, Integer function) {
        Snapshot snapshot = select(limit, sessionId, direction, beforeMs, stream, function);
        StringBuilder sb = new StringBuilder();
        for (MessageEvent event : snapshot.messages()) {
            sb.append(toJson(event)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 日志文件位置。
     *
     * @return 文件
     */
    public Path file() {
        return this.file;
    }

    @Override
    public synchronized void close() {
        try {
            this.writer.close();
        } catch (IOException e) {
            Log.error("关闭报文日志失败: " + this.file, e);
        }
    }

    private void loadRecent() {
        try {
            long size = Files.size(this.file);
            if (size <= 0L) {
                return;
            }
            long start = Math.max(0L, size - MAX_TAIL_BYTES);
            if (start >= size) {
                return;
            }

            int length = (int) (size - start);
            byte[] bytes = new byte[length];
            try (FileChannel channel = FileChannel.open(this.file, StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                long position = start;
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer, position);
                    if (read < 0) {
                        break;
                    }
                    position += read;
                }
            }

            String text = new String(bytes, StandardCharsets.UTF_8);
            /* 从中间开始读时，第一行大概率是半截；丢掉它 */
            if (start > 0L) {
                int newline = text.indexOf('\n');
                text = newline >= 0 ? text.substring(newline + 1) : "";
            }

            for (String line : text.split("\n")) {
                parse(line);
            }
        } catch (IOException | RuntimeException e) {
            /* 历史读不出来不影响新报文继续写；这里只是少一段"刚才" */
            Log.error("读取报文历史失败（继续运行）: " + this.file, e);
        }
    }

    private void parse(String line) {
        if (line.isBlank()) {
            return;
        }
        try {
            JsonHub root = JsonHub.fromJson(line);
            String sessionId = root.get("sessionId").optionalString().orElse("");
            String direction = root.get("direction").optionalString().orElse("");
            String body = root.get("body").optionalString().orElse("");
            int stream = root.get("stream").intValue();
            int function = root.get("function").intValue();
            boolean wbit = root.get("wbit").booleanValue();
            long at = root.get("at").longValue();
            if (!"in".equals(direction) && !"out".equals(direction)) {
                return;
            }
            addRecent(new MessageEvent(sessionId, direction, stream, function, wbit, body, at));
        } catch (RuntimeException e) {
            /* 日志文件允许被人工编辑，一条坏行不该让整个历史不可用 */
        }
    }

    private void addRecent(MessageEvent event) {
        this.recent.addLast(event);
        while (this.recent.size() > this.capacity) {
            this.recent.removeFirst();
        }
    }

    private Snapshot select(int limit, String sessionId, String direction) {
        return this.select(limit, sessionId, direction, 0L, null, null);
    }

    private Snapshot select(int limit, String sessionId, String direction,
                            long beforeMs, Integer stream, Integer function) {
        String session = sessionId == null ? "" : sessionId.trim();
        String dir = direction == null ? "" : direction.trim();
        List<MessageEvent> matches = new ArrayList<>();
        for (MessageEvent event : this.recent) {
            if (!session.isEmpty() && !session.equals(event.sessionId())) {
                continue;
            }
            if (!dir.isEmpty() && !dir.equals(event.direction())) {
                continue;
            }
            if (beforeMs > 0L && event.atMillis() >= beforeMs) {
                continue;
            }
            if (stream != null && stream.intValue() != event.stream()) {
                continue;
            }
            if (function != null && function.intValue() != event.function()) {
                continue;
            }
            matches.add(event);
        }
        int total = matches.size();
        int wanted = Math.max(1, limit);
        if (matches.size() > wanted) {
            matches = new ArrayList<>(matches.subList(matches.size() - wanted, matches.size()));
        }
        return new Snapshot(List.copyOf(matches), total);
    }

    private static JsonHub toJsonHub(MessageEvent event, JsonHubBuilder b) {
        return b.object(
                b.pair("sessionId", event.sessionId()),
                b.pair("direction", event.direction()),
                b.pair("stream", event.stream()),
                b.pair("function", event.function()),
                b.pair("wbit", event.wbit()),
                b.pair("body", event.body()),
                b.pair("at", event.atMillis()));
    }

    private static String toJson(MessageEvent event) {
        return toJsonHub(event, JsonHub.getBuilder()).toJson();
    }

    private record Snapshot(List<MessageEvent> messages, int total) {
    }
}
