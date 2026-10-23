package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 测试夹具的"连接就绪"等待。
 *
 * <p>这些用例都是"设备侧起一个 HSMS 被动端，再用 ACTIVE 客户端连上去"。</p>
 *
 * <p><b>为什么不能只调 {@code client.openAndWaitUntilCommunicating()}：</b>它只保证
 * <b>客户端</b>这一侧走完了 connect → Select.req → Select.rsp。设备侧把连接加入选中集合、
 * 回写 {@code communicatable} 是另一个线程里的事，两者之间有窗口。同一 JVM 内串行跑
 * 几十个这样的用例时，这个窗口会被放大成偶发失败：
 * 客户端说"连上了"，设备侧 {@code hasPeer()} 还是 false，
 * 于是发给设备的报文被拒（"会话未打开或尚未与主机建立通信"）、
 * 或设备侧状态迁移还没发生就被断言。</p>
 *
 * <p>所以凡是要<b>通过设备侧会话做事</b>的用例，连上之后都要再等设备侧就绪。</p>
 */
final class SessionTestSupport {

    /** 等待设备侧看到主机上线的上限。超过这个时长通常意味着真出问题了。 */
    private static final long PEER_TIMEOUT_MS = 5_000L;

    private SessionTestSupport() {
    }

    /**
     * 等设备侧看到主机已选中链路。
     *
     * @param session 设备侧会话
     * @throws InterruptedException 被中断
     */
    static void awaitPeer(HsmsSessionRuntime session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PEER_TIMEOUT_MS;

        while (!session.hasPeer()) {
            if (System.currentTimeMillis() >= deadline) {
                assertTrue(session.hasPeer(),
                        "设备侧在 " + PEER_TIMEOUT_MS + " ms 内没有看到主机上线（lastError="
                                + session.lastError() + "）");
            }
            Thread.sleep(10L);
        }
    }
}
