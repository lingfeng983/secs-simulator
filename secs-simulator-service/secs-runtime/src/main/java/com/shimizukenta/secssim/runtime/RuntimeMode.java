package com.shimizukenta.secssim.runtime;

/**
 * 运行形态。
 *
 * <p>三种形态共用<b>同一套</b>内核、API 与前端，差别只有绑定地址与配置目录：
 * </p>
 * <ul>
 *   <li>{@link #INTEGRATED}：桌面一体化。随壳启动，只绑回环，单用户。</li>
 *   <li>{@link #SERVER}：Linux 服务端。对外绑定；提供 {@code --tls-keystore} 时走 HTTPS，
 *        否则仍是明文 HTTP。内部工具不做鉴权，直连访问。</li>
 *   <li>{@link #PROBE}：能力探针。在目标机器上验证 SECS 端口与异步 I/O 是否可用。</li>
 * </ul>
 */
public enum RuntimeMode {

    /** 一体化：随桌面壳启动，仅供本机访问。 */
    INTEGRATED("integrated"),

    /** 服务端：常驻 Linux，供远程客户端访问。 */
    SERVER("server"),

    /** 探针：开一个 HSMS 被动端口并应答 S1F1，用于现场排障与 CI。 */
    PROBE("probe");

    private final String id;

    RuntimeMode(String id) {
        this.id = id;
    }

    /**
     * 命令行标识。
     *
     * @return 小写标识
     */
    public String id() {
        return this.id;
    }

    /**
     * 按命令行标识解析，未知时回退到 {@link #INTEGRATED}。
     *
     * @param id 标识
     * @return 形态
     */
    public static RuntimeMode fromId(String id) {
        String v = id == null ? "" : id.trim().toLowerCase();
        for (RuntimeMode mode : values()) {
            if (mode.id.equals(v)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown mode: " + id + "（可选 integrated / server / probe）");
    }
}
