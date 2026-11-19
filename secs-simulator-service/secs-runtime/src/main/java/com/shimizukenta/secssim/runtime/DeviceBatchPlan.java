package com.shimizukenta.secssim.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 批量新建设备时的名字与端口规划：纯函数，不碰配置库也不碰会话。
 *
 * <p>抽成独立一类的理由很实际：这几条规则（设备名 = 前缀 + 序号、名字必须唯一、
 * 前缀不能变成路径或引号）是"错一次就来一次现场事故"的那类约束，
 * 值得单测直接打，而不是隔着 HTTP 与配置库才能验。</p>
 *
 * <p>名字同时是配置文件名（{@code dev-3.json}）与设备名（{@code dev-3}）：
 * 清单按配置名去重，编辑器按设备名展示，两者不同源就会出现
 * 「点开 A 改的是 B」。</p>
 */
final class DeviceBatchPlan {

    /** 台数上限：与 {@code POST /api/v1/sessions/bulk} 保持一致。 */
    static final int MAX_COUNT = 100;

    private DeviceBatchPlan() {
    }

    /**
     * 校验入参；合法返回空列表。
     *
     * @param prefix   批次前缀
     * @param start    起始序号（含）
     * @param count    台数
     * @param portBase 起始端口
     * @return 问题清单；空表示可以开始建
     */
    static List<String> problems(String prefix, int start, int count, int portBase) {
        List<String> problems = new ArrayList<>();
        if (prefix == null || prefix.isBlank()) {
            problems.add("批次前缀不能为空");
        } else if (prefix.matches(".*[/\\\\\"\\s].*")) {
            /* 前缀要同时当文件名与设备名：带斜杠会跑出配置目录，
               带空白/引号会让文件名解析与 JSON 转义出问题 */
            problems.add("批次前缀不能含空白、斜杠或引号");
        }
        if (count < 1 || count > MAX_COUNT) {
            problems.add("台数必须在 1.." + MAX_COUNT + " 之间");
        }
        if (start < 0) {
            problems.add("起始序号必须是非负整数");
        }
        if (portBase < 1 || (long) portBase + Math.max(count, 1) - 1 > 65535) {
            problems.add("起始端口加台数不能超过 65535");
        }
        return problems;
    }

    /**
     * 第 i 台（从 0 起）的设备名 / 配置文件名主名。
     *
     * @param prefix 批次前缀
     * @param start  起始序号
     * @param index  批内序号（0 起）
     * @return 设备名，如 {@code dev-3}
     */
    static String deviceName(String prefix, int start, int index) {
        return prefix + (start + index);
    }

    /**
     * 整批的设备名。
     *
     * @param prefix 批次前缀
     * @param start  起始序号
     * @param count  台数
     * @return 设备名列表（顺序即建库顺序）
     */
    static List<String> deviceNames(String prefix, int start, int count) {
        List<String> names = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            names.add(deviceName(prefix, start, i));
        }
        return names;
    }

    /**
     * 整批的配置文件名（设备名 + {@code .json}）。
     *
     * @param prefix 批次前缀
     * @param start  起始序号
     * @param count  台数
     * @return 文件名列表
     */
    static List<String> configNames(String prefix, int start, int count) {
        List<String> names = new ArrayList<>(Math.max(count, 0));
        for (String device : deviceNames(prefix, start, count)) {
            names.add(device + ".json");
        }
        return names;
    }

    /**
     * 已有名字里撞上的那些（按 {@code .json} 文件名比对，不分大小写）。
     *
     * <p>非空就是「一台都不能建」：建到第 4 台才发现撞名，留下三台半成品
     * 让人自己收拾，比整批重来难处理得多。</p>
     *
     * @param names  这一批要用的文件名
     * @param taken  已占用的文件名（大小写不敏感）
     * @return 撞上的文件名；空表示整批可用
     */
    static List<String> conflicts(Collection<String> names, Collection<String> taken) {
        Set<String> used = new HashSet<>();
        for (String name : taken) {
            if (name != null) {
                used.add(name.toLowerCase(Locale.ROOT));
            }
        }
        List<String> conflicts = new ArrayList<>();
        for (String name : names) {
            if (used.contains(name.toLowerCase(Locale.ROOT))) {
                conflicts.add(name);
            }
        }
        return conflicts;
    }
}
