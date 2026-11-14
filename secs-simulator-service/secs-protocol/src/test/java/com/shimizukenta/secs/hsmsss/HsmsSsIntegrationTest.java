package com.shimizukenta.secs.hsmsss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsLog;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsThrowableLog;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * Socket-level integration tests for the HSMS-SS fixes.
 *
 * <p>
 * Each test drives the real {@link AbstractHsmsSsCommunicator} reading loop
 * with raw sockets, so the fixed defects (T8 on the first read, the
 * too-long-message refusal, the short-frame tolerance, the S-Type check and
 * the guarded wait of the active circuit) are observed on the wire.
 * </p>
 */
@Timeout(120)
class HsmsSsIntegrationTest {

    private final List<SecsLog> logs = new CopyOnWriteArrayList<>();
    private final List<HsmsSsCommunicator> communicators = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeCommunicators() {
        communicators.forEach(c -> {
            try {
                c.close();
            }
            catch ( IOException ignore ) {
            }
        });
        communicators.clear();
    }

    @AfterEach
    void dumpLogs() {
        synchronized ( logs ) {
            logs.forEach(l -> System.out.println("[log] " + l.subject()
                    + " | " + l.optionalValueString().orElse("")));
        }
        logs.clear();
    }

    /* helpers */

    private static int freePort() throws IOException {
        try ( ServerSocket s = new ServerSocket(0) ) {
            return s.getLocalPort();
        }
    }

    private static void awaitCondition(BooleanSupplier cond, long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while ( System.currentTimeMillis() < deadline ) {
            if ( cond.getAsBoolean() ) {
                return;
            }
            sleep(25L);
        }
        fail("condition not met within " + timeoutMs + " ms");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    private void awaitLog(Predicate<SecsLog> pred, long timeoutMs) {
        awaitCondition(() -> {
            synchronized ( logs ) {
                return logs.stream().anyMatch(pred);
            }
        }, timeoutMs);
    }

    private static long countLog(List<SecsLog> logs, Predicate<SecsLog> pred) {
        synchronized ( logs ) {
            return logs.stream().filter(pred).count();
        }
    }

    private static boolean isThrowable(SecsLog log, Class<? extends Throwable> type) {
        return log instanceof SecsThrowableLog
                && type.isInstance(((SecsThrowableLog) log).getCause());
    }

    private HsmsSsCommunicator openPassive(HsmsSsCommunicatorConfig config) throws IOException {
        /* the plain passive variant binds synchronously in open();
         * the rebind variant binds on a background thread */
        config.notRebindIfPassive();
        HsmsSsCommunicator comm = HsmsSsCommunicator.newInstance(config);
        communicators.add(comm);
        comm.addSecsLogListener(logs::add);
        comm.open();
        return comm;
    }

    /** builds the 4-bytes length field + 10-bytes header of a HSMS frame */
    private static byte[] frame(int length, byte[] head) {
        byte[] bs = new byte[14];
        ByteBuffer b = ByteBuffer.wrap(bs);
        b.putInt(length);
        b.put(head);
        return bs;
    }

    private static byte[] controlHead(byte p, byte s, int systemBytes) {
        return new byte[] {
                0, 0, 0, 0, p, s,
                (byte)(systemBytes >> 24),
                (byte)(systemBytes >> 16),
                (byte)(systemBytes >> 8),
                (byte)(systemBytes)
        };
    }

    /** 帧头 index 6..9 的 system-bytes。 */
    private static int systemBytes(byte[] head) {
        return ((head[6] & 0xFF) << 24) | ((head[7] & 0xFF) << 16)
                | ((head[8] & 0xFF) << 8) | (head[9] & 0xFF);
    }

    private static void write(Socket socket, byte[]... chunks) throws IOException {
        /* single write: a stall between chunks could exceed the passive
         * side's T8 and terminate the connection under load */
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for ( byte[] chunk : chunks ) {
            out.write(chunk);
        }
        socket.getOutputStream().write(out.toByteArray());
        socket.getOutputStream().flush();
    }

    /** reads one HSMS frame, returns the 10-bytes header (body is drained) */
    private static byte[] readFrame(Socket socket, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());
        int length = in.readInt();
        byte[] head = new byte[10];
        in.readFully(head);
        if ( length > 10 ) {
            in.readFully(new byte[length - 10]);
        }
        return head;
    }

