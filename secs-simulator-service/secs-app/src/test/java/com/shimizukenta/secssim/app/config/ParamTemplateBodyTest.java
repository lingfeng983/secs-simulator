package com.shimizukenta.secssim.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ParamTemplateBody：简形归一化、完整形校验、空绑定中间实例、环与未知引用。 */
class ParamTemplateBodyTest {

    private static final String SINE = "{\"type\":\"sine\",\"min\":0,\"max\":10,\"periodMs\":1000}";

    @Test
    void shorthandNormalizesToFullForm() {
        ParamTemplateBody.Body body = ParamTemplateBody.parse("{\"curve\":" + SINE + "}");

        assertEquals(1, body.templates().curves().size());
        assertEquals("main", body.templates().curves().get(0).id());
        assertEquals(1, body.templates().instances().size());
        assertTrue(body.templates().instances().get(0).svids().isEmpty(), "模板里的实例不绑 SVID");
        assertEquals("main", body.resultInstance());
        assertEquals("V", body.resultOutput());

        String normalized = ParamTemplateBody.normalize("{\"curve\":" + SINE + "}");
        assertTrue(normalized.contains("\"result\""), normalized);
        assertTrue(normalized.contains("\"curves\""), normalized);
    }

    @Test
    void shorthandHonorsCustomId() {
        ParamTemplateBody.Body body = ParamTemplateBody.parse("{\"id\":\"c1\",\"curve\":" + SINE + "}");

        assertEquals("c1", body.templates().curves().get(0).id());
        assertEquals("c1", body.resultInstance());
    }

    @Test
    void fullFormWithIntermediateInstanceAndComposite() {
        String text = """
                {
                  "curves": [{"id": "base", "type": "sine", "min": 0, "max": 10, "periodMs": 1000}],
                  "composites": [{"id": "main", "outputs": {"value": "in.ibase.V * 2"}}],
                  "instances": [{"id": "ibase", "template": "base", "svids": {}}],
                  "result": "main.value"
                }""";

        ParamTemplateBody.Body body = ParamTemplateBody.parse(text);

        assertEquals("main", body.resultInstance());
        assertEquals("value", body.resultOutput());
        assertTrue(ParamTemplateBody.normalize(text).contains("ibase"));
    }

    @Test
    void resultPointingAtTemplateIdSynthesizesInstance() {
        String text = """
                {
                  "curves": [{"id": "base", "type": "sine", "min": 0, "max": 10, "periodMs": 1000}],
                  "composites": [{"id": "main", "outputs": {"value": "in.base.V * 2"}}],
                  "result": "base.V"
                }""";

        ParamTemplateBody.Body body = ParamTemplateBody.parse(text);

        /* result 直接指向模板 id：自动补一枚同名空绑定实例 */
        assertTrue(body.templates().instances().stream()
                .anyMatch((inst) -> inst.id().equals("base") && inst.template().equals("base")));
    }

    @Test
    void rejectsSvidsInTemplateInstances() {
        String bad = """
                {
                  "curves": [{"id": "base", "type": "sine", "min": 0, "max": 10, "periodMs": 1000}],
                  "instances": [{"id": "i", "template": "base", "svids": {"V": 1001}}],
                  "result": "i.V"
                }""";

        ConfigException e = assertThrows(ConfigException.class, () -> ParamTemplateBody.parse(bad));
        assertTrue(e.getMessage().contains("不绑定 SVID"), e.getMessage());
    }

    @Test
    void rejectsCycle() {
        String text = """
                {
                  "composites": [{"id": "main", "outputs": {"value": "in.main.value + 1"}}],
                  "result": "main.value"
                }""";

        ConfigException e = assertThrows(ConfigException.class, () -> ParamTemplateBody.parse(text));
        assertTrue(e.getMessage().contains("环") || e.getMessage().contains("CYCLE"), e.getMessage());
    }

    @Test
    void rejectsUnknownResultTarget() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ParamTemplateBody.parse("{\"curve\":" + SINE + ",\"result\":\"nope.x\"}"));
        assertTrue(e.getMessage().contains("未知的实例或模板"), e.getMessage());
    }

    @Test
    void rejectsUnknownResultOutput() {
        String text = "{\"curve\":" + SINE + ",\"result\":\"main.pressure\"}";

        ConfigException e = assertThrows(ConfigException.class, () -> ParamTemplateBody.parse(text));
        assertTrue(e.getMessage().contains("输出不存在"), e.getMessage());
    }

    @Test
    void missingResultIsRejected() {
        assertThrows(ConfigException.class, () -> ParamTemplateBody.parse("{}"));
        assertThrows(ConfigException.class, () -> ParamTemplateBody.parse("{\"curves\":[]}"));
        assertThrows(ConfigException.class, () -> ParamTemplateBody.parse("不是 JSON"));
    }
}
