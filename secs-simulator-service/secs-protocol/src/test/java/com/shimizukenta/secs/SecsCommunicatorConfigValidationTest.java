package com.shimizukenta.secs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;

/**
 * Tests for the config validation of
 * {@link AbstractSecsCommunicatorConfig}: {@code deviceId} range and the
 * new {@code maxMessageBytes} property, plus the fail-fast check in the
 * {@code AbstractHsmsSsCommunicator} constructor.
 */
class SecsCommunicatorConfigValidationTest {

    @Test
    @DisplayName("maxMessageBytes defaults to 8 MiB")
    void maxMessageBytesDefault() {
        assertEquals(8L * 1024L * 1024L, new HsmsSsCommunicatorConfig().maxMessageBytes().longValue());
    }

    @Test
    @DisplayName("maxMessageBytes rejects non-positive values")
    void maxMessageBytesRejectsNonPositive() {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        assertThrows(IllegalArgumentException.class, () -> config.maxMessageBytes(0L));
        assertThrows(IllegalArgumentException.class, () -> config.maxMessageBytes(-1L));
        config.maxMessageBytes(1024L);
        assertEquals(1024L, config.maxMessageBytes().longValue());
    }

    @Test
    @DisplayName("deviceId accepts 0..65535 only")
    void deviceIdRange() {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        assertThrows(IllegalArgumentException.class, () -> config.deviceId(-1));
        assertThrows(IllegalArgumentException.class, () -> config.deviceId(65536));

        config.deviceId(0);
        config.deviceId(65535);
        assertEquals(65535, config.deviceId().intValue());
    }

    @Test
    @DisplayName("sessionId shares the deviceId range (sessionId delegates to deviceId)")
    void sessionIdRange() {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        assertThrows(IllegalArgumentException.class, () -> config.sessionId(-1));
        assertThrows(IllegalArgumentException.class, () -> config.sessionId(65536));
    }

    @Test
    @DisplayName("communicator constructor fails fast on an out-of-range deviceId (e.g. deserialized config)")
    void constructorRejectsOutOfRangeDeviceId() throws Exception {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();

        /* bypass the setter validation, as a hand-edited or deserialized config could */
        Field f = AbstractSecsCommunicatorConfig.class.getDeclaredField("deviceId");
        f.setAccessible(true);
        NumberProperty property = (NumberProperty) f.get(config);
        property.set(70000);

        assertThrows(IllegalArgumentException.class, () -> HsmsSsCommunicator.newInstance(config));
    }
}
