/**
 * Implementation package providing SECS-I(SEMI-E4) Communicator, Message.
 *
 * <p><b>本产品不使用 SECS-I</b>：模拟器只支持 HSMS-SS（TCP/IP），
 * {@code secs-runtime} / {@code secs-cli} 中没有任何调用方，也没有测试覆盖。
 * 这套实现是从上游库保留的，已知问题（T4 重传递归无上限、
 * 发送等待无超时、电路线程中断时的空指针）不在本产品修复范围内。
 * 需要 SECS-I 的场景请另行评估，不要把它当成本产品的可用能力。</p>
 *
 * @author kenta-shimizu
 *
 */
package com.shimizukenta.secs.secs1;