    /* tests */

    @Test
    @DisplayName("active and passive complete a select + S1F1/S1F2 session")
    void activeAndPassiveCompleteSession() throws Exception {
        int port = freePort();

        HsmsSsCommunicatorConfig passiveConfig = new HsmsSsCommunicatorConfig();
        passiveConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        HsmsSsCommunicator passive = openPassive(passiveConfig);

        HsmsSsCommunicatorConfig activeConfig = new HsmsSsCommunicatorConfig();
        activeConfig.protocol(HsmsSsProtocol.ACTIVE);
        activeConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        activeConfig.timeout().t3(10F);
        activeConfig.timeout().t6(3F);
        activeConfig.timeout().t5(1F);
        activeConfig.timeout().t8(30F);
        HsmsSsCommunicator active = HsmsSsCommunicator.newInstance(activeConfig);
        communicators.add(active);
        active.addSecsLogListener(logs::add);
        active.open();

        List<SecsMessage> received = new CopyOnWriteArrayList<>();
        List<Throwable> replyErrors = new CopyOnWriteArrayList<>();
        passive.addSecsMessageReceiveListener(msg -> {
            received.add(msg);
            try {
                /* reply must echo the primary's system-bytes: use the
                 * primary-message overload of send */
                passive.send(msg, 1, 2, false, Secs2.ascii("PONG"));
            }
            catch ( Exception e ) {
                replyErrors.add(e);
            }
        });

        try {
            active.openAndWaitUntilCommunicating();

            Optional<SecsMessage> r = active.send(1, 1, true, Secs2.ascii("PING"));

            assertTrue(r.isPresent(), "S1F1 W must be answered");
            assertEquals(1, r.get().getStream());
            assertEquals(2, r.get().getFunction());
            assertEquals("PONG", r.get().secs2().getAscii());

            awaitCondition(() -> ! received.isEmpty(), 5000L);
            assertEquals(1, received.get(0).getStream());
            assertEquals(1, received.get(0).getFunction());
            assertTrue(replyErrors.isEmpty(), () -> "reply failures: " + replyErrors);
        }
        finally {
            active.close();
            passive.close();
        }
    }

    @Test
    @DisplayName("T7 drops a silent pre-select peer (T8 must not police inter-message idle)")
    void silentPreSelectPeerIsDroppedByT7() throws IOException {
        int port = freePort();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.timeout().t7(1F);
        openPassive(config);

        try ( Socket client = new Socket("127.0.0.1", port) ) {
            /* connected, but not a single byte is sent */

            awaitCondition(() -> {
                try {
                    return client.getInputStream().read() < 0;   /* EOF */
                }
                catch ( IOException e ) {
                    return false;
                }
            }, 8000L);

            awaitLog(log -> isThrowable(log, HsmsSsTimeoutT7Exception.class), 5000L);
        }
    }

