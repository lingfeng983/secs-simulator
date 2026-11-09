/**
 * 命令行入口与端到端测试宿主。
 *
 * <p>本模块提供真正的 JVM 入口 {@link com.shimizukenta.secssim.cli.CliMain}
 * （{@code main} + {@code System.exit}），装配逻辑在 {@code secs-runtime}；
 * 同时承载跨模块的端到端测试（会话训练、TLS 服务端、原生镜像烟测）。
 * </p>
 */
package com.shimizukenta.secssim.cli;
