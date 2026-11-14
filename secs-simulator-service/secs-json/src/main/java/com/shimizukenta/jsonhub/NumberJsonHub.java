package com.shimizukenta.jsonhub;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * This class is implements of JSON value NUMBER.
 * 
 * <p>
 * Instances of this class are immutable.
 * </p>
 * <p>
 * Number-String is parsed as follows.<br />
 * Only the RFC 8259 number grammar is accepted
 * ({@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?});
 * {@code +1}, {@code 007}, {@code .5} and {@code 1.} are rejected.<br />
 * A value without {@code '.'}, {@code 'e'} or {@code 'E'} is parsed as a
 * signed 64-bit {@code long}; out of range is rejected.<br />
 * Anything with a fraction or exponent is parsed as IEEE-754 binary64
 * {@code double}.<br />
 * Serialization emits the canonical {@code Long/Double.toString} form,
 * so {@code toJson()} always produces valid JSON.<br />
 * Non-finite values ({@code NaN}, {@code Infinity}) are not
 * representable in JSON and are rejected.<br />
 * </p>
 * 
 * @author kenta-shimizu
 *
 */
public class NumberJsonHub extends AbstractJsonHub {
	
	private static final long serialVersionUID = -2924637446448005150L;
	
	private final Number num;
	private final String str;
	
	/**
	 * RFC 8259 的 JSON number 文法。
	 *
	 * <p>{@code Long/Double.valueOf} 会接受 {@code +1}、{@code 007}、{@code .5}、{@code 1.}——
	 * 这些都不是合法 JSON。解析时放过、序列化时原样吐出，等于让"本进程能读、
	 * 浏览器/第三方工具读不了"的文件从配置编辑器流出去。</p>
	 */
	private static final java.util.regex.Pattern JSON_NUMBER =
			java.util.regex.Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
	
	protected NumberJsonHub(CharSequence cs) {
		super();
		
		final String raw = Objects.requireNonNull(cs).toString();
		if ( ! JSON_NUMBER.matcher(raw).matches() ) {
			throw new JsonHubNumberFormatException("invalid JSON number: " + raw);
		}
		
		final Number parsed;
		try {
			if ( raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0 ) {
				parsed = Double.valueOf(raw);
			} else {
				parsed = Long.valueOf(raw);
			}
		}
		catch ( NumberFormatException e ) {
			throw new JsonHubNumberFormatException(e.getMessage());
		}
		
		validateFinite(parsed);
		this.num = parsed;
		/* 输出规范化文本，而不是原字面量：保证 toJson() 的产物一定是合法 JSON。 */
		this.str = parsed.toString();
	}
	
	protected NumberJsonHub(Number num) {
		super();
		
		this.num = Objects.requireNonNull(num);
		
		validateFinite(this.num);
		
		this.str = this.num.toString();
	}
	
	private static void validateFinite(Number num) {
		
		if ( (num instanceof Double) || (num instanceof Float) ) {
			
			double d = num.doubleValue();
			
			if ( Double.isNaN(d) || Double.isInfinite(d) ) {
				
				throw new JsonHubNumberFormatException("not finite NUMBER: " + num);
			}
		}
	}
	
	@Override
	public JsonHubType type() {
		return JsonHubType.NUMBER;
	}
	
	@Override
	public OptionalInt optionalInt() {
		return OptionalInt.of(num.intValue());
	}
	
	@Override
	public OptionalLong optionalLong() {
		return OptionalLong.of(num.longValue());
	}
	
	@Override
	public OptionalDouble optionalDouble() {
		return OptionalDouble.of(num.doubleValue());
	}
	
	@Override
	public Optional<Number> optionalNubmer() {
		return Optional.of(num);
	}
	
	@Override
	public String toJson() {
		return str;
	}
	
	@Override
	public void toJson(Writer writer) throws IOException {
		writer.write(str);
	}
	
	@Override
	public String toJsonExcludedNullValueInObject() {
		return toJson();
	}
	
	@Override
	public void toJsonExcludedNullValueInObject(Writer writer) throws IOException {
		toJson(writer);
	}
	
	@Override
	public String toString() {
		return str;
	}
	
	@Override
	public boolean equals(Object o) {
		if ((o != null) && (o instanceof NumberJsonHub)) {
			return ((NumberJsonHub) o).num.equals(num);
		} else {
			return false;
		}
	}
	
	@Override
	public int hashCode() {
		return num.hashCode();
	}
	
}
