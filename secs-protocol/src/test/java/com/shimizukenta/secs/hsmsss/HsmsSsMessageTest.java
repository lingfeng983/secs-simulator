package com.shimizukenta.secs.hsmsss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HsmsSsMessage#deviceId()}.
 *
 * <p>
 * The Device-ID/Session-ID is the full 16-bit header word head[0..1];
 * masking head[0] with 0x7F (the former bug) drops the high bit and
 * mis-decodes every Device-ID >= 0x8000 (e.g. 0xFFFF decodes as 0x7FFF).
 * </p>
 */
class HsmsSsMessageTest {

    private static HsmsSsMessage dataMessage(int deviceId) {
        return new HsmsSsMessage(new byte[] {
                (byte)(deviceId >> 8),
                (byte)(deviceId),
                1, 1, 0, 0, 0, 0, 0, 1
        });
    }

    @Test
    @DisplayName("device-id round-trips for values including the high bit")
    void deviceIdRoundTrip() {
        int[] ids = { 0x0000, 0x00FF, 0x0100, 0x1234, 0x7FFF, 0x8000, 0x8001, 0xFF00, 0xFFFE, 0xFFFF };
        for (int id : ids) {
            assertEquals(id, dataMessage(id).deviceId(), "deviceId " + id);
        }
    }

    @Test
    @DisplayName("device-id 0xFFFF keeps the high bit")
    void deviceIdFFFF() {
        assertEquals(0xFFFF, dataMessage(0xFFFF).deviceId());
    }

    @Test
    @DisplayName("device-id 0x8000 boundary")
    void deviceIdBoundary() {
        assertEquals(0x8000, dataMessage(0x8000).deviceId());
    }

    @Test
    @DisplayName("session-id equals device-id for HSMS-SS")
    void sessionIdEqualsDeviceId() {
        assertEquals(0xFFFF, dataMessage(0xFFFF).sessionId());
    }

    @Test
    @DisplayName("toJson contains the full device-id")
    void toJsonContainsDeviceId() {
        String json = dataMessage(0xFFFF).toJson();
        assertTrue(json.contains("\"deviceId\":65535"), json);
    }

    @Test
    @DisplayName("device-id of a non-data message throws")
    void deviceIdOfNonDataMessageThrows() {
        HsmsSsMessage control = new HsmsSsMessage(new byte[] {
                0, 0, 0, 0, 0, 5, 0, 0, 0, 1
        });

        assertThrows(IllegalStateException.class, control::deviceId);
    }
}
