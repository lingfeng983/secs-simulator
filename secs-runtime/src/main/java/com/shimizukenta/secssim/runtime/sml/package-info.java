/**
 * SML 渲染：把 SECS-II 数据项渲染成工程师看得懂的文本。
 *
 * <p>协议层只提供 {@code Secs2.toJson()}，没有 SML 输出。报文面板要给人看，
 * JSON 里的 {@code {"L":{"L":[...]}}} 既不直观也不符合大家的阅读习惯，
 * 所以这里自己实现一个只读的渲染器。</p>
 */
package com.shimizukenta.secssim.runtime.sml;
