package com.shimizukenta.secssim.runtime.session;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import com.shimizukenta.secssim.core.value.ValueSource;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonHub;

/**
 * 曲线预览的分桶计算与序列化（JSON / SCSP v1 二进制）。
 *
 * <p>从 HsmsSessionRuntime 拆出的纯计算块：输入只有值来源与设备模型，
 * 不碰协议状态——这让它可以单独测试，也让运行时类少掉 200 行。
 * 而 min/max 双值能保留尖峰（故障注入要看得见），avg 用于看趋势。</p>
 */
public final class PreviewSupport {

    /** 二进制预览格式版本。 */
    private static final short PREVIEW_BINARY_VERSION = 1;

    /** 二进制固定头长度。 */
    static final int PREVIEW_BINARY_HEADER_BYTES = 48;

    private PreviewSupport() {
    }

    /** 一条序列的分桶统计。 */
    public record PreviewSeries(int svid, String owner, String unit,
                         double[] min, double[] max, double[] avg) {
    }

    /** 一次预览的完整结果。 */
    public record PreviewData(long fromMs, long toMs, int buckets, long bucketMs,
                       int svidTotal, List<PreviewSeries> series) {
    }

    /**
     * 分桶计算 min/max/avg。
     *
     * @param values  值来源
     * @param model   设备模型（取归属与单位）
     * @param fromMs  起始时刻
     * @param toMs    结束时刻
     * @param buckets 桶数（自动收敛到 1..500）
     * @return 预览结果
     */
    public static PreviewData compute(ValueSource values, ResolvedEquipment model,
                               long fromMs, long toMs, int buckets) {
        int count = Math.max(1, Math.min(500, buckets));
        long span = Math.max(1L, toMs - fromMs);
        long bucketMs = Math.max(1L, span / count);
        long sampleMs = Math.max(1L, bucketMs / 4L);

        List<Integer> allSvids = values.svids().stream().sorted().toList();
        List<Integer> svids = allSvids.stream().limit(32).toList();
        List<PreviewSeries> series = new ArrayList<>();

        for (int svid : svids) {
            double[] min = new double[count];
            double[] max = new double[count];
            double[] avg = new double[count];
            java.util.Arrays.fill(min, Double.POSITIVE_INFINITY);
            java.util.Arrays.fill(max, Double.NEGATIVE_INFINITY);

            for (int i = 0; i < count; i++) {
                long bucketFrom = fromMs + i * bucketMs;
                long bucketTo = Math.min(toMs, bucketFrom + bucketMs);
                double sum = 0.0;
                int n = 0;

                for (long t = bucketFrom; t <= bucketTo; t += sampleMs) {
                    OptionalDouble value = values.valueAt(svid, t);
                    if (value.isPresent()) {
                        double v = value.getAsDouble();
                        min[i] = Math.min(min[i], v);
                        max[i] = Math.max(max[i], v);
                        sum += v;
                        n++;
                    }
                }

                if (n == 0) {
                    min[i] = 0.0;
                    max[i] = 0.0;
                }
                avg[i] = n == 0 ? 0.0 : sum / n;
            }

            ResolvedSvid resolved = model.svids().get(svid);
            series.add(new PreviewSeries(svid,
                    resolved == null ? "" : resolved.owner(),
                    resolved == null ? "" : resolved.unitSymbol(),
                    min, max, avg));
        }

        return new PreviewData(fromMs, toMs, count, bucketMs, allSvids.size(), series);
    }

    /**
     * 渲染为 JSON。
     *
     * @param data 预览结果
     * @return JSON 文本
     */
    public static String toJson(PreviewData data) {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> series = new ArrayList<>();

        for (PreviewSeries value : data.series()) {
            series.add(b.object(
                    b.pair("svid", value.svid()),
                    b.pair("owner", value.owner()),
                    b.pair("unit", value.unit()),
                    b.pair("min", b.array(numbers(value.min(), b))),
                    b.pair("max", b.array(numbers(value.max(), b))),
                    b.pair("avg", b.array(numbers(value.avg(), b)))));
        }

        return b.object(
                b.pair("from", data.fromMs()),
                b.pair("to", data.toMs()),
                b.pair("buckets", data.buckets()),
                b.pair("bucketMs", data.bucketMs()),
                b.pair("svidTotal", data.svidTotal()),
                b.pair("truncated", data.svidTotal() > data.series().size()),
                b.pair("series", b.array(series))).toJson();
    }

    /**
     * 渲染为 SCSP v1 二进制（小端）。
     *
     * <pre>
     * magic 4 字节 "SCSP"
     * version u16 = 1
     * flags   u16 = 0
     * from    i64
     * to      i64
     * buckets u32
     * bucketMs u64
     * svidTotal u32
     * seriesCount u32
     * reserved u32
     * 每条序列：svid i32, min f64[buckets], max f64[buckets], avg f64[buckets]
     * </pre>
     *
     * @param data 预览结果
     * @return 二进制正文
     */
    static byte[] toBinary(PreviewData data) {
        int count = data.buckets();
        int seriesCount = data.series().size();
        int size = PREVIEW_BINARY_HEADER_BYTES
                + seriesCount * (Integer.BYTES + 3 * count * Double.BYTES);
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);

        buffer.put((byte) 'S').put((byte) 'C').put((byte) 'S').put((byte) 'P');
        buffer.putShort(PREVIEW_BINARY_VERSION);
        buffer.putShort((short) 0);
        buffer.putLong(data.fromMs());
        buffer.putLong(data.toMs());
        buffer.putInt(count);
        buffer.putLong(data.bucketMs());
        buffer.putInt(data.svidTotal());
        buffer.putInt(seriesCount);
        buffer.putInt(0);

        for (PreviewSeries value : data.series()) {
            buffer.putInt(value.svid());
            for (double v : value.min()) {
                buffer.putDouble(v);
            }
            for (double v : value.max()) {
                buffer.putDouble(v);
            }
            for (double v : value.avg()) {
                buffer.putDouble(v);
            }
        }
        return buffer.array();
    }

    private static List<JsonHub> numbers(double[] values, JsonHubBuilder b) {
        List<JsonHub> list = new ArrayList<>(values.length);
        for (double value : values) {
            list.add(b.number(Math.round(value * 1000.0) / 1000.0));
        }
        return list;
    }
}
