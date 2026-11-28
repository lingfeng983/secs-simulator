package com.shimizukenta.secssim.core.context;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.value.DataSource;
import com.shimizukenta.secssim.core.value.SourceEvaluator;

/**
 * 晶圆属性表（schema v7）：LOTID / RCPID / PPID / WAFERID / BATCHID 这类"随片走"的数据。
 *
 * <p>它们不是 SVID，也不是 ECID：不进 S1F3、不进 S2F13，只在事件报文的报告里被引用
 * （{@code ctx.<id>}）。值随周期片号变化，所以同一条报告在 25 片上会带出 25 组 ID。</p>
 *
 * <p>类型决定报文编码：{@code ASCII} 原样写字符串，数值型按位宽编码。
 * 这正是 v6 做不到的部分——过去 ASCII 型 SVID 只能存数字。</p>
 */
public final class ContextTable {

    /**
     * 一条晶圆属性定义。
     *
     * @param id     属性名（如 {@code LOTID}）
     * @param type   报文类型
     * @param source 值定义
     */
    public record Def(String id, ValueType type, DataSource source) {

        /** 校验并创建。 */
        public Def {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("context id must not be blank");
            }
        }
    }

    private final Map<String, Def> defs;
    private final CycleClock clock;

    /**
     * 创建。
     *
     * @param defs  定义（顺序即界面顺序）
     * @param clock 周期时钟
     */
    public ContextTable(Collection<Def> defs, CycleClock clock) {
        Map<String, Def> map = new LinkedHashMap<>();
        for (Def def : defs) {
            if (map.putIfAbsent(def.id(), def) != null) {
                throw new IllegalArgumentException("晶圆属性重名：" + def.id());
            }
        }
        /* 顺序即界面与报文顺序：Map.copyOf 不保证迭代顺序，必须留 LinkedHashMap */
        this.defs = java.util.Collections.unmodifiableMap(map);
        this.clock = clock;
    }

    /** 空表。 */
    public static ContextTable empty() {
        return new ContextTable(java.util.List.of(), CycleClock.passthrough());
    }

    /**
     * 按名取定义。
     *
     * @param id 属性名
     * @return 定义
     */
    public Optional<Def> def(String id) {
        return Optional.ofNullable(this.defs.get(id));
    }

    /**
     * 全部属性名。
     *
     * @return 名字集合
     */
    public Set<String> ids() {
        return this.defs.keySet();
    }

    /**
     * 某个属性的报文类型。
     *
     * @param id 属性名
     * @return 类型
     */
    public Optional<ValueType> typeOf(String id) {
        Def def = this.defs.get(id);
        return def == null ? Optional.empty() : Optional.of(def.type());
    }

    /**
     * 某时刻的文本值。
     *
     * @param id  属性名
     * @param tMs 时刻
     * @return 文本
     */
    public Optional<String> textAt(String id, long tMs) {
        Def def = this.defs.get(id);
        if (def == null) {
            return Optional.empty();
        }
        /* 用 ofNullable：文本来源为 null 时（直接构造 Text(null)，或上层漏了默认值）
           过去这里抛 NPE，把一次「值缺失」放大成整条报文组装失败。 */
        return Optional.ofNullable(SourceEvaluator.text(def.source(), tMs, this.clock));
    }

    /**
     * 某时刻的全部属性（按定义顺序）。
     *
     * @param tMs 时刻
     * @return 属性名 → 文本
     */
    public Map<String, String> snapshotAt(long tMs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Def> entry : this.defs.entrySet()) {
            values.put(entry.getKey(), SourceEvaluator.text(entry.getValue().source(), tMs, this.clock));
        }
        return values;
    }
}
