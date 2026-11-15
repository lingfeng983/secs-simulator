package com.shimizukenta.secssim.api.http;

/**
 * HTTP 层需要的运行时信息。
 *
 * <p>把"我是什么形态、版本多少、有哪些附加字段"抽成接口，
 * 使 HTTP 层不认识运行时的具体实现，运行时不认识 HTTP 的具体细节。
 * </p>
 *
 * <p>default 方法按职责域拆在四个父接口里，本接口本身只留形态、版本、停机与日志级别：
 * {@link ApiContextSessionOps}（会话/事件/目录/设备运行时）、
 * {@link ApiContextHostOps}（主机实例/模板/采集器）、
 * {@link ApiContextConfigOps}（配置与模板文件）、
 * {@link ApiContextObservationOps}（预览/报文/审计/压测）。
 * 拆分前的全部 default 方法仍可经本接口调用。</p>
 */
public interface ApiContext extends ApiContextSessionOps, ApiContextHostOps,
        ApiContextConfigOps, ApiContextObservationOps {

    /**
     * 运行形态标识。
     *
     * @return 如 {@code integrated}
     */
    String mode();

    /**
     * 版本号。
     *
     * @return 版本
     */
    String version();

    /**
     * 追加到 health JSON 里的字段，形如 {@code ,"hsmsPort":15000}。
     *
     * @return 附加字段，无则为空串
     */
    String extraHealthJson();

    /**
     * 请求优雅停机。
     */
    void requestShutdown();

    /**
     * 当前日志级别。
     *
     * @return 级别名（小写）
     */
    default String logLevel() {
        return com.shimizukenta.secssim.app.log.Log.level().name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 运行时切换日志级别。
     *
     * @param level 级别名（error/info/debug，不分大小写）
     * @return 切换是否成功
     */
    default boolean setLogLevel(String level) {
        try {
            com.shimizukenta.secssim.app.log.Log.setLevel(
                    com.shimizukenta.secssim.app.log.Log.Level.parse(level));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * v2 新建主机的返回值：状态 + 新实例 id。
     *
     * <p>早先的签名只回 {@code SessionOutcome}，新 id 靠
     * {@code lastCreatedHostId()} 这个共享字段带出来——两个请求并发创建时，
     * 先返回的那个会读到后一个的 id，客户端拿错 id 去连接，等到"主机不存在"
     * 再回头查已经无从复现。id 必须随结果一起返回。</p>
     *
     * @param outcome 结果
     * @param hostId  新实例 id；失败时为空串
     */
    record HostOpenResult(SessionOutcome outcome, String hostId) {

        /** 失败结果（无 id）。 */
        public static HostOpenResult failed(SessionOutcome outcome) {
            return new HostOpenResult(outcome, "");
        }

        /** 是否创建成功。 */
        public boolean ok() {
            return outcome == SessionOutcome.OK;
        }
    }

    /**
     * 配置正文 + 修改时间的一次性快照。
     *
     * @param text       原文
     * @param modifiedMs 修改时间（epoch 毫秒）；{@code null} 表示取不到
     */
    record ConfigSnapshot(String text, Long modifiedMs) {
    }
}