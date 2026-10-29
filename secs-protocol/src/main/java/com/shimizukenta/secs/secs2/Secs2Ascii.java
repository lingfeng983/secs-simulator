package com.shimizukenta.secs.secs2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * ASCII 数据项（SECS-II 格式码 0x20）。
 *
 * <p><b>编解码固定用 US-ASCII</b>：收到 ≥0x80 的字节会变成 {@code U+FFFD}，
 * 再编码回去是 {@code '?'}——长度不变、内容被改。SECS 的 A 项按标准就是 7 位
 * ASCII，现场若有人往里塞 GBK/UTF-8 字节，视图与回发都会失真。</p>
 *
 * <p>另外 {@link #size()} 返回的是<b>字符数</b>而不是字节数：对 A 项两者相等，
 * 但不要把 size() 当作线上的字节长度去做长度校验（历史评审在这里踩过）。</p>
 */
public class Secs2Ascii extends AbstractSecs2 {
	
	private static final long serialVersionUID = -1511335317630097868L;
	
	private static Secs2Item secs2Item = Secs2Item.ASCII;
	private static Charset charset = StandardCharsets.US_ASCII;

	private String ascii;
	private byte[] bytes;
	
	protected Secs2Ascii(CharSequence cs) {
		super();
		
		this.ascii = Objects.requireNonNull(cs).toString();
		this.bytes = null;
	}
	
	protected Secs2Ascii(byte[] bs) {
		super();
		
		Objects.requireNonNull(bs);
		
		this.ascii = null;
		this.bytes = Arrays.copyOf(bs, bs.length);
	}
	
	@Override
	public int size() {
		return ascii().length();
	}
	
	@Override
	protected void putBytesPack(Secs2BytesPackBuilder builder) throws Secs2BuildException {
		putHeadAndBodyBytesToBytesPack(builder, bytes());
	}
	
	private synchronized String ascii() {
		if ( this.ascii == null ) {
			this.ascii = new String(bytes, charset);
		}
		
		return this.ascii;
	}
	
	private synchronized byte[] bytes() {
		if ( this.bytes == null ) {
			this.bytes = ascii.getBytes(charset);
		}
		
		return this.bytes;
	}
	
	@Override
	public String getAscii() throws Secs2Exception {
		return ascii();
	}
	
	@Override
	public Secs2Item secs2Item() {
		return secs2Item;
	}
	
	@Override
	protected String toJsonValue() {
		return "\"" + escapeJsonString(ascii()) + "\"";
	}
	
	
	private static final byte BACKSLASH = 0x5C;
	private static final byte[] BS = new byte[]{BACKSLASH, 0x62};	/* \\b */
	private static final byte[] HT = new byte[]{BACKSLASH, 0x74};	/* \\t */
	private static final byte[] LF = new byte[]{BACKSLASH, 0x6E};	/* \\n */
	private static final byte[] FF = new byte[]{BACKSLASH, 0x66};	/* \\f */
	private static final byte[] CR = new byte[]{BACKSLASH, 0x72};	/* \\r */
	private static final byte MAX_SHORT_ESCAPE = 0x1F;

	private static final String escapeJsonString(CharSequence cs) {
		
		try (
				ByteArrayOutputStream strm = new ByteArrayOutputStream();
				) {
			
			byte[] bb = cs.toString().getBytes(StandardCharsets.US_ASCII);
			
			for (byte b : bb) {
				
				if (b == 0x08 /* BS */) {
					
					strm.write(BS);
					
				} else if (b == 0x09 /* HT */) {
					
					strm.write(HT);
					
				} else if (b == 0x0A /* LF */) {
					
					strm.write(LF);
					
				} else if (b == 0x0C /* FF */) {
					
					strm.write(FF);
					
				} else if (b == 0x0D /* CR */) {
					
					strm.write(CR);
					
				} else if (
						b == 0x22 /* " */
						|| b == 0x2F /* / */
						|| b == BACKSLASH /* \\ */
						) {
					
					strm.write(BACKSLASH);
					strm.write(b);
					
				} else if (
						(b >= 0x00)
						&& (b <= MAX_SHORT_ESCAPE)
						) {
					
				/* other control characters: unicode-escaped (u00XX) */
				strm.write(BACKSLASH);
				strm.write(0x75 /* u */);
				strm.write(String.format("%04X", b).getBytes(StandardCharsets.US_ASCII));
					
				} else {
					
					strm.write(b);
				}
			}
			
			return new String(strm.toByteArray(), StandardCharsets.US_ASCII);
		}
		catch (IOException notHappen) {
			notHappen.printStackTrace();
		}
		
		return "";
	}
	
	@Override
	protected String toStringValue() {
		return "\"" + ascii() + "\"";
	}
}
