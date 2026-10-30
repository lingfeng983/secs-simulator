// 实机全量验收主机：真实 HSMS ACTIVE 主机 + 全量 HTTP 端点。
// 用法: java -cp ".;<secs-protocol classes>" SimHost <httpPort> <hsmsPort> <deviceId>
// 退出码 = 失败项数量。
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.jsonhub.JsonHub;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class SimHost {

    static int passed = 0;
    static int failed = 0;
    static final List<String> failures = new CopyOnWriteArrayList<>();

    static void check(boolean ok, String name, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            failures.add(name + ": " + detail);
            System.out.println("FAIL  " + name + "  -> " + detail);
        }
    }

    static void section(String s) {
        System.out.println();
        System.out.println("==== " + s + " ====");
    }

    /* ================= HTTP ================= */

    static HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    static String base;

    static HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30));
        if (body != null) {
            b.header("Content-Type", "application/json; charset=utf-8");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else if ("GET".equals(method)) {
            b.GET();
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static void expect(int want, String name, HttpResponse<String> r) {
        check(r.statusCode() == want, name, "status=" + r.statusCode() + " body=" + brief(r.body()));
    }

    static String brief(String s) {
        if (s == null) {
            return "(null)";
        }
        return s.length() > 240 ? s.substring(0, 240) + "..." : s;
    }

    static String jsonField(String body, String key) {
        int i = body.indexOf("\"" + key + "\":");
        if (i < 0) {
            return null;
        }
        int s = i + key.length() + 3;
        while (s < body.length() && (body.charAt(s) == ' ' || body.charAt(s) == '"')) {
            s++;
        }
        int e = s;
        while (e < body.length() && body.charAt(e) != ',' && body.charAt(e) != '}' && body.charAt(e) != '"') {
            e++;
        }
        return body.substring(s, e);
    }

    /* ================= SECS ================= */

    static final BlockingQueue<SecsMessage> push = new LinkedBlockingQueue<>();
    static HsmsSsCommunicator host;
    static int hsmsPort;
    static int deviceId;

    static HsmsSsCommunicator connect() throws Exception {
        HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
        cfg.protocol(HsmsSsProtocol.ACTIVE);
        cfg.socketAddress(new InetSocketAddress("127.0.0.1", hsmsPort));
        cfg.deviceId(deviceId);
        cfg.timeout().t3(10F);
        cfg.timeout().t6(3F);
        cfg.timeout().t5(2F);
        cfg.timeout().t8(60F);
        HsmsSsCommunicator comm = HsmsSsCommunicator.newInstance(cfg);
        comm.addSecsMessageReceiveListener(msg -> {
            push.add(msg);
            /* 设备主动上报的 W 位报文必须及时确认，否则设备侧 ACK 队列阻塞到 T3 */
            try {
                if (msg.wbit() && msg.getStream() == 6 && msg.getFunction() == 11) {
                    comm.send(msg, 6, 12, false, Secs2.getBuilder().binary((byte) 0));
                } else if (msg.wbit() && msg.getStream() == 5 && msg.getFunction() == 1) {
                    comm.send(msg, 5, 2, false, Secs2.getBuilder().binary((byte) 0));
                } else if (msg.wbit() && msg.getStream() == 1 && msg.getFunction() == 1) {
                    comm.send(msg, 1, 2, false, Secs2.getBuilder().list(
                            Secs2.getBuilder().ascii("      "), Secs2.getBuilder().ascii("      ")));
                } else if (msg.wbit() && msg.getStream() == 6 && msg.getFunction() == 1) {
                    comm.send(msg, 6, 2, false, Secs2.getBuilder().binary((byte) 0));
                }
            } catch (Exception ignored) {
                /* 设备可能已断开：记录没有额外价值 */
            }
        });
        comm.open();
        comm.openAndWaitUntilCommunicating();
        return comm;
    }

    /** 等在 push 队列里出现指定 SxFy（期间其它报文由 listener 自动 ACK）。 */
    static SecsMessage awaitMessage(int stream, int function, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            SecsMessage m = push.poll(left, TimeUnit.MILLISECONDS);
            if (m == null) {
                return null;
            }
            if (m.getStream() == stream && m.getFunction() == function) {
                return m;
            }
        }
    }

    /* ================= raw socket（S9F7） ================= */

    static byte[] frame(int length, byte[] chunk) {
        byte[] out = new byte[4 + length];
        out[0] = (byte) (length >>> 24);
        out[1] = (byte) (length >>> 16);
        out[2] = (byte) (length >>> 8);
        out[3] = (byte) length;
        System.arraycopy(chunk, 0, out, 4, length);
        return out;
    }

    static byte[] controlHead(byte p, byte s, int systemBytes) {
        return new byte[] {
                (byte) 0xFF, (byte) 0xFF, 0, 0, p, s,
                (byte) (systemBytes >>> 24), (byte) (systemBytes >>> 16),
                (byte) (systemBytes >>> 8), (byte) systemBytes
        };
    }

    static byte[] readN(InputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) {
                throw new java.io.EOFException();
            }
            off += r;
        }
        return b;
    }

    static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(a, 0, a.length);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        int httpPort = Integer.parseInt(args[0]);
        hsmsPort = Integer.parseInt(args[1]);
        deviceId = Integer.parseInt(args[2]);
        base = "http://127.0.0.1:" + httpPort;

        section("A. HTTP 基础与进程形态");
        HttpResponse<String> health = call("GET", "/api/v1/health", null);
        expect(200, "health 200", health);
        check(health.body().contains("\"vm\":\"runtime\""), "vm=runtime（原生镜像）", brief(health.body()));
        check(health.body().contains("\"sessions\":2"), "sessions=2", brief(health.body()));
        check(health.body().contains("\"utf8\":true"), "utf8 自检通过", brief(health.body()));

        HttpResponse<String> sessions = call("GET", "/api/v1/sessions", null);
        expect(200, "sessions 200", sessions);
        check(sessions.body().contains("etch-01") && sessions.body().contains("cvd-01"),
                "两台演示设备", brief(sessions.body()));

        section("B. HSMS 会话建立");
        long t0 = System.currentTimeMillis();
        host = connect();
        check(true, "SELECT 完成", (System.currentTimeMillis() - t0) + "ms");
        check(host.linktest(), "linktest 应答", "");

        SecsMessage f2 = host.gem().s1f1().orElseThrow(() -> new IllegalStateException("S1F2 缺失"));
        check(f2.getStream() == 1 && f2.getFunction() == 2, "S1F1 → S1F2", f2.secs2().toJson());

        SecsMessage f14 = host.send(1, 13, true, Secs2.empty()).orElseThrow();
        check(f14.getFunction() == 14, "S1F13 → S1F14", f14.secs2().toJson());
        SecsMessage f18 = host.send(1, 17, true, Secs2.empty()).orElseThrow();
        check(f18.getFunction() == 18 && f18.secs2().getByte(0) == 0,
                "S1F17 → S1F18(ONLACK=0)", f18.secs2().toJson());

        section("C. 数据采集（S1F3/F4、S1F11/F12）");
        Secs2Builder b = Secs2.getBuilder();
        SecsMessage s1f4 = host.send(1, 3, true, b.list(
                b.uint4(1001), b.uint4(1002), b.uint4(1090), b.uint4(1101), b.uint4(9001), b.uint4(7777)))
                .orElseThrow();
        Secs2 vals = s1f4.secs2();
        check(vals.size() == 6, "S1F4 六项同序", "size=" + vals.size());
        double pv = vals.get(0).getDouble(0);
        double sv = vals.get(1).getDouble(0);
        int unit = vals.get(2).getInt(0);
        double pv2 = vals.get(3).getDouble(0);
        double fac = vals.get(4).getDouble(0);
        int unknown = vals.get(5).getInt(0);
        check(pv >= 0 && pv <= 400 && Double.isFinite(pv), "1001 TEMP_PV 在量程内", String.valueOf(pv));
        check(Math.abs(sv - 200) < 0.01, "1002 TEMP_SV 恒 200", String.valueOf(sv));
        check(unit == 1, "1090 UNIT_ID=1（多腔注入）", String.valueOf(unit));
        check(pv2 >= 0 && pv2 <= 400 && Double.isFinite(pv2), "1101 CH2 TEMP_PV", String.valueOf(pv2));
        check(fac >= 15 && fac <= 30, "9001 全局 FACILITY_TEMP 在配置范围内", String.valueOf(fac));
        check(unknown == 0, "未知 SVID → U4 0", String.valueOf(unknown));

        Thread.sleep(1200);
        double fac2 = host.send(1, 3, true, b.list(b.uint4(9001))).orElseThrow().secs2().get(0).getDouble(0);
        check(Math.abs(fac2 - fac) > 1e-9, "曲线随时间演化", fac + " -> " + fac2);

        Secs2 rows = host.send(1, 11, true, b.list()).orElseThrow().secs2();
        check(rows.size() >= 12, "S1F11 空列表枚举全部", "rows=" + rows.size());
        check(!rows.get(0).get(1).getAscii().isBlank(), "S1F12 名字非空", rows.get(0).get(1).getAscii());

        section("D. 控制类（S2F17/S2F31）");
        SecsMessage f18b = host.send(2, 17, true, Secs2.empty()).orElseThrow();
        check(f18b.getFunction() == 18, "S2F17 → S2F18", f18b.secs2().toJson());
        SecsMessage ok32 = host.send(2, 31, true, b.ascii("2026103014000000")).orElseThrow();
        check(ok32.secs2().getByte(0) == 0, "S2F31 合法时间 → TIACK=0", ok32.secs2().toJson());
        SecsMessage bad32 = host.send(2, 31, true, b.ascii("not-a-time")).orElseThrow();
        check(bad32.secs2().getByte(0) == 1, "S2F31 非法时间 → TIACK=1", bad32.secs2().toJson());

        section("E. 动态报告 + S6F11");
        SecsMessage dr = host.send(2, 33, true, b.list(b.uint4(0),
                b.list(b.list(b.uint4(5001), b.list(b.uint4(1001), b.uint4(1090)))))).orElseThrow();
        check(dr.secs2().getByte(0) == 0, "S2F33 → DRACK=0", dr.secs2().toJson());
        SecsMessage lr = host.send(2, 35, true, b.list(b.uint4(0),
                b.list(b.list(b.uint4(2003), b.list(b.uint4(5001)))))).orElseThrow();
        check(lr.secs2().getByte(0) == 0, "S2F35 → LRACK=0", lr.secs2().toJson());
        SecsMessage er = host.send(2, 37, true, b.list(b.bool(true), b.list(b.uint4(2003)))).orElseThrow();
        check(er.secs2().getByte(0) == 0, "S2F37 → ERACK=0", er.secs2().toJson());

        push.clear();
        expect(202, "HTTP 触发 CEID 2003", call("POST", "/api/v1/sessions/etch-01/ceid/2003", null));
        SecsMessage s6f11 = awaitMessage(6, 11, 8000);
        check(s6f11 != null, "设备主动上报 S6F11", s6f11 == null ? "(超时)" : "ok");
        if (s6f11 != null) {
            check(s6f11.secs2().get(1).getInt(0) == 2003, "S6F11 CEID=2003", s6f11.secs2().toJson());
            check(s6f11.secs2().size() >= 3, "S6F11 含 DATAID/CEID/报告列表", s6f11.secs2().toJson());
        }

        section("F. 不支持报文 → S9F3/S9F5（E5）");
        push.clear();
        host.send(3, 1, false, Secs2.empty());
        SecsMessage s9f3 = awaitMessage(9, 3, 5000);
        check(s9f3 != null, "S3F1 → S9F3（流不支持）", s9f3 == null ? "(超时)" : s9f3.secs2().toJson());
        push.clear();
        host.send(1, 51, false, Secs2.empty());
        SecsMessage s9f5 = awaitMessage(9, 5, 5000);
        check(s9f5 != null, "S1F51 → S9F5（功能不支持）", s9f5 == null ? "(超时)" : s9f5.secs2().toJson());

        section("G. 报警 S5F1（HTTP 置位 → 设备推送）");
        push.clear();
        expect(202, "HTTP 置位报警", call("POST",
                "/api/v1/sessions/etch-01/alarm/1?set=true&text=SIM%20HIGH&category=4", null));
        SecsMessage s5f1 = awaitMessage(5, 1, 8000);
        check(s5f1 != null, "收到 S5F1", s5f1 == null ? "(超时)" : s5f1.secs2().toJson());
        if (s5f1 != null) {
            check(s5f1.secs2().get(1).getInt(0) == 1, "S5F1 ALID=1", s5f1.secs2().toJson());
            check("SIM HIGH".equals(s5f1.secs2().get(2).getAscii()), "S5F1 ALTX 正确",
                    s5f1.secs2().get(2).getAscii());
        }
        call("POST", "/api/v1/sessions/etch-01/alarm/1?set=false", null);

        section("H. S9F7 非法数据（raw socket 连 cvd-01）");
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress("127.0.0.1", hsmsPort + 1), 3000);
            raw.setSoTimeout(8000);
            OutputStream out = raw.getOutputStream();
            DataInputStream in = new DataInputStream(raw.getInputStream());

            out.write(frame(10, controlHead((byte) 0, (byte) 1, 900001)));
            out.flush();
            in.readInt();
            byte[] selH = readN(in, 10);
            check(selH[5] == 2, "cvd raw SELECT.rsp", "sType=" + (selH[5] & 0xFF));

            /* S1F3 W + 坏 body（F4 声明 2 字节） */
            byte[] head = new byte[] { 0, (byte) 11, (byte) 0x81, 3, 0, 0,
                    (byte) 0x00, (byte) 0x0D, (byte) 0xBB, (byte) 0xE1 };
            byte[] badBody = new byte[] { 0x2E, 1, 2 };
            out.write(frame(10 + badBody.length, concat(head, badBody)));
            out.flush();
            int len = in.readInt();
            byte[] rspH = readN(in, 10);
            byte[] rspB = readN(in, len - 10);
            check(rspH[2] == 9 && rspH[3] == 7, "坏 S1F3 → S9F7",
                    "S" + (rspH[2] & 0xFF) + "F" + (rspH[3] & 0xFF));
            byte[] echo = new byte[10];
            System.arraycopy(rspB, rspB.length - 10, echo, 0, 10);
            boolean idnOk = true;
            for (int i = 0; i < 10; i++) {
                if (echo[i] != head[i]) {
                    idnOk = false;
                }
            }
            check(idnOk, "S9F7 携带 IDN（10 字节头回声）", java.util.Arrays.toString(echo));

            /* 同连接仍可用：header-only S1F1 W */
            byte[] okHead = new byte[] { 0, (byte) 11, (byte) 0x81, 1, 0, 0,
                    (byte) 0x00, (byte) 0x0D, (byte) 0xBB, (byte) 0xE2 };
            out.write(frame(10, okHead));
            out.flush();
            int len2 = in.readInt();
            byte[] rsp2H = readN(in, 10);
            readN(in, len2 - 10);
            check(rsp2H[2] == 1 && rsp2H[3] == 2, "S9F7 后连接仍可用（S1F1 → S1F2）",
                    "S" + (rsp2H[2] & 0xFF) + "F" + (rsp2H[3] & 0xFF));
        }

        section("I. 全量 HTTP 端点");
        HttpResponse<String> catalog = call("GET", "/api/v1/sessions/etch-01/catalog", null);
        expect(200, "catalog", catalog);
        check(catalog.body().contains("TEMP_PV"), "catalog 含 TEMP_PV", brief(catalog.body()));

        expect(200, "alarms", call("GET", "/api/v1/sessions/etch-01/alarms", null));
        expect(200, "terminal", call("GET", "/api/v1/sessions/etch-01/terminal", null));

        long nowMs = System.currentTimeMillis();
        HttpResponse<String> preview = call("GET", "/api/v1/preview?session=etch-01&from="
                + (nowMs - 60000) + "&to=" + nowMs + "&buckets=60&svids=1001,1002,9001", null);
        expect(200, "preview", preview);
        check(preview.body().contains("\"series\""), "preview 含 series", brief(preview.body()));

        HttpResponse<byte[]> bin = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/preview.bin?session=etch-01&from="
                        + (nowMs - 60000) + "&to=" + nowMs + "&buckets=60"))
                        .timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        check(bin.statusCode() == 200 && bin.body().length > 16
                        && bin.body()[0] == 'S' && bin.body()[1] == 'C' && bin.body()[2] == 'S' && bin.body()[3] == 'P',
                "preview.bin SCSP 魔数", "len=" + bin.body().length + " status=" + bin.statusCode());

        HttpResponse<String> overview = call("GET", "/api/v1/overview", null);
        expect(200, "overview", overview);
        check(overview.body().contains("etch-01"), "overview 含 etch-01", brief(overview.body()));

        HttpResponse<String> msgs = call("GET", "/api/v1/messages?limit=100", null);
        expect(200, "messages", msgs);
        check(msgs.body().contains("\"stream\":6") && msgs.body().contains("\"function\":11"),
                "报文历史含本次 S6F11", brief(msgs.body()));
        check(msgs.body().contains("\"seq\""), "报文历史带 seq", brief(msgs.body()));

        HttpResponse<String> ticket = call("POST", "/api/v1/ticket", null);
        expect(200, "ticket", ticket);
        final String tk = jsonField(ticket.body(), "ticket");
        check(tk != null && tk.length() >= 8, "ticket 非空", String.valueOf(tk));

        CountDownLatch sseDone = new CountDownLatch(1);
        final StringBuilder sseBuf = new StringBuilder();
        Thread sseThread = new Thread(() -> {
            try {
                HttpResponse<InputStream> sse = http.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/v1/events?ticket=" + tk))
                                .timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (sse.statusCode() != 200) {
                    return;
                }
                InputStream is = sse.body();
                byte[] buf = new byte[8192];
                long end = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < end) {
                    int n = is.read(buf);
                    if (n <= 0) {
                        break;
                    }
                    synchronized (sseBuf) {
                        sseBuf.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (sseBuf.indexOf("event: message") >= 0) {
                            sseDone.countDown();
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                /* 关流/超时都走这里 */
            }
        });
        sseThread.setDaemon(true);
        sseThread.start();
        Thread.sleep(400);
        call("POST", "/api/v1/sessions/etch-01/ceid/2003", null);
        boolean gotSse = sseDone.await(12, TimeUnit.SECONDS);
        String sseText;
        synchronized (sseBuf) {
            sseText = sseBuf.toString();
        }
        check(gotSse && sseText.contains("event: message"), "SSE 收到 message 事件",
                gotSse ? brief(sseText) : "(超时)");

        section("J. 配置与模板");
        HttpResponse<String> configs = call("GET", "/api/v1/configs", null);
        expect(200, "configs 列表", configs);
        check(configs.body().contains("demo-etch"), "含 demo-etch", brief(configs.body()));

        HttpResponse<String> cfgGet = call("GET", "/api/v1/configs/demo-etch.json", null);
        expect(200, "configs 读取", cfgGet);
        String cfgText = JsonHub.fromJson(cfgGet.body()).get("text").optionalString().orElse("");
        check(cfgText.contains("\"schemaVersion\""), "configs 返回原文（含 schemaVersion）", brief(cfgText));

        HttpResponse<String> valOk = call("POST", "/api/v1/configs/demo-copy.json/validate", cfgText);
        check(valOk.statusCode() == 200 && valOk.body().contains("\"ok\":true"),
                "validate 通过", brief(valOk.body()));
        expect(200, "configs 新建 demo-copy", call("PUT", "/api/v1/configs/demo-copy.json", cfgText));

        String badText = cfgText.replace("\"schemaVersion\": 6", "\"schemaVersion\": 99");
        HttpResponse<String> valBad = call("POST", "/api/v1/configs/demo-copy.json/validate", badText);
        check(valBad.statusCode() == 200 && valBad.body().contains("\"ok\":false")
                        && valBad.body().contains("schemaVersion"),
                "非法 schemaVersion → 校验问题清单", "status=" + valBad.statusCode() + " " + brief(valBad.body()));
        /* DELETE /configs/{name} 本来就未提供（配置只能新建/保存/校验） */
        HttpResponse<String> cfgDel = call("DELETE", "/api/v1/configs/demo-copy.json", null);
        check(cfgDel.statusCode() == 405, "DELETE config → 405（无此能力）", "status=" + cfgDel.statusCode());

        expect(200, "config-templates", call("GET", "/api/v1/config-templates", null));
        expect(200, "templates 列表", call("GET", "/api/v1/templates", null));

        HttpResponse<String> cp = call("POST", "/api/v1/config-preview?from=" + (nowMs - 60000)
                + "&to=" + nowMs + "&buckets=30", cfgText);
        check(cp.statusCode() == 200 && cp.body().contains("series"),
                "config-preview（草稿编译）", brief(cp.body()));

        expect(200, "log-level PUT", call("PUT", "/api/v1/log-level", "debug"));
        expect(200, "log-level 恢复", call("PUT", "/api/v1/log-level", "info"));
        HttpResponse<String> logBad = call("PUT", "/api/v1/log-level", "nope");
        check(logBad.statusCode() == 400, "非法 log-level → 400", "status=" + logBad.statusCode());

        section("K. 运行时编辑");
        String plan = "{\"id\":\"pl-host\",\"ceid\":2003,"
                + "\"trigger\":{\"type\":\"periodic\",\"periodMs\":600000},\"effects\":[]}";
        expect(201, "新增编排", call("POST", "/api/v1/sessions/etch-01/events", plan));
        expect(200, "禁用编排", call("POST", "/api/v1/sessions/etch-01/events/pl-host?enabled=false", null));
        expect(200, "删除编排", call("DELETE", "/api/v1/sessions/etch-01/events/pl-host", null));

        String report = "{\"id\":6001,\"slot\":\"HOSTTEST\",\"svids\":[1001,1002]}";
        expect(201, "新增报告", call("POST", "/api/v1/sessions/etch-01/reports", report));
        expect(200, "删除报告", call("DELETE", "/api/v1/sessions/etch-01/reports/6001", null));

        String svidDef = "{\"id\":6101,\"slot\":\"HOST_VAR\",\"type\":\"F4\",\"unit\":\"X\",\"decimals\":1}";
        expect(201, "新增 SVID", call("POST", "/api/v1/sessions/etch-01/svids", svidDef));
        expect(200, "钉值 42.5", call("PUT", "/api/v1/sessions/etch-01/svids/6101/value", "{\"value\":42.5}"));
        double pinV = host.send(1, 3, true, b.list(b.uint4(6101))).orElseThrow().secs2().get(0).getDouble(0);
        check(Math.abs(pinV - 42.5) < 0.01, "S1F3 读到钉值", String.valueOf(pinV));
        expect(200, "解钉", call("DELETE", "/api/v1/sessions/etch-01/svids/6101/value", null));
        expect(200, "删除 SVID", call("DELETE", "/api/v1/sessions/etch-01/svids/6101", null));

        section("L. 手动发送 SML");
        HttpResponse<String> sent0 = call("POST", "/api/v1/sessions/etch-01/message",
                "S5F1 <L <B 0x84> <U4 9> <A \"MANUAL\">>.");
        expect(200, "手动发送 S5F1（设备→主机）", sent0);
        SecsMessage manual = awaitMessage(5, 1, 5000);
        check(manual != null, "主机收到手动发送的 S5F1", manual == null ? "(超时)" : manual.secs2().toJson());

        section("M. 并发与压测");
        List<Socket> half = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Socket sk = new Socket();
            sk.connect(new InetSocketAddress("127.0.0.1", httpPort), 2000);
            OutputStream so = sk.getOutputStream();
            so.write(("POST /api/v1/sessions HTTP/1.1\r\nHost: x\r\nContent-Type: application/json"
                    + "\r\nContent-Length: 5000\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
            so.flush();
            half.add(sk);
        }
        long h0 = System.currentTimeMillis();
        HttpResponse<String> fastHealth = call("GET", "/api/v1/health", null);
        long fastMs = System.currentTimeMillis() - h0;
        check(fastHealth.statusCode() == 200 && fastMs < 1000,
                "12 条半截正文下 health <1s（虚拟线程）", "ms=" + fastMs);
        for (Socket sk : half) {
            try {
                sk.close();
            } catch (Exception ignored) {
                /* 收尾 */
            }
        }

        int[] errors = { 0 };
        CountDownLatch done = new CountDownLatch(40);
        for (int i = 0; i < 40; i++) {
            new Thread(() -> {
                try {
                    if (call("GET", "/api/v1/sessions", null).statusCode() != 200) {
                        synchronized (errors) {
                            errors[0]++;
                        }
                    }
                } catch (Exception e) {
                    synchronized (errors) {
                        errors[0]++;
                    }
                } finally {
                    done.countDown();
                }
            }).start();
        }
        done.await(25, TimeUnit.SECONDS);
        check(errors[0] == 0, "40 并发 sessions 全 200", "errors=" + errors[0]);

        HttpResponse<String> burst = call("POST", "/api/v1/sessions/etch-01/burst",
                "{\"kind\":\"ceid\",\"ceid\":2003,\"count\":60,\"intervalMs\":20}");
        check(burst.statusCode() == 202, "burst 受理 202", "status=" + burst.statusCode() + " " + brief(burst.body()));
        HttpResponse<String> burstBusy = call("POST", "/api/v1/sessions/etch-01/burst",
                "{\"kind\":\"ceid\",\"ceid\":2003,\"count\":10}");
        check(burstBusy.statusCode() == 409, "burst 重复 → 409", "status=" + burstBusy.statusCode());
        String stats = null;
        for (int i = 0; i < 100; i++) {
            Thread.sleep(200);
            HttpResponse<String> bget = call("GET", "/api/v1/sessions/etch-01/burst", null);
            if (bget.body().contains("\"running\":false") && bget.body().contains("\"sent\"")) {
                stats = bget.body();
                break;
            }
        }
        check(stats != null && stats.contains("\"sent\":"), "burst 完成并含 sent 统计", brief(stats));

        section("N. 会话重载（真主机自动重连）");
        host.close();
        expect(200, "etch-01 重载", call("POST", "/api/v1/sessions/etch-01/reload", null));
        Thread.sleep(1500);
        host = connect();
        SecsMessage after = host.send(1, 1, true, Secs2.empty()).orElseThrow();
        check(after.getFunction() == 2, "重载后重新 SELECT 且 S1F1 可用", after.secs2().toJson());

        section("O. host-sim 实例（开 → 发 → 采集 → 关）");
        host.close();
        HttpResponse<String> hs = call("POST", "/api/v1/host-sims", "{\"session\":\"etch-01\"}");
        check(hs.statusCode() == 200 || hs.statusCode() == 201, "host-sim 打开",
                "status=" + hs.statusCode() + " " + brief(hs.body()));
        HttpResponse<String> hsList = call("GET", "/api/v1/host-sims", null);
        expect(200, "host-sims 列表", hsList);
        String hostId = null;
        int idAt = hsList.body().indexOf("\"id\":\"");
        if (idAt >= 0) {
            int s = idAt + 6;
            int e = hsList.body().indexOf('"', s);
            hostId = hsList.body().substring(s, e);
        }
        check(hostId != null && !hostId.isEmpty(), "实例 id 可解析", String.valueOf(hostId));

        if (hostId != null) {
            HttpResponse<String> hsSend = call("POST", "/api/v1/host-sims/" + hostId + "/send", "S1F1 W.");
            check(hsSend.statusCode() == 200 && hsSend.body().contains("replySml"),
                    "host-sim 发 S1F1 W 并拿到应答", brief(hsSend.body()));

            HttpResponse<String> colCfg = call("POST", "/api/v1/host-sims/" + hostId + "/collector",
                    "{\"startCeid\":2001,\"stopCeid\":2002,\"intervalMs\":500,"
                            + "\"svids\":[{\"id\":1001,\"name\":\"T\",\"unit\":\"C\"},"
                            + "{\"id\":1090,\"name\":\"U\",\"unit\":\"\"}]}");
            check(colCfg.statusCode() == 200, "采集器配置下发", brief(colCfg.body()));
            HttpResponse<String> colStart = call("POST", "/api/v1/host-sims/" + hostId + "/collector/start", null);
            check(colStart.statusCode() == 200, "采集器启动", brief(colStart.body()));
            Thread.sleep(2600);
            HttpResponse<String> colState = call("GET", "/api/v1/host-sims/" + hostId + "/collector", null);
            String samples = jsonField(colState.body(), "samples");
            long sampleCount = samples == null ? -1 : Long.parseLong(samples);
            check(colState.statusCode() == 200 && sampleCount >= 2,
                    "采集样本 >=2（真实 S1F3 轮询）", "samples=" + sampleCount + " " + brief(colState.body()));
            expect(200, "采集器停止",
                    call("POST", "/api/v1/host-sims/" + hostId + "/collector/stop", null));
            expect(200, "host-sim 关闭", call("DELETE", "/api/v1/host-sims/" + hostId, null));
        }

        section("P. 审计与停机");
        HttpResponse<String> audit = call("GET", "/api/v1/audit?limit=200", null);
        expect(200, "audit", audit);
        check(audit.body().contains("burst"), "审计含 burst", brief(audit.body()));
        check(audit.body().contains("log-level"), "审计含 log-level", brief(audit.body()));

        push.clear();
        HttpResponse<String> shutdown = call("POST", "/api/v1/shutdown", null);
        check(shutdown.statusCode() == 200, "shutdown 200", brief(shutdown.body()));

        System.out.println();
        System.out.println("=============================================");
        System.out.println("passed=" + passed + " failed=" + failed);
        for (String f : failures) {
            System.out.println("  FAILED: " + f);
        }
        System.exit(Math.min(failed, 125));
    }
}
