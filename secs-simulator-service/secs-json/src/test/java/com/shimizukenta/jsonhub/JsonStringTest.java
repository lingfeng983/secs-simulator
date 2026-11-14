package com.shimizukenta.jsonhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link JsonString} (escaped/unescaped double cache and equals).
 */
class JsonStringTest {

	private final JsonStringCoder coder = JsonStringCoder.getInstance();

	@Test
	@DisplayName("escaped() factory: unescaped() decodes, escaped() keeps the literal")
	void fromEscaped() {
		JsonString js = JsonString.escaped("\\u4e2d\\t");
		assertEquals("\\u4e2d\\t", js.escaped());
		assertEquals("\u4e2d\t", js.unescaped());
	}

	@Test
	@DisplayName("unescaped() factory: escaped() escapes back")
	void fromUnescaped() {
		JsonString js = JsonString.unescaped("\u4e2d\t\"");
		assertEquals("\u4e2d\t\"", js.unescaped());
		assertEquals("\u4e2d\\t\\\"", js.escaped());
	}

	@Test
	@DisplayName("escaped/unescaped caches are stable (same value on repeated calls)")
	void cachesAreStable() {
		JsonString fromEscaped = JsonString.escaped("\\u4e2d");
		assertEquals(fromEscaped.escaped(), fromEscaped.escaped());
		assertEquals(fromEscaped.unescaped(), fromEscaped.unescaped());

		JsonString fromUnescaped = JsonString.unescaped("\u4e2d");
		assertEquals(fromUnescaped.escaped(), fromUnescaped.escaped());
		assertEquals(fromUnescaped.unescaped(), fromUnescaped.unescaped());
	}

	@Test
	@DisplayName("escaped() and unescaped() are mutual inverses through the cache")
	void mutualInverse() {
		/* escaped() output is canonical by construction */
		JsonString js = JsonString.unescaped("a\u0000b\u4e2d\uD83D\uDE00");
		assertEquals(js.unescaped(), coder.unescape(js.escaped()));
		assertEquals(js.escaped(), coder.escape(js.unescaped()));

		/* a canonical escaped string survives the round-trip */
		JsonString es = JsonString.escaped(coder.escape("a\u0000b\u4e2d"));
		assertEquals(es.escaped(), coder.escape(es.unescaped()));
		assertEquals(es.unescaped(), coder.unescape(es.escaped()));
	}

	@Test
	@DisplayName("equals compares unescaped content, hashCode is consistent")
	void equalsByUnescapedContent() {
		JsonString a = JsonString.escaped("\\u0041");
		JsonString b = JsonString.unescaped("A");
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());

		JsonString c = JsonString.unescaped("B");
		assertNotEquals(a, c);
		assertNotEquals(a, null);
		assertNotEquals(a, "A");
	}

	@Test
	@DisplayName("toString returns the unescaped content")
	void toStringIsUnescaped() {
		assertEquals("\u4e2d", JsonString.escaped("\\u4e2d").toString());
		assertEquals("A", JsonString.unescaped("A").toString());
	}

	@Test
	@DisplayName("length and isEmpty follow the unescaped content")
	void lengthAndEmpty() {
		assertEquals(3, JsonString.unescaped("abc").length());
		assertEquals(1, JsonString.unescaped("\u4e2d").length());
		assertTrue(JsonString.escaped("").isEmpty());
		assertTrue(JsonString.unescaped("").isEmpty());
	}

	@Test
	@DisplayName("null is rejected by both factories")
	void nullRejected() {
		assertThrows(NullPointerException.class, () -> JsonString.escaped((CharSequence) null));
		assertThrows(NullPointerException.class, () -> JsonString.unescaped((CharSequence) null));
	}

	@Test
	@DisplayName("invalid escaped content throws on unescaped() (parse-time fail-fast rationale)")
	void invalidEscapedThrowsOnUnescaped() {
		JsonString bad = JsonString.escaped("\\x");
		assertThrows(JsonHubParseException.class, bad::unescaped);
		assertThrows(JsonHubParseException.class, bad::toString);
	}

	@Test
	@DisplayName("StringJsonHub built from JsonString keeps the escaped literal in toJson")
	void stringJsonHubKeepsLiteral() {
		JsonHub v = JsonHub.getBuilder().string(JsonString.escaped("\\u0041"));
		assertEquals("\"\\u0041\"", v.toJson());
		assertEquals("A", v.toString());
	}
}
