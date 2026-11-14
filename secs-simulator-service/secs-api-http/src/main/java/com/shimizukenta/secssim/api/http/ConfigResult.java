package com.shimizukenta.secssim.api.http;

import java.util.List;

/**
 * 一次配置操作的结果：状态 + 具体问题。
 *
 * <p>光有 {@link ConfigOutcome} 不够：编辑器的价值就在于告诉用户
 * "第几行、哪个键不对"，只回一句"保存失败"等于把排障的活儿全推给了用户。
 * </p>
 *
 * @param outcome  结果分类
 * @param problems 问题清单；成功时为空
 */
public record ConfigResult(ConfigOutcome outcome, List<String> problems) {

    /**
     * 固化问题清单。
     *
     * @param outcome  结果分类
     * @param problems 问题清单
     */
    public ConfigResult {
        problems = List.copyOf(problems);
    }

    /**
     * 成功。
     *
     * @return 成功结果
     */
    public static ConfigResult ok() {
        return new ConfigResult(ConfigOutcome.OK, List.of());
    }

    /**
     * 内容不合法；不会落盘。
     *
     * @param problems 问题清单
     * @return 非法结果
     */
    public static ConfigResult invalid(List<String> problems) {
        return new ConfigResult(ConfigOutcome.INVALID, problems);
    }

    /**
     * 文件不存在或名字非法。
     *
     * @param problem 原因
     * @return 未找到结果
     */
    public static ConfigResult notFound(String problem) {
        return new ConfigResult(ConfigOutcome.NOT_FOUND, List.of(problem));
    }

    /**
     * 读或写盘失败。
     *
     * @param problem 原因
     * @return 失败结果
     */
    public static ConfigResult failed(String problem) {
        return new ConfigResult(ConfigOutcome.FAILED, List.of(problem));
    }

    /**
     * 已经有同名的文件了。
     *
     * @param problem 原因
     * @return 冲突结果
     */
    public static ConfigResult conflict(String problem) {
        return new ConfigResult(ConfigOutcome.CONFLICT, List.of(problem));
    }
}
