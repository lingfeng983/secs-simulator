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
    @DisplayName("T8 covers the first read: a silent peer is timed out, not hung")
    void t8TimeoutTerminatesSilentPeerConnection() throws IOException {
        int port = freePort();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.timeout().t8(0.5F);
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

            awaitLog(log -> isThrowable(log, HsmsSsTimeoutT8Exception.class), 5000L);
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
    @DisplayName("a short frame (length < 10) is logged and does not break the connection")
    void shortFrameDoesNotTerminateConnection() throws IOException {
        int port = freePort();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.timeout().t8(30F);
        openPassive(config);

        try ( Socket client = new Socket("127.0.0.1", port) ) {
            /* short frame: the length field declares only 5 total bytes,
             * followed by a valid SELECT_REQ */
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

            byte[] rsp = readFrame(client, 8000);
            assertEquals(2, rsp[5] & 0xFF, "SELECT_RSP expected: connection survived the short frame");
        }
    }

    @Test
    @DisplayName("DESELECT_REQ (S-Type 3) is a supported S-Type and is not rejected")
    void deselectReqIsNotRejectedAsUnsupportedSType() throws IOException {
        int port = freePort();

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.timeout().t8(30F);
        openPassive(config);

        try ( Socket client = new Socket("127.0.0.1", port) ) {
            write(client, frame(10, controlHead((byte)0, (byte)3, 1)));

            /* no response: S-Type 3 is valid, so no REJECT must be sent */
            client.setSoTimeout(1500);
            try {
                int r = client.getInputStream().read();
                if ( r >= 0 ) {
                    fail("unexpected response to DESELECT_REQ, first byte " + r);
                }
                fail("connection closed unexpectedly");
            }
            catch ( SocketTimeoutException expected ) {
            }

            /* the connection is still usable: LINKTEST_REQ is answered */
            write(client, frame(10, controlHead((byte)0, (byte)5, 2)));
            byte[] rsp = readFrame(client, 8000);
            assertEquals(6, rsp[5] & 0xFF, "LINKTEST_RSP expected");
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
}
