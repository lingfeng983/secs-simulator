package com.shimizukenta.secssim.runtime.sml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Item;

/** 手写 SML 的反向解析：只覆盖手动发送需要的受控子集。 */
class SmlParserTest {

    @Test
    void parsesHeaderOnlyAndWBit() throws Exception {
        SmlParser.Message message = SmlParser.parse("S1F1 W.");

        assertEquals(1, message.stream());
        assertEquals(1, message.function());
        assertTrue(message.wbit());
        assertEquals(Secs2Item.UNDEFINED, message.body().secs2Item(), "header-only 报文使用空原始数据");

        SmlParser.Message withoutW = SmlParser.parse("S2F17");
        assertFalse(withoutW.wbit());
        assertEquals(2, withoutW.stream());
        assertEquals(17, withoutW.function());

        assertEquals(1, SmlParser.parse("\uFEFFS1F1 W.").stream(), "应容忍 UTF-8 BOM");
    }

    @Test
    void parsesNestedListBinaryAndAscii() throws Exception {
        SmlParser.Message message = SmlParser.parse("""
                S10F1 W
                <L
                  <B 0x0>
                  <A "Terminal Request">
                >.
                """);

        assertEquals(2, message.body().size());
        assertEquals(0, message.body().get(0).getByte(0));
        assertEquals("Terminal Request", message.body().get(1).getAscii());
    }

    @Test
    void parsesNumericArraysAndNesting() throws Exception {
        SmlParser.Message message = SmlParser.parse("""
                S2F33 W
                <L [2]
                  <U4 [1] 1000>
                  <L [2]
                    <U4 [1] 10001>
                    <U4 [1] 10002>
                  >
                >.
                """);

        Secs2 body = message.body();
        assertEquals(2, body.size());
        assertEquals(1000L, body.get(0).getLong(0));
        assertEquals(2, body.get(1).size());
        assertEquals(10002L, body.get(1).get(1).getLong(0));
    }

    @Test
    void parsesAllSupportedScalarTypes() throws Exception {
        SmlParser.Message message = SmlParser.parse("""
                S1F2
                <L
                  <I1 -1 2>
                  <I2 -300>
                  <I4 -70000>
                  <I8 -9000000000>
                  <U1 255>
                  <U2 65535>
                  <U4 4000000000>
                  <U8 0x10>
                  <F4 1.5>
                  <F8 2.5>
                  <BOOLEAN 0x01 0x00 TRUE FALSE>
                >.
                """);

        Secs2 body = message.body();
        assertEquals(-1L, body.get(0).getLong(0));
        assertEquals(2L, body.get(0).getLong(1));
        assertEquals(255L, body.get(4).getLong(0));
        assertEquals(16L, body.get(7).getLong(0));
        assertEquals(1.5, body.get(8).getFloat(0), 1e-6);
        assertEquals(2.5, body.get(9).getDouble(0), 1e-9);
        assertTrue(body.get(10).getBoolean(0));
        assertFalse(body.get(10).getBoolean(1));
        assertTrue(body.get(10).getBoolean(2));
        assertFalse(body.get(10).getBoolean(3));
    }

    @Test
    void parsesEscapedAscii() throws Exception {
        SmlParser.Message message = SmlParser.parse("S1F2\n<A \"a\\\"b\\n\">.");

        assertEquals("a\"b\n", message.body().getAscii());
    }

    @Test
    void rejectsMalformedInputWithPosition() {
        assertThrows(SmlParseException.class, () -> SmlParser.parse(""));
        assertThrows(SmlParseException.class, () -> SmlParser.parse("S1F1\n<L <A \"x\">"));
        assertThrows(SmlParseException.class, () -> SmlParser.parse("S1F1\n<U4AUTO 1>"));
        assertThrows(SmlParseException.class, () -> SmlParser.parse("S1F1\n<U4 -1>"));
        assertThrows(SmlParseException.class, () -> SmlParser.parse("S1F1\n<A \"x\"> trailing"));
        assertThrows(SmlParseException.class, () -> SmlParser.parse("S1F1\n<L [2] <U4 1>"));
        assertThrows(SmlParseException.class,
                () -> SmlParser.parse("S1F1\n<A \"" + "x".repeat(70_000) + "\">"));

        SmlParseException error = assertThrows(SmlParseException.class,
                () -> SmlParser.parse("S1F1\n<L <U4 1>"));
        assertTrue(error.getMessage().contains("位置"), error.getMessage());
    }
}
