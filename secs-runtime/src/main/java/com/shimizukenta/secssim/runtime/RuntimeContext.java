package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.util.List;

import com.shimizukenta.secssim.api.http.ApiContext;
import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.api.http.EventOutcome;
import com.shimizukenta.secssim.api.http.SessionCreateRequest;
import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.api.http.TriggerOutcome;
import com.shimizukenta.secssim.app.audit.AuditLog;
import com.shimizukenta.secssim.app.event.MessageLog;
import com.shimizukenta.secssim.app.session.SessionManager;

/**
 * 运行时上下文：{@code ApiContext} 的门面，也是停机时统一关闭后台资源的入口。
 *
 * <p>原来这个类一千二百多行，同时管着健康信息、会话查询、运行时编辑、主机模拟器、
 * 压测线程池和配置读写。现在按"谁拥有状态"拆成四个：</p>
 *
 * <ul>
 *   <li>{@link RuntimeApi}——会话/配置/报文/审计的接口落地（无状态，只查对象调方法）</li>
 *   <li>{@link HostSimRegistry}——主机模拟器的注册、查找、重连与关闭</li>
 *   <li>{@link BurstRunner}——压测线程池、预算与取消</li>
 *   <li>本类——健康信息、停机编排与 {@code ApiContext} 委托</li>
 * </ul>
 *
 * <p>启动流程不在这里：参数解析、播种配置、起 HTTP 服务都在 {@code Bootstrap}。</p>
 */
final class RuntimeContext implements ApiContext, AutoCloseable {

    private final String mode;
    private final String version;
    private final String extraHealthJson;
    private final Runnable shutdown;
    private final RuntimeApi api;
    private final HostSimRegistry hosts;
    private final BurstRunner bursts;

    RuntimeContext(String mode, String version, String extraHealthJson, Runnable shutdown,
            SessionManager sessions, SessionLauncher launcher, ConfigStore configs,
            MessageLog messages, AuditLog audit) {
        this.mode = mode;
        this.version = version;
        this.extraHealthJson = extraHealthJson;
        this.shutdown = shutdown;
        this.hosts = new HostSimRegistry(sessions);
        this.bursts = new BurstRunner(sessions);
        this.api = new RuntimeApi(sessions, launcher, configs, messages, audit, this.hosts, this.bursts);
    }

    /**
     * 关闭上下文持有的后台资源：主机模拟器连接、在跑的压测与压测执行器。
     *
     * <p>以前停机只关会话与 HTTP，host-sim 的连接与采集线程、以及最多 2 个
     * 压测线程（可能在 {@code Thread.sleep} 里等 5 分钟）都靠 JVM 退出兜底。
     * 嵌入/测试宿主里这就变成泄漏。</p>
     */
    @Override
    public void close() {
        this.hosts.close();
        this.bursts.close();
    }

    @Override
    public String mode() {
        return this.mode;
    }

    @Override
    public String version() {
        return this.version;
    }

    @Override
    public String extraHealthJson() {
        return this.extraHealthJson;
    }

    @Override
    public void requestShutdown() {
        this.shutdown.run();
    }

    /* ---------- 会话查询 ---------- */

    @Override
    public String sessionsJson() {
        return this.api.sessionsJson();
    }