    @Test
    @DisplayName("a selected session stays alive across the 1000ms circuit-wait tick")
    void longLivedSessionSurvivesCircuitWaitTick() throws Exception {
        int port = freePort();

        HsmsSsCommunicatorConfig passiveConfig = new HsmsSsCommunicatorConfig();
        passiveConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        HsmsSsCommunicator passive = openPassive(passiveConfig);

        HsmsSsCommunicatorConfig activeConfig = new HsmsSsCommunicatorConfig();
        activeConfig.protocol(HsmsSsProtocol.ACTIVE);
        activeConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        activeConfig.timeout().t3(10F);
        activeConfig.timeout().t6(3F);
        activeConfig.timeout().t5(1F);
        activeConfig.timeout().t8(30F);
        HsmsSsCommunicator active = HsmsSsCommunicator.newInstance(activeConfig);
        communicators.add(active);
        active.addSecsLogListener(logs::add);
        active.open();

        List<SecsMessage> received = new CopyOnWriteArrayList<>();
        passive.addSecsMessageReceiveListener(msg -> {
            received.add(msg);
            try {
                passive.send(msg, 1, 2, false, Secs2.ascii("PONG"));
            }
            catch ( Exception ignore ) {
            }
        });

        try {
            active.openAndWaitUntilCommunicating();

            /* three transactions, each separated by more than the 1000ms
             * circuit-wait tick: the circuit loop must stay parked until the
             * connection really ends */
            for ( int i = 0; i < 3; ++i ) {
                Optional<SecsMessage> r = active.send(1, 1, true, Secs2.ascii("PING" + i));

                assertTrue(r.isPresent(), "transaction " + i + " must be answered");
                assertEquals("PONG", r.get().secs2().getAscii());

                if ( i < 2 ) {
                    sleep(1200L);
                }
            }

            awaitCondition(() -> received.size() >= 3, 5000L);
            assertTrue(active.isOpen(), "active communicator must still be open");
        }
        finally {
            active.close();
            passive.close();
        }
    }

    @Test
    @DisplayName("an idle selected link survives beyond T8 (T8 is an inter-character timeout)")
    void idleSelectedLinkSurvivesBeyondT8() throws Exception {
        int port = freePort();

        HsmsSsCommunicatorConfig passiveConfig = new HsmsSsCommunicatorConfig();
        passiveConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        HsmsSsCommunicator passive = openPassive(passiveConfig);

        HsmsSsCommunicatorConfig activeConfig = new HsmsSsCommunicatorConfig();
        activeConfig.protocol(HsmsSsProtocol.ACTIVE);
        activeConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        activeConfig.timeout().t3(10F);
        activeConfig.timeout().t6(3F);
        activeConfig.timeout().t5(1F);
        /* T8 stays at its 6s default on both sides */
        HsmsSsCommunicator active = HsmsSsCommunicator.newInstance(activeConfig);
        communicators.add(active);
        active.addSecsLogListener(logs::add);
        active.open();

        List<SecsMessage> received = new CopyOnWriteArrayList<>();
        passive.addSecsMessageReceiveListener(msg -> {
            try {
                passive.send(msg, 1, 2, false, Secs2.ascii("PONG"));
            }
            catch ( Exception ignore ) {
            }
        });

        try {
            active.openAndWaitUntilCommunicating();

            final long tryConnectsBefore = countLog(logs,
                    l -> "HSMS-SS Try-Connect".equals(l.subject()));

            /* idle for longer than T8: no message is in flight, so the
             * inter-character timeout must not fire */
            sleep(7500L);

            Optional<SecsMessage> r = active.send(1, 1, true, Secs2.ascii("PING"));
            assertTrue(r.isPresent(), "the idle link must still carry transactions");
            assertEquals("PONG", r.get().secs2().getAscii());

            /* and it must be the SAME connection: no T8, no reconnect */
            assertTrue(logs.stream().noneMatch(l -> isThrowable(l, HsmsSsTimeoutT8Exception.class)),
                    "T8 must not fire while the link is idle between messages");
            assertEquals(tryConnectsBefore, countLog(logs,
                    l -> "HSMS-SS Try-Connect".equals(l.subject())),
                    "the connection must not have been re-established");
        }
        finally {
            active.close();
            passive.close();
        }
    }

    @Test
    @DisplayName("a message declaring more than maxMessageBytes is refused before the body is read")
    void tooLongMessageIsRefused() throws IOException {
        int port = freePort();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.maxMessageBytes(64L);
        config.timeout().t8(30F);
        openPassive(config);

        try ( Socket client = new Socket("127.0.0.1", port) ) {
            /* length = 100 -> body of 90 bytes > 64 */
            write(client,
                    frame(100, controlHead((byte)0, (byte)0, 1)),
                    new byte[90]);

            awaitLog(log -> log instanceof SecsThrowableLog
                    && ((SecsThrowableLog) log).getCause() != null
                    && String.valueOf(((SecsThrowableLog) log).getCause().getMessage())
                        .contains("too long message: 90"), 8000L);

            awaitCondition(() -> {
                try {
                    return client.getInputStream().read() < 0;   /* EOF */
                }
                catch ( IOException e ) {
                    return false;
                }
            }, 8000L);
        }
    }

