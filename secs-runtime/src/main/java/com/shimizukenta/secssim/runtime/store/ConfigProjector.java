package com.shimizukenta.secssim.runtime.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.store.ConfigRepository;
import com.shimizukenta.secssim.store.StoreException;

/**
 * 配置正文 → 投影表（3.0 设计 §2.2.1）。
 *
 * <p>secs-store 不解析配置正文（依赖纪律），所以「正文重算投影」在 runtime 侧
 * 实现：直接遍历原始 JSON（不经过领域模型往返，未知键不影响投影），按
 * idSpace 把单元内的 offset 换算成绝对 SVID / RPTID / CEID 写进各投影表。
 * 只由 {@code ConfigRepository} 的写事务调用——禁止单表写入。</p>
 *
 * <p>idSpace 换算与既有运行时一致：单元 n 的绝对 id = base + (n-1)×stride + offset；
 * 全局 SVID 用绝对 id 声明。报告/事件里的 {@code svids}/{@code reports} 引用的是
 * <b>槽位名</b>，在本单元（含全局）槽位表里解析成绝对 id。</p>
 */
public final class ConfigProjector implements ConfigRepository.DocumentProjector {

    @Override
    public void project(Connection connection, String deviceId, String body) throws SQLException {
        JsonHub root;
        try {
            root = JsonHub.fromJson(body);
        } catch (RuntimeException e) {
            /* 投影发生在「校验通过之后」的写事务里；解析失败属于不变量破坏，直接失败 */
            throw new StoreException("配置正文无法解析，投影中止: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new StoreException("配置正文不是 JSON 对象，投影中止");
        }

        JsonHub session = root.get("session");
        JsonHub equipment = root.get("equipment");
        JsonHub events = root.get("events");
        JsonHub templates = root.get("templates");

        /* 最小正文（测试 / 迁移边界）可以缺段：缺哪段就不投影哪段 */
        if (session != null && session.isObject()) {
            projectConnection(connection, deviceId, session);
        }
        projectEquipment(connection, deviceId, equipment);
        projectEvents(connection, deviceId, events);
        projectTemplates(connection, deviceId, templates);
    }

    /* ---------- connection_spec ---------- */

    private static void projectConnection(Connection connection, String deviceId, JsonHub session)
            throws SQLException {
        String sessionId = str(session, "id");
        boolean passive = session != null && session.get("passive") != null && session.get("passive").booleanValue();
        int port = intOf(session, "port", 5000);
        int deviceIdNum = intOf(session, "deviceId", 0);
        long seed = longOf(session, "seed", 0L);
        String bindHost = str(session, "bindHost");
        if (bindHost == null || bindHost.isBlank()) {
            bindHost = "0.0.0.0";
        }
        JsonHub timeouts = session == null ? null : session.get("timeouts");
        JsonHub trace = session == null ? null : session.get("trace");

        try (PreparedStatement st = connection.prepareStatement(
                "INSERT INTO connection_spec(device_id, session_id, passive, bind_host, port, "
                        + "device_id_num, seed, timeouts_json, trace_json) VALUES(?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(device_id) DO UPDATE SET session_id=excluded.session_id, "
                        + "passive=excluded.passive, bind_host=excluded.bind_host, port=excluded.port, "
                        + "device_id_num=excluded.device_id_num, seed=excluded.seed, "
                        + "timeouts_json=excluded.timeouts_json, trace_json=excluded.trace_json")) {
            st.setString(1, deviceId);
            st.setString(2, sessionId);
            st.setInt(3, passive ? 1 : 0);
            st.setString(4, bindHost);
            st.setInt(5, port);
            st.setInt(6, deviceIdNum);
            st.setLong(7, seed);
            st.setString(8, timeouts == null ? "{}" : timeouts.toJson());
            st.setString(9, trace == null ? "{}" : trace.toJson());
            st.executeUpdate();
        }
    }

    /* ---------- equipment ---------- */

    private static void projectEquipment(Connection connection, String deviceId, JsonHub equipment)
            throws SQLException {
        if (equipment == null || !equipment.isObject()) {
            return;
        }
        JsonHub idSpace = equipment.get("idSpace");
        /* 缺省号段与引擎的 IdSpacePolicy.defaults() 一致：配置不写 idSpace 时两台
           单元也各有自己的段，投影必须用同一套换算，否则两单元 SVID 撞号 */
        long svidBase = longOf(idSpace, "svidBase", 1000L);
        long ceidBase = longOf(idSpace, "ceidBase", 2000L);
        long reportBase = longOf(idSpace, "reportBase", 3000L);
        long svidStride = longOf(idSpace, "svidStride", 100L);
        long ceidStride = longOf(idSpace, "ceidStride", 100L);
        long reportStride = longOf(idSpace, "reportStride", 100L);

        try (PreparedStatement spec = connection.prepareStatement(
                "INSERT INTO equipment_spec(device_id, name, id_space_json, global_svids_json, global_ceids_json) "
                        + "VALUES(?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET name=excluded.name, "
                        + "id_space_json=excluded.id_space_json, global_svids_json=excluded.global_svids_json, "
                        + "global_ceids_json=excluded.global_ceids_json")) {
            spec.setString(1, deviceId);
            spec.setString(2, str(equipment, "name"));
            spec.setString(3, idSpace == null ? "{}" : idSpace.toJson());
            JsonHub globalSvids = equipment.get("globalSvids");
            spec.setString(4, globalSvids == null ? "[]" : globalSvids.toJson());
            spec.setString(5, "[]");
            spec.executeUpdate();
        }

        /* 全局槽位名 → 绝对 SVID（报告/事件里按名引用） */
        Map<String, Long> globalSlots = new HashMap<>();
        JsonHub globalSvids = equipment.get("globalSvids");
        if (globalSvids != null && globalSvids.isArray()) {
            for (JsonHub svid : globalSvids.values()) {
                long abs = longOf(svid, "id", 0L);
                String slot = str(svid, "name");
                if (slot != null && abs > 0) {
                    globalSlots.put(slot, abs);
                }
                insertSvid(connection, deviceId, abs, "GLOBAL", str(svid, "name"),
                        str(svid, "type"), str(svid, "unit"), svid);
            }
        }

        /* 设备常量 */
        JsonHub constants = equipment.get("constants");
        if (constants != null && constants.isArray()) {
            for (JsonHub constant : constants.values()) {
                try (PreparedStatement st = connection.prepareStatement(
                        "INSERT INTO equipment_constant(device_id, ecid, name, value_type, value, unit) "
                                + "VALUES(?,?,?,?,?,?)")) {
                    st.setString(1, deviceId);
                    st.setLong(2, longOf(constant, "id", 0L));
                    st.setString(3, str(constant, "name"));
                    st.setString(4, str(constant, "type"));
                    st.setDouble(5, doubleOf(constant, "value", 0.0));
                    st.setString(6, str(constant, "unit"));
                    st.executeUpdate();
                }
            }
        }

        JsonHub units = equipment.get("units");
        if (units == null || !units.isArray()) {
            return;
        }
        for (JsonHub unit : units.values()) {
            long unitNo = longOf(unit, "unitId", 0L);
            String unitName = str(unit, "name");
            boolean inject = unit.get("injectUnitId") != null && unit.get("injectUnitId").booleanValue();

            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO unit_def(device_id, unit_no, name, inject_unit_id) VALUES(?,?,?,?)")) {
                st.setString(1, deviceId);
                st.setLong(2, unitNo);
                st.setString(3, unitName);
                st.setInt(4, inject ? 1 : 0);
                st.executeUpdate();
            }

            /* 单元槽位表：UNIT_ID 直配除外（injectUnitId 时 offset 90 的槽位也注册） */
            Map<String, Long> unitSlots = new HashMap<>(globalSlots);
            JsonHub svids = unit.get("svids");
            if (svids != null && svids.isArray()) {
                for (JsonHub svid : svids.values()) {
                    long abs = svidBase + (unitNo - 1) * svidStride + longOf(svid, "offset", 0L);
                    String slot = str(svid, "slot");
                    if (slot != null) {
                        unitSlots.put(slot, abs);
                    }
                    insertSvid(connection, deviceId, abs, unitName, slot,
                            str(svid, "type"), str(svid, "unit"), svid);
                }
            }

            /* 报告定义与 SVID 组成 */
            JsonHub reports = unit.get("reports");
            if (reports != null && reports.isArray()) {
                for (JsonHub report : reports.values()) {
                    long rptid = reportBase + (unitNo - 1) * reportStride + longOf(report, "offset", 0L);
                    try (PreparedStatement st = connection.prepareStatement(
                            "INSERT INTO report_def(device_id, rptid, owner, slot) VALUES(?,?,?,?)")) {
                        st.setString(1, deviceId);
                        st.setLong(2, rptid);
                        st.setString(3, unitName);
                        st.setString(4, str(report, "slot"));
                        st.executeUpdate();
                    }
                    JsonHub reportSvids = report.get("svids");
                    if (reportSvids != null && reportSvids.isArray()) {
                        int ordinal = 0;
                        for (JsonHub slotName : reportSvids.values()) {
                            Long abs = unitSlots.get(slotName.optionalString().orElse(""));
                            if (abs == null) {
                                continue;
                            }
                            try (PreparedStatement st = connection.prepareStatement(
                                    "INSERT INTO report_svid(device_id, rptid, ordinal, svid) VALUES(?,?,?,?)")) {
                                st.setString(1, deviceId);
                                st.setLong(2, rptid);
                                st.setInt(3, ordinal++);
                                st.setLong(4, abs);
                                st.executeUpdate();
                            }
                        }
                    }
                }
            }

            /* 事件定义与报告绑定 */
            JsonHub ceids = unit.get("ceids");
            if (ceids != null && ceids.isArray()) {
                for (JsonHub ceid : ceids.values()) {
                    long absCeid = ceidBase + (unitNo - 1) * ceidStride + longOf(ceid, "offset", 0L);
                    try (PreparedStatement st = connection.prepareStatement(
                            "INSERT INTO ceid_def(device_id, ceid, owner, slot) VALUES(?,?,?,?)")) {
                        st.setString(1, deviceId);
                        st.setLong(2, absCeid);
                        st.setString(3, unitName);
                        st.setString(4, str(ceid, "slot"));
                        st.executeUpdate();
                    }
                    JsonHub ceidReports = ceid.get("reports");
                    if (ceidReports != null && ceidReports.isArray()) {
                        for (JsonHub reportSlot : ceidReports.values()) {
                            long rptid = reportBase + (unitNo - 1) * reportStride
                                    + slotOffset(units, unitNo, reportSlot.optionalString().orElse(""));
                            try (PreparedStatement st = connection.prepareStatement(
                                    "INSERT INTO ceid_report(device_id, ceid, rptid) VALUES(?,?,?)")) {
                                st.setString(1, deviceId);
                                st.setLong(2, absCeid);
                                st.setLong(3, rptid);
                                st.executeUpdate();
                            }
                        }
                    }
                }
            }
        }
    }

