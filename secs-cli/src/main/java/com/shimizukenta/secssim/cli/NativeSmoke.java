package com.shimizukenta.secssim.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * M0 风险验证程序：把"可能只存在于 JVM 上"的四件事塞进一个 native 可执行文件里跑一遍。
 *
 * <ol>
 *   <li>HSMS-SS 被动连接 —— 依赖 {@code AsynchronousSocketChannel}（本项目最大的 native 风险）</li>
 *   <li>{@code com.sun.net.httpserver} —— 未来的 API 层</li>
 *   <li>SSE 长连接与 flush —— 未来的实时值推送</li>
 *   <li>中文目录 / 中文文件名读写 —— 构建机区域是 zh_CN，native 会把默认编码固化进镜像</li>
 * </ol>
 *
 * <p>启动后会向 stdout 打印一行 {@code READY hsms=… http=…}，供外部脚本与集成测试握手。
 * </p>
 */
public final class NativeSmoke {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 180L;

    private final CountDownLatch stop = new CountDownLatch(1);
    private SecsCommunicator communicator;

    /**
     * 创建实例。
     */
    public NativeSmoke() {
        /* nothing */
    }

    /**
     * 程序入口。
     *
     * @param args {@code --hsms-port} 与 {@code --work-dir}
     * @throws Exception 任意失败
     */
    public static void main(String[] args) throws Exception {
        int code = 0;
        try {
            new NativeSmoke().run(args);
        } catch (Exception e) {
            e.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    private void run(String[] args) throws Exception {
        int hsmsPort = intArg(args, "--hsms-port", 5000);
        Path workDir = Path.of(stringArg(args, "--work-dir", System.getProperty("java.io.tmpdir")));

        boolean utf8 = utf8RoundTrip(workDir);

        this.communicator = openPassive(hsmsPort);
        HttpServer http = startHttp(hsmsPort, utf8);
        int httpPort = http.getAddress().getPort();

        System.out.println("READY hsms=" + hsmsPort + " http=" + httpPort
                + " utf8=" + utf8 + " vm=" + vmKind());
        System.out.flush();

        try {
            this.stop.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            shutdown(http);
        }
    }

    /**
     * 收尾：HTTP 停服、通信器关闭都要有上限，避免进程退不掉。
     *
     * <p>在 native 下如果依赖"main 返回即进程退出"是不可靠的，
     * 因此最后显式 {@link System#exit(int)}。这里同时记录关闭耗时，
     * 让"关不掉"变成一条可诊断的日志而不是一个挂住的进程。
     * </p>
     */
    private void shutdown(HttpServer http) {
        http.stop(0);

        SecsCommunicator comm = this.communicator;
        if (comm == null) {
            return;
        }

        Thread closer = Thread.ofPlatform().name("comm-close").daemon(true).unstarted(() -> {
            try {
                comm.close();
            } catch (IOException e) {
                System.err.println("communicator.close() failed: " + e);
            }
        });
        closer.start();
        try {
            closer.join(3_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (closer.isAlive()) {
            System.err.println("communicator.close() did not finish within 3s");
        }
    }

    private SecsCommunicator openPassive(int port) throws IOException {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(HsmsSsProtocol.PASSIVE);
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.deviceId(10);
        config.isEquip(true);

        SecsCommunicator comm = HsmsSsCommunicator.open(config);
        comm.addSecsMessageReceiveListener(this::onPrimary);
        return comm;
    }

    private void onPrimary(SecsMessage primary) {
        try {
            if (primary.getStream() == 1 && primary.getFunction() == 1 && primary.wbit()) {
                this.communicator.send(primary, 1, 2, false, Secs2.getBuilder().list());
            }
        } catch (Exception e) {
            System.err.println("S1F2 reply failed: " + e);
        }
    }

    private HttpServer startHttp(int hsmsPort, boolean utf8) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        http.createContext("/health", ex -> respond(ex, 200,
                "{\"ok\":true,\"hsms\":" + hsmsPort
                        + ",\"utf8\":" + utf8
                        + ",\"vm\":\"" + vmKind() + "\"}"));

        http.createContext("/sse", NativeSmoke::streamTicks);

        http.createContext("/shutdown", ex -> {
            respond(ex, 200, "{\"ok\":true}");
            this.stop.countDown();
        });

        http.setExecutor(Executors.newFixedThreadPool(2));
        http.start();
        return http;
    }

    private static void streamTicks(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            for (int i = 0; i < 5; i++) {
                out.write(("event: tick\ndata: " + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    Thread.sleep(30L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static boolean utf8RoundTrip(Path root) {
        try {
            Path dir = Files.createDirectories(root.resolve("腔体配置"));
            Path file = dir.resolve("模板-温度.json");
            String content = "{\"单元\":\"CH1\",\"名称\":\"温度设置值\",\"值\":180.5}";
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return content.equals(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("utf8 round trip failed: " + e);
            return false;
        }
    }

    private static String vmKind() {
        return System.getProperty("org.graalvm.nativeimage.imagecode", "jvm");
    }

    private static int intArg(String[] args, String name, int fallback) {
        String value = stringArg(args, name, null);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static String stringArg(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
