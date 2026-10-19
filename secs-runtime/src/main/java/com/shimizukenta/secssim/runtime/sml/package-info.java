/**
 * SML 文本：渲染与受控子集解析。
 *
 * <p>协议层只提供 {@code Secs2.toJson()}，没有 SML 输出。报文面板要给人看，
 * JSON 里的 {@code {"L":{"L":[...]}}} 既不直观也不符合大家的阅读习惯，
 * 所以这里有 {@link com.shimizukenta.secssim.runtime.sml.SmlRenderer}
 * 做只读渲染。</p>
 *
 * <p>手动发送又需要反向：{@link com.shimizukenta.secssim.runtime.sml.SmlParser}
 * 只接受手动发送真正需要的子集，未知类型/简写一律报错，
 * 不把"猜"出来的字节发给主机。</p>
 */
package com.shimizukenta.secssim.runtime.sml;
