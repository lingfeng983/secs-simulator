package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.secssim.runtime.sml.SmlRenderer;

/**
 * SML 渲染：报文面板给人看，必须像 SML 而不是 JSON。
 */
class SmlRendererTest {

    private static final Secs2Builder B = Secs2.getBuilder();

    @Test
    void rendersListWithScalars() {
        Secs2 value = B.list(B.uint4(1001L), B.ascii("CH1"));

        assertEquals("<L[2] <U4 1001> <A \"CH1\">>", SmlRenderer.render(value));
    }

    @Test
    void rendersEmptyList() {
        assertEquals("<L[0]>", SmlRenderer.render(B.list()));
    }

    @Test
    void rendersNestedList() {
        Secs2 value = B.list(B.list(B.uint4(3001L), B.list(B.float4(1.5F))));

        assertEquals("<L[1] <L[2] <U4 3001> <L[1] <F4 1.5>>>>", SmlRenderer.render(value));
    }

    @Test
    void rendersBinaryAndBoolean() {
        assertEquals("<B 0x01>", SmlRenderer.render(B.binary((byte) 1)));
        assertEquals("<BOOLEAN 0x01>", SmlRenderer.render(B.bool(true)));
        assertEquals("<BOOLEAN 0x00>", SmlRenderer.render(B.bool(false)));
    }

    @Test
    void rendersMultipleValuesInOneItem() {
        Secs2 value = B.list(B.uint4(1L, 2L, 3L));

        assertEquals("<L[1] <U4 1 2 3>>", SmlRenderer.render(value));
    }

    @Test
    void rendersNullAndNeverThrows() {
        assertEquals("<>", SmlRenderer.render(null));
        assertTrue(SmlRenderer.render(B.list(B.ascii("温度"))).contains("温度"));
    }
}