    /** 在本单元的 reports 里按槽位名找 offset（ceid_report 绑定用）。 */
    private static long slotOffset(JsonHub units, long unitNo, String slot) {
        if (units == null) {
            return 0L;
        }
        for (JsonHub unit : units.values()) {
            if (longOf(unit, "unitId", -1L) != unitNo) {
                continue;
            }
            JsonHub reports = unit.get("reports");
            if (reports == null) {
                continue;
            }
            for (JsonHub report : reports.values()) {
                if (slot.equals(str(report, "slot"))) {
                    return longOf(report, "offset", 0L);
                }
            }
        }
        return 0L;
    }

    private static void insertSvid(Connection connection, String deviceId, long abs, String owner,
                                   String slot, String type, String unit, JsonHub raw) throws SQLException {
        String sourceKind = raw.get("curve") != null ? "curve" : raw.get("template") != null ? "template" : "default";
        try (PreparedStatement st = connection.prepareStatement(
                "INSERT INTO svid_def(device_id, svid, owner, slot, value_type, unit, source_kind, source_json) "
                        + "VALUES(?,?,?,?,?,?,?,?)")) {
            st.setString(1, deviceId);
            st.setLong(2, abs);
            st.setString(3, owner);
            st.setString(4, slot);
            st.setString(5, type);
            st.setString(6, unit);
            st.setString(7, sourceKind);
            st.setString(8, raw.toJson());
            st.executeUpdate();
        }
    }

