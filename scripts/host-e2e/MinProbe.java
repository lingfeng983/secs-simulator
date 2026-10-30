// 最小实机探针：以真实 HSMS 主机身份连设备，验证 GEM 主链路 + S9Fx。
// javac -cp <secs-protocol classes> MinProbe.java
// java  -cp .;<classes> MinProbe <hsmsPort> <deviceId>
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class MinProbe {

    static final BlockingQueue<SecsMessage> push = new LinkedBlockingQueue<>();

    public static void main(String[] args) throws Exception {
        int hsmsPort = Integer.parseInt(args[0]);
        int deviceId = Integer.parseInt(args[1]);

        HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
        cfg.protocol(HsmsSsProtocol.ACTIVE);
        cfg.socketAddress(new InetSocketAddress("127.0.0.1", hsmsPort));
        cfg.deviceId(deviceId);
        cfg.timeout().t3(8F);
        cfg.timeout().t6(3F);
        cfg.timeout().t5(2F);
        cfg.timeout().t8(60F);

        HsmsSsCommunicator host = HsmsSsCommunicator.newInstance(cfg);
        host.addSecsMessageReceiveListener(push::add);
        host.open();
        host.openAndWaitUntilCommunicating();
        System.out.println("SELECT ok");

        Secs2Builder b = Secs2.getBuilder();

        step("linktest", () -> { host.linktest(); return null; });
        step("S1F1", () -> host.gem().s1f1());
        step("S1F13", () -> host.send(1, 13, true, Secs2.empty()));
        step("S1F17(ONLINE)", () -> host.send(1, 17, true, Secs2.empty()));
        step("S2F17", () -> host.send(2, 17, true, Secs2.empty()));

        /* S2F31：E5 TIME 是 12 或 16 位数字 */
        step("S2F31 valid", () -> host.send(2, 31, true, b.ascii("2026103014000000")));
        step("S2F31 invalid", () -> host.send(2, 31, true, b.ascii("not-a-time")));

        /* 动态报告：S2F33 标准形状 L[2]{U4 DATAID, L{L{U4 RPTID, L{U4 VID}}}} */
        step("S2F33", () -> host.send(2, 33, true, b.list(
                b.uint4(0),
                b.list(b.list(b.uint4(5001), b.list(b.uint4(1001), b.uint4(1090)))))));
        step("S2F35", () -> host.send(2, 35, true, b.list(
                b.uint4(0),
                b.list(b.list(b.uint4(2003), b.list(b.uint4(5001)))))));
        /* S2F37 标准形状 L[2]{BOOLEAN CEED, L{U4 CEID}} */
        step("S2F37", () -> host.send(2, 37, true, b.list(
                b.bool(true),
                b.list(b.uint4(2003)))));

        /* 不支持报文：设备回 S9F3 / S9F5（以新 primary 发出，不是应答） */
        host.send(3, 1, false, Secs2.empty());
        dumpPush("after S3F1", 3000);
        host.send(1, 51, false, Secs2.empty());
        dumpPush("after S1F51", 3000);

        /* 主机 W 请求 + 坏 body → 设备回 S9F7（raw 见 SimHost，此处跳过） */

        host.close();
        System.out.println("done");
    }

    static void dumpPush(String label, long ms) throws Exception {
        SecsMessage m = push.poll(ms, TimeUnit.MILLISECONDS);
        System.out.println(label + " -> push=" + (m == null ? "(none)"
                : "S" + m.getStream() + "F" + m.getFunction() + " " + m.secs2().toJson()));
    }

    interface Act { Optional<SecsMessage> run() throws Exception; }

    static void step(String name, Act a) {
        try {
            Optional<SecsMessage> r = a.run();
            System.out.println(name + " -> " + (r == null ? "(void)"
                    : r.map(m -> "S" + m.getStream() + "F" + m.getFunction() + " " + m.secs2().toJson())
                            .orElse("(empty)")));
        } catch (Exception e) {
            System.out.println(name + " -> EXC " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
