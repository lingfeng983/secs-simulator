package com.shimizukenta.secs.secs2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the control-character escaping of {@link Secs2Ascii#toJson()}.
 *
 * <p>
 * JSON (RFC 8259) requires every control character U+0000..U+001F inside a
 * string to be escaped; a raw control byte makes the whole document invalid.
 * </p>
 */
class Secs2AsciiJsonEscapeTest {

    @Test
    @DisplayName("control characters other than the short escapes are unicode-escaped")
    void controlCharactersAreUnicodeEscaped() {
        String json = Secs2.ascii("a\u0001b\u001Fc").toJson();

        assertTrue(json.contains("\\u0001"), json);
        assertTrue(json.contains("\\u001F"), json);

        for (int i = 0; i < json.length(); ++i) {
            char c = json.charAt(i);
            assertTrue(c >= 0x20,
                    "raw control char U+" + Integer.toHexString(c) + " in " + json);
        }
    }

    @Test
    @DisplayName("common control characters keep their short escapes")
    void shortEscapesAreUsedForCommonControlChars() {
        String json = Secs2.ascii("\b\t\n\f\r").toJson();

        assertTrue(json.contains("\\b"), json);
        assertTrue(json.contains("\\t"), json);
        assertTrue(json.contains("\\n"), json);
        assertTrue(json.contains("\\f"), json);
        assertTrue(json.contains("\\r"), json);
    }

    @Test
    @DisplayName("quote, backslash and slash are escaped")
    void quoteBackslashSlashAreEscaped() {
        String json = Secs2.ascii("a\"b\\c/d").toJson();

        assertTrue(json.contains("\\\""), json);
        assertTrue(json.contains("\\\\"), json);
        assertTrue(json.contains("\\/"), json);
    }

    @Test
    @DisplayName("unescaping the json value restores the original ascii")
    void escapedJsonRoundTripsToOriginalAscii() {
        String original = "x\u0000y\u0001z\u001Fw";
        String json = Secs2.ascii(original).toJson();

        int begin = json.indexOf("\"v\":") + 5;
        String value = json.substring(begin, json.length() - 2);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (c == '\\' && (i + 1 < value.length())) {
                char n = value.charAt(++i);
                switch (n) {
                case 'b': sb.append('\b'); break;
                case 't': sb.append('\t'); break;
                case 'n': sb.append('\n'); break;
                case 'f': sb.append('\f'); break;
                case 'r': sb.append('\r'); break;
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                    break;
                default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }

        assertEquals(original, sb.toString());
    }

    @Test
    @DisplayName("json wrapper keeps the item symbol")
    void jsonWrapperKeepsItemSymbol() {
        assertEquals("{\"f\":\"A\",\"v\":\"abc\"}", Secs2.ascii("abc").toJson());
    }
}
