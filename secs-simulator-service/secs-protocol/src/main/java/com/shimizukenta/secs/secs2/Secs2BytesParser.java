package com.shimizukenta.secs.secs2;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Secs2BytesParser {
	
	/**
	 * Maximum nesting depth of SECS-II items.
	 * 
	 * <p>
	 * Kept in sync with the other parsers of this project (64 levels).<br />
	 * A deeper nesting is refused with {@link Secs2BytesParseException}
	 * instead of {@link StackOverflowError}.
	 * </p>
	 */
	private static final int MAX_DEPTH = 64;
	
	private Secs2BytesParser() {
	}
	
	private static class SingletonHolder {
		private static final Secs2BytesParser inst = new Secs2BytesParser();
	}
	
	public static Secs2BytesParser getInstance() {
		return SingletonHolder.inst;
	}

	public Secs2 parse(List<byte[]> bss) throws Secs2BytesParseException {
		
		final BytesPack pack = new BytesPack(bss);
		
		if ( pack.hasRemaining() ) {
			
			Secs2 ss = stpParse(pack, 0);
			
			if ( pack.hasRemaining() ) {
				throw new Secs2BytesParseException("not reach end bytes");
			}
			
			return ss;
			
		} else {
			
			return new Secs2RawBytes();
		}
	}
	
	/**
	 * @param depth list-nesting depth. {@code 0} if the item is top-level
	 */
	private static Secs2 stpParse(BytesPack pack, int depth) throws Secs2BytesParseException {
		
		byte b = pack.get();
		
		Secs2Item s2i = Secs2Item.get(b);
		int lengthBits = b & 0x03;
		int size = 0;
		
		if ( lengthBits == 3 ) {
			
			size =  ((int)(pack.get()) << 16) & 0x00FF0000;
			size |= ((int)(pack.get()) <<  8) & 0x0000FF00;
			size |= ((int)(pack.get())      ) & 0x000000FF;
			
		} else if ( lengthBits == 2 ) {
			
			size =  ((int)(pack.get()) <<  8) & 0x0000FF00;
			size |= ((int)(pack.get())      ) & 0x000000FF;
			
		} else if ( lengthBits == 1 ) {
			
			size =  ((int)(pack.get())      ) & 0x000000FF;
		}
		
		if ( s2i == Secs2Item.LIST ) {
			
			if ( depth >= MAX_DEPTH ) {
				throw new Secs2BytesParseException(
						"reach maximum nesting depth: " + MAX_DEPTH);
			}
			
			List<Secs2> ll = new ArrayList<>();
			
			for (int i = 0 ; i < size ; ++i) {
				ll.add(stpParse(pack, depth + 1));
			}
			
			return new Secs2List(ll);
			
		} else {
			
			byte[] bs = pack.get(size);
			
			switch ( s2i ) {
			case ASCII: {
				return new Secs2Ascii(bs);
				/* break */
			}
			case BINARY: {
				return new Secs2Binary(bs);
				/* break */
			}
			case BOOLEAN: {
				return new Secs2Boolean(bs);
				/* break */
			}
			case INT1: {
				return new Secs2Int1(bs);
				/* break */
			}
			case INT2: {
				return new Secs2Int2(bs);
				/* break */
			}
			case INT4: {
				return new Secs2Int4(bs);
				/* break */
			}
			case INT8: {
				return new Secs2Int8(bs);
				/* break */
			}
			case UINT1: {
				return new Secs2Uint1(bs);
				/* break */
			}
			case UINT2: {
				return new Secs2Uint2(bs);
				/* break */
			}
			case UINT4: {
				return new Secs2Uint4(bs);
				/* break */
			}
			case UINT8: {
				return new Secs2Uint8(bs);
				/* break */
			}
			case FLOAT4: {
				return new Secs2Float4(bs);
				/* break */
			}
			case FLOAT8: {
				return new Secs2Float8(bs);
				/* break */
			}
			case JIS8: {
				return new Secs2Jis8(bs);
				/* break */
			}
			case UNICODE: {
				return new Secs2Unicode(bs);
				/* break */
			}
			default: {
				throw new Secs2UnsupportedDataFormatException();
			}
			}
		}
	}
	
	private static class BytesPack {
		
		/**
		 * Maximum bytes of a single non-list item.
		 * 
		 * <p>
		 * The 3-bytes length encoding declares at most {@code 0xFFFFFF},
		 * this bound refuses an out-of-range size before allocating.
		 * </p>
		 */
		private static final int MAX_ITEM_SIZE = 16 * 1024 * 1024;
		
		private final List<byte[]> bss;
		private final int mPack;
		private int mBytes;
		private int iPack;
		private int iBytes;
		
		private BytesPack(List<byte[]> bss) {
			this.bss = bss.stream()
					.filter(bs -> bs.length > 0)
					.collect(Collectors.toList());
			this.mPack = this.bss.size() - 1;
			this.mBytes = this.mPack < 0 ? -1 : (this.bss.get(0).length - 1);
			this.iPack = 0;
			this.iBytes = 0;
		}
		
		public boolean hasRemaining() {
			
			if ( iPack < mPack ) {
			
				return true;
				
			} else if ( iPack == mPack ) {
				
				if ( iBytes <= mBytes ) {
					return true;
				}
			}
			
			return false;
		}
		
		public long remaining() {
			
			if ( iPack > mPack ) {
				return 0L;
			}
			
			long n = (long)(this.bss.get(iPack).length) - (long)iBytes;
			
			for ( int i = iPack + 1 ; i <= mPack ; ++i ) {
				n += this.bss.get(i).length;
			}
			
			return n;
		}
		
		public byte get() throws Secs2BytesParseException {
			
			while ( iPack <= mPack ) {
				
				if ( iBytes > mBytes ) {
					
					++ iPack;
					
					if ( iPack <= mPack ) {
						iBytes = 0;
						mBytes = this.bss.get(iPack).length - 1;
					}
					
				} else {
					
					byte b = (this.bss.get(iPack))[iBytes];
					++ iBytes;
					return b;
				}
			}
			
			throw new Secs2BytesParseException("reach end bytes");
		}
		
		public byte[] get(int size) throws Secs2BytesParseException {
			
			/* validate before allocating */
			if ( (size < 0) || (size > MAX_ITEM_SIZE) ) {
				throw new Secs2BytesParseException("too big item size: " + size);
			}
			
			if ( size > remaining() ) {
				throw new Secs2BytesParseException(
						"not enough bytes: required " + size + ", remaining " + remaining());
			}
			
			byte[] bs = new byte[size];
			for (int i = 0; i < size; ++i ) {
				bs[i] = get();
			}
			return bs;
		}
	}
	
}
