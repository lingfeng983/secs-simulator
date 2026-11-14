package com.shimizukenta.secs.secs2;

/**
 * SECS-II 数值超出该格式的值域。
 *
 * <p>{@code ByteBuffer.putXxx} 会静默截断：U1 的 300 会被编码成 44，主机收到
 * 另一个数却没有任何报错。SECS-II 的格式码决定了值域，越界必须在编码前拒绝。</p>
 *
 * <p>用非受检异常是有意的：{@code AbstractSecs2.bytes()} 的签名不声明受检异常，
 * 且这类错误等价于"调用方给了非法值"，与 {@link IllegalArgumentException} 同义。</p>
 */
public class Secs2ValueOutOfRangeException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	private final int sizeBytes;

	public Secs2ValueOutOfRangeException(String message, int sizeBytes) {
		super(message);
		this.sizeBytes = sizeBytes;
	}

	/**
	 * 出错的格式宽度。
	 *
	 * @return 字节数（1/2/4/8）
	 */
	public int sizeBytes() {
		return this.sizeBytes;
	}
}
