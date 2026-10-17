package com.shimizukenta.jsonhub;

/**
 * This class is JSON-String to escape or unescape.
 * 
 * @author kenta-shimizu
 *
 */
public class JsonStringCoder {

	private JsonStringCoder() {
		/* Nothing */
	}
	
	private static class SingletonHolder {
		private static final JsonStringCoder inst = new JsonStringCoder();
	}
	
	/**
	 * Returns coder instance.
	 * 
	 * <p>
	 * This class is Singleton-pattern.
	 * </p>
	 * 
	 * @return coder instance
	 */
	public static JsonStringCoder getInstance() {
		return SingletonHolder.inst;
	}
	
	/**
	 * Returns snippet of {@code s} for exception messages.
	 * 
	 * <p>
	 * The full content is truncated to at most 256 characters
	 * (an ellipsis is appended when truncated),
	 * so that exception messages stay bounded even for huge inputs.
	 * </p>
	 * 
	 * @param s String, {@code null} is not accepted
	 * @return snippet of {@code s}, at most 256 characters
	 */
	static String snippet(String s) {
		
		String v = s.trim();
		
		if ( v.length() > 256 ) {
			return v.substring(0, 253) + "...";
		}
		
		return v;
	}
	
	
	protected static final byte BACKSLASH = 0x5C;	/* \ */
	protected static final byte UNICODE = 0x75;	/* u */
	
	protected enum EscapeSets {
		
		BS(0x08, 0x62),	/* b */
		HT(0x09, 0x74),	/* t */
		LF(0x0A, 0x6E),	/* n */
		FF(0x0C, 0x66),	/* f */
		CR(0x0D, 0x72),	/* r */
		QUOT(0x22, 0x22),	/* " */
		SLASH(0x2F, 0x2F),	/* / */
		BSLASH(BACKSLASH, BACKSLASH), /* \ */
		;
		
		private byte a;
		private byte b;
		
		private EscapeSets(int a, int b) {
			this.a = (byte)a;
			this.b = (byte)b;
		}
		
		public static Byte escape(byte b) {
			
			for ( EscapeSets x : values() ) {
				if ( x.a == b ) {
					return Byte.valueOf(x.b);
				}
			}
			
			return null;
		}
		
		public static Byte unescape(byte b) {
			
			for ( EscapeSets x : values() ) {
				if ( x.b == b ) {
					return Byte.valueOf(x.a);
				}
			}
			
			return null;
		}
	}
	
	/**
	 * Retruns escaped JSON-String.
	 * 
	 * <p>
	 * Not Accept {@code null}.<br />
	 * </p>
	 * <p>
	 * Characters in {@code 0x00} to {@code 0x1F} are escaped as
	 * {@code \b}, {@code \t}, {@code \n}, {@code \f}, {@code \r} when a
	 * short-escape exists, and as {@code U+00XX} otherwise.<br />
	 * Characters in {@code 0x7F} and above are passed through as-is
	 * (encoded in UTF-8 by the String).<br />
	 * </p>
	 * <p>
	 * Invariant: {@code unescape(escape(s)) == s} for any unescaped
	 * JSON-String {@code s} without unpaired surrogates.<br />
	 * </p>
	 * 
	 * @param cs unescaped-JSON-Stirng
	 * @return escaped-JSON-String
	 * @throws JsonHubBuildException if {@code cs} contains an unpaired surrogate
	 */
	public String escape(CharSequence cs) {
		
		String v = cs.toString();
		
		StringBuilder sb = new StringBuilder(v.length() + 16);
		
		for (int i = 0, len = v.length(); i < len; ++i) {
			
			char c = v.charAt(i);
			
			if ( Character.isHighSurrogate(c) ) {
				
				if ( (i + 1) < len && Character.isLowSurrogate(v.charAt(i + 1)) ) {
					
					sb.append(c);
					sb.append(v.charAt(i + 1));
					++i;
					continue;
					
				} else {
					
					throw new JsonHubBuildException("escape failed: unpaired high-surrogate. index: " + i);
				}
			}
			
			if ( Character.isLowSurrogate(c) ) {
				
				throw new JsonHubBuildException("escape failed: unpaired low-surrogate. index: " + i);
			}
			
			if ( c < 0x80 ) {
				
				Byte x = EscapeSets.escape((byte) c);
				
				if ( x == null ) {
					
					if ( c < 0x20 ) {
						
						appendUnicodeEscape(sb, c);
						
					} else {
						
						sb.append(c);
					}
					
				} else {
					
					sb.append((char) BACKSLASH);
					sb.append((char) x.byteValue());
				}
				
			} else {
				
				sb.append(c);
			}
		}
		
		return sb.toString();
	}
	
