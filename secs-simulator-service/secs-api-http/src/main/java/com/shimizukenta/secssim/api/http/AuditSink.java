package com.shimizukenta.secssim.api.http;

/**
 * 审计旁路。
 *
 * <p>HTTP 层只负责把"谁、什么角色、做了什么、对什么、结果如何"交给它；
 * 写文件、保留策略由实现决定。审计失败不能影响接口本身。</p>
 */
public interface AuditSink {

    /**
     * 记录一条审计。
     *
     * @param actor  操作者
     * @param role   角色（小写）
     * @param action 动作
     * @param target 目标
     * @param detail 细节
     */
    void record(String actor, String role, String action, String target, String detail);
}
