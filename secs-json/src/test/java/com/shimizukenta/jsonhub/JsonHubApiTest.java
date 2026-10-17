package com.shimizukenta.jsonhub;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of the JsonHub API: path access, equals, printers, builder and messages.
 */
class JsonHubApiTest {

	private final JsonHubBuilder builder = JsonHub.getBuilder();

	private JsonHub sample() {
		return JsonHub.fromJson("{\"a\":{\"b\":42,\"c\":[7,8],\"d\":\"s\"},\"e\":null}");
	}

	@Test
	@DisplayName("get(String...) returns scalar leaf (no ClassCastException)")
	void pathReturnsScalarLeaf() {
		JsonHub v = sample();
		assertEquals(42, v.get("a", "b").intValue());
		assertEquals("s", v.get("a", "d").toString());
	}

	@Test
	@DisplayName("get(String...) returns array/object leaf")
	void pathReturnsContainerLeaf() {
		JsonHub v = sample();
		assertEquals("[7,8]", v.get("a", "c").toJson());
		assertTrue(v.get("a", "c").isArray());
		assertTrue(v.get("a").isObject());
		assertEquals(v, v.get());
	}

	@Test
	@DisplayName("get(String...) through a scalar or missing name returns null")
	void pathThroughScalarReturnsNull() {
		JsonHub v = sample();
		assertNull(v.get("a", "b", "c"));
		assertNull(v.get("a", "zz"));
		assertNull(v.get("zz", "b"));
	}

	@Test
	@DisplayName("get(CharSequence) returns null when missing")
	void getByNameMissing() {
		JsonHub v = sample();
		assertNull(v.get("zz"));
		assertEquals("{}", v.getOrDefault("zz").toJson());
		assertEquals("{}", v.getOrDefault("zz", builder.emptyObject()).toJson());
		assertEquals(42, v.get("a", "b").intValue());
	}

	@Test
	@DisplayName("containsKey and keySet use unescaped names")
	void containsKeyAndKeySet() {
		JsonHub v = JsonHub.fromJson("{\"a\\u0062\":1,\"c\":2}");
		assertTrue(v.containsKey("ab"));
		assertTrue(v.containsKey("c"));
		assertFalse(v.containsKey("zz"));
		assertEquals(2, v.keySet().size());
	}

	@Test
	@DisplayName("array get(int) out of bounds throws JsonHubIndexOutOfBoundsException")
	void arrayIndexOutOfBounds() {
		JsonHub v = JsonHub.fromJson("[1,2]");
		assertThrows(JsonHubIndexOutOfBoundsException.class, () -> v.get(5));
		assertThrows(JsonHubIndexOutOfBoundsException.class, () -> v.get(-1));
		assertEquals(1, v.get(0).intValue());
	}

