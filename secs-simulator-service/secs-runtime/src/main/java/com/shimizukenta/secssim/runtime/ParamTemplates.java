package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.shimizukenta.jsonhub.JsonHub;

/**
 * 内置参数模板库：所有设备共享的「参数变化逻辑」起点（schema v7）。
 *
 * <p>模板正文来自 classpath 上的 {@code seed/param-templates.json}：每项
 * {@code {"id","name","description","body"}}，{@code body} 是一段
 * {@code {"source": …}} 的 JSON 文本。改模板只改数据，不必重新编译。</p>
 *
 * <p>v7 的模板体只有一段 {@code source}，因此"模板"不再是一张迷你模板图，
 * 而是一段可以直接拷贝到数据项上的值定义。库的作用回到它该有的样子：
 * <b>给人参考的量纲与波形</b>，复制出去就独立了。</p>
 *
 * <p>目录是"默认送多少条"的唯一来源：条目删掉后，启动时 {@code Bootstrap}
 * 会把库里同属内置、已不在目录中的行下线（只动 readonly 行）。</p>
 */
public final class ParamTemplates {

    private static final String RESOURCE = "seed/param-templates.json";

    private ParamTemplates() {
    }

    /**
     * 目录里的一项。
     *
     * @param id          模板标识（稳定，播种幂等键）
     * @param name        界面显示名（讲清是什么设备的什么参数在怎么变）
     * @param description 一句话说明变化逻辑与量纲参考
     * @param body        模板体 JSON（{@code {"source": …}}）
     */
    public record Entry(String id, String name, String description, String body) {
    }

    private static volatile List<Entry> catalog;

    /**
     * 模板目录。
     *
     * @return 全部内置参数模板
     */
    public static List<Entry> catalog() {
        List<Entry> cached = catalog;
        if (cached != null) {
            return cached;
        }
        synchronized (ParamTemplates.class) {
            if (catalog == null) {
                catalog = parse(readResource());
            }
            return catalog;
        }
    }

    private static String readResource() {
        try (InputStream in = ParamTemplates.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 里没有 " + RESOURCE + "：内置参数模板资源缺失");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + RESOURCE + " 失败: " + e.getMessage(), e);
        }
    }

    private static List<Entry> parse(String text) {
        JsonHub root;
        try {
            root = JsonHub.fromJson(text);
        } catch (RuntimeException e) {
            throw new IllegalStateException("解析 " + RESOURCE + " 失败: " + e.getMessage(), e);
        }
        if (!root.isArray()) {
            throw new IllegalStateException(RESOURCE + " 应为 JSON 数组");
        }
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < root.length(); i++) {
            JsonHub item = root.get(i);
            if (item == null || !item.isObject()) {
                throw new IllegalStateException(RESOURCE + "[" + i + "] 应为对象");
            }
            list.add(new Entry(requiredText(item, "id", i), requiredText(item, "name", i),
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
}