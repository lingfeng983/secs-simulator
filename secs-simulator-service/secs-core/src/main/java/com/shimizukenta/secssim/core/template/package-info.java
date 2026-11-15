/**
 * 受限的纯函数表达式：值定义里 {@code mode: "expr"} 的求值引擎。
 *
 * <p>只保留 {@link com.shimizukenta.secssim.core.template.Expression} 与
 * {@link com.shimizukenta.secssim.core.template.ExpressionException}：
 * 表达式允许四则运算、少量纯函数与 {@code p.*} 私有变量，没有赋值、循环、
 * 状态——同一个表达式在任意时刻都能重算，可以逐个时刻批量试算预览。</p>
 *
 * <p>旧的「模板图 + 实例绑定」已随 schema v7 删除：一个数据项只有一份
 * 值定义（{@code source}），不再有跨实例的 {@code in.<实例>.<输出>} 引用，
 * 因此也不再需要拓扑排序与单写者检查。</p>
 */
package com.shimizukenta.secssim.core.template;
