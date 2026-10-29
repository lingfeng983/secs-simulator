package com.shimizukenta.jsonhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link NumberJsonHub}.
 */
class NumberJsonHubTest {

	private final JsonHubBuilder builder = JsonHub.getBuilder();

	@Nested
	@DisplayName("parsing from string")
	class FromString {

		@Test
		@DisplayName("value without dot is long")
		void longValue() {
			assertEquals(42L, builder.number("42").longValue());
			assertEquals(-42L, builder.number("-42").longValue());
			assertEquals(Long.MAX_VALUE, builder.number("9223372036854775807").longValue());
			assertEquals(Long.MIN_VALUE, builder.number("-9223372036854775808").longValue());
		}

		@Test
		@DisplayName("value with dot is double")
		void doubleValue() {
			assertEquals(2.5, builder.number("2.5").doubleValue(), 1e-12);
			assertEquals(-0.125, builder.number("-0.125").doubleValue(), 1e-12);
		}

		@Test
		@DisplayName("exponent notation is valid JSON with or without a dot")
		void exponentNotation() {
			assertEquals(1500.0, builder.number("1.5e3").doubleValue(), 1e-9);
			assertEquals(0.0015, builder.number("1.5e-3").doubleValue(), 1e-15);
			assertEquals(100000.0, builder.number("1e5").doubleValue(), 1e-9);
			assertEquals(100000.0, builder.number("1E5").doubleValue(), 1e-9);
		}

		@Test
		@DisplayName("long overflow is rejected")
		void longOverflowRejected() {
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number("9223372036854775808"));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number("-9223372036854775809"));
		}

		@Test
		@DisplayName("garbage is rejected")
		void garbageRejected() {
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number("abc"));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number(""));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number("1.2.3"));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number("--1"));
		}

		@Test
		@DisplayName("null is rejected")
		void nullRejected() {
			assertThrows(NullPointerException.class, () -> builder.number((CharSequence) null));
		}

		@Test
		@DisplayName("non-standard JSON numbers are rejected, output is canonical")
		void nonStandardLiteralsAreRejectedAndOutputIsCanonical() {
			/* 这些 Long/Double.valueOf 都能解析，但不是合法 JSON */
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number("007"));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number("+1"));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number(".5"));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number("1."));

			assertEquals("1.5", builder.number("1.50").toJson());
			assertEquals("1500.0", builder.number("1.5e3").toJson());
			assertEquals("-0.0", builder.number("-0.0").toJson());
			assertEquals("7", builder.number("7").toJson());
		}
	}

	@Nested
	@DisplayName("finite validation")
	class Finite {

		@Test
		@DisplayName("NaN and Infinity are rejected")
		void nonFiniteRejected() {
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number(Double.NaN));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number(Double.POSITIVE_INFINITY));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number(Double.NEGATIVE_INFINITY));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number(Float.NaN));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number(Float.POSITIVE_INFINITY));
			assertThrows(JsonHubNumberFormatException.class, () -> builder.number(Float.NEGATIVE_INFINITY));
		}

		@Test
		@DisplayName("finite values are accepted")
		void finiteAccepted() {
			assertEquals(1.5, builder.number(1.5).doubleValue(), 1e-12);
			assertEquals(-2.5f, builder.number(-2.5f).doubleValue(), 1e-6);
			assertEquals(0.0, builder.number(0.0).doubleValue(), 0.0);
			assertEquals(3, builder.number(3).intValue());
			assertEquals(3L, builder.number(3L).longValue());
		}

		@Test
		@DisplayName("rejection message names the value")
		void messageNamesValue() {
			JsonHubNumberFormatException e = assertThrows(JsonHubNumberFormatException.class,
					() -> builder.number(Double.NaN));
			assertTrue(e.getMessage().contains("not finite"), e.getMessage());
			assertTrue(e.getMessage().contains("NaN"), e.getMessage());
		}
	}

	@Nested
	@DisplayName("value access")
	class Access {

		@Test
		@DisplayName("optional getters")
		void optionals() {
			NumberJsonHub n = builder.number("42");
			assertEquals(OptionalInt.of(42), n.optionalInt());
			assertEquals(OptionalLong.of(42L), n.optionalLong());
			assertEquals(OptionalDouble.of(42.0), n.optionalDouble());
			assertEquals(Optional.of(42L), n.optionalNubmer());

			NumberJsonHub d = builder.number("2.5");
			assertEquals(OptionalDouble.of(2.5), d.optionalDouble());
			assertEquals(Optional.of(2.5d), d.optionalNubmer());
		}

		@Test
		@DisplayName("primitive getters narrow")
		void primitives() {
			assertEquals(42, builder.number("42").intValue());
			assertEquals(42L, builder.number("42").longValue());
			assertEquals(42.0, builder.number("42").doubleValue(), 0.0);
			assertEquals(2, builder.number("2.9").intValue());
			assertEquals(3L, builder.number("3.2").longValue());
		}
	}

	@Nested
	@DisplayName("value semantics")
	class Semantics {

		@Test
		@DisplayName("type is NUMBER")
		void type() {
			assertTrue(builder.number("1").isNumber());
			assertEquals(JsonHubType.NUMBER, builder.number("1").type());
		}

		@Test
		@DisplayName("equals compares the Number value; long 1 is not double 1.0")
		void equalsByNumberValue() {
			assertEquals(builder.number("1"), builder.number("1"));
			assertEquals(builder.number("1"), builder.number(1L));
			assertEquals(builder.number("2.5"), builder.number(2.5));
			assertNotEquals(builder.number("1"), builder.number("1.0"));
			assertNotEquals(builder.number("1"), builder.string("1"));
			assertEquals(builder.number("1").hashCode(), builder.number("1").hashCode());
		}

		@Test
		@DisplayName("integers routed through double keep at most 53 bits (documented risk)")
		void doublePrecisionLimit() {
			/* 9007199254740993 = 2^53 + 1 is not representable as double */
			assertEquals(9007199254740993L, builder.number("9007199254740993").longValue());
			assertEquals(9007199254740992L, builder.number("9007199254740993.0").longValue());
			assertEquals("9.007199254740992E15", builder.number("9007199254740993.0").toJson(),
					"规范化输出使用 Double.toString，仍是合法 JSON");
		}
	}
}
