/**
 * 应用服务层：会话、配置、模板注册表、预览、审计。
 *
 * <p>本层允许依赖 secs-core 与 secs-json，不得依赖 HTTP 实现细节。
 * 与协议栈的接触被收口到 {@code SessionRuntime} 端口，具体实现放在 {@code secs-runtime}。
 * </p>
 */
package com.shimizukenta.secssim.app;
