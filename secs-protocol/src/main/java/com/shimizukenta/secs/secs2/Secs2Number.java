package com.shimizukenta.secs.secs2;

import java.math.BigInteger;
import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

abstract public class Secs2Number<T extends Number> extends AbstractSecs2 {
	
	private static final long serialVersionUID = -5315163278193292437L;
	
	protected List<T> values;
	protected byte[] bytes;
	
	public Secs2Number() {
		super();
		
		this.values = null;
		this.bytes = null;
	}
	
	abstract protected T byteBufferGetter(ByteBuffer bf);
	abstract protected void byteBufferPutter(ByteBuffer bf, T value);
	
	/**
	 * 无符号整数的编码前校验。
	 *
	 * <p>{@code ByteBuffer.putXxx} 会静默截断：U1 的 300 直接编码成 44，
	 * 主机收到的是另一个数且没有任何报错。SECS-II 的值域是协议的一部分，
	 * 越界必须在这里拒绝，而不是"发出去再说"。
	 * </p>
	 *
	 * @param value 值
	 * @param sizeBytes 该格式的字节宽度
	 * @return 可直接交给 {@code putXxx} 的低位比特（U8 按 64 位补码解释）
	 * @throws Secs2ValueOutOfRangeException 负值或超出无符号值域
	 */
	protected static long unsignedValue(BigInteger value, int sizeBytes) {
		if ( value == null ) {
			throw new Secs2ValueOutOfRangeException("value must not be null", sizeBytes);
		}
		if ( value.signum() < 0 ) {
			throw new Secs2ValueOutOfRangeException(
					"unsigned " + (sizeBytes * 8) + "-bit value must not be negative: " + value, sizeBytes);
		}
		if ( value.bitLength() > sizeBytes * 8 ) {
			throw new Secs2ValueOutOfRangeException(
					"unsigned " + (sizeBytes * 8) + "-bit value out of range: " + value, sizeBytes);
		}
		return value.longValue();
	}
	
	/**
	 * 有符号整数的编码前校验。
	 *
	 * @param value 值
	 * @param sizeBytes 该格式的字节宽度
	 * @return 可直接交给 {@code putXxx} 的值
	 * @throws Secs2ValueOutOfRangeException 超出有符号值域
	 */
	protected static long signedValue(BigInteger value, int sizeBytes) {
		if ( value == null ) {
			throw new Secs2ValueOutOfRangeException("value must not be null", sizeBytes);
		}
		final int bits = sizeBytes * 8;
		final BigInteger min = BigInteger.ONE.shiftLeft(bits - 1).negate();
		final BigInteger max = BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE);
		if ( value.compareTo(min) < 0 || value.compareTo(max) > 0 ) {
			throw new Secs2ValueOutOfRangeException(
					"signed " + bits + "-bit value out of range: " + value, sizeBytes);
		}
		return value.longValue();
	}
	
	protected synchronized List<T> values() throws Secs2Exception {
		
		if ( this.values == null ) {
			
			try {
				ByteBuffer bf = ByteBuffer.allocate(this.bytes.length);
				bf.put(this.bytes);
				((Buffer)bf).flip();
				
				List<T> vv = new ArrayList<>();
				while ( bf.hasRemaining() ) {
					vv.add(byteBufferGetter(bf));
				}
				
				this.values = vv;
			}
			catch ( BufferUnderflowException e ) {
				throw new Secs2Exception(e);
			}
		}
		
		return this.values;
	}
	
	protected synchronized byte[] bytes() {
		
		if ( this.bytes == null ) {
			
			int size = secs2Item().size() * this.values.size();
			
			ByteBuffer bf = ByteBuffer.allocate(size);
			this.values.forEach(v -> { byteBufferPutter(bf, v); });
			((Buffer)bf).flip();
			
			this.bytes = new byte[size];
			bf.get(this.bytes);
		}
		
		return this.bytes;
	}
	
	@Override
	public int size() {
		try {
			return values().size();
		}
		catch ( Secs2Exception e ) {
			return -1;
		}
	}
	
	@Override
	protected void putBytesPack(Secs2BytesPackBuilder builder) throws Secs2BuildException {
		this.putHeadAndBodyBytesToBytesPack(builder, bytes());
	}

	@Override
	protected int getInt(int index) throws Secs2Exception {
		
		try {
			return values().get(index).intValue();
		}
		catch ( IndexOutOfBoundsException e ) {
			throw new Secs2IndexOutOfBoundsException(e);
		}
	}
	
	@Override
	protected long getLong(int index) throws Secs2Exception {
		
		try {
			return values().get(index).longValue();
		}
		catch ( IndexOutOfBoundsException e ) {
			throw new Secs2IndexOutOfBoundsException(e);
		}
	}
	
	@Override
	protected float getFloat(int index) throws Secs2Exception {
		
		try {
			return values().get(index).floatValue();
		}
		catch ( IndexOutOfBoundsException e ) {
			throw new Secs2IndexOutOfBoundsException(e);
		}
	}
	
	@Override
	protected double getDouble(int index) throws Secs2Exception {
		
		try {
			return values().get(index).doubleValue();
		}
		catch ( IndexOutOfBoundsException e ) {
			throw new Secs2IndexOutOfBoundsException(e);
		}
	}
	
	@Override
	protected String toJsonValue() {
		
		try {
			return values().stream()
					.map(Secs2Number::jsonNumber)
					.collect(Collectors.joining(",", "[", "]"));
		}
		catch ( Secs2Exception e ) {
			return "false";
		}
	}
	
	/**
	 * 单个数值的 JSON 文本。
	 *
	 * <p>JSON 没有 NaN/Infinity：直接拼进去会让整条报文变成非法 JSON，
	 * 前端解析失败、报文面板整段空白。按 JSON 惯例输出 {@code null}。</p>
	 */
	private static String jsonNumber(Number value) {
		if ( value instanceof Double d && ! Double.isFinite(d) ) {
			return "null";
		}
		if ( value instanceof Float f && ! Float.isFinite(f) ) {
			return "null";
		}
		return value.toString();
	}
	
	@Override
	protected String toStringValue() {
		try {
			return values().stream()
					.map(Number::toString)
					.collect(Collectors.joining(" "));
		}
		catch ( Secs2Exception e ) {
			return "PARSE_FAILED";
		}
	}
	
}
