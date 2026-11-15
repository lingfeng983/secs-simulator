package com.shimizukenta.secssim.core.compile;

/**
 * 报告里的一个字段（schema v7）。
 *
 * <p>v6 的报告只能装 SVID。v7 里三类数据都能进报文：</p>
 * <ul>
 *   <li>{@link Svid} —— 参数变量，取自值来源；</li>
 *   <li>{@link Ecid} —— 设备常量，可以随时间变化；</li>
 *   <li>{@link Context} —— 晶圆属性（LOTID / RCPID / PPID …），随片号变化。</li>
 * </ul>
 *
 * <p>顺序即报文顺序，三种字段可以任意混排——主机侧只看位置，不看类型。</p>
 */
public sealed interface FieldRef permits FieldRef.Svid, FieldRef.Ecid, FieldRef.Context {

    /**
     * 一个 SVID 字段。
     *
     * @param id 绝对 SVID
     */
    record Svid(int id) implements FieldRef {
    }

    /**
     * 一个设备常量字段。
     *
     * @param id 绝对 ECID
     */
    record Ecid(int id) implements FieldRef {
    }

    /**
     * 一个晶圆属性字段。
     *
     * @param name 属性名（{@code LOTID} 之类）
     */
    record Context(String name) implements FieldRef {
    }
}
