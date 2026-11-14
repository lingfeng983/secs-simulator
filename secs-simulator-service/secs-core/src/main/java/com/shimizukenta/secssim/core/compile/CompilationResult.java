package com.shimizukenta.secssim.core.compile;

import java.util.List;

/**
 * 编译结果：模型 + 全部问题。
 *
 * <p>即使存在错误也会返回尽力而为的模型，方便前端把错误标记到具体字段上，
 * 但 {@link #ok()} 为 {@code false} 时不得用于实际通信。
 * </p>
 *
 * @param equipment 解析后的设备模型
 * @param issues    问题列表
 */
public record CompilationResult(ResolvedEquipment equipment, List<Issue> issues) {

    /**
     * 校验并创建。
     *
     * @param equipment 模型
     * @param issues    问题
     */
    public CompilationResult {
        issues = List.copyOf(issues);
    }

    /**
     * 是否没有阻断性错误。
     *
     * @return 无错误返回 {@code true}
     */
    public boolean ok() {
        return this.issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
    }

    /**
     * 全部错误。
     *
     * @return 错误列表
     */
    public List<Issue> errors() {
        return this.issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
    }

    /**
     * 全部提示。
     *
     * @return 提示列表
     */
    public List<Issue> warnings() {
        return this.issues.stream().filter(i -> i.severity() == Severity.WARNING).toList();
    }
}
