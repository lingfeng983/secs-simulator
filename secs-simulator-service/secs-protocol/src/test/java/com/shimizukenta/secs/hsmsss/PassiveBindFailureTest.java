package com.shimizukenta.secs.hsmsss;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

/**
 * 被动端"首次绑定失败"必须让 open() 失败。
 *
 * <p>rebind 变体天生是"绑不上就等 10 秒再试"的循环：运行期断线重绑这样处理是对的，
 * 但<b>第一次</b>绑定失败如果也静默重试，open() 就会正常返回——会话层据此把状态置为
 * OPEN，界面显示设备健康，而端口上根本没有监听，主机永远连不上。</p>
 *
 * <p>用 TEST-NET-3（203.0.113.0/24）的地址制造确定的绑定失败：它不是本机地址，
 * 两个平台上都会立即抛 "Cannot assign requested address"，不依赖端口占用与
 * SO_REUSEADDR 的平台差异。</p>
 */
class PassiveBindFailureTest {

    @Test
    void openFailsAndRollsBackWhenFirstBindFails() {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(HsmsSsProtocol.PASSIVE);
        config.socketAddress(new InetSocketAddress("203.0.113.1", 5199));

        HsmsSsCommunicator communicator = HsmsSsCommunicator.newInstance(config);

        IOException error = assertThrows(IOException.class, communicator::open,
                "首次 bind 失败必须让 open() 失败，而不是静默重试后返回成功");
        assertTrue(communicator.isClosed(),
                "open 失败必须回滚关闭：否则分发线程与 channel 会跟着一个「半个打开」的通信器泄漏");
        assertTrue(error.getMessage() != null, "异常需要带原因，现场才知道是 bind 失败");
    }
}
