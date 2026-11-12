package com.shimizukenta.secssim.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 正文 SHA-256（迁移自检与 config_document 的 content_sha256 用）。
 *
 * <p>迁移自检的判据是"逐字节相符"：导入前后各算一次哈希比对，
 * 而不是肉眼比字符串。</p>
 */
public final class ContentHash {

    private ContentHash() {
    }

    /**
     * 计算文本的 SHA-256 十六进制串（UTF-8 字节）。
     *
     * @param text 正文
     * @return 64 位十六进制（小写）
     */
    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new StoreException("JVM 缺少 SHA-256", e);
        }
    }
}
