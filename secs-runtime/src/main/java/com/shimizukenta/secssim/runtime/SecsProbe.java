package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secssim.app.log.Log;

/**
 * SECS 能力探针：开一个 HSMS-SS 被动端口并应答 S1F1。
 *
 * <p>用途是把"这台机器能不能跑 SECS 通讯"变成一个 5 秒就能回答的问题：
 * 端口是否被防火墙拦、异步 I/O 是否可用、native 镜像是否把 NIO 裁剪掉了。
 * CI 也用它守护 {@code AsynchronousSocketChannel} 这条最容易在原生镜像里出问题的链路。
 * </p>
 */
public final class SecsProbe implements AutoCloseable {

    private final SecsCommunicator communicator;
    private volatile boolean replied;

    private SecsProbe(SecsCommunicator communicator) {
        this.communicator = communicator;
    }

    /**
     * 打开被动端口。
     *
     * @param port 监听端口
     * @return 探针
     * @throws IOException 绑定失败
     */
    public static SecsProbe open(int port) throws IOException {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(HsmsSsProtocol.PASSIVE);
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.deviceId(10);
        config.isEquip(true);

        SecsCommunicator communicator = HsmsSsCommunicator.open(config);
        SecsProbe probe = new SecsProbe(communicator);
        communicator.addSecsMessageReceiveListener(probe::onPrimary);
        return probe;
    }

    /**
     * 是否已经成功应答过 S1F1。
     *
     * @return 应答过返回 {@code true}
     */
    public boolean replied() {
        return this.replied;
    }

    private void onPrimary(SecsMessage primary) {
        try {
            if (primary.getStream() == 1 && primary.getFunction() == 1 && primary.wbit()) {
                this.communicator.send(primary, 1, 2, false, Secs2.getBuilder().list());
                this.replied = true;
            }
        } catch (Exception e) {
            Log.error("S1F2 应答失败", e);
        }
    }

    @Override
    public void close() {
        try {
            this.communicator.close();
        } catch (IOException e) {
            Log.error("关闭探针端口失败", e);
        }
    }
}
