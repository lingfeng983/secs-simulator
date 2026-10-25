package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 配置读写接口。
 *
 * <p>要盯住三件事：<b>角色门禁</b>（读是 viewer，写是 operator）、
 * <b>被拒的请求没有副作用</b>（403 之后磁盘必须没动过），
 * 以及<b>问题清单真的带回来了</b>（只回一个 422 等于把活儿推给用户）。
 * </p>
 */
class ConfigApiTest {

    private static final String VIEWER_PASS = "g-pass";
    private static final String OPERATOR_PASS = "o-pass";

    private HttpApiServer server;
    private String base;
    private final List<String> saves = new ArrayList<>();
    private String stored = "{\"原文\":true}";
    /** 当前的"文件修改时间";If-Match 并发保护的测试桩。 */
    private volatile long currentModifiedMs = 1_700_000_000_000L;

    @BeforeEach
    void startServer() throws IOException {
        AccessControl access = AccessControl.parse("""
                {"users": [
                  {"name": "guest", "role": "viewer",   "secret": "g-pass"},
                  {"name": "line1", "role": "operator", "secret": "o-pass"}
                ]}""");

        ApiContext context = new ApiContext() {
            @Override
            public String mode() {
                return "server";
            }

            @Override
            public String version() {
                return "test";
            }

            @Override
            public String extraHealthJson() {
                return "";
            }

            @Override
            public void requestShutdown() {
                /* 本测试用不上 */
            }

            @Override
            public String configsJson() {
                return "[{\"name\":\"line1.json\",\"sizeBytes\":12,\"modifiedMs\":1700000000000}]";
            }

            @Override
            public String configText(String name) {
                return "nope.json".equals(name) ? null : ConfigApiTest.this.stored;
            }

            @Override
            public List<String> validateConfig(String text) {
                return text.contains("坏") ? List.of("第 3 行：设备名不能为空") : List.of();
            }

            @Override
            public ConfigResult saveConfig(String name, String text) {
                return this.saveConfig(name, text, null);
            }

            @Override
            public Long configModifiedMs(String name) {
                return "nope.json".equals(name) ? null : ConfigApiTest.this.currentModifiedMs;
            }

            @Override
            public ConfigResult saveConfig(String name, String text, Long expectedModifiedMs) {
                if (name.contains("/") || name.contains("\\") || name.contains("..") || name.contains(":")) {
                    return ConfigResult.notFound("只能是 --work-dir 下的文件名，不能带路径");
                }
                if (expectedModifiedMs != null && expectedModifiedMs != ConfigApiTest.this.currentModifiedMs) {
                    return ConfigResult.conflict("配置在编辑期间被其他操作修改过，请重新载入后再保存");
                }
                ConfigApiTest.this.saves.add(name + ":" + text);
                if (text.contains("坏")) {
                    return ConfigResult.invalid(List.of("第 3 行：设备名不能为空"));
                }
                ConfigApiTest.this.stored = text;
                return ConfigResult.ok();
            }

            @Override
            public ConfigResult createConfig(String name) {
                ConfigApiTest.this.saves.add("create:" + name);
                if ("exists.json".equals(name)) {
                    return ConfigResult.conflict("已经有同名的配置了");
                }
                if (!name.endsWith(".json")) {
                    return ConfigResult.notFound("必须是 .json 文件");
                }
                if (name.contains("/") || name.contains("\\") || name.contains("..") || name.contains(":")) {
                    return ConfigResult.notFound("只能是 --work-dir 下的文件名，不能带路径");
                }
                return ConfigResult.ok();
            }
        };

        this.server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), access, context);
        this.base = "http://127.0.0.1:" + this.server.port();
    }

    @AfterEach
    void stopServer() {
        this.server.close();
    }

    @Test
    void listsConfigs() throws Exception {
        HttpResponse<String> response = get("/api/v1/configs", OPERATOR_PASS);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"name\":\"line1.json\""), response.body());
        assertTrue(response.body().contains("\"sizeBytes\":12"), response.body());
    }

    @Test
    void readsRawText() throws Exception {
        this.stored = "{\"名字\":\"腔体\",\"值\":1}";

        HttpResponse<String> response = get("/api/v1/configs/line1.json", OPERATOR_PASS);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"text\":\"{\\\"名字\\\":\\\"腔体\\\",\\\"值\\\":1}\""), response.body());
    }

    @Test
    void missingConfigIsNotFound() throws Exception {
        assertEquals(404, get("/api/v1/configs/nope.json", OPERATOR_PASS).statusCode());
    }

    @Test
    void validatesWithoutTouchingTheFile() throws Exception {
        HttpResponse<String> response = post("/api/v1/configs/line1.json/validate", "{\"有\":\"坏\"}", OPERATOR_PASS);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"valid\":false"), response.body());
        assertTrue(response.body().contains("第 3 行：设备名不能为空"), response.body());
        assertTrue(this.saves.isEmpty(), "校验不该写盘: " + this.saves);
    }

    @Test
    void validConfigPassesValidation() throws Exception {
        HttpResponse<String> response = post("/api/v1/configs/line1.json/validate", "{\"好\":1}", OPERATOR_PASS);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"valid\":true"), response.body());
    }

    @Test
    void savesRawTextVerbatim() throws Exception {
        String body = "{\n  \"排版\": \"别动我\"\n}\n";

        HttpResponse<String> response = put("/api/v1/configs/line1.json", body, OPERATOR_PASS);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("line1.json:" + body), this.saves, "存回去的必须和发过来的一模一样");
    }

    /** GET 带回 modifiedMs:编辑器存盘时经 If-Match 带回,是并发保护的依据。 */
    @Test
    void readsModifiedMsForTheConcurrencyGuard() throws Exception {
        HttpResponse<String> response = get("/api/v1/configs/line1.json", OPERATOR_PASS);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"modifiedMs\":" + this.currentModifiedMs), response.body());
    }

    /** If-Match 对得上:正常保存。 */
    @Test
    void saveWithMatchingIfMatchIsAccepted() throws Exception {
        HttpResponse<String> response = put("/api/v1/configs/line1.json", "{\"好\":1}", OPERATOR_PASS,
                String.valueOf(this.currentModifiedMs));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, this.saves.size());
    }

    /** If-Match 过期(编辑期间被别人改过):409 拒绝覆盖,不产生副作用。 */
    @Test
    void saveWithStaleIfMatchIsRejected() throws Exception {
        HttpResponse<String> response = put("/api/v1/configs/line1.json", "{\"新\":1}", OPERATOR_PASS, "123");

        assertEquals(409, response.statusCode(), response.body());
        assertTrue(response.body().contains("重新载入"), response.body());
        assertTrue(this.saves.isEmpty(), "冲突的保存不得写盘: " + this.saves);
    }

    /** If-Match 不是数字:400,也不能落盘。 */
    @Test
    void saveWithMalformedIfMatchIsBadRequest() throws Exception {
        HttpResponse<String> response = put("/api/v1/configs/line1.json", "{\"新\":1}", OPERATOR_PASS, "abc");

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"bad-if-match\""), response.body());
        assertTrue(this.saves.isEmpty());
    }

    /** 不带 If-Match 的旧客户端行为不变:直接保存。 */
    @Test
    void saveWithoutIfMatchKeepsWorking() throws Exception {
        assertEquals(200, put("/api/v1/configs/line1.json", "{\"好\":1}", OPERATOR_PASS).statusCode());
    }

    @Test
    void invalidContentIsRejectedWithTheReason() throws Exception {
        HttpResponse<String> response = put("/api/v1/configs/line1.json", "{\"有\":\"坏\"}", OPERATOR_PASS);

        assertEquals(422, response.statusCode(), response.body());
        assertTrue(response.body().contains("第 3 行：设备名不能为空"), response.body());
    }

    @Test
    void viewerCanReadButNotWrite() throws Exception {
        assertEquals(200, get("/api/v1/configs", VIEWER_PASS).statusCode());
        assertEquals(200, get("/api/v1/configs/line1.json", VIEWER_PASS).statusCode());
        assertEquals(200, post("/api/v1/configs/line1.json/validate", "{}", VIEWER_PASS).statusCode());

        HttpResponse<String> denied = put("/api/v1/configs/line1.json", "{}", VIEWER_PASS);
        assertEquals(403, denied.statusCode(), denied.body());
        assertTrue(denied.body().contains("\"required\":\"OPERATOR\""), denied.body());
        assertTrue(this.saves.isEmpty(), "被拒的保存不得产生副作用: " + this.saves);
    }

    @Test
    void anonymousGetsNothing() throws Exception {
        assertEquals(401, get("/api/v1/configs", null).statusCode());
        assertEquals(401, get("/api/v1/configs/line1.json", null).statusCode());
        assertEquals(401, put("/api/v1/configs/line1.json", "{}", null).statusCode());
        assertEquals(401, post("/api/v1/configs/line1.json/validate", "{}", null).statusCode());
        assertEquals(401, post("/api/v1/configs", "{\"name\":\"x.json\"}", null).statusCode());
        assertTrue(this.saves.isEmpty());
    }

    @Test
    void traversalNamesAreRejected() throws Exception {
        assertEquals(400, get("/api/v1/configs/sub/dir.json", OPERATOR_PASS).statusCode());
        assertEquals(400, put("/api/v1/configs/..%2Fevil.json", "{}", OPERATOR_PASS).statusCode());
        assertTrue(this.saves.isEmpty(), "路径穿越不得触达保存: " + this.saves);
    }

    /** 路径形状不合法时要在进保存逻辑之前就被挡住。 */
    @Test
    void savingThroughAPathShapeIsRejected() throws Exception {
        HttpResponse<String> response = put("/api/v1/configs/..%2Fevil.json", "{}", OPERATOR_PASS);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"bad-path\""), response.body());
        assertTrue(this.saves.isEmpty(), "路径穿越不得触达保存: " + this.saves);
    }

    @Test
    void chineseNamesAreAddressable() throws Exception {
        HttpResponse<String> response = get("/api/v1/configs/" + encode("模板-温度.json"), OPERATOR_PASS);

        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void oversizedBodyIsRejected() throws Exception {
        String huge = "x".repeat(300 * 1024);

        HttpResponse<String> response = put("/api/v1/configs/line1.json", huge, OPERATOR_PASS);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(this.saves.isEmpty(), "超限的请求不该进到保存逻辑: " + this.saves);
    }

    @Test
    void wrongMethodIsNotAllowed() throws Exception {
        assertEquals(405, delete("/api/v1/configs/line1.json", OPERATOR_PASS).statusCode());
    }

    @Test
    void createsANewConfig() throws Exception {
        HttpResponse<String> response = post("/api/v1/configs", "{\"name\":\"new.json\"}", OPERATOR_PASS);

        assertEquals(201, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"name\":\"new.json\""), response.body());
        assertTrue(this.saves.contains("create:new.json"), this.saves.toString());
    }

    @Test
    void creatingAnExistingConfigConflicts() throws Exception {
        HttpResponse<String> response = post("/api/v1/configs", "{\"name\":\"exists.json\"}", OPERATOR_PASS);

        assertEquals(409, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"config-exists\""), response.body());
    }

    @Test
    void createWithoutANameIsRejected() throws Exception {
        assertEquals(400, post("/api/v1/configs", "{}", OPERATOR_PASS).statusCode());
        assertEquals(400, post("/api/v1/configs", "{ 这不是 JSON", OPERATOR_PASS).statusCode());
    }

    /** 名字非法要说清为什么，不能笼统回一句"创建失败"。 */
    @Test
    void createWithAnIllegalNameExplainsWhy() throws Exception {
        HttpResponse<String> response = post("/api/v1/configs", "{\"name\":\"../evil.json\"}", OPERATOR_PASS);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("不能带路径"), response.body());
    }

    @Test
    void savingToAnIllegalNameIsABadRequestNotAMissingFile() throws Exception {
        /* 名字里带路径分隔符：请求字段不合法，回 400 并说明原因，而不是 404 */
        HttpResponse<String> response = put("/api/v1/configs/bad%5Cname.json", "{}", OPERATOR_PASS);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("不能带路径"), response.body());
        assertTrue(this.saves.isEmpty(), "非法的名字不该进保存逻辑: " + this.saves);
    }

    @Test
    void viewerCannotCreateConfig() throws Exception {
        assertEquals(403, post("/api/v1/configs", "{\"name\":\"new.json\"}", VIEWER_PASS).statusCode());
        assertTrue(this.saves.isEmpty(), "被拒的新建不得产生副作用: " + this.saves);
    }

    private static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path)).GET(), token);
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), token);
    }

    private HttpResponse<String> put(String path, String body, String token) throws Exception {
        return put(path, body, token, null);
    }

    private HttpResponse<String> put(String path, String body, String token, String ifMatch) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.base + path))
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        return send(builder, token);
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path)).DELETE(), token);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, String token) throws Exception {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }

        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(builder.timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
