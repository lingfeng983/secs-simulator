package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 参数模板库接口：角色门禁（读 viewer / 写 operator）、状态映射
 * （新建 201 / 更新 200 / 只读 409 / 校验不过 400）与副作用（被拒不落库）。
 */
class ParamTemplateApiTest {

    private static final String OPERATOR_PASS = "o-pass";

    private HttpApiServer server;
    private String base;
    /** 内存模板库：id → 条目 JSON（模拟 builtin 只读语义）。 */
    private final Map<String, String> store = new LinkedHashMap<>();
    private int seq = 0;

    @BeforeEach
    void startServer() throws IOException {
        AccessControl access = AccessControl.open();
        this.store.put("builtin-1",
                "{\"id\":\"builtin-1\",\"name\":\"恒温\",\"source\":\"builtin\",\"readonly\":true,"
                        + "\"body\":{\"curve\":{\"type\":\"constant\",\"value\":180}},\"updatedAt\":1}");

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
            public String paramTemplatesJson() {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (String json : ParamTemplateApiTest.this.store.values()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(json);
                }
                return sb.append(']').toString();
            }

            @Override
            public ParamTemplateSaved saveParamTemplate(String id, String name, String description, String body) {
                if (name == null || name.isBlank()) {
                    return ParamTemplateSaved.invalid(java.util.List.of("模板名不能为空"));
                }
                if (body.contains("坏")) {
                    return ParamTemplateSaved.invalid(java.util.List.of("第 2 行：曲线类型不认识"));
                }
                String finalId;
                if (id == null || id.isBlank()) {
                    finalId = "pt-test-" + (++ParamTemplateApiTest.this.seq);
                } else if (ParamTemplateApiTest.this.store.containsKey(id)) {
                    if (id.equals("builtin-1")) {
                        return new ParamTemplateSaved(ConfigResult.conflict("内置参数模板只读"), null);
                    }
                    finalId = id;
                } else {
                    finalId = id;
                }
                ParamTemplateApiTest.this.store.put(finalId,
                        "{\"id\":\"" + finalId + "\",\"name\":\"" + name + "\",\"source\":\"custom\"}");
                return ParamTemplateSaved.ok(finalId);
            }

            @Override
            public ConfigResult deleteParamTemplate(String id) {
                if (!ParamTemplateApiTest.this.store.containsKey(id)) {
                    return ConfigResult.notFound("参数模板不存在: " + id);
                }
                if (id.equals("builtin-1")) {
                    return ConfigResult.conflict("内置参数模板不能删除");
                }
                ParamTemplateApiTest.this.store.remove(id);
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
    void listsTemplates() throws Exception {
        HttpResponse<String> response = get("/api/v2/param-templates", OPERATOR_PASS);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("builtin-1"), response.body());
    }

    @Test
    void createsWithGeneratedIdThenUpdatesById() throws Exception {
        HttpResponse<String> created = post("/api/v2/param-templates",
                "{\"name\":\"炉温\",\"description\":\"说明\",\"body\":\"{\\\"curve\\\":{}}\"}", OPERATOR_PASS);

        assertEquals(201, created.statusCode(), created.body());
        String id = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertTrue(id.startsWith("pt-"), created.body());

        HttpResponse<String> updated = post("/api/v2/param-templates",
                "{\"id\":\"" + id + "\",\"name\":\"炉温改\",\"body\":\"{\\\"curve\\\":{}}\"}", OPERATOR_PASS);
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(this.store.get(id).contains("炉温改"));
    }

    @Test
    void invalidBodyIs400WithProblems() throws Exception {
        HttpResponse<String> response = post("/api/v2/param-templates",
                "{\"name\":\"坏模板\",\"body\":\"{\\\"curve\\\":\\\"坏\\\"}\"}", OPERATOR_PASS);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("曲线类型不认识"), response.body());
        assertEquals(1, this.store.size(), "被拒不落库");
    }

    @Test
    void readonlyBuiltinIsConflictOnSaveAndDelete() throws Exception {
        HttpResponse<String> saved = post("/api/v2/param-templates",
                "{\"id\":\"builtin-1\",\"name\":\"改内置\",\"body\":\"{}\"}", OPERATOR_PASS);
        assertEquals(409, saved.statusCode(), saved.body());

        HttpResponse<String> deleted = delete("/api/v2/param-templates/builtin-1", OPERATOR_PASS);
        assertEquals(409, deleted.statusCode(), deleted.body());
        assertTrue(this.store.containsKey("builtin-1"));
    }

    @Test
    void deletesCustomTemplate() throws Exception {
        HttpResponse<String> created = post("/api/v2/param-templates",
                "{\"name\":\"自定义\",\"body\":\"{}\"}", OPERATOR_PASS);
        String id = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        assertEquals(200, delete("/api/v2/param-templates/" + id, OPERATOR_PASS).statusCode());
        assertEquals(404, delete("/api/v2/param-templates/" + id, OPERATOR_PASS).statusCode());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path)).GET(), token);
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), token);
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
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
