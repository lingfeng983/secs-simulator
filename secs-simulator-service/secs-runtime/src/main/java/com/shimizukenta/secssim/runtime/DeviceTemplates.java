package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.shimizukenta.jsonhub.JsonHub;

/**
 * 内置设备模板库：新建配置时的选型起点（schema v7）。
 *
 * <p>模板正文不再写在源码里，而是 classpath 上的
 * {@code seed/device-templates.json}：每项
 * {@code {"id","label","description","body"}}，{@code body} 是一份完整的
 * schema v7 配置文本。这样改模板只改数据，不必重新编译。</p>
 *
 * <p>v7 把「值从哪来」收成一个挂在数据项上的 {@code source}，模板因此不再需要
 * 模板图与实例绑定——每份模板就是"三张表 + 一条周期"：</p>
 * <ul>
 *   <li>{@code context} —— 晶圆属性（LOTID / RCPID / PPID / WAFERID），随片号变；</li>
 *   <li>{@code svids[].source} —— 参数变量，{@code clock: "phase"} 的曲线按片重放；</li>
 *   <li>{@code constants[]} —— 设备常量，可以是常数也可以是时间函数；</li>
 *   <li>{@code cycle.events[]} —— 片内事件，只问"哪一刻、报哪个 CEID"，不改任何值。</li>
 * </ul>
 *
 * <p>每份模板都必须能直接编译——校验走 {@link ConfigStore#validate}，
 * 与保存、启动同一条判据。</p>
 */
public final class DeviceTemplates {

    /** 空骨架的模板标识：等价于原来的 ConfigTemplate.skeleton。 */
    public static final String BARE_ID = "bare";

    private static final String RESOURCE = "seed/device-templates.json";

    private DeviceTemplates() {
    }

    /**
     * 目录里的一项。
     *
     * @param id          模板标识（新建配置请求里的 {@code template} 字段）
     * @param label       界面显示名
     * @param description 一句话说明设备形态
     */
    public record Entry(String id, String label, String description) {
    }

    /** 资源文件里的一项：目录信息 + 正文。 */
    private record Seed(String id, String label, String description, String body) {
    }

    private static volatile List<Seed> seeds;