    @Override
    public String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
        return this.api.previewJson(sessionId, fromMs, toMs, buckets);
    }

    @Override
    public String previewJson(String sessionId, long fromMs, long toMs, int buckets,
                              java.util.Set<Integer> svids, boolean withEvents) {
        return this.api.previewJson(sessionId, fromMs, toMs, buckets, svids, withEvents);
    }

    @Override
    public String previewJson(String sessionId, long fromMs, long toMs, int buckets,
                              java.util.Set<Integer> svids, boolean withEvents, String planId) {
        return this.api.previewJson(sessionId, fromMs, toMs, buckets, svids, withEvents, planId);
    }

    @Override
    public byte[] previewBinary(String sessionId, long fromMs, long toMs, int buckets) {
        return this.api.previewBinary(sessionId, fromMs, toMs, buckets);
    }

    /* ---------- 手动触发 ---------- */

    @Override
    public TriggerOutcome triggerCeid(String sessionId, int ceid) {
        return this.api.triggerCeid(sessionId, ceid);
    }

    @Override
    public TriggerOutcome triggerAlarm(String sessionId, int alid, boolean set, String text, int category) {
        return this.api.triggerAlarm(sessionId, alid, set, text, category);
    }

    @Override
    public String alarmsJson(String sessionId) {
        return this.api.alarmsJson(sessionId);
    }

    @Override
    public String terminalMessagesJson(String sessionId) {
        return this.api.terminalMessagesJson(sessionId);
    }

    @Override
    public String catalogJson(String sessionId) {
        return this.api.catalogJson(sessionId);
    }

    @Override
    public String eventsJson(String sessionId) {
        return this.api.eventsJson(sessionId);
    }

    @Override
    public EventOutcome setEventEnabled(String sessionId, String planId, boolean enabled) {
        return this.api.setEventEnabled(sessionId, planId, enabled);
    }

    /* ---------- 报文与审计 ---------- */

    @Override
    public String messagesJson(int limit, String sessionId, String direction) {
        return this.api.messagesJson(limit, sessionId, direction);
    }

    @Override
    public String messagesJson(int limit, String sessionId, String direction,
                               long beforeMs, Integer stream, Integer function) {
        return this.api.messagesJson(limit, sessionId, direction, beforeMs, stream, function);
    }

    @Override
    public String messagesJsonl(int limit, String sessionId, String direction) {
        return this.api.messagesJsonl(limit, sessionId, direction);
    }

    @Override
    public String messagesJsonl(int limit, String sessionId, String direction,
                                long beforeMs, Integer stream, Integer function) {
        return this.api.messagesJsonl(limit, sessionId, direction, beforeMs, stream, function);
    }

    @Override
    public String auditJson(int limit) {
        return this.api.auditJson(limit);
    }

    @Override
    public String auditJson(int limit, String action, String actor) {
        return this.api.auditJson(limit, action, actor);
    }

    @Override
    public String auditJson(int limit, String action, String actor, long beforeMs) {
        return this.api.auditJson(limit, action, actor, beforeMs);
    }

    /* ---------- 运行时编辑(设备页·事件/报告/状态变量) ---------- */

    @Override
    public int addEventPlan(String sessionId, String planJson) {
        return this.api.addEventPlan(sessionId, planJson);
    }

    @Override
    public int updateEventPlan(String sessionId, String planId, String planJson) {
        return this.api.updateEventPlan(sessionId, planId, planJson);
    }

    @Override
    public int removeEventPlan(String sessionId, String planId) {
        return this.api.removeEventPlan(sessionId, planId);
    }

    @Override
    public int addReport(String sessionId, String reportJson) {
        return this.api.addReport(sessionId, reportJson);
    }

    @Override
    public int updateReport(String sessionId, int rptid, String reportJson) {
        return this.api.updateReport(sessionId, rptid, reportJson);
    }

    @Override
    public int addCeid(String sessionId, String ceidJson) {
        return this.api.addCeid(sessionId, ceidJson);
    }

    @Override
    public int updateCeid(String sessionId, int ceid, String ceidJson) {
        return this.api.updateCeid(sessionId, ceid, ceidJson);
    }

    @Override
    public int removeCeid(String sessionId, int ceid) {
        return this.api.removeCeid(sessionId, ceid);
    }

    @Override
    public int removeReport(String sessionId, int rptid) {
        return this.api.removeReport(sessionId, rptid);
    }

    @Override
    public int addSvid(String sessionId, String svidJson) {
        return this.api.addSvid(sessionId, svidJson);
    }

    @Override
    public int updateSvid(String sessionId, int svid, String svidJson) {
        return this.api.updateSvid(sessionId, svid, svidJson);
    }

    @Override
    public int removeSvid(String sessionId, int svid) {
        return this.api.removeSvid(sessionId, svid);
    }

    @Override
    public int pinSvidValue(String sessionId, int svid, String valueJson) {
        return this.api.pinSvidValue(sessionId, svid, valueJson);
    }

    @Override
    public int unpinSvidValue(String sessionId, int svid) {
        return this.api.unpinSvidValue(sessionId, svid);
    }

    @Override
    public String sendSml(String sessionId, String sml) throws IOException {
        return this.api.sendSml(sessionId, sml);
    }

    /* ---------- 会话生命周期 ---------- */

    @Override
    public SessionOutcome createSession(SessionCreateRequest request) {
        return this.api.createSession(request);
    }

    @Override
    public SessionOutcome closeSession(String sessionId) {
        return this.api.closeSession(sessionId);
    }

    @Override
    public SessionOutcome reloadSession(String sessionId) {
        return this.api.reloadSession(sessionId);
    }

    /* ---------- 主机模拟器 ---------- */

    @Override
    public SessionOutcome openHostSim(String sessionId) {
        return this.hosts.open(sessionId, null, null);
    }

    @Override
    public SessionOutcome openHostSim(String sessionId, String targetJson) {
        return this.hosts.open(sessionId, targetJson, null);
    }

    @Override
    public SessionOutcome openHostSim(String sessionId, String targetJson, String hostId) {
        return this.hosts.open(sessionId, targetJson, hostId);
    }

    @Override
    public SessionOutcome closeHostSim(String sessionId) {
        return this.hosts.closeBySession(sessionId);
    }

    @Override
    public SessionOutcome closeHostSimById(String hostId) {
        return this.hosts.closeById(hostId);
    }

    @Override
    public String hostSimJson(String sessionId) {
        return this.hosts.jsonOfSession(sessionId);
    }

    @Override
    public String hostSimsJson() {
        return this.hosts.allJson();
    }

    @Override
    public String hostSimJsonById(String hostId) {
        return this.hosts.jsonById(hostId);
    }

    @Override
    public String sendHostSmlById(String hostId, String sml) throws IOException {
        return this.hosts.sendById(hostId, sml);
    }

    @Override
    public com.shimizukenta.secssim.api.http.CollectorResult setHostSimCollectorById(
            String hostId, String requestJson) {
        return this.hosts.setCollectorById(hostId, requestJson);
    }

    @Override
    public com.shimizukenta.secssim.api.http.CollectorResult hostSimCollectorCommandById(
            String hostId, boolean start) {
        return this.hosts.commandById(hostId, start);
    }

    @Override
    public String hostSimCollectorJsonById(String hostId) {
        return this.hosts.collectorJsonById(hostId);
    }

    @Override
    public String sendHostSml(String sessionId, String sml) throws IOException {
        return this.hosts.sendBySession(sessionId, sml);
    }

    @Override
    public com.shimizukenta.secssim.api.http.CollectorResult setHostSimCollector(
            String sessionId, String requestJson) {
        return this.hosts.setCollectorBySession(sessionId, requestJson);
    }

    @Override
    public com.shimizukenta.secssim.api.http.CollectorResult hostSimCollectorCommand(
            String sessionId, boolean start) {
        return this.hosts.commandBySession(sessionId, start);
    }

    @Override
    public String hostSimCollectorJson(String sessionId) {
        return this.hosts.collectorJsonBySession(sessionId);
    }

    /* ---------- 总览与压测 ---------- */

    @Override
    public String overviewJson() {
        return this.api.overviewJson();
    }

    @Override
    public String burst(String sessionId, String requestJson) {
        return this.bursts.burst(sessionId, requestJson);
    }

    /* ---------- 配置与模板 ---------- */

    @Override
    public String configPreviewJson(String text, long fromMs, long toMs, int buckets) {
        return this.api.configPreviewJson(text, fromMs, toMs, buckets);
    }

    @Override
    public String configPreviewJson(String text, long fromMs, long toMs, int buckets,
                                    java.util.Set<Integer> svids, boolean withEvents) {
        return this.api.configPreviewJson(text, fromMs, toMs, buckets, svids, withEvents);
    }

    @Override
    public String configsJson() {
        return this.api.configsJson();
    }

    @Override
    public ApiContext.ConfigSnapshot configSnapshot(String name) {
        return this.api.configSnapshot(name);
    }

    @Override
    public String configText(String name) {
        return this.api.configText(name);
    }

    @Override
    public List<String> validateConfig(String text) {
        return this.api.validateConfig(text);
    }

    @Override
    public ConfigResult saveConfig(String name, String text) {
        return this.api.saveConfig(name, text);
    }

    @Override
    public ConfigResult saveConfig(String name, String text, Long expectedModifiedMs) {
        return this.api.saveConfig(name, text, expectedModifiedMs);
    }

    @Override
    public Long configModifiedMs(String name) {
        return this.api.configModifiedMs(name);
    }

    @Override
    public ConfigResult createConfig(String name) {
        return this.api.createConfig(name);
    }

    @Override
    public ConfigResult createConfig(String name, String templateId) {
        return this.api.createConfig(name, templateId);
    }

    @Override
    public String configTemplatesJson() {
        return this.api.configTemplatesJson();
    }

    @Override
    public String templatesJson() {
        return this.api.templatesJson();
    }

    @Override
    public java.util.Optional<String> templateText(String id) {
        return this.api.templateText(id);
    }

    @Override
    public ConfigResult saveTemplate(String id, String text) {
        return this.api.saveTemplate(id, text);
    }

    @Override
    public ConfigResult deleteTemplate(String id) {
        return this.api.deleteTemplate(id);
    }
}
