/**
 * Trace（S2F23 / S6F1）：主机按周期点播一组 SVID。
 *
 * <p>与事件（S6F11）的区别：事件是"有事才报"，Trace 是"按固定周期采样"。
 * 两者的数据都取自同一个值来源，因此同一时刻的 S1F4 / S6F11 / S6F1 必须自洽——
 * 主机侧常做这种交叉校验。
 * </p>
 */
package com.shimizukenta.secssim.core.trace;
