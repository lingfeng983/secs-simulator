package com.shimizukenta.secssim.api.http;

import java.util.List;

/**
 * 批量新建设备的请求与结果。
 *
 * <p>与 {@code POST /api/v1/sessions/bulk} 的区别要说清楚：那个是「一份配置开多个会话」，
 * 多台设备共用一份正文、只有运行期覆盖不同，清单里它们的设备名是同一个；
 * 这里是「一台设备一份配置」——每台都能在清单里单独编辑、单独删除、单独重载。</p>
 *
 * <p>名称规则：第 i 台的设备名 = 批次前缀 + 序号，序号从 {@code start} 起逐台 +1，
 * 配置文件名就是这个名字加 {@code .json}。设备名与文件名同源，重名检查只需看一处。
 * Device ID 一律写 0：这一批是同一角色下的多台设备，逐台编 device-id 只会让
 * 现场对着主机侧配置多改一遍。</p>
 */
public final class DeviceBatch {

    private DeviceBatch() {
    }

    /**
     * 一次批量新建的请求。
     *
     * @param templateId 设备模板标识（{@code bare} 或模板目录里的 id）
     * @param prefix     批次前缀；第 i 台的名字是「前缀 + 序号」
     * @param start      起始序号（含），第 i 台用 {@code start + i - 1}
     * @param count      台数（1–100）
     * @param portBase   起始端口：第 i 台的端口 = {@code portBase + i - 1}
     * @param bindHost   监听/连接地址；空串按 {@code 0.0.0.0}
     * @param passive    是否被动监听
     * @param startNow   建完是否立刻启动会话
     */
    public record Request(String templateId, String prefix, int start, int count, int portBase,
                          String bindHost, boolean passive, boolean startNow) {
    }

    /**
     * 建成的一台设备。
     *
     * @param name         配置文件名（{@code dev-1.json}）
     * @param deviceName   设备名（{@code equipment.name}，即「前缀 + 序号」）
     * @param port         这台设备写进配置的端口
     * @param deviceId     这台设备的 device-id（批量新建恒为 0）
     * @param sessionId    会话 id；没启动或启动失败时为空串
     * @param sessionError 会话启动失败的原因；成功或未启动时为空串
     */
    public record Created(String name, String deviceName, int port, int deviceId,
                          String sessionId, String sessionError) {
    }

    /**
     * 没建成的一台设备。
     *
     * @param name    配置文件名
     * @param error   失败分类（{@code conflict} / {@code invalid} / {@code failed}）
     * @param message 人话原因
     */
    public record Failed(String name, String error, String message) {
    }

    /**
     * 批量新建的结果。
     *
     * <p>{@code conflicts} 非空表示「一台都没建」：重名在动手之前就查出来，
     * 不能建了三台才发现第四台撞名，留下半批设备让人自己收拾。</p>
     *
     * @param created   建成的设备
     * @param failed    没建成的设备
     * @param conflicts 已存在、导致整批放弃的名字
     * @param problems  入参本身不合法（前缀、台数、端口段）；非空表示整批未执行
     */
    public record Result(List<Created> created, List<Failed> failed, List<String> conflicts,
                         List<String> problems) {

        /**
         * 固化列表。
         *
         * @param created   建成的设备
         * @param failed    没建成的设备
         * @param conflicts 冲突的名字
         * @param problems  入参问题
         */
        public Result {
            created = List.copyOf(created);
            failed = List.copyOf(failed);
            conflicts = List.copyOf(conflicts);
            problems = List.copyOf(problems);
        }

        /**
         * 只有逐台结果的便利构造（重名或入参问题之外的情形）。
         *
         * @param created   建成的设备
         * @param failed    没建成的设备
         * @param conflicts 重名的文件名
         */
        public Result(List<Created> created, List<Failed> failed, List<String> conflicts) {
            this(created, failed, conflicts, List.of());
        }

        /** 是否存在重名（整批放弃）。 */
        public boolean hasConflicts() {
            return !this.conflicts.isEmpty();
        }

        /** 入参是否不合法（整批未执行）。 */
        public boolean hasProblems() {
            return !this.problems.isEmpty();
        }
    }
}
