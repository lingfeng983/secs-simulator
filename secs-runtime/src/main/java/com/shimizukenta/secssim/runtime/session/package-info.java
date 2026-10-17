/**
 * 会话的协议实现。
 *
 * <p>这里把 {@code secs-app} 的 {@code SessionRuntime} 端口实现成真正的 SECS 通讯：
 * 打开 HSMS-SS 端口、应答 S1F1/S1F3、把值引擎的数据装进 S1F4。
 * 应用层与 HTTP 层都不认识协议类型，依赖方向始终朝着 {@code secs-core}。
 * </p>
 */
package com.shimizukenta.secssim.runtime.session;
