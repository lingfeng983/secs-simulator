/**
 * 模板与 DAG：让"一组 SVID 由一个模板统一生产"成立。
 *
 * <p>现实中的变量从来不是各自独立的：温度上升会带来压力变化，
 * 总流量必须等于各支路之和，加热器的开关跟随温度偏差。
 * 一个 SVID 一条曲线的做法表达不了这种关系，只能靠人肉对齐，迟早漂移。
 * </p>
 *
 * <p>本包提供三件东西：</p>
 * <ul>
 *   <li>{@link com.shimizukenta.secssim.core.template.Template} —— 多输出生产单元；</li>
 *   <li>{@link com.shimizukenta.secssim.core.template.Expression} —— 受限的纯函数表达式；</li>
 *   <li>{@link com.shimizukenta.secssim.core.template.TemplateGraph} —— 依赖拓扑求值，
 *       并强制"一个 SVID 只能有一个生产者"。</li>
 * </ul>
 */
package com.shimizukenta.secssim.core.template;
