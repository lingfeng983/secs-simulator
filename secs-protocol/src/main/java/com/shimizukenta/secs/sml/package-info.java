/**
 * Provides SML-Parsing.
 *
 * <p><b>本产品不使用这套 SML 实现</b>：它与 {@code secssim.runtime.sml} 是两套
 * 规则不同的解析器（后者支持本产品要求的 {@code SxFy [W] <...>.} 语法、行间距与
 * 单元号注入约定）。{@link com.shimizukenta.secs.SecsCommunicator} 上原来的
 * {@code send(SmlMessage)} / {@code send(SecsMessage, SmlMessage)} 入口已从公共
 * API 摘除，全仓（含测试）零调用方。</p>
 *
 * <p>保留这套类只是遵循上游库的目录结构；已知局限（不支持 Unicode/JIS8、
 * 列表长度语义与运行时不同）不在本产品修复范围内。需要手动发报文时用
 * HTTP 的 {@code POST /sessions/{id}/message}（走 {@code secssim.runtime.sml}），
 * 不要把它当成本产品的可用能力。</p>
 *
 * @author kenta-shimizu
 *
 */
package com.shimizukenta.secs.sml;
