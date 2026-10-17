package com.shimizukenta.jsonhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link JsonStringCoder}.
 */
class JsonStringCoderTest {

	private final JsonStringCoder coder = JsonStringCoder.getInstance();

	@Nested
	@DisplayName("escape()")
	class Escape {

		@Test
		@DisplayName("control characters without short-escape are U+00XX escaped")
		void controlCharsAreUnicodeEscaped() {
			assertEquals("\\u0000", coder.escape("\u0000"));
			assertEquals("\\u0001", coder.escape("\u0001"));
			assertEquals("\\u0010", coder.escape("\u0010"));
			assertEquals("\\u001F", coder.escape("\u001F"));
		}

		@Test
		@DisplayName("short escapes are kept for backspace/tab/LF/FF/CR")
		void shortEscapesAreKept() {
			assertEquals("\\b", coder.escape("\b"));
			assertEquals("\\t", coder.escape("\t"));
			assertEquals("\\n", coder.escape("\n"));
			assertEquals("\\f", coder.escape("\f"));
			assertEquals("\\r", coder.escape("\r"));
		}

		@Test
		@DisplayName("quote, backslash and slash are escaped")
		void quotBackslashSlashAreEscaped() {
			assertEquals("\\\"", coder.escape("\""));
			assertEquals("\\\\", coder.escape("\\"));
			assertEquals("\\/", coder.escape("/"));
		}

		@Test
		@DisplayName("0x7F and above pass through as-is (including han and emoji)")
		void highCharsPassThrough() {
			assertEquals("\u007F", coder.escape("\u007F"));
			assertEquals("\u0080", coder.escape("\u0080"));
			assertEquals("\u00e9", coder.escape("\u00e9"));
			assertEquals("\u4e2d", coder.escape("\u4e2d"));
			assertEquals("\uD83D\uDE00", coder.escape("\uD83D\uDE00"));
		}

		@Test
		@DisplayName("printable ASCII is unchanged")
		void printableAsciiUnchanged() {
			assertEquals("", coder.escape(""));
			assertEquals(" ", coder.escape(" "));
			assertEquals("~", coder.escape("~"));
			assertEquals("abcXYZ019", coder.escape("abcXYZ019"));
		}

		@Test
		@DisplayName("unpaired surrogates are rejected with JsonHubBuildException")
		void unpairedSurrogatesRejected() {
			JsonHubBuildException e1 = assertThrows(JsonHubBuildException.class,
					() -> coder.escape("\uD83D"));
			assertTrue(e1.getMessage().contains("unpaired high-surrogate"), e1.getMessage());
			assertTrue(e1.getMessage().contains("index: 0"), e1.getMessage());

			JsonHubBuildException e2 = assertThrows(JsonHubBuildException.class,
					() -> coder.escape("a\uDE00"));
			assertTrue(e2.getMessage().contains("unpaired low-surrogate"), e2.getMessage());
			assertTrue(e2.getMessage().contains("index: 1"), e2.getMessage());
		}

		@Test
		@DisplayName("escape accepts CharSequence, not only String")
		void acceptsCharSequence() {
			StringBuilder sb = new StringBuilder("a\"b");
			assertEquals("a\\\"b", coder.escape(sb));
			assertEquals(coder.escape("x"), coder.escape((CharSequence) "x"));
		}
	}

	@Nested
	@DisplayName("unescape()")
	class Unescape {

		@Test
		@DisplayName("U+XXXX escape is decoded (han, control, latin-1)")
		void unicodeEscapesDecoded() {
			assertEquals("\u4e2d", coder.unescape("\\u4e2d"));
			assertEquals("\u0000", coder.unescape("\\u0000"));
			assertEquals("\u001F", coder.unescape("\\u001F"));
			assertEquals("\u00e9", coder.unescape("\\u00e9"));
			assertEquals("A", coder.unescape("\\u0041"));
		}

		@Test
		@DisplayName("hex digits are case-insensitive")
		void hexIsCaseInsensitive() {
			assertEquals("\u00ff", coder.unescape("\\u00ff"));
			assertEquals("\u00ff", coder.unescape("\\u00FF"));
		}

		@Test
		@DisplayName("surrogate escape pair is combined into one supplementary code point")
		void surrogatePairCombined() {
			String emoji = coder.unescape("\\uD83D\\uDE00");
			assertEquals(1, emoji.codePointCount(0, emoji.length()));
			assertEquals(0x1F600, emoji.codePointAt(0));
			assertEquals("\uD83D\uDE00", emoji);
		}

		@Test
		@DisplayName("short escapes and escaped literals are decoded")
		void shortEscapesDecoded() {
			assertEquals("\b\t\n\f\r", coder.unescape("\\b\\t\\n\\f\\r"));
			assertEquals("\"", coder.unescape("\\\""));
			assertEquals("\\", coder.unescape("\\\\"));
			assertEquals("/", coder.unescape("\\/"));
		}

		@Test
		@DisplayName("raw characters (including raw surrogate pair) pass through")
		void rawCharsPassThrough() {
			assertEquals("abc", coder.unescape("abc"));
			assertEquals("\u4e2d", coder.unescape("\u4e2d"));
			assertEquals("\uD83D\uDE00", coder.unescape("\uD83D\uDE00"));
		}