    /**
     * 模板目录。
     *
     * @return 全部内置模板（第一项是空骨架）
     */
    public static List<Entry> catalog() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(BARE_ID, "空骨架", "最小可编译配置：一个腔、一条温度曲线、两个片内事件"));
        load().forEach((seed) -> entries.add(new Entry(seed.id(), seed.label(), seed.description())));
        return List.copyOf(entries);
    }

    /** 供错误提示的模板 id 清单，如 {@code bare / etch / cvd / …}。 */
    public static String ids() {
        return String.join(" / ", catalog().stream().map(Entry::id).toList());
    }

    /**
     * 目录的 JSON 数组。
     *
     * @return JSON 文本
     */
    public static String listJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Entry entry : catalog()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":\"").append(escape(entry.id()))
                    .append("\",\"label\":\"").append(escape(entry.label()))
                    .append("\",\"description\":\"").append(escape(entry.description()))
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    /**
     * 按模板生成一份新配置。
     *
     * @param templateId 模板标识（{@link #catalog()} 里的 id；空串等价于 {@link #BARE_ID}）
     * @param fileName   目标文件名；会话 id 与设备名取它的主名
     * @return Schema v7 JSON 文本；模板不存在时为空
     */
    public static Optional<String> json(String templateId, String fileName) {
        String wanted = templateId == null ? "" : templateId.trim();
        if (wanted.isEmpty()) {
            wanted = BARE_ID;
        }
        if (BARE_ID.equalsIgnoreCase(wanted)) {
            /* 空骨架不是种子模板：由 ConfigTemplate.skeleton 生成，
               与资源目录解耦，也保证既有「bare == skeleton」行为不变。 */
            return Optional.of(ConfigTemplate.skeleton(fileName));
        }
        String id = wanted;
        for (Seed seed : load()) {
            if (seed.id().equalsIgnoreCase(id)) {
                return Optional.of(withSessionId(seed.body(), deriveId(fileName)));
            }
        }
        return Optional.empty();
    }

    /** 会话 id / 设备名取文件名主名，与空骨架同一套规则。 */
    private static String deriveId(String fileName) {
        String id = fileName == null ? "" : fileName.trim();
        if (id.toLowerCase(Locale.ROOT).endsWith(".json")) {
            id = id.substring(0, id.length() - ".json".length());
        }
        if (id.isEmpty()) {
            id = "line1";
        }
        return id;
    }

    /**
     * 把正文里的 {@code session.id} 换成文件名主名。
     *
     * <p>只改这一个值、其余字节原样保留：模板正文是给人读的（缩进、键序），
     * 重新序列化会把它们全部压平。定位依赖 schema v7 的结构约定——
     * {@code session} 对象里的第一个 {@code "id"} 就是会话 id。</p>
     */
    private static String withSessionId(String body, String sessionId) {
        String replaced = replaceStringValue(body, "\"session\"", "\"id\"", sessionId);
        /* 设备名与会话 id 同源：模板生成器的既有语义是 equipment.name = 文件名主名大写 */
        return replaceStringValue(replaced, "\"equipment\"", "\"name\"",
                sessionId.toUpperCase(Locale.ROOT));
    }

    /** 在 {@code sectionKey} 段内把第一个 {@code key} 的字符串值换成 {@code value}。 */
    private static String replaceStringValue(String body, String sectionKey, String key, String value) {
        int section = body.indexOf(sectionKey);
        if (section < 0) {
            throw new IllegalStateException(RESOURCE + " 里有一份模板缺少 " + sectionKey + " 段");
        }
        int keyAt = body.indexOf(key, section);
        if (keyAt < 0) {
            throw new IllegalStateException(RESOURCE + " 里有一份模板缺少 " + key);
        }
        int colon = body.indexOf(':', keyAt + key.length());
        if (colon < 0) {
            throw new IllegalStateException(RESOURCE + " 里有一份模板的 " + key + " 不是字符串值");
        }
        int open = body.indexOf('"', colon + 1);
        int close = open < 0 ? -1 : closingQuote(body, open + 1);
        if (close < 0) {
            throw new IllegalStateException(RESOURCE + " 里有一份模板的 " + key + " 不是字符串值");
        }
        return body.substring(0, open + 1) + escape(value) + body.substring(close);
    }

    /** 从 {@code from} 起找结束引号，跳过转义字符。 */
    private static int closingQuote(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static List<Seed> load() {
        List<Seed> cached = seeds;
        if (cached != null) {
            return cached;
        }
        synchronized (DeviceTemplates.class) {
            if (seeds == null) {
                seeds = parse(readResource());
            }
            return seeds;
        }
    }

    private static String readResource() {
        try (InputStream in = DeviceTemplates.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 里没有 " + RESOURCE + "：内置设备模板资源缺失");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + RESOURCE + " 失败: " + e.getMessage(), e);
        }
    }

    private static List<Seed> parse(String text) {
        JsonHub root;
        try {
            root = JsonHub.fromJson(text);
        } catch (RuntimeException e) {
            throw new IllegalStateException("解析 " + RESOURCE + " 失败: " + e.getMessage(), e);
        }
        if (!root.isArray()) {
            throw new IllegalStateException(RESOURCE + " 应为 JSON 数组");
        }
        List<Seed> list = new ArrayList<>();
        for (int i = 0; i < root.length(); i++) {
            JsonHub item = root.get(i);
            if (item == null || !item.isObject()) {
                throw new IllegalStateException(RESOURCE + "[" + i + "] 应为对象");
            }
            list.add(new Seed(requiredText(item, "id", i), requiredText(item, "label", i),
                    requiredText(item, "description", i), requiredText(item, "body", i)));
        }
        return list;
    }

    private static String requiredText(JsonHub item, String key, int index) {
        JsonHub value = item.get(key);
        if (value == null || !value.isString()) {
            throw new IllegalStateException(RESOURCE + "[" + index + "] 缺少字符串字段 " + key);
        }
        return value.optionalString().orElse("");
    }

    /** JSON 字符串转义；正文里的模板信息来自内部资源，这里只为稳妥。 */
    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}