    /* ---------- events ---------- */

    private static void projectEvents(Connection connection, String deviceId, JsonHub events)
            throws SQLException {
        if (events == null || !events.isObject()) {
            return;
        }
        JsonHub plans = events.get("plans");
        if (plans == null || !plans.isArray()) {
            return;
        }
        int ordinal = 0;
        for (JsonHub plan : plans.values()) {
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO event_plan(device_id, plan_id, ceid, trigger_json, enabled_default, "
                            + "offline_policy, on_send_failure, ordinal) VALUES(?,?,?,?,?,?,?,?)")) {
                st.setString(1, deviceId);
                st.setString(2, str(plan, "id"));
                st.setLong(3, longOf(plan, "ceid", 0L));
                JsonHub trigger = plan.get("trigger");
                st.setString(4, trigger == null ? "{}" : trigger.toJson());
                st.setInt(5, plan.get("enabled") != null && !plan.get("enabled").booleanValue() ? 0 : 1);
                st.setString(6, str(plan, "offlinePolicy") == null ? "skip" : str(plan, "offlinePolicy"));
                st.setString(7, str(plan, "onSendFailure") == null ? "keep" : str(plan, "onSendFailure"));
                st.setInt(8, ordinal++);
                st.executeUpdate();
            }
            /* 3.0 需求 5：动作列表按序投影（空 = 旧语义「一条默认 S6F11」）。
               投影表只用于查询与展示，执行路径读的是解析后的配置正文。 */
            JsonHub actions = plan.get("actions");
            if (actions != null && actions.isArray()) {
                int actionOrdinal = 0;
                for (JsonHub action : actions.values()) {
                    try (PreparedStatement st = connection.prepareStatement(
                            "INSERT INTO plan_action(device_id, plan_id, ordinal, kind, stream, function, "
                                    + "wbit, sml, params_json) VALUES(?,?,?,?,?,?,?,?,?)")) {
                        st.setString(1, deviceId);
                        st.setString(2, str(plan, "id"));
                        st.setInt(3, actionOrdinal++);
                        String kind = str(action, "kind");
                        st.setString(4, kind == null || kind.isBlank() ? "s6f11" : kind);
                        setNullableLong(st, 5, action.get("stream"));
                        setNullableLong(st, 6, action.get("function"));
                        setNullableBool(st, 7, action.get("wbit"));
                        st.setString(8, str(action, "sml"));
                        JsonHub params = action.get("params");
                        st.setString(9, params == null || !params.isObject() ? "{}" : params.toJson());
                        st.executeUpdate();
                    }
                }
            }
            JsonHub effects = plan.get("effects");
            if (effects != null && effects.isArray()) {
                int effectOrdinal = 0;
                for (JsonHub effect : effects.values()) {
                    try (PreparedStatement st = connection.prepareStatement(
                            "INSERT INTO event_effect(device_id, plan_id, ordinal, svid, op, value, delta, hold_ms, decay_ms) "
                                    + "VALUES(?,?,?,?,?,?,?,?,?)")) {
                        st.setString(1, deviceId);
                        st.setString(2, str(plan, "id"));
                        st.setInt(3, effectOrdinal++);
                        st.setLong(4, longOf(effect, "svid", 0L));
                        st.setString(5, str(effect, "op"));
                        setNullableDouble(st, 6, effect.get("value"));
                        setNullableDouble(st, 7, effect.get("delta"));
                        setNullableLong(st, 8, effect.get("holdMs"));
                        setNullableLong(st, 9, effect.get("decayMs"));
                        st.executeUpdate();
                    }
                }
            }
        }
    }

    /* ---------- templates ---------- */

    private static void projectTemplates(Connection connection, String deviceId, JsonHub templates)
            throws SQLException {
        if (templates == null || !templates.isObject()) {
            return;
        }
        insertTemplateRows(connection, deviceId, templates.get("curves"), "template_curve", "curve_id");
        insertTemplateRows(connection, deviceId, templates.get("composites"), "template_composite", "composite_id");
        insertTemplateRows(connection, deviceId, templates.get("instances"), "template_instance", "instance_id");
    }

    private static void insertTemplateRows(Connection connection, String deviceId, JsonHub rows,
                                           String table, String idColumn) throws SQLException {
        if (rows == null || !rows.isArray()) {
            return;
        }
        for (JsonHub row : rows.values()) {
            String id = str(row, "id");
            try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO " + table + "(device_id, " + idColumn + ", definition_json) VALUES(?,?,?)")) {
                st.setString(1, deviceId);
                st.setString(2, id);
                st.setString(3, row.toJson());
                st.executeUpdate();
            }
        }
    }

    /* ---------- JsonHub 取值工具 ---------- */

    private static String str(JsonHub hub, String key) {
        if (hub == null || !hub.isObject()) {
            return null;
        }
        JsonHub value = hub.get(key);
        return value == null ? null : value.optionalString().orElse(null);
    }

    private static int intOf(JsonHub hub, String key, int fallback) {
        if (hub == null || !hub.isObject()) {
            return fallback;
        }
        JsonHub value = hub.get(key);
        return value == null ? fallback : value.optionalInt().orElse(fallback);
    }

    private static long longOf(JsonHub hub, String key, long fallback) {
        if (hub == null || !hub.isObject()) {
            return fallback;
        }
        JsonHub value = hub.get(key);
        return value == null ? fallback : value.optionalLong().orElse(fallback);
    }

    private static double doubleOf(JsonHub hub, String key, double fallback) {
        if (hub == null || !hub.isObject()) {
            return fallback;
        }
        JsonHub value = hub.get(key);
        return value == null ? fallback : value.optionalDouble().orElse(fallback);
    }

    private static void setNullableDouble(PreparedStatement st, int index, JsonHub value) throws SQLException {
        if (value == null || value.isNull()) {
            st.setNull(index, java.sql.Types.REAL);
        } else {
            st.setDouble(index, value.doubleValue());
        }
    }

    private static void setNullableLong(PreparedStatement st, int index, JsonHub value) throws SQLException {
        if (value == null || value.isNull()) {
            st.setNull(index, java.sql.Types.INTEGER);
        } else {
            st.setLong(index, value.longValue());
        }
    }

    /** wbit 列是 INTEGER：未配置写 NULL（执行时用动作/会话默认值）。 */
    private static void setNullableBool(PreparedStatement st, int index, JsonHub value) throws SQLException {
        if (value == null || value.isNull() || value.optionalBoolean().isEmpty()) {
            st.setNull(index, java.sql.Types.INTEGER);
        } else {
            st.setInt(index, value.optionalBoolean().get() ? 1 : 0);
        }
    }
}
