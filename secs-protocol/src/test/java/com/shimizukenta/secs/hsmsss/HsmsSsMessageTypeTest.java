package com.shimizukenta.secs.hsmsss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HsmsSsMessageType#supportSType(HsmsSsMessage)}.
 *
 * <p>
 * The message-variant must validate the S-Type byte (head[5]) against the
 * S-Type table, exactly like {@link HsmsSsMessageType#supportSType(byte)}.
 * Delegating to the P-Type table instead (the former bug) rejects valid
 * control messages such as DESELECT_REQ (S-Type 3) and accepts the
 * undefined P-Type 0x80.
 * </p>
 */
class HsmsSsMessageTypeTest {

    private static HsmsSsMessage msg(byte p, byte s) {
        return new HsmsSsMessage(new byte[] {
                0, 0, 0, 0, p, s, 0, 0, 0, 1
        });
    }

    @Test
    @DisplayName("every S-Type defined by the enum is supported")
    void validSTypesAreSupported() {
        byte[] validSTypes = { 0, 1, 2, 3, 4, 5, 6, 7, 9 };
        for (byte s : validSTypes) {
            assertTrue(HsmsSsMessageType.supportSType(msg((byte)0, s)),
                    "S-Type " + s + " must be supported");
        }
    }

    @Test
    @DisplayName("unsupported S-Types (8, 0x80, ...) are not supported")
    void unsupportedSTypesAreNotSupported() {
        byte[] invalidSTypes = { 8, 10, (byte)0x80, (byte)0xFF };
        for (byte s : invalidSTypes) {
            assertFalse(HsmsSsMessageType.supportSType(msg((byte)0, s)),
                    "S-Type " + s + " must not be supported");
        }
    }

    @Test
    @DisplayName("message-variant checks the S-Type, not the P-Type")
    void messageVariantChecksSTypeNotPType() {
        /* S-Type 3 (DESELECT_REQ) is a valid S-Type ... */
        assertTrue(HsmsSsMessageType.supportSType(msg((byte)0, (byte)3)));
        /* ... while P-Type 3 does not exist */
        assertFalse(HsmsSsMessageType.supportPType((byte)3));

        /* 0x80 is the P-Type of UNDEFINED, but not a valid S-Type */
        assertFalse(HsmsSsMessageType.supportPType((byte)0x80));
        assertFalse(HsmsSsMessageType.supportSType(msg((byte)0, (byte)0x80)));
    }

    @Test
    @DisplayName("message-variant and byte-variant agree for every S-Type")
    void messageVariantAgreesWithByteVariant() {
        for (int s = 0; s <= 0xFF; ++s) {
            byte b = (byte) s;
            assertTrue(HsmsSsMessageType.supportSType(msg((byte)0, b))
                    == HsmsSsMessageType.supportSType(b), "S-Type " + s);
        }
    }
}