	/**
	 * Returns unescaped JSON-String.
	 * 
	 * <p>
	 * Not Accept {@code null}.<br />
	 * </p>
	 * <p>
	 * Supported escape sequences are {@code \b}, {@code \t}, {@code \n},
	 * {@code \f}, {@code \r}, {@code \"}, {@code \/}, {@code \\} and
	 * {@code U+XXXX}. A {@code U+XXXX} high-surrogate must be followed by a
	 * {@code U+XXXX} low-surrogate (or a raw low-surrogate character); they
	 * are combined into one supplementary code point.<br />
	 * </p>
	 * <p>
	 * Invariant: {@code escape(unescape(s)) == s} for any escaped
	 * JSON-String {@code s} in canonical form, i.e. as returned by
	 * {@link #escape(CharSequence)}.<br />
	 * </p>
	 * 
	 * @param cs escaped-JSON-String
	 * @return unescaped-JSON-String
	 * @throws JsonHubParseException if {@code cs} contains an illegal escape
	 * sequence, a truncated {@code U+XXXX} sequence, non-hexadecimal digits
	 * or an unpaired surrogate; the message contains the index
	 */
	public String unescape(CharSequence cs) {
		
		String v = cs.toString();
		
		StringBuilder sb = new StringBuilder(v.length());
		
		int pendingHigh = -1;
		int pendingHighIndex = -1;
		
		for (int i = 0, len = v.length(); i < len; ++i) {
			
			char c = v.charAt(i);
			
			if ( c == (char) BACKSLASH ) {
				
				++i;
				
				if ( i >= len ) {
					
					throw new JsonHubParseException("unescape failed: not found escape-character. index: " + (i - 1));
				}
				
				char c2 = v.charAt(i);
				
				if ( c2 == (char) UNICODE ) {
					
					if ( (i + 4) >= len ) {
						
						throw new JsonHubParseException("unescape failed: not found 4 hexadecimal-digits after \\u. index: " + i);
					}
					
					int code = parseHex4(v, i + 1);
					
					i += 4;
					
					if ( Character.isHighSurrogate((char) code) ) {
						
						if ( pendingHigh >= 0 ) {
							
							throw new JsonHubParseException("unescape failed: unpaired high-surrogate. index: " + pendingHighIndex);
						}
						
						pendingHigh = code;
						pendingHighIndex = i - 5;
						
					} else if ( Character.isLowSurrogate((char) code) ) {
						
						if ( pendingHigh < 0 ) {
							
							throw new JsonHubParseException("unescape failed: unpaired low-surrogate. index: " + (i - 5));
						}
						
						sb.append(Character.toChars(Character.toCodePoint((char) pendingHigh, (char) code)));
						pendingHigh = -1;
						pendingHighIndex = -1;
						
					} else {
						
						if ( pendingHigh >= 0 ) {
							
							throw new JsonHubParseException("unescape failed: unpaired high-surrogate. index: " + pendingHighIndex);
						}
						
						sb.append((char) code);
					}
					
				} else if ( c2 < 0x80 ) {
					
					Byte x = EscapeSets.unescape((byte) c2);
					
					if ( x == null ) {
						
						throw new JsonHubParseException("unescape failed: illegal escape-sequence \\" + c2 + ". index: " + i);
					}
					
					if ( pendingHigh >= 0 ) {
						
						throw new JsonHubParseException("unescape failed: unpaired high-surrogate. index: " + pendingHighIndex);
					}
					
					sb.append((char) x.byteValue());
					
				} else {
					
					throw new JsonHubParseException("unescape failed: illegal escape-sequence \\" + c2 + ". index: " + i);
				}
				
			} else if ( Character.isHighSurrogate(c) ) {
				
				if ( pendingHigh >= 0 ) {
					
					throw new JsonHubParseException("unescape failed: unpaired high-surrogate. index: " + pendingHighIndex);
				}
				
				pendingHigh = c;
				pendingHighIndex = i;
				
			} else if ( Character.isLowSurrogate(c) ) {
				
				if ( pendingHigh < 0 ) {
					
					throw new JsonHubParseException("unescape failed: unpaired low-surrogate. index: " + i);
				}
				
				sb.append(Character.toChars(Character.toCodePoint((char) pendingHigh, c)));
				pendingHigh = -1;
				pendingHighIndex = -1;
				
			} else {
				
				if ( pendingHigh >= 0 ) {
					
					throw new JsonHubParseException("unescape failed: unpaired high-surrogate. index: " + pendingHighIndex);
				}
				
				sb.append(c);
			}
		}
		
		if ( pendingHigh >= 0 ) {
			
			throw new JsonHubParseException("unescape failed: unpaired high-surrogate. index: " + pendingHighIndex);
		}
		
		return sb.toString();
	}
	
	private static void appendUnicodeEscape(StringBuilder sb, char c) {
		
		sb.append((char) BACKSLASH);
		sb.append((char) UNICODE);
		sb.append('0');
		sb.append('0');
		sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
		sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
	}
	
	private static int parseHex4(String s, int fromIndex) {
		
		int code = 0;
		
		for (int i = fromIndex, n = fromIndex + 4; i < n; ++i) {
			
			int d = Character.digit(s.charAt(i), 16);
			
			if ( d < 0 ) {
				
				throw new JsonHubParseException("unescape failed: not hexadecimal-digit '" + s.charAt(i) + "'. index: " + i);
			}
			
			code = (code << 4) | d;
		}
		
		return code;
	}

}
