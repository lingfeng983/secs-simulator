package com.shimizukenta.secssim.app.config;

import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.event.EventSchedule;

/**
 * 一份完整配置：会话参数 + 事件编排 + 限流。
 *
 * @param spec            会话参数
 * @param schedule        事件编排
 * @param maxEventsPerSec 设备级每秒最大上报数
 */
public record SessionConfig(SessionSpec spec, EventSchedule schedule, int maxEventsPerSec) {

    /** 当前 schema 版本。 */
    public static final int SCHEMA_VERSION = 4;

    /**
     * 校验并创建。
     *
     * @param spec            会话参数
     * @param schedule        编排
     * @param maxEventsPerSec 限流
     */
    public SessionConfig {
        if (schedule == null) {
            schedule = EventSchedule.empty();
        }
        if (maxEventsPerSec < 1) {
            throw new IllegalArgumentException("maxEventsPerSec must be >= 1 but was " + maxEventsPerSec);
        }
    }

    /**
     * 编译设备模型（解析绝对 ID 并校验）。
     *
     * @return 编译结果
     */
    public CompilationResult compile() {
        return EquipmentCompiler.compile(this.spec.equipment());
    }
}
