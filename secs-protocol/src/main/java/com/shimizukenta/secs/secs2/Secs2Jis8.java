package com.shimizukenta.secs.secs2;

import java.util.Arrays;
import java.util.Objects;

public class Secs2Jis8 extends AbstractSecs2 {
	
	private static final long serialVersionUID = 4496230020131931885L;

	private static final Secs2Item secs2Item = Secs2Item.JIS8;
	
	private byte[] bytes;
	
	protected Secs2Jis8(byte[] bs) {
		super();
		
		Objects.requireNonNull(bs);
		
		this.bytes = Arrays.copyOf(bs, bs.length);
	}

	private synchronized byte[] bytes() {
		
		if ( bytes == null ) {
			
			bytes = new byte[0];
		}
		
		return bytes;
	}

	@Override
	public int size() {
		return bytes().length;
	}
	
	@Override
	protected void putBytesPack(Secs2BytesPackBuilder builder) throws Secs2BuildException {
		this.putHeadAndBodyBytesToBytesPack(builder, bytes());
	}
	
	@Override
	public Secs2Item secs2Item() {
		return secs2Item;
	}
	
	@Override
	protected String toJsonValue() {
		/* 未支持解码的类型输出 JSON null：原来返回字面量 false，
		   报文详情里会显示成"false"，被误读成布尔值 */
		return "null";
	}
	
	@Override
	protected String toStringValue() {
		return "NOT_SUPPORT";
	}
	
}
