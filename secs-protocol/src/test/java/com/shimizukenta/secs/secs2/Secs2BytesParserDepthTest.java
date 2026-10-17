package com.shimizukenta.secs.secs2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Secs2BytesParser}: the nesting-depth limit and the
 * size validation of {@code BytesPack#get(int)}.
 */
class Secs2BytesParserDepthTest {

    private static byte[] bytesOf(Secs2 secs2) throws Secs2BuildException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<byte[]> bss = Secs2BytesPackBuilder.build(1024, secs2).getBytes();
        for (byte[] bs : bss) {
            out.write(bs);
        }
        return out.toByteArray();
    }

    private static Secs2 nestedList(int depth) {
        Secs2 s = Secs2.uint1(1);
        for (int i = 0; i < depth; ++i) {
            s = Secs2.list(s);
        }
        return s;
    }

    @Test
    @DisplayName("list nesting at the maximum depth (64) parses")
    void parsesNestingAtMaximumDepth() throws Secs2BuildException, Secs2BytesParseException, Secs2Exception, IOException {
        Secs2 ss = Secs2BytesParser.getInstance().parse(List.of(bytesOf(nestedList(64))));

        Secs2 cur = ss;
        for (int i = 0; i < 64; ++i) {
            cur = cur.get(0);
        }
        assertEquals(1L, cur.getLong(0), "innermost item must survive the round trip");
    }

    @Test
    @DisplayName("list nesting beyond the maximum depth throws Secs2BytesParseException, not StackOverflowError")
    void rejectsNestingBeyondMaximumDepth() throws Secs2BuildException, IOException {
        Secs2BytesParseException e = assertThrows(Secs2BytesParseException.class,
                () -> Secs2BytesParser.getInstance().parse(List.of(bytesOf(nestedList(65)))));

        assertTrue(e.getMessage().contains("maximum nesting depth"), e.getMessage());
    }

    @Test
    @DisplayName("very deep nesting is refused with Secs2BytesParseException (would StackOverflowError without the limit)")
    void veryDeepNestingDoesNotOverflowTheStack() throws Secs2BuildException, IOException {
        assertThrows(Secs2BytesParseException.class,
                () -> Secs2BytesParser.getInstance().parse(List.of(bytesOf(nestedList(5000)))));
    }

    @Test
    @DisplayName("item size larger than the remaining bytes is refused")
    void refusesItemSizeLargerThanRemaining() {
        /* A[5] declares 5 bytes but only 3 follow */
        byte[] bs = new byte[] { (byte)0x41, (byte)0x05, (byte)'a', (byte)'b', (byte)'c' };

        Secs2BytesParseException e = assertThrows(Secs2BytesParseException.class,
                () -> Secs2BytesParser.getInstance().parse(List.of(bs)));

        assertTrue(e.getMessage().contains("not enough bytes"), e.getMessage());
    }

    @Test
    @DisplayName("a huge declared item size is refused before allocating")
    void refusesHugeItemSizeBeforeAllocating() {
        /* A[0xFFFFFF] but only 3 bytes follow: the size must be refused up-front */
        byte[] bs = new byte[] { (byte)0x43, (byte)0xFF, (byte)0xFF, (byte)0xFF,
                (byte)'a', (byte)'b', (byte)'c' };

        Secs2BytesParseException e = assertThrows(Secs2BytesParseException.class,
                () -> Secs2BytesParser.getInstance().parse(List.of(bs)));

        assertTrue(e.getMessage().contains("not enough bytes"), e.getMessage());
    }

    @Test
    @DisplayName("trailing bytes are still detected")
    void detectsTrailingBytes() throws Secs2BuildException, Secs2BytesParseException, IOException {
        byte[] bs = bytesOf(Secs2.uint1(1));
        byte[] withTrailer = new byte[bs.length + 1];
        System.arraycopy(bs, 0, withTrailer, 0, bs.length);

        assertThrows(Secs2BytesParseException.class,
                () -> Secs2BytesParser.getInstance().parse(List.of(withTrailer)));
    }

    @Test
    @DisplayName("empty body parses to Secs2RawBytes")
    void emptyBodyParsesToRawBytes() throws Secs2BytesParseException {
        assertTrue(Secs2BytesParser.getInstance().parse(List.of()).isEmpty());
    }
}
