package com.shimizukenta.secssim.store;

import java.math.BigInteger;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 持久实体 id（ULID 形状：Crockford Base32，26 字符，128 bit）。
 *
 * <p>3.0 里设备 / 主机 / 主机模板都是持久实体：id 一旦生成不再改变，
 * 重启后仍然有效（旧版主机 id 是进程内计数器 {@code host-N}，重启即变，
 * 见设计 §0.6）。布局为 48 bit 毫秒时间 + 80 bit 随机；同毫秒内随机低位
 * 单调 +1，保证同一进程内生成的 id 严格递增，列表默认顺序按时间稳定。</p>
 */
public final class Ids {

    /** Crockford Base32 字母表（无 I L O U）。 */
    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final BigInteger THIRTY_TWO = BigInteger.valueOf(32);
    private static final BigInteger MASK_128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
    private static final long MASK_48 = (1L << 48) - 1;

    /** 进程级随机高 32 位：同毫秒碰撞概率 = 2^-32 × 同毫秒数，足够。 */
    private static final long RANDOM_HIGH = ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFL;

    private static final Object MONOTONIC_LOCK = new Object();
    private static long lastMillis = 0L;
    private static long lastRandomLow = 0L;

    private Ids() {
    }

    /**
     * 生成一枚 ULID（26 字符）。
     *
     * @return 形如 {@code 01J…} 的 id
     */
    public static String next() {
        long millis;
        long randomLow;
        synchronized (MONOTONIC_LOCK) {
            long now = System.currentTimeMillis();
            if (now <= lastMillis) {
                /* 时钟回拨或同毫秒连发：沿用上一毫秒并推进随机段，保单调 */
                millis = lastMillis;
                randomLow = (lastRandomLow + 1) & MASK_48;
            } else {
                millis = now;
                randomLow = ThreadLocalRandom.current().nextLong(MASK_48 + 1);
            }
            lastMillis = millis;
            lastRandomLow = randomLow;
        }

        BigInteger value = BigInteger.valueOf(millis & MASK_48).shiftLeft(80)
                .or(BigInteger.valueOf(RANDOM_HIGH).shiftLeft(48))
                .or(BigInteger.valueOf(randomLow))
                .and(MASK_128);

        char[] chars = new char[26];
        for (int i = 25; i >= 0; i--) {
            chars[i] = ENCODING[value.mod(THIRTY_TWO).intValue()];
            value = value.shiftRight(5);
        }
        return new String(chars);
    }

    /** 设备实体 id（{@code dev_} 前缀）。 */
    public static String nextDevice() {
        return "dev_" + next();
    }

    /** 主机实体 id（{@code host_} 前缀）。 */
    public static String nextHost() {
        return "host_" + next();
    }

    /** 主机模板 id（{@code ht_} 前缀）。 */
    public static String nextHostTemplate() {
        return "ht_" + next();
    }
}