	@Test
	@DisplayName("unsupported operations on ARRAY throw with bounded message")
	void unsupportedOperationMessageBounded() {
		JsonHub arr = JsonHub.fromJson("[1]");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 1000; ++i) {
			sb.append('n');
		}
		JsonHubUnsupportedOperationException e = assertThrows(JsonHubUnsupportedOperationException.class,
				() -> arr.get(sb.toString()));
		assertTrue(e.getMessage().length() < 400, "message length: " + e.getMessage().length());
		assertThrows(JsonHubUnsupportedOperationException.class, () -> arr.get("x", "y"));
	}

	@Test
	@DisplayName("builder.build(unsupported type) throws with bounded message")
	void buildUnsupportedMessageBounded() {
		Object huge = new Object() {
			@Override
			public String toString() {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < 1000; ++i) {
					sb.append('z');
				}
				return sb.toString();
			}
		};
		JsonHubBuildException e = assertThrows(JsonHubBuildException.class, () -> builder.build(huge));
		assertTrue(e.getMessage().length() < 400, "message length: " + e.getMessage().length());
	}

	@Test
	@DisplayName("equals across types")
	void equalsAcrossTypes() {
		assertEquals(JsonHub.fromJson("[1,\"a\"]"), JsonHub.fromJson("[1,\"a\"]"));
		assertEquals(JsonHub.fromJson("{\"a\":1}"), JsonHub.fromJson("{\"a\":1}"));
		assertNotEquals(JsonHub.fromJson("[1]"), JsonHub.fromJson("{\"a\":1}"));
		assertNotEquals(JsonHub.fromJson("1"), JsonHub.fromJson("\"1\""));
		assertEquals(JsonHub.fromJson("1").hashCode(), JsonHub.fromJson("1").hashCode());
	}

	@Test
	@DisplayName("prettyPrint uses System.lineSeparator by default")
	void prettyPrintDefault() {
		String ls = System.lineSeparator();
		JsonHub v = JsonHub.fromJson("{\"a\":[1,2]}");
		assertEquals("{" + ls + "  \"a\": [" + ls + "    1," + ls + "    2" + ls + "  ]" + ls + "}",
				v.prettyPrint());
	}

	@Test
	@DisplayName("prettyPrint honors custom config")
	void prettyPrintCustomConfig() {
		JsonHubPrettyPrinterConfig cfg = JsonHubPrettyPrinterConfig.defaultConfig();
		cfg.indent("\t");
		cfg.lineSeparator("\n");
		cfg.suffixNameSeparator("");
		JsonHub v = JsonHub.fromJson("{\"a\":1}");
		assertEquals("{\n\t\"a\":1\n}", v.prettyPrint(cfg));
	}

	@Test
	@DisplayName("compact printer and none-null printer")
	void compactPrinters() {
		JsonHub v = JsonHub.fromJson("{\"a\":null,\"b\":[1,null,2]}");
		assertEquals("{\"a\":null,\"b\":[1,null,2]}", JsonHubPrettyPrinter.getCompactPrinter().print(v));
		assertEquals("{\"b\":[1,null,2]}",
				JsonHubPrettyPrinter.getNoneNullValueInObjectCompactPrinter().print(v));
		assertEquals("{\"b\":[1,null,2]}", v.toJsonExcludedNullValueInObject());
	}

	@Test
	@DisplayName("getBytes / writeBytes and their excluded-null variants")
	void bytesApis() throws IOException {
		JsonHub v = JsonHub.fromJson("{\"a\":null,\"b\":\"\\u4e2d\"}");
		byte[] bs = v.getBytes();
		assertEquals("{\"a\":null,\"b\":\"\\u4e2d\"}", new String(bs, StandardCharsets.UTF_8));

		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		v.writeBytes(bos);
		assertArrayEquals(bs, bos.toByteArray());

		byte[] ex = v.getBytesExcludedNullValueInObject();
		assertEquals("{\"b\":\"\\u4e2d\"}", new String(ex, StandardCharsets.UTF_8));

		ByteArrayOutputStream bos2 = new ByteArrayOutputStream();
		v.writeBytesExcludedNullValueInObject(bos2);
		assertArrayEquals(ex, bos2.toByteArray());

		/* defensive copy: mutating the returned array must not corrupt the cache */
		bs[0] = 'X';
		assertEquals('{', v.getBytes()[0]);
	}

	@Test
	@DisplayName("fromBytes / fromFile-ish entry points")
	void fromBytes() throws IOException {
		JsonHub v = JsonHub.fromBytes("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
		assertEquals(1, v.get("a").intValue());
		JsonHub v2 = JsonHub.fromBytes(new ByteArrayInputStream("[1]".getBytes(StandardCharsets.UTF_8)));
		assertEquals(1, v2.length());
	}

	@Test
	@DisplayName("empty string value is the shared singleton")
	void emptyStringSingleton() {
		assertSame(builder.string(""), builder.string(""));
		assertSame(builder.emptyArray(), builder.array());
		assertSame(builder.emptyObject(), builder.object());
	}

	@Test
	@DisplayName("concurrent printing on shared singleton printers stays correct (no global lock)")
	void concurrentPrinting() throws Exception {
		final JsonHub v = JsonHub.fromJson("{\"a\":[1,2,{\"b\":\"\\u4e2d\"}],\"c\":null,\"d\":true}");
		final String expectedPretty = v.prettyPrint();
		final String expectedCompact = v.toJson();
		final JsonHubPrettyPrinter defaultPrinter = JsonHubPrettyPrinter.getDefaultPrinter();
		final JsonHubPrettyPrinter compactPrinter = JsonHubPrettyPrinter.getCompactPrinter();

		int threads = 8;
		int iterations = 50;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Callable<Boolean>> tasks = new ArrayList<>();
			for (int t = 0; t < threads; ++t) {
				tasks.add(() -> {
					for (int i = 0; i < iterations; ++i) {
						if (!expectedPretty.equals(defaultPrinter.print(v))) {
							return Boolean.FALSE;
						}
						if (!expectedCompact.equals(compactPrinter.print(v))) {
							return Boolean.FALSE;
						}
						if (!expectedCompact.equals(v.toJson())) {
							return Boolean.FALSE;
						}
					}
					return Boolean.TRUE;
				});
			}
			for (Future<Boolean> f : pool.invokeAll(tasks)) {
				assertTrue(f.get());
			}
		}
		finally {
			pool.shutdown();
		}
	}
}
