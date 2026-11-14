/**
 * 主机模拟器：站在"主机侧"主动连设备、发预设报文、收采集数据。
 *
 * <p>与 {@code runtime.session} 的分界：那边实现"设备"（HSMS-SS 被动端，
 * 应答 S1F1/S1F3、把值装进 S1F4），这边实现"主机"（主动端，跑 S1F1/S1F13/S2F33…
 * 这套对话）。两边共用协议层的 {@code HsmsSsCommunicator}，但生命周期、
 * 报文语法与并发模型互不相干——放在同一个包会让"改设备协议"和"改主机预设"
 * 互相牵连。</p>
 *
 * <p>{@code HostSimRegistry}（注册、查找、重连、关闭）留在 {@code runtime} 包：
 * 它需要会话管理器和 HTTP 结果类型，属于装配层，不属于主机实现本身。</p>
 *
 * <p>依赖方向只有一个：本包 → {@code runtime.session}。主机句柄需要从设备会话
 * 取本机模型（{@code HsmsSessionRuntime.model()}）来构造预设报文；
 * 设备侧解释主机命令的逻辑在 {@code runtime.session.HostCommandHandler}，不在这里。</p>
 */
package com.shimizukenta.secssim.runtime.hostsim;
