package com.shimizukenta.secssim.app.config;

import com.shimizukenta.secssim.app.session.DefaultCurves;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.template.TemplateGraph;
import com.shimizukenta.secssim.core.value.BoundedValueSource;
import com.shimizukenta.secssim.core.value.CurveValueSource;
import com.shimizukenta.secssim.core.value.GraphValueSource;
import com.shimizukenta.secssim.core.value.ValueSource;
import com.shimizukenta.secssim.core.value.ValueStore;

/**
 * 会话值来源的统一构建入口（schema v6）。
 *
 * <p>此前"有模板走模板图、没模板走默认曲线"的判断在
 * {@code SessionLauncher}、{@code Main}（草稿预览）里各写了一份，
 * v6 加入 SVID 直配后出现了第三种情况（只有直配、没有 templates 段）——
 * 再各写一份迟早出现"预览是一套语义、启动是另一套"的分叉。
 * 三处统一调这里。</p>
 */
public final class ValueSources {

    private ValueSources() {
    }

    /**
     * 按配置构建值来源。
     *
     * <p>没有任何自定义值来源（templates 段为空且无 SVID 直配）时绑定
     * {@code DefaultCurves} 默认曲线；否则构建模板图
     * （用户实例 + 直配合成实例），未绑定到来源的 SVID 取 0。</p>
     *
     * @param config    会话配置
     * @param equipment 已编译的设备模型
     * @return 值来源
     * @throws ConfigException 模板图构建失败（未知引用/环/单写者冲突）
     */
    public static ValueSource of(SessionConfig config, ResolvedEquipment equipment) {
        final ValueSource values;

        if (!config.hasCustomValues()) {
            ValueStore store = new ValueStore(config.spec().seed());
            DefaultCurves.bind(store, config.spec().equipment(), equipment);
            values = new CurveValueSource(store);
        } else {
            TemplateGraph graph = config.buildValues(equipment);
            values = new GraphValueSource(graph, new Rng(config.spec().seed()).derive("graph"));
        }

        /* 值域在取值处收紧：类型宽度是硬约束，越界/NaN 不能留到报文编码时才炸。
           启动、热生效、草稿预览都走这里，三种形态的语义因此一致。 */
        return new BoundedValueSource(values, equipment.svids());
    }
}
