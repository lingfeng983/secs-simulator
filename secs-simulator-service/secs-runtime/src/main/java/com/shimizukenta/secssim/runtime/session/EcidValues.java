package com.shimizukenta.secssim.runtime.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.core.value.DataSource;
import com.shimizukenta.secssim.core.value.SourceEvaluator;

/**
 * 设备常量的取值（schema v7）。
 *
 * <p>v6 的 ECV 是"一个静态值"，只有 S2F15 能改动它。v7 允许常量带
 * {@code source}——可以是常数、绝对时间曲线、也可以是周期曲线，
 * 于是同一个 ECID 在 S2F13 查询与 S6F11 报告里都会随时刻变化。</p>
 *
 * <p>两者必须给出一致的值，所以查询与报文共用这一个取值器：
 * 常量定义提供类型与兜底值，`source` 提供时间函数。</p>
 */
public final class EcidValues {

    private final Map<Integer, EquipmentConstantDef> defs;
    private final Map<Integer, DataSource> sources;
    private final CycleClock clock;
    private final Rng root;

    /**
     * 创建。
     *
     * @param defs    常量定义（类型 + 兜底值）
     * @param sources ECID → 时间函数（可空）
     * @param clock   周期时钟
     * @param seed    噪声种子
     */
    public EcidValues(Map<Integer, EquipmentConstantDef> defs, Map<Integer, DataSource> sources,
                      CycleClock clock, long seed) {
        this.defs = defs == null ? Map.of() : new LinkedHashMap<>(defs);
        this.sources = sources == null ? Map.of() : Map.copyOf(sources);
        this.clock = clock == null ? CycleClock.passthrough() : clock;
        this.root = new Rng(seed).derive("ecid");
    }

    /**
     * 某时刻的值。
     *
     * @param ecid 常量编号
     * @param tMs  时刻
     * @return 值
     */
    public OptionalDouble valueAt(int ecid, long tMs) {
        DataSource source = this.sources.get(ecid);
        if (source != null) {
            return OptionalDouble.of(SourceEvaluator.numeric(source, tMs, this.clock,
                    this.root.derive("ecid:" + ecid)));
        }
        EquipmentConstantDef def = this.defs.get(ecid);
        return def == null ? OptionalDouble.empty() : OptionalDouble.of(def.value());
    }

    /**
     * 是否是"时间函数型"常量。
     *
     * <p>S2F15 只允许写静态常量：给一个随时间变的值写死一个新值，
     * 语义上必然自相矛盾（下一秒它又变了）。</p>
     *
     * @param ecid 常量编号
     * @return 时间变化型返回 {@code true}
     */
    public boolean isTimeVarying(int ecid) {
        DataSource source = this.sources.get(ecid);
        return source != null && !(source instanceof DataSource.Const);
    }

    /**
     * 编码成报文数据项（按常量声明的类型）。
     *
     * @param ecid 常量编号
     * @param tMs  时刻
     * @return 数据项
     */
    public Secs2 encode(int ecid, long tMs) {
        EquipmentConstantDef def = this.defs.get(ecid);
        double value = this.valueAt(ecid, tMs).orElse(0.0);
        if (def == null) {
            return Secs2.getBuilder().uint4((long) value);
        }
        return Secs2Mapper.of(def.type(), value);
    }
}
