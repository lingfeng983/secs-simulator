package com.shimizukenta.jsonhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link JsonHubJsonParser}.
 */
class JsonHubJsonParserTest {

	private final JsonHubJsonParser parser = JsonHubJsonParser.getInstance();

	private static String nest(char open, char close, int levels, String inner) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < levels; ++i) {
			sb.append(open);
		}
		sb.append(inner);
		for (int i = 0; i < levels; ++i) {
			sb.append(close);
		}
		return sb.toString();
	}

	@Nested
	@DisplayName("string values")
	class StringValues {

		@Test
		@DisplayName("U+XXXX escape is decoded to the character")
		void unicodeEscapeDecoded() {
			JsonHub v = parser.parse("{\"a\":\"\\u4e2d\"}");
			assertEquals("\u4e2d", v.get("a").toString());
		}

		@Test
		@DisplayName("short escapes are decoded")
		void shortEscapesDecoded() {
			JsonHub v = parser.parse("\"\\b\\t\\n\\f\\r\\\"\\\\\\/\"");
			assertEquals("\b\t\n\f\r\"\\/", v.toString());
		}

		@Test
		@DisplayName("escaped surrogate pair is decoded to one emoji")
		void surrogatePairDecoded() {
			JsonHub v = parser.parse("\"\\uD83D\\uDE00\"");
			assertEquals("\uD83D\uDE00", v.toString());
			assertEquals(1, v.toString().codePointCount(0, v.toString().length()));
		}

		@Test
		@DisplayName("illegal escape fails fast at parse-time")
		void illegalEscapeFailsFast() {
			assertThrows(JsonHubParseException.class, () -> parser.parse("\"\\x\""));
			assertThrows(JsonHubParseException.class, () -> parser.parse("\"abc\\q\""));
		}

		@Test
		@DisplayName("lone surrogate escape fails fast at parse-time")
		void loneSurrogateFailsFast() {
			assertThrows(JsonHubParseException.class, () -> parser.parse("\"\\uD83D\""));
			assertThrows(JsonHubParseException.class, () -> parser.parse("\"\\uDE00\""));
			assertThrows(JsonHubParseException.class, () -> parser.parse("\"\\uD83Dx\""));
		}

		@Test
		@DisplayName("raw control character inside STRING is rejected")
		void rawControlCharRejected() {
			assertThrows(JsonHubParseException.class, () -> parser.parse("\"a\nb\""));
			assertThrows(JsonHubParseException.class, () -> parser.parse("\"a\u0000b\""));
			assertThrows(JsonHubParseException.class, () -> parser.parse("\"a\u001Fb\""));
		}

		@Test
		@DisplayName("string values always serialize with double quotes")
		void stringSerializesWithDoubleQuotes() {
			JsonHub v = JsonHub.getBuilder().string("a'b\"c");
			assertEquals("\"a'b\\\"c\"", v.toJson());
			JsonHub parsed = parser.parse(v.toJson());
			assertEquals(v, parsed);
		}

		@Test
		@DisplayName("single-quoted string is not accepted (existing semantics)")
		void singleQuotedStringRejected() {
			assertThrows(JsonHubParseException.class, () -> parser.parse("'abc'"));
		}
	}

	@Nested
	@DisplayName("BOM handling")
	class Bom {

		@Test
		@DisplayName("one leading BOM is skipped")
		void leadingBomSkipped() {
			assertEquals("abc", parser.parse("\uFEFF\"abc\"").toString());
			assertEquals(2, parser.parse("\uFEFF[1,2]").length());
			assertEquals("1", parser.parse("\uFEFF 1").toString());
			assertEquals("\u4e2d", parser.parse("\uFEFF\"\\u4e2d\"").toString());
		}

		@Test
		@DisplayName("BOM only is still empty")
		void bomOnlyIsEmpty() {
			assertThrows(JsonHubParseException.class, () -> parser.parse("\uFEFF"));
			assertThrows(JsonHubParseException.class, () -> parser.parse("\uFEFF   "));
		}

		@Test
		@DisplayName("BOM in the middle is not skipped")
		void midBomNotSkipped() {
			/* U+FEFF is a valid in-string character, but as bare value it is not a number */
			assertThrows(JsonHubParseException.class, () -> parser.parse("1\uFEFF"));
		}
	}

	@Nested
	@DisplayName("nesting depth")
	class NestingDepth {

		@Test
		@DisplayName("65 nested ARRAYs are accepted")
		void accepts65Arrays() {
			JsonHub v = parser.parse(nest('[', ']', 65, "1"));
			assertInstanceOf(ArrayJsonHub.class, v);
		}

		@Test
		@DisplayName("66 nested ARRAYs are rejected with JsonHubParseException, not StackOverflowError")
		void rejects66Arrays() {
			JsonHubParseException e = assertThrows(JsonHubParseException.class,
					() -> parser.parse(nest('[', ']', 66, "1")));
			assertTrue(e.getMessage().contains("nesting too deep"), e.getMessage());
		}

		@Test
		@DisplayName("65 nested OBJECTs are accepted")
		void accepts65Objects() {
			JsonHub v = parser.parse(nestObjects(65));
			assertInstanceOf(ObjectJsonHub.class, v);
		}

		@Test
		@DisplayName("66 nested OBJECTs are rejected with JsonHubParseException")
		void rejects66Objects() {
			JsonHubParseException e = assertThrows(JsonHubParseException.class,
					() -> parser.parse(nestObjects(66)));
			assertTrue(e.getMessage().contains("nesting too deep"), e.getMessage());
		}

		@Test
		@DisplayName("65 mixed containers are accepted, 66 rejected")
		void mixedNesting() {
			StringBuilder ok = new StringBuilder();
			for (int i = 0; i < 33; ++i) {
				ok.append('[');
			}
			for (int i = 0; i < 32; ++i) {
				ok.append("{\"k\":");
			}
			ok.append("1");
			for (int i = 0; i < 32; ++i) {
				ok.append('}');
			}
			for (int i = 0; i < 33; ++i) {
				ok.append(']');
			}
			parser.parse(ok.toString());

			StringBuilder tooDeep = new StringBuilder(ok.toString().replace("1", "[1"));
			assertThrows(JsonHubParseException.class, () -> parser.parse(tooDeep.toString()));
		}

		private static String nestObjects(int levels) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < levels; ++i) {
				sb.append("{\"k\":");
			}
			sb.append("1");
			for (int i = 0; i < levels; ++i) {
				sb.append('}');
			}
			return sb.toString();
		}
	}

	@Nested
	@DisplayName("values and literals")
	class Values {

		@Test
		@DisplayName("literals are parsed")
		void literals() {
			assertTrue(parser.parse("true").isTrue());
			assertTrue(parser.parse("false").isFalse());
			assertTrue(parser.parse("null").isNull());
			assertEquals("42", parser.parse("42").toString());
			assertEquals("-7", parser.parse("-7").toString());
			assertEquals("2.5", parser.parse("2.5").toString());
		}

		@Test
		@DisplayName("empty and blank input is rejected")
		void emptyRejected() {
			for (String s : new String[]{"", "   ", "\t\n "}) {
				JsonHubParseException e = assertThrows(JsonHubParseException.class, () -> parser.parse(s));
				assertEquals("JSON is empty", e.getMessage());
			}
		}

		@Test
		@DisplayName("trailing content is rejected")
		void trailingContentRejected() {
			assertThrows(JsonHubParseException.class, () -> parser.parse("1 2"));
			assertThrows(JsonHubParseException.class, () -> parser.parse("[1] 2"));
			assertThrows(JsonHubParseException.class, () -> parser.parse("\"a\" \"b\""));
		}

		@Test
		@DisplayName("arrays and objects with whitespace")
		void whitespaceTolerant() {
			assertEquals("[1,2,3]", parser.parse("  [ 1 , 2 , 3 ]  ").toJson());
			assertEquals("{\"a\":1}", parser.parse(" { \"a\" : 1 } ").toJson());
			assertEquals("[]", parser.parse("[ ]").toJson());
			assertEquals("{}", parser.parse("{ }").toJson());
		}

		@Test
		@DisplayName("duplicate keys are preserved in insertion order, get returns first")
		void duplicateKeysPreserved() {
			JsonHub v = parser.parse("{\"a\":1,\"a\":2,\"b\":3}");
			assertEquals(3, v.length());
			assertEquals("1", v.get("a").toString());
			assertEquals("{\"a\":1,\"a\":2,\"b\":3}", v.toJson());
		}

		@Test
		@DisplayName("round-trip parse(toJson(v)) equals v")
		void roundTrip() {
			String json = "{\"s\":\"\\u4e2d\\uD83D\\uDE00\",\"n\":[1,2.5,-3],\"o\":{\"x\":true,\"y\":null}}";
			JsonHub v = parser.parse(json);
			assertEquals(v, parser.parse(v.toJson()));
			assertEquals(json, v.toJson());
		}

		@Test
		@DisplayName("parsed values are usable through the JsonHub API")
		void parsedValuesUsable() {
			JsonHub v = parser.parse("{\"list\":[10,20,30],\"nested\":{\"deep\":\"v\"}}");
			assertEquals(20, v.get("list").get(1).intValue());
			assertEquals("v", v.get("nested", "deep").toString());
			assertTrue(v.containsKey("list"));
			assertFalse(v.containsKey("missing"));
			assertNull(v.get("missing"));
			assertEquals(3, v.get("list").length());
		}
	}

	@Nested
	@DisplayName("number syntax (accepted risks documented in NumberJsonHub)")
	class Numbers {

		@Test
		@DisplayName("integers without dot are long")
		void longIntegers() {
			assertEquals(42L, parser.parse("42").longValue());
			assertEquals(Long.MAX_VALUE, parser.parse("9223372036854775807").longValue());
			assertEquals(Long.MIN_VALUE, parser.parse("-9223372036854775808").longValue());
		}

		@Test
		@DisplayName("exponent without dot is rejected")
		void exponentWithoutDotRejected() {
			assertThrows(JsonHubParseException.class, () -> parser.parse("1e5"));
		}

		@Test
		@DisplayName("long overflow is rejected")
		void longOverflowRejected() {
			assertThrows(JsonHubParseException.class, () -> parser.parse("9223372036854775808"));
		}

		@Test
		@DisplayName("exponent with dot is accepted as double")
		void exponentWithDotAccepted() {
			assertEquals(1500.0, parser.parse("1.5e3").doubleValue(), 1e-9);
			assertEquals("1.5e3", parser.parse("1.5e3").toJson());
		}
	}

	@Nested
	@DisplayName("null handling")
	class Nulls {

		@Test
		@DisplayName("parse(null CharSequence) throws NullPointerException with message")
		void nullCharSequenceRejected() {
			NullPointerException e = assertThrows(NullPointerException.class,
					() -> parser.parse((CharSequence) null));
			assertTrue(e.getMessage().contains("nonNull"), e.getMessage());
		}

		@Test
		@DisplayName("parse(null Reader) throws NullPointerException with message")
		void nullReaderRejected() {
			NullPointerException e = assertThrows(NullPointerException.class,
					() -> parser.parse((java.io.Reader) null));
			assertTrue(e.getMessage().contains("nonNull"), e.getMessage());
		}

		@Test
		@DisplayName("builder.fromJson(null) also throws NullPointerException")
		void builderNullRejected() {
			assertThrows(NullPointerException.class,
					() -> JsonHub.getBuilder().fromJson((CharSequence) null));
		}
	}

	@Nested
	@DisplayName("Reader entry point")
	class ReaderEntry {

		@Test
		@DisplayName("parses from Reader, including BOM")
		void fromReader() throws IOException {
			assertEquals("{\"a\":1}", parser.parse(new StringReader("{\"a\":1}")).toJson());
			assertEquals("abc", parser.parse(new StringReader("\uFEFF\"abc\"")).toString());
			assertEquals("[]", parser.parse(new StringReader("[]")).toJson());
		}
	}

	@Nested
	@DisplayName("exception messages")
	class Messages {

		@Test
		@DisplayName("parse exception messages are bounded for huge inputs")
		void messagesBounded() {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 10000; ++i) {
				sb.append('x');
			}
			JsonHubParseException e = assertThrows(JsonHubParseException.class,
					() -> parser.parse(sb.toString()));
			assertTrue(e.getMessage().length() < 400, "message length: " + e.getMessage().length());

			JsonHubParseException e2 = assertThrows(JsonHubParseException.class,
					() -> parser.parse("[" + sb));
			assertTrue(e2.getMessage().length() < 400, "message length: " + e2.getMessage().length());
		}

		@Test
		@DisplayName("unescape error messages carry the index")
		void unescapeMessagesCarryIndex() {
			JsonHubParseException e = assertThrows(JsonHubParseException.class,
					() -> parser.parse("\"ab\\q\""));
			assertTrue(e.getMessage().contains("index: 3"), e.getMessage());
		}

		@Test
		@DisplayName("deep nesting message carries the index")
		void deepNestingMessageCarriesIndex() {
			JsonHubParseException e = assertThrows(JsonHubParseException.class,
					() -> parser.parse(nest('[', ']', 66, "1")));
			assertTrue(e.getMessage().contains("index: 65"), e.getMessage());
		}
	}
}
