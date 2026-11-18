package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.runtime.ConfigSection;

/**
 * 分段生效的「动什么」推导（2026-11-18 真机验守卫 B 后收紧）：
 * 只有结构（单元/号段）才全停；变量/报告/事件目录只换视图与编排，周期与计划照跑。
 */
class SectionEffectsTest {

    private static HsmsSessionRuntime.SectionEffects of(ConfigSection... sections) {
        Set<ConfigSection> changed = EnumSet.noneOf(ConfigSection.class);
        for (ConfigSection section : sections) {
            changed.add(section);
        }
        return HsmsSessionRuntime.SectionEffects.of(changed);
    }

    @Test
    void structureStopsEverything() {
        HsmsSessionRuntime.SectionEffects fx = of(ConfigSection.STRUCTURE);
        assertTrue(fx.structure());
        assertFalse(fx.catalog());
        /* 模型换了：值、编排、限流、数据、周期全部重建 */
        assertTrue(fx.values());
        assertTrue(fx.schedule());
        assertTrue(fx.throttle());
        assertTrue(fx.data());
        assertTrue(fx.cycle());
    }

    @Test
    void svidsChangeKeepsCycleAndPlans() {
        HsmsSessionRuntime.SectionEffects fx = of(ConfigSection.SVIDS);
        assertFalse(fx.structure(), "变量不是结构：不能整会话全停");
        assertTrue(fx.catalog(), "要换模型视图");
        /* 值来源重建（钉值迁移）；编排保留态换表 */
        assertTrue(fx.values());
        assertTrue(fx.schedule());
        /* 关键：周期不回 STOPPED */
        assertFalse(fx.cycle(), "改未被周期引用的变量不该停周期");
    }

    @Test
    void reportsAndCeidsCountAsCatalogOnly() {
        assertTrue(of(ConfigSection.REPORTS).catalog());
        assertFalse(of(ConfigSection.REPORTS).cycle());
        assertTrue(of(ConfigSection.CEIDS).catalog());
        assertFalse(of(ConfigSection.CEIDS).cycle());
    }

    @Test
    void plansOnlyTouchesSchedule() {
        HsmsSessionRuntime.SectionEffects fx = of(ConfigSection.PLANS);
        assertFalse(fx.structure());
        assertFalse(fx.catalog());
        assertFalse(fx.values());
        assertTrue(fx.schedule());
        assertFalse(fx.cycle());
    }

    @Test
    void cycleOnlyRebuildsValuesAndStopsCycle() {
        HsmsSessionRuntime.SectionEffects fx = of(ConfigSection.CYCLE);
        assertFalse(fx.structure());
        assertTrue(fx.values());
        assertFalse(fx.schedule());
        assertTrue(fx.cycle());
    }

    @Test
    void contextOnlySwapsDataModel() {
        HsmsSessionRuntime.SectionEffects fx = of(ConfigSection.CONTEXT);
        assertTrue(fx.data());
        assertFalse(fx.values());
        assertFalse(fx.cycle());
        assertFalse(fx.schedule());
    }
}