    @Test
    @DisplayName("a message within maxMessageBytes is still processed")
    void messageWithinLimitIsProcessed() throws IOException {
        int port = freePort();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.maxMessageBytes(64L);
        config.timeout().t8(30F);
        openPassive(config);

        try ( Socket client = new Socket("127.0.0.1", port) ) {
            /* S1F1 with a valid ASCII body, not selected yet -> REJECT NOT_SELECTED */
            byte[] head = new byte[] { 0, 0, 1, 1, 0, 0, 0, 0, 0, 1 };
            write(client,
                    frame(16, head),
                    new byte[] { (byte)0x41, (byte)0x04, (byte)'a', (byte)'b', (byte)'c', (byte)'d' });

            byte[] rsp = readFrame(client, 8000);
            assertEquals(7, rsp[5] & 0xFF, "REJECT_REQ expected");
            assertEquals(4, rsp[3] & 0xFF, "NOT_SELECTED expected");
        }
    }

    @Test
    @DisplayName("a short frame (length < 10) terminates the connection: the stream cannot be re-synchronized")
    void shortFrameTerminatesConnection() throws IOException {
        int port = freePort();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.timeout().t8(30F);
        openPassive(config);

        try ( Socket client = new Socket("127.0.0.1", port) ) {
            /* short frame: the length field declares only 5 total bytes,
             * followed by a valid SELECT_REQ. The header (10 bytes) is consumed
             * anyway, so the next frame's bytes are already misaligned: the only
             * safe behavior is to drop the connection. */
            byte[] shortFrame = ByteBuffer.allocate(14)
                    .putInt(5)
                    .put(controlHead((byte)0, (byte)0, 1))
                    .array();
            write(client,
                    shortFrame,
                    frame(10, controlHead((byte)0, (byte)1, 2)));

            awaitLog(log -> log instanceof SecsThrowableLog
                    && ((SecsThrowableLog) log).getCause() != null
                    && String.valueOf(((SecsThrowableLog) log).getCause().getMessage())
                        .contains("short frame: length=5"), 8000L);

            awaitCondition(() -> {
                try {
                    return client.getInputStream().read() < 0;   /* EOF */
                }
                catch ( IOException e ) {
                    return false;
                }
            }, 8000L);
        }
    }

    @Test
    @DisplayName("DESELECT_REQ in NOT_SELECTED is answered with status 1 (not rejected)")
    void deselectReqInNotSelectedIsAnswered() throws IOException {
        int port = freePort();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.timeout().t8(30F);
        openPassive(config);

        try ( Socket client = new Socket("127.0.0.1", port) ) {
            /* 未 SELECT 时收到 Deselect.req：E37 的状态码是 1（通信未建立），
               但不能沉默、更不能回 REJECT——以前这里既不回也不报，对端只能等 T6。 */
            write(client, frame(10, controlHead((byte)0, (byte)3, 1)));

            byte[] rsp = readFrame(client, 3000);
            assertEquals(4, rsp[5] & 0xFF, "DESELECT_RSP expected");
            assertEquals(1, rsp[3] & 0xFF, "status must be 1 when not selected");

            /* the connection is still usable: LINKTEST_REQ is answered */
            write(client, frame(10, controlHead((byte)0, (byte)5, 2)));
            byte[] linkRsp = readFrame(client, 8000);
            assertEquals(6, linkRsp[5] & 0xFF, "LINKTEST_RSP expected");
        }
    }

