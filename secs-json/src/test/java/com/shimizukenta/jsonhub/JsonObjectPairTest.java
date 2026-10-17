package com.shimizukenta.jsonhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link JsonObjectPair}.
 */
class JsonObjectPairTest {

	private final JsonHubBuilder builder = JsonHub.getBuilder();

	private JsonObjectPair pair(String name, JsonHub value) {
		return builder.pair(JsonString.unescaped(name), value);
	}

	@Test
	@DisplayName("accessors return the given instances")
	void accessors() {
		JsonString name = JsonString.unescaped("n");
		JsonHub value = builder.number(1);
		JsonObjectPair p = builder.pair(name, value);
		assertSame(name, p.name());
		assertSame(value, p.value());
	}

	@Test
	@DisplayName("equals: same name and same value")
	void equalsSameNameAndValue() {
		JsonObjectPair p1 = pair("a", builder.number(1));
		JsonObjectPair p2 = pair("a", builder.number(1));
		assertEquals(p1, p2);
		assertEquals(p1.hashCode(), p2.hashCode());
	}

	@Test
	@DisplayName("equals: different value is not equal even with same name")
	void notEqualsDifferentValue() {
		JsonObjectPair p1 = pair("a", builder.number(1));
		JsonObjectPair p2 = pair("a", builder.number(2));
		assertNotEquals(p1, p2);
	}

	@Test
	@DisplayName("equals: different name is not equal even with same value")
	void notEqualsDifferentName() {
		JsonObjectPair p1 = pair("a", builder.number(1));
		JsonObjectPair p2 = pair("b", builder.number(1));
		assertNotEquals(p1, p2);
	}

	@Test
	@DisplayName("equals: different value type is not equal")
	void notEqualsDifferentType() {
		JsonObjectPair p1 = pair("a", builder.number(1));
		JsonObjectPair p2 = pair("a", builder.string("1"));
		assertNotEquals(p1, p2);
		assertNotEquals(p1, null);
		assertNotEquals(p1, "a");
	}

	@Test
	@DisplayName("equals is consistent with ObjectJsonHub.equals")
	void consistentWithObjectEquals() {
		JsonObjectPair p1 = pair("a", builder.number(1));
		JsonObjectPair p2 = pair("a", builder.number(2));

		JsonHub o1 = builder.object(p1);
		JsonHub o2 = builder.object(pair("a", builder.number(1)));
		JsonHub o3 = builder.object(p2);

		assertEquals(p1.equals(p2), o1.equals(o3));
		assertFalse(p1.equals(p2));
		assertNotEquals(o1, o3);

		assertEquals(p1.equals(pair("a", builder.number(1))), o1.equals(o2));
		assertTrue(p1.equals(pair("a", builder.number(1))));
		assertEquals(o1, o2);
	}

	@Test
	@DisplayName("usable in hash-based collections")
	void usableInHashCollections() {
		Set<JsonObjectPair> set = new LinkedHashSet<>();
		set.add(pair("a", builder.number(1)));
		set.add(pair("a", builder.number(1)));
		set.add(pair("a", builder.number(2)));
		set.add(pair("b", builder.number(1)));
		assertEquals(3, set.size());
		assertTrue(set.contains(pair("a", builder.number(2))));
	}

	@Test
	@DisplayName("toString contains name and value")
	void toStringFormat() {
		JsonObjectPair p = pair("a", builder.number(1));
		assertEquals("(\"a\": 1)", p.toString());
	}

	@Test
	@DisplayName("null name is rejected; null value becomes null-value")
	void nullRejected() {
		assertThrowsNpe(() -> builder.pair((JsonString) null, builder.number(1)));
		JsonObjectPair p = builder.pair(JsonString.unescaped("a"), (Object) null);
		assertTrue(p.value().isNull());
		assertEquals("(\"a\": null)", p.toString());
	}

	private static void assertThrowsNpe(Runnable r) {
		try {
			r.run();
		}
		catch ( NullPointerException e ) {
			return;
		}
		throw new AssertionError("expected NullPointerException");
	}
}