		@Test
		@DisplayName("illegal escape sequence throws with index")
		void illegalEscapeRejected() {
			JsonHubParseException e = assertThrows(JsonHubParseException.class,
					() -> coder.unescape("\\x"));
			assertTrue(e.getMessage().contains("illegal escape-sequence"), e.getMessage());
			assertTrue(e.getMessage().contains("index: 1"), e.getMessage());

			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\a"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\ "));
		}

		@Test
		@DisplayName("truncated U+XXXX throws")
		void truncatedUnicodeRejected() {
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\u"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\u1"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\u12"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\u123"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("ab\\u12"));
		}

		@Test
		@DisplayName("non-hexadecimal digit throws with index")
		void nonHexDigitRejected() {
			JsonHubParseException e = assertThrows(JsonHubParseException.class,
					() -> coder.unescape("\\uZZZZ"));
			assertTrue(e.getMessage().contains("not hexadecimal-digit"), e.getMessage());
			assertTrue(e.getMessage().contains("index: 2"), e.getMessage());
		}

		@Test
		@DisplayName("trailing backslash throws")
		void trailingBackslashRejected() {
			JsonHubParseException e = assertThrows(JsonHubParseException.class,
					() -> coder.unescape("abc\\"));
			assertTrue(e.getMessage().contains("not found escape-character"), e.getMessage());
			assertTrue(e.getMessage().contains("index: 3"), e.getMessage());
		}

		@Test
		@DisplayName("lone high surrogate (escaped or raw) throws")
		void loneHighSurrogateRejected() {
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\uD83D"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\uD83Dx"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\uD83D\\u0041"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\uD83D\\uD83D\\uDE00"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\uD83D"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\uD83Dabc"));
		}

		@Test
		@DisplayName("lone low surrogate (escaped or raw) throws")
		void loneLowSurrogateRejected() {
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\uDE00"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\uDE00\\uDE01"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\uDE00"));
		}

		@Test
		@DisplayName("high surrogate followed by short-escape or another high throws")
		void highSurrogateFollowedByOtherThrows() {
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\uD83D\\n"));
			assertThrows(JsonHubParseException.class, () -> coder.unescape("\\uD83D\\uD83D"));
		}

		@Test
		@DisplayName("escaped high surrogate may pair with raw low surrogate and vice versa")
		void mixedEscapedRawPairing() {
			/* escaped high + raw low */
			assertEquals("\uD83D\uDE00", coder.unescape("\uD83D" + "\uDE00"));
			/* raw high + escaped low */
			assertEquals("\uD83D\uDE00", coder.unescape("\uD83D" + "\\uDE00"));
		}

		@Test
		@DisplayName("unescape accepts CharSequence, not only String")
		void acceptsCharSequence() {
			StringBuilder sb = new StringBuilder("a\\\"b");
			assertEquals("a\"b", coder.unescape(sb));
		}
	}

	@Nested
	@DisplayName("round-trip invariants")
	class RoundTrip {

		@Test
		@DisplayName("unescape(escape(s)) == s")
		void unescapeOfEscapeIsIdentity() {
			String s = "a\u0000b\u001Fc\"d\\e/f\u4e2d\uD83D\uDE00g\u007Fh \u0080~";
			assertEquals(s, coder.unescape(coder.escape(s)));
			assertEquals("", coder.unescape(coder.escape("")));
			assertEquals("\u0000", coder.unescape(coder.escape("\u0000")));
		}

		@Test
		@DisplayName("escape(unescape(s)) == s for canonical escaped strings")
		void escapeOfUnescapeIsIdentity() {
			String es = coder.escape("a\u0000b\u001Fc\"d\\e/f\u4e2d\uD83D\uDE00g\u007Fh");
			assertEquals(es, coder.escape(coder.unescape(es)));
			assertEquals("\\u0000", coder.escape(coder.unescape("\\u0000")));
			assertEquals("\\b\\t\\n\\f\\r", coder.escape(coder.unescape("\\b\\t\\n\\f\\r")));
			assertEquals("\u4e2d", coder.escape(coder.unescape("\\u4e2d")));
		}
	}

	@Nested
	@DisplayName("snippet()")
	class Snippet {

		@Test
		@DisplayName("long strings are truncated to 256 chars with ellipsis")
		void longStringsTruncated() {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 1000; ++i) {
				sb.append('x');
			}
			String snip = JsonStringCoder.snippet(sb.toString());
			assertEquals(256, snip.length());
			assertTrue(snip.endsWith("..."));
			assertTrue(snip.startsWith("xxx"));
		}

		@Test
		@DisplayName("256-char boundary: kept as-is, 257 truncated")
		void boundaryLengths() {
			String s256 = repeat('y', 256);
			assertEquals(256, JsonStringCoder.snippet(s256).length());
			assertFalse(JsonStringCoder.snippet(s256).endsWith("..."));

			String s257 = repeat('y', 257);
			String snip = JsonStringCoder.snippet(s257);
			assertEquals(256, snip.length());
			assertTrue(snip.endsWith("..."));
		}

		@Test
		@DisplayName("short strings are only trimmed")
		void shortStringsTrimmed() {
			assertEquals("abc", JsonStringCoder.snippet("  abc  "));
			assertEquals("", JsonStringCoder.snippet("   "));
			assertEquals("abc", JsonStringCoder.snippet("abc"));
		}

		private static String repeat(char c, int n) {
			char[] cs = new char[n];
			java.util.Arrays.fill(cs, c);
			return new String(cs);
		}
	}
}
