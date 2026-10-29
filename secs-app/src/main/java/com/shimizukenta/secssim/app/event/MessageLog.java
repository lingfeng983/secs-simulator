package com.shimizukenta.secssim.app.event;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p><b>写盘不落在收发线程上</b>：{@link #append(MessageEvent)} 只把一行放进有界队列，
 * 由单独的写线程批量 flush。原先每帧同步 flush 三份文件（主文件 + 设备/主机视角），
 * 而 append 是从协议分发线程的 EventBus 回调里调用的——磁盘一慢就拖慢应答，
 * 高报文速率下会连锁触发主机侧 T3 超时。队列满时丢最旧的诊断行：这是诊断材料，
 * 不能反过来把设备拖死。</p>
 *
 * <p>主文件与按会话拆分的文件都按大小轮转，保留 {@value #KEEP_ROTATED} 份历史；
 * 拆分文件同时限制同时打开的数量（LRU），反复建删会话不会累积文件句柄。</p>
 */
public final class MessageLog implements AutoCloseable {

    /** 内存里保留的最近条数，也是 HTTP 历史接口的默认上限。 */
    public static final int DEFAULT_RECENT = 5_000;

    /** 重启时最多从文件尾部读这么多字节，避免日志很大时启动变慢。 */
    private static final long MAX_TAIL_BYTES = 4L * 1024L * 1024L;

    /** 单个日志文件上限；可用 {@code -Dsecssim.messages.maxBytes} 覆盖（测试用）。 */
    private static final long DEFAULT_MAX_BYTES = 16L * 1024L * 1024L;

    /** 保留的历史份数（messages.jsonl.1 … .N）。 */
    private static final int KEEP_ROTATED = 3;

    /** 待写队列上限：满了丢最旧，绝不阻塞调用方。 */
    private static final int QUEUE_CAPACITY = 8_192;

    /** 同时打开的“按会话拆分”文件上限（LRU 淘汰）。 */
    private static final int SPLIT_WRITERS_MAX = 64;

    /** 批量 flush 的最大间隔。 */
    private static final long FLUSH_INTERVAL_MS = 200L;

    private final Path file;
    private final Path devicesDir;
    private final Path hostsDir;
    private final int capacity;
    private final long maxBytes;

    private final Deque<MessageEvent> recent = new ArrayDeque<>();

    /** 待写行（文件正文由写线程独占）。 */
    private final BlockingQueue<Line> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /** 因队列满而丢弃的行数（诊断用）。 */
    private final AtomicLong dropped = new AtomicLong();

    /** 写线程；{@code null} 表示构造失败，只保留内存历史。 */
    private final Thread writerThread;

    private volatile boolean running = true;

    /* 以下字段只由写线程（或 close 时已停止写线程的调用方）访问 */
    private RotatingWriter mainWriter;
    private final Map<String, RotatingWriter> deviceWriters = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<String, RotatingWriter> hostWriters = new LinkedHashMap<>(16, 0.75F, true);
    private final Set<Path> failedSplits = new HashSet<>();

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
     * 打开（或创建）workDir 下的报文日志,内存保留条数可配。
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
        MessageLog log = new MessageLog(dir, file, Math.max(1, recent));
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

    private MessageLog(Path dir, Path file, int capacity) throws IOException {
        this.file = file;
        this.devicesDir = dir.resolve("devices");
        this.hostsDir = dir.resolve("hosts");
        Files.createDirectories(this.devicesDir);
        Files.createDirectories(this.hostsDir);
        this.capacity = capacity;
        this.maxBytes = Long.getLong("secssim.messages.maxBytes", DEFAULT_MAX_BYTES);
        this.mainWriter = new RotatingWriter(file, this.maxBytes, KEEP_ROTATED);

        this.writerThread = new Thread(this::writeLoop, "message-log-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * 追加一条报文。
     *
     * <p>订阅者回调里调用，不能向外抛异常，也不能做磁盘 I/O：
     * 一条日志写不进去不该让会话线程崩掉，更不该拖慢应答。</p>
     *
     * @param event 报文事件
     */
    public void append(MessageEvent event) {
        /* 两次序列化放到锁外：append 是从协议分发线程调用的，锁只保护
           "最近窗口 + 待写队列"。以前连序列化一起锁住，刷历史（recentJson）
           持同一把锁时会把所有会话的收发都堵在这里。 */
        final String json = toJson(event);
        final String session = sanitizeSession(event.sessionId());
        final String hostJson = toHostJson(event);
        final Line line = new Line(json, session, hostJson);

        synchronized ( this ) {
            addRecent(event);
            if ( ! this.queue.offer(line) ) {
                /* 队列满：丢最旧的一行，保证写线程永远跟得上 */
                this.queue.poll();
                this.queue.offer(line);
                this.dropped.incrementAndGet();
            }
        }
    }

    /** 写线程：批量 flush，避免每帧一次系统调用。 */
    private void writeLoop() {
        long lastFlush = System.currentTimeMillis();

        while ( this.running || ! this.queue.isEmpty() ) {
            Line line;
            try {
                line = this.queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
            catch ( InterruptedException e ) {
                Thread.currentThread().interrupt();
                break;
            }

            if ( line != null ) {
                write(line);
            }

            long now = System.currentTimeMillis();
            if ( now - lastFlush >= FLUSH_INTERVAL_MS ) {
                flushAll();
                lastFlush = now;
            }
        }

        flushAll();
    }

    /** 主文件写失败只报一次；写线程继续消费队列，不能因为磁盘满就死掉。 */
    private boolean mainWriteFailed;

    private void write(Line line) {
        try {
            this.mainWriter.write(line.json());
            this.mainWriteFailed = false;
        }
        catch ( RuntimeException e ) {
            if ( ! this.mainWriteFailed ) {
                this.mainWriteFailed = true;
                Log.error("写报文日志失败（后续仍继续运行）: " + this.file, e);
            }
        }

        if ( ! line.session().isEmpty() ) {
            writeSplit(this.deviceWriters, this.devicesDir, line.session(), line.json());
            writeSplit(this.hostWriters, this.hostsDir, line.session(), line.hostJson());
        }
    }

    /** 追加到 {@code devices/} 或 {@code hosts/} 下的单会话文件；失败只报一次。 */
    private void writeSplit(Map<String, RotatingWriter> writers, Path dir, String session, String json) {
        Path target = dir.resolve(session + ".jsonl");
        RotatingWriter writer = writers.get(session);

        if ( writer == null ) {
            try {
                writer = new RotatingWriter(target, this.maxBytes, KEEP_ROTATED);
            }
            catch ( IOException e ) {
                if ( this.failedSplits.add(target) ) {
                    Log.error("打开拆分报文日志失败（后续仍继续运行）: " + target, e);
                }
                return;
            }
            writers.put(session, writer);
            evictOverflow(writers);
        }

        try {
            writer.write(json);
            this.failedSplits.remove(target);
        }
        catch ( RuntimeException e ) {
            if ( this.failedSplits.add(target) ) {
                Log.error("写拆分报文日志失败（后续仍继续运行）: " + target, e);
            }
        }
    }

    /** 同时打开的文件太多时，关掉最久没写的那几个（LRU 表头）。 */
    private static void evictOverflow(Map<String, RotatingWriter> writers) {
        while ( writers.size() > SPLIT_WRITERS_MAX ) {
            String oldest = writers.keySet().iterator().next();
            RotatingWriter evicted = writers.remove(oldest);
            if ( evicted != null ) {
                evicted.close();
            }
        }
    }

    private void flushAll() {
        this.mainWriter.flush();
        this.deviceWriters.values().forEach(RotatingWriter::flush);
        this.hostWriters.values().forEach(RotatingWriter::flush);
    }

    /** 会话 id 只保留文件名安全字符，避免路径穿越与 Windows 非法字符。 */
    private static String sanitizeSession(String sessionId) {
        if (sessionId == null) {
            return "";
        }
        String safe = sessionId.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
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
    public String recentJson(int limit, String sessionId, String direction,
                             long beforeMs, Integer stream, Integer function) {
        final Snapshot snapshot;
        synchronized ( this ) {
            /* 锁内只复制快照：5000 条序列化不能阻塞协议线程的 append */
            snapshot = select(limit, sessionId, direction, beforeMs, stream, function);
        }
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
    public String recentJsonl(int limit, String sessionId, String direction,
                              long beforeMs, Integer stream, Integer function) {
        final Snapshot snapshot;
        synchronized ( this ) {
            snapshot = select(limit, sessionId, direction, beforeMs, stream, function);
        }
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

    /**
     * 因队列满被丢弃的行数（诊断用）。
     *
     * @return 丢弃行数
     */
    public long droppedLines() {
        return this.dropped.get();
    }

    @Override
    public void close() {
        this.running = false;

        try {
            this.writerThread.join(2_000L);
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
        }

        /* 写线程已停：剩下的由当前线程补写，保证不丢已入队的行。
           线程没停下来（磁盘卡住）时不再并发写同一个文件，直接关闭。 */
        if ( this.writerThread.isAlive() ) {
            /* 写线程还卡在磁盘上：这时关 writer 会与它并发写同一个 BufferedWriter，
               可能写出半行或抛异常。宁可让 JVM 退出时丢弃缓冲，也不要破坏文件。 */
            Log.warn("报文日志写线程未在 2 秒内结束，跳过关闭以避免并发写: " + this.file);
            return;
        }

        Line line;
        while ( (line = this.queue.poll()) != null ) {
            this.write(line);
        }

        this.mainWriter.close();
        closeAll(this.deviceWriters);
        closeAll(this.hostWriters);
    }

    private static void closeAll(Map<String, RotatingWriter> writers) {
        for (RotatingWriter writer : writers.values()) {
            writer.close();
        }
        writers.clear();
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

    /**
     * 主机视角：同一条线上报文，方向与设备相反。
     *
     * <p>本机主机与设备会话一一对应（一台设备只允许一个本机主机），
     * 所以主机拆分文件按会话 id 命名；远程主机不经过本进程，不在这里。</p>
     */
    private static String toHostJson(MessageEvent event) {
        JsonHubBuilder b = JsonHub.getBuilder();
        String direction = "in".equals(event.direction()) ? "out" : "in";
        return b.object(
                b.pair("sessionId", event.sessionId()),
                b.pair("perspective", "host"),
                b.pair("direction", direction),
                b.pair("stream", event.stream()),
                b.pair("function", event.function()),
                b.pair("wbit", event.wbit()),
                b.pair("body", event.body()),
                b.pair("at", event.atMillis())).toJson();
    }

    private record Snapshot(List<MessageEvent> messages, int total) {
    }

    /** 一行待写内容：正文 + 会话名 + 主机视角正文。 */
    private record Line(String json, String session, String hostJson) {
    }

    /**
     * 按大小轮转的追加写器。
     *
     * <p>缓冲写 + 显式 flush：只有写线程调用，无需再加锁。</p>
     */
    private static final class RotatingWriter {

        private final Path file;
        private final long maxBytes;
        private final int keepRotated;
        private BufferedWriter out;
        private long bytes;

        RotatingWriter(Path file, long maxBytes, int keepRotated) throws IOException {
            this.file = file;
            this.maxBytes = maxBytes <= 0L ? DEFAULT_MAX_BYTES : maxBytes;
            this.keepRotated = Math.max(1, keepRotated);
            this.out = open(file);
            this.bytes = Files.exists(file) ? Files.size(file) : 0L;
        }

        private static BufferedWriter open(Path file) throws IOException {
            return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        void write(String json) {
            /* 先判断再写：轮转发生在两条日志之间，当前文件永远保有最新的一行。
               反过来（写完再轮转）会让正常停机时最后一批日志全落进 .1、
               当前文件变空，重启后"最近历史"反而读不到。 */
            if ( this.bytes >= this.maxBytes ) {
                rotate();
            }
            try {
                this.out.write(json);
                this.out.newLine();
                /* 按 UTF-8 字节数计：中文报文体按字符数算会让实际文件超出上限近一倍 */
                this.bytes += json.getBytes(StandardCharsets.UTF_8).length + 1L;
            }
            catch ( IOException e ) {
                throw new IllegalStateException("write failed: " + this.file, e);
            }
        }

        void flush() {
            try {
                this.out.flush();
            }
            catch ( IOException e ) {
                /* flush 失败交给下一次 write 暴露；这里不能抛给停机流程 */
            }
        }

        /** 关闭当前文件并轮转；最旧的 .N 被覆盖。 */
        private void rotate() {
            try {
                this.out.close();
            }
            catch ( IOException ignore ) {
            }

            try {
                for (int i = this.keepRotated - 1; i >= 1; --i) {
                    Path from = this.file.resolveSibling(this.file.getFileName() + "." + i);
                    Path to = this.file.resolveSibling(this.file.getFileName() + "." + (i + 1));
                    if ( Files.exists(from) ) {
                        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.move(this.file, this.file.resolveSibling(this.file.getFileName() + ".1"),
                        StandardCopyOption.REPLACE_EXISTING);
                this.out = open(this.file);
                this.bytes = 0L;
            }
            catch ( IOException e ) {
                Log.error("报文日志轮转失败（重新打开当前文件继续写）: " + this.file, e);
                try {
                    /* 失败路径必须把 writer 重新打开：rotate() 已经关掉了它，
                       不重开的话后续每次 write 都抛异常，报文日志会静默停写。 */
                    this.out = open(this.file);
                    this.bytes = Files.exists(this.file) ? Files.size(this.file) : 0L;
                }
                catch ( IOException reopen ) {
                    Log.error("报文日志重新打开失败（后续写入将失败）: " + this.file, reopen);
                }
            }
        }

        void close() {
            try {
                this.out.close();
            }
            catch ( IOException e ) {
                Log.error("关闭报文日志失败: " + this.file, e);
            }
        }
    }
}
