package com.shimizukenta.secssim.app.audit;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.secssim.app.log.Log;

/**
 * 审计日志：谁在什么时候对哪个对象做了什么。
 *
 * <p>与报文日志分开：报文是协议证据，审计是操作证据。两者生命周期、
 * 保留策略和访问权限都不同，混在一个文件里会让"谁改了配置"这种问题
 * 需要在一堆 S6F11 里翻半天。</p>
 *
 * <p>写入失败不影响业务操作：审计是旁路，不是事务的一部分。</p>
 */
public final class AuditLog implements AutoCloseable {

    /** 内存中保留的最近条数，也是 HTTP 审计接口的默认上限。 */
    public static final int DEFAULT_RECENT = 1_000;

    private final Path file;
    private final BufferedWriter writer;
    private final int capacity;
    private final Deque<Entry> recent = new ArrayDeque<>();
    private boolean writeFailed;

    /**
     * 打开（或创建）审计日志。
     *
     * @param workDir 工作目录；日志写到 {@code workDir/logs/audit.jsonl}
     * @return 已加载最近历史的审计日志
     * @throws IOException 目录或文件创建失败
     */
    public static AuditLog open(Path workDir) throws IOException {
        Path dir = workDir.resolve("logs");
        Files.createDirectories(dir);
        AuditLog log = new AuditLog(dir.resolve("audit.jsonl"), DEFAULT_RECENT);
        log.loadRecent();
        return log;
    }

    private AuditLog(Path file, int capacity) throws IOException {
        this.file = file;
        this.capacity = capacity;
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * 追加一条审计记录。
     *
     * @param actor  操作者
     * @param role   角色
     * @param action 动作
     * @param target 目标
     * @param detail 细节
     */
    public synchronized void record(String actor, String role, String action, String target, String detail) {
        Entry entry = new Entry(System.currentTimeMillis(),
                actor == null ? "" : actor,
                role == null ? "" : role,
                action == null ? "" : action,
                target == null ? "" : target,
                detail == null ? "" : detail);
        addRecent(entry);
        try {
            this.writer.write(toJson(entry));
            this.writer.newLine();
            this.writer.flush();
            this.writeFailed = false;
        } catch (IOException e) {
            if (!this.writeFailed) {
                this.writeFailed = true;
                Log.error("写审计日志失败（后续仍继续运行）: " + this.file, e);
            }
        }
    }

    /**
     * 最近审计记录。
     *
     * @param limit 最多返回条数
     * @return JSON 文本
     */
    public synchronized String recentJson(int limit) {
        return this.recentJson(limit, "", "", 0L);
    }

    /**
     * 最近审计记录（带服务端过滤,支持 before 游标翻旧账）。
     *
     * @param limit  最多返回条数
     * @param action 只取这个动作;空串表示全部
     * @param actor  只取这个操作者;空串表示全部
     * @param beforeMs 只取时间早于该值的记录;0 表示不限制
     * @return JSON 文本
     */
    public synchronized String recentJson(int limit, String action, String actor, long beforeMs) {
        return this.recentJsonImpl(limit, action, actor, beforeMs);
    }

    /**
     * 最近审计记录（带服务端过滤）。
     *
     * <p>action/actor 为精确匹配,空串表示不过滤;过滤后的 total/truncated
     * 随响应带回,界面能区分"查不到"和"被 limit 截断"。</p>
     *
     * @param limit  最多返回条数
     * @param action 只取这个动作;空串表示全部
     * @param actor  只取这个操作者;空串表示全部
     * @return JSON 文本
     */
    private synchronized String recentJsonImpl(int limit, String action, String actor, long beforeMs) {
        int wanted = Math.max(1, limit);
        String actionFilter = action == null ? "" : action.trim();
        String actorFilter = actor == null ? "" : actor.trim();
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : this.recent) {
            if (!actionFilter.isEmpty() && !actionFilter.equals(entry.action())) {
                continue;
            }
            if (!actorFilter.isEmpty() && !actorFilter.equals(entry.actor())) {
                continue;
            }
            if (beforeMs > 0L && entry.atMillis() >= beforeMs) {
                continue;
            }
            matches.add(entry);
        }
        int total = matches.size();
        List<Entry> selected = matches.size() > wanted
                ? matches.subList(matches.size() - wanted, matches.size())
                : matches;

        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> entries = new ArrayList<>(selected.size());
        for (Entry entry : selected) {
            entries.add(b.object(
                    b.pair("at", entry.atMillis()),
                    b.pair("actor", entry.actor()),
                    b.pair("role", entry.role()),
                    b.pair("action", entry.action()),
                    b.pair("target", entry.target()),
                    b.pair("detail", entry.detail())));
        }
        return b.object(
                b.pair("audit", b.array(entries)),
                b.pair("limit", wanted),
                b.pair("total", total),
                b.pair("truncated", total > selected.size())).toJson();
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
            Log.error("关闭审计日志失败: " + this.file, e);
        }
    }

    private void loadRecent() {
        try (Stream<String> lines = Files.lines(this.file, StandardCharsets.UTF_8)) {
            lines.forEach(this::parse);
        } catch (IOException | RuntimeException e) {
            Log.error("读取审计历史失败（继续运行）: " + this.file, e);
        }
    }

    private void parse(String line) {
        if (line.isBlank()) {
            return;
        }
        try {
            JsonHub root = JsonHub.fromJson(line);
            addRecent(new Entry(
                    root.get("at").longValue(),
                    root.get("actor").optionalString().orElse(""),
                    root.get("role").optionalString().orElse(""),
                    root.get("action").optionalString().orElse(""),
                    root.get("target").optionalString().orElse(""),
                    root.get("detail").optionalString().orElse("")));
        } catch (RuntimeException e) {
            /* 人工编辑过审计文件时，一条坏行不该让整个历史不可用 */
        }
    }

    private void addRecent(Entry entry) {
        this.recent.addLast(entry);
        while (this.recent.size() > this.capacity) {
            this.recent.removeFirst();
        }
    }

    private static String toJson(Entry entry) {
        JsonHubBuilder b = JsonHub.getBuilder();
        return b.object(
                b.pair("at", entry.atMillis()),
                b.pair("actor", entry.actor()),
                b.pair("role", entry.role()),
                b.pair("action", entry.action()),
                b.pair("target", entry.target()),
                b.pair("detail", entry.detail())).toJson();
    }

    private record Entry(long atMillis, String actor, String role,
                         String action, String target, String detail) {
    }
}