    @Test
    @DisplayName("an unsupported S-Type (8) is rejected with NOT_SUPPORT_TYPE_S")
    void unsupportedSTypeIsRejected() throws IOException {
        int port = freePort();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.timeout().t8(30F);
        openPassive(config);

        try ( Socket client = new Socket("127.0.0.1", port) ) {
            write(client, frame(10, controlHead((byte)0, (byte)8, 1)));

            byte[] rsp = readFrame(client, 8000);
            assertEquals(7, rsp[5] & 0xFF, "REJECT_REQ expected");
            assertEquals(1, rsp[3] & 0xFF, "NOT_SUPPORT_TYPE_S expected");
        }
    }

    @Test
    @DisplayName("active circuit keeps retrying when the peer closes immediately (no lost-notification stall)")
    void activeCircuitKeepsRetryingWhenPeerClosesImmediately() throws Exception {
        int port = freePort();

        AtomicBoolean accepting = new AtomicBoolean(true);
        Thread acceptor = new Thread(() -> {
            try ( ServerSocket server = new ServerSocket(port) ) {
                while ( accepting.get() ) {
                    try {
                        Socket s = server.accept();
                        s.close();
                    }
                    catch ( IOException e ) {
                        break;
                    }
                }
            }
            catch ( IOException ignore ) {
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(HsmsSsProtocol.ACTIVE);
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.timeout().t5(0.2F);

        HsmsSsCommunicator active = HsmsSsCommunicator.newInstance(config);
        communicators.add(active);
        active.addSecsLogListener(logs::add);
        active.open();

        try {
            awaitCondition(() ->
                    countLog(logs, l -> "HSMS-SS Try-Connect".equals(l.subject())) >= 3,
                    10000L);
        }
        finally {
            accepting.set(false);
            active.close();
        }
    }

    @Test
    @DisplayName("a second TCP connection must not unregister the selected link")
    void secondConnectionDoesNotUnregisterSelectedLink() throws Exception {
        int port = freePort();

        HsmsSsCommunicatorConfig passiveConfig = new HsmsSsCommunicatorConfig();
        passiveConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        HsmsSsCommunicator passive = openPassive(passiveConfig);

        List<Boolean> communicatable = new CopyOnWriteArrayList<>();
        passive.addSecsCommunicatableStateChangeListener(communicatable::add);
        passive.addSecsMessageReceiveListener(msg -> {
            try {
                passive.send(msg, 1, 2, false, Secs2.ascii("PONG"));
            }
            catch ( Exception ignore ) {
            }
        });

        HsmsSsCommunicatorConfig activeConfig = new HsmsSsCommunicatorConfig();
        activeConfig.protocol(HsmsSsProtocol.ACTIVE);
        activeConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        activeConfig.timeout().t3(3F);
        activeConfig.timeout().t5(1F);
        activeConfig.timeout().t6(3F);
        activeConfig.timeout().t8(30F);
        HsmsSsCommunicator active = HsmsSsCommunicator.newInstance(activeConfig);
        communicators.add(active);
        active.open();

        try {
            active.openAndWaitUntilCommunicating();
            awaitCondition(() -> communicatable.contains(Boolean.TRUE), 5000L);
            final int firstTrue = communicatable.indexOf(Boolean.TRUE);

            /* 第二条连接：SELECT 被拒（ALREADY_USED）后立即关闭。
               修复前，这条连接结束时的 finally 会把它"自己"当成选中连接注销掉，
               把 active 已经建立的链路打成 NOT_CONNECTED。 */
            try ( Socket second = new Socket("127.0.0.1", port) ) {
                write(second, frame(10, controlHead((byte)0, (byte)1, 7)));
                byte[] rsp = readFrame(second, 8000);
                assertEquals(2, rsp[5] & 0xFF, "SELECT_RSP expected");
                assertEquals(3, rsp[3] & 0xFF, "ALREADY_USED expected");
            }

            /* 给被动端时间走完第二条连接的 EOF 清理（mainTask 的 finally） */
            sleep(800L);

            assertTrue(communicatable.subList(firstTrue, communicatable.size())
                            .stream().allMatch(Boolean::booleanValue),
                    "closing another connection must not flip the selected link to NOT_CONNECTED: "
                            + communicatable);

            /* 修复前这里会抛 NotConnected：getSelectedConnection() 已被置空 */
            passive.send(1, 1, false, Secs2.ascii("STILL-ALIVE"));

            Optional<SecsMessage> r = active.send(1, 1, true, Secs2.ascii("PING"));
            assertTrue(r.isPresent(), "the selected link must still carry transactions");
            assertEquals("PONG", r.get().secs2().getAscii());
        }
        finally {
            active.close();
            passive.close();
        }
    }

    @Test
    @DisplayName("deviceId >= 0x8000 travels through a real active session")
    void highDeviceIdSession() throws Exception {
        int port = freePort();

        HsmsSsCommunicatorConfig passiveConfig = new HsmsSsCommunicatorConfig();
        passiveConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        passiveConfig.deviceId(0xFFFF);
        HsmsSsCommunicator passive = openPassive(passiveConfig);

        HsmsSsCommunicatorConfig activeConfig = new HsmsSsCommunicatorConfig();
        activeConfig.protocol(HsmsSsProtocol.ACTIVE);
        activeConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        activeConfig.deviceId(0x8000);
        activeConfig.timeout().t3(10F);
        activeConfig.timeout().t6(3F);
        activeConfig.timeout().t5(1F);
        activeConfig.timeout().t8(30F);
        HsmsSsCommunicator active = HsmsSsCommunicator.newInstance(activeConfig);
        communicators.add(active);
        active.addSecsLogListener(logs::add);
        active.open();

        List<SecsMessage> received = new CopyOnWriteArrayList<>();
        passive.addSecsMessageReceiveListener(received::add);

        try {
            active.openAndWaitUntilCommunicating();

            Optional<SecsMessage> r = active.send(1, 1, false, Secs2.ascii("HI"));
            assertTrue(r.isEmpty());

            awaitCondition(() -> ! received.isEmpty(), 5000L);
            assertEquals(0x8000, ((HsmsSsMessage) received.get(0)).deviceId(),
                    "the high bit of the device-id must survive the wire");
        }
        finally {
            active.close();
            passive.close();
        }
    }

    @Test
    @DisplayName("DESELECT.req is answered and releases the selection before the connection closes")
    void deselectReqIsAnsweredAndReleasesSelection() throws Exception {
        int port = freePort();

        HsmsSsCommunicatorConfig passiveConfig = new HsmsSsCommunicatorConfig();
        passiveConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
        passiveConfig.timeout().t6(3F);
        openPassive(passiveConfig);

        try ( Socket socket = new Socket("127.0.0.1", port) ) {
            socket.setTcpNoDelay(true);

            write(socket, frame(10, controlHead((byte)0, (byte)1, 900)));
            byte[] selectRsp = readFrame(socket, 3000);
            assertEquals(2, selectRsp[5] & 0xFF, "SELECT.rsp expected");
            assertEquals(0, selectRsp[3] & 0xFF, "SELECT status must be 0");

            /* E37 7.4：SELECTED 态收到 Deselect.req 必须回 Deselect.rsp。
               修复前这里落到 default 分支，什么也不发，对端只能等 T6 超时。 */
            write(socket, frame(10, controlHead((byte)0, (byte)3, 901)));
            byte[] deselectRsp = readFrame(socket, 3000);
            assertEquals(4, deselectRsp[5] & 0xFF, "DESELECT.rsp expected");
            assertEquals(0, deselectRsp[3] & 0xFF, "DESELECT status must be 0");
            assertEquals(901,
                    ((deselectRsp[6] & 0xFF) << 24) | ((deselectRsp[7] & 0xFF) << 16)
                    | ((deselectRsp[8] & 0xFF) << 8) | (deselectRsp[9] & 0xFF),
                    "Deselect.rsp must reuse the request system bytes");

            /* 同一 TCP 连接上重新 SELECT：修复前 selectedConnection 没释放，
               这里会拿到 ALREADY_USED(3)，现场表现为"必须重开会话才能连上"。 */
            write(socket, frame(10, controlHead((byte)0, (byte)1, 902)));
            byte[] secondSelect = readFrame(socket, 3000);
            assertEquals(2, secondSelect[5] & 0xFF, "second SELECT.rsp expected");
            assertEquals(0, secondSelect[3] & 0xFF,
                    "selection must be released by Deselect (got ALREADY_USED?)");
        }
    }


    @Test
    @DisplayName("undecodable body of a W primary is answered with S9F7 (Illegal Data)")
    void illegalDataIsAnsweredWithS9F7() throws Exception {
        int port = freePort();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        openPassive(config);

        try ( Socket client = new Socket("127.0.0.1", port) ) {
            client.setTcpNoDelay(true);

            write(client, frame(10, controlHead((byte)0, (byte)1, 4001)));
            byte[] selectRsp = readFrame(client, 3000);
            assertEquals(2, selectRsp[5] & 0xFF, "SELECT.rsp expected");

            /* S1F3 W，但 body 是坏的：一个声明 2 字节的 F4 项（F4 必须是 4 的倍数）。
               以前这种帧只进日志队列，主机既收不到数据也收不到否定应答，只能等 T3。 */
            byte[] head = new byte[] {
                    0, 0, 1, (byte)0x83, 0, 0,
                    (byte)(4002 >> 24), (byte)(4002 >> 16), (byte)(4002 >> 8), (byte)4002
            };
            byte[] badBody = new byte[] { 0x2E, 1, 2 };   /* F4, len=2, then two bytes */
            byte[] msg = new byte[14 + badBody.length];
            byte[] framed = frame(10 + badBody.length, head);
            System.arraycopy(framed, 0, msg, 0, 14);
            System.arraycopy(badBody, 0, msg, 14, badBody.length);
            write(client, msg);

            client.setSoTimeout(8000);
            java.io.DataInputStream in = new java.io.DataInputStream(client.getInputStream());
            int len = in.readInt();
            byte[] rspHead = new byte[10];
            in.readFully(rspHead);
            byte[] rspBody = new byte[Math.max(0, len - 10)];
            if (rspBody.length > 0) {
                in.readFully(rspBody);
            }

            assertEquals(9, rspHead[2] & 0xFF, "S9 expected");
            assertEquals(7, rspHead[3] & 0xFF, "S9F7 (Illegal Data) expected");
            /* E5：S9F7 的 body 是一个 B 项（格式码 2，扩展长度字节 10），
               携带被拒报文的 10 字节头（IDN），主机据此把否定应答关联到原事务。 */
            assertEquals(12, rspBody.length, "B item: 2-byte header + 10 data bytes");
            assertEquals(0x20, rspBody[0] & 0xF0, "B format item (code 2) expected");
            assertEquals(10, rspBody[1] & 0xFF, "extended length byte = 10 expected");
            for (int i = 0; i < 10; i++) {
                assertEquals(head[i], rspBody[rspBody.length - 10 + i], "echoed header byte " + i);
            }
        }
    }

    @Test
    @DisplayName("an unsolicited control response on a selected active link is rejected, not treated as Deselect")
    void unsolicitedResponseOnActiveLinkIsRejected() throws Exception {
        /* 端口用 ServerSocket(0) 现取：freePort() 到 bind 之间开个口子，CI 并行时会被抢 */
        try ( ServerSocket peer = new ServerSocket(0) ) {
            int port = peer.getLocalPort();

            /* 用 raw socket 扮被动端，才能精确构造“选中之后收到一个无人应答的应答帧”。 */
            HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
            config.protocol(HsmsSsProtocol.ACTIVE);
            config.socketAddress(new InetSocketAddress("127.0.0.1", port));
            /* 关掉心跳，否则主动端自己发出的 LINKTEST.req 会吞掉这个应答 */
            config.notLinktest();
            config.timeout().t3(10F);
            config.timeout().t5(1F);
            config.timeout().t6(5F);
            config.timeout().t8(30F);

            HsmsSsCommunicator active = HsmsSsCommunicator.newInstance(config);
            communicators.add(active);
            active.addSecsLogListener(logs::add);
            active.open();

            try ( Socket socket = peer.accept() ) {
                socket.setTcpNoDelay(true);

                byte[] selectReq = readFrame(socket, 10000);
                assertEquals(1, selectReq[5] & 0xFF, "SELECT.req expected");

                /* Select.rsp(0) -> active 进入 SELECTED */
                write(socket, frame(10, controlHead((byte)0, (byte)2, systemBytes(selectReq))));

                /* 非应答期收到 Linktest.rsp：E37 要求回 Reject.req(TRANSACTION_NOT_OPEN)，
                   且不得改变选中状态。修复前这里缺 break，会落穿到 DESELECT_REQ，
                   回一条 Deselect.rsp 并把已经选中的连接拆掉。 */
                write(socket, frame(10, controlHead((byte)0, (byte)6, 0x7FFFFFFF)));

                byte[] rsp = readFrame(socket, 10000);
                assertEquals(7, rsp[5] & 0xFF, "REJECT.req expected (got DESELECT.rsp?)");
                assertEquals(3, rsp[3] & 0xFF, "TRANSACTION_NOT_OPEN expected");
                assertEquals(6, rsp[2] & 0xFF, "Reject must echo the rejected frame's SType");
                assertEquals(0x7FFFFFFF, systemBytes(rsp),
                        "Reject must reuse the rejected frame's system bytes");
            }
            finally {
                active.close();
            }
        }
    }

    @Test
    @DisplayName("a Reject.req on a selected active link is absorbed without answering")
    void receivedRejectIsNotAnswered() throws Exception {
        try ( ServerSocket peer = new ServerSocket(0) ) {
            int port = peer.getLocalPort();

            HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
            config.protocol(HsmsSsProtocol.ACTIVE);
            config.socketAddress(new InetSocketAddress("127.0.0.1", port));
            config.notLinktest();
            config.timeout().t3(10F);
            config.timeout().t5(1F);
            config.timeout().t6(5F);
            config.timeout().t8(30F);

            HsmsSsCommunicator active = HsmsSsCommunicator.newInstance(config);
            communicators.add(active);
            active.addSecsLogListener(logs::add);
            active.open();

            try ( Socket socket = peer.accept() ) {
                socket.setTcpNoDelay(true);

                byte[] selectReq = readFrame(socket, 10000);
                assertEquals(1, selectReq[5] & 0xFF, "SELECT.req expected");
                write(socket, frame(10, controlHead((byte)0, (byte)2, systemBytes(selectReq))));

                /* 对端主动发 Reject.req(reason=3)：Reject 是终点消息。若本端再回一条 Reject，
                   SystemBytes 原样回带会让双方无速率限制地互回（同库主动端↔被动端就能触发）。 */
                write(socket, frame(10, new byte[] {
                        0, 0, 5, 3, 0, 7,
                        (byte)0x7F, (byte)0xFF, (byte)0xFF, (byte)0xFE
                }));

                try {
                    byte[] echo = readFrame(socket, 500);
                    fail("收到 Reject.req 后不得再发任何帧，实际收到 SType=" + (echo[5] & 0xFF));
                }
                catch ( SocketTimeoutException expected ) {
                    /* 500ms 内没有任何回落：正确 */
                }

                /* 拒绝不得改变选中状态：Linktest 必须照常应答 */
                write(socket, frame(10, controlHead((byte)0, (byte)5, 0x01020304)));
                byte[] linktestRsp = readFrame(socket, 5000);
                assertEquals(6, linktestRsp[5] & 0xFF, "LINKTEST.rsp expected");
                assertEquals(0x01020304, systemBytes(linktestRsp));
            }
            finally {
                active.close();
            }
        }
    }

}
