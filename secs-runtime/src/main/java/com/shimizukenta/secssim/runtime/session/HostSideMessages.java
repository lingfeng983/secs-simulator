package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.EquipmentControlState;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.gem.CEED;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.secs.secs2.Secs2Exception;
import com.shimizukenta.secs.secs2.Secs2Item;
import com.shimizukenta.secs.secs2.Secs2IrregalDataFormatException;

/**
 * 主机侧报文的处理逻辑（S2 家族）。
 *
 * <p>这一块是"设备被主机指挥"的部分：查常量（S2F13）、写常量（S2F15）、
 * 定义/删除报告（S2F33/S2F34）、链接事件（S2F35/S2F36）、启停事件（S2F37/S2F38）、
 * 远程命令（S2F41）。它们同属一件事——按 GEM 语义回话给主机，
 * 所以从 {@code HsmsSessionRuntime} 里拆出来，自成一个协作者。</p>
 *
 * <p>需要会话状态的地方（常量表、主机报告表、连接状态）由构造参数传入，
 * 本类只做协议翻译，不碰通信。</p>
 */
final class HostSideMessages {

    /** 未知 SVID 在目录里的占位名。 */
    private static final String UNKNOWN_SVID_NAME = "UNKNOWN";

    /** 设备常量表：ECID → 定义。 */
    private final Map<Integer, EquipmentConstantDef> constants;

    /** 主机定义的报告/链接表（S2F33/S2F35 写入）。 */
    private final com.shimizukenta.secssim.app.session.HostReportTable hostReports;

    /** 写常量后的落盘回调；{@code null} 表示不落盘。 */
    private HostSideMessages.ConstantSaver constantSaver;

    /** 出错时的回调（记入会话的最后错误）。 */
    private java.util.function.Consumer<Exception> errorSink;

    /** 回话的出口：以主机期望的 stream/function 应答。 */
    private ProtocolReply replySink;

    /** 设备模型：查 SVID 定义。 */
    private java.util.function.Supplier<com.shimizukenta.secssim.core.compile.ResolvedEquipment> modelSupplier;

    /** 当前控制状态（远程命令要判断设备是否 ONLINE）。 */
    private java.util.function.Supplier<com.shimizukenta.secssim.app.session.EquipmentControlState> controlStateSupplier;

    /** 会话 id（错误日志用）。 */
    private String sessionId = "";

    HostSideMessages(Map<Integer, EquipmentConstantDef> constants,
                     com.shimizukenta.secssim.app.session.HostReportTable hostReports) {
        this.constants = constants;
        this.hostReports = hostReports;
    }

    /**
     * 取请求里点名的 SVID 项。
     *
     * <p>S1F3 / S2F13 的请求体是一串 SVID 项；空体表示"全部"。
     * 主机按自己请求的顺序解释应答，所以这里保持原序。</p>
     */
    List<Secs2> requestSvids(SecsMessage primary) throws Secs2Exception {
        Secs2 body = primary.secs2();
        List<Secs2> items = new ArrayList<>();

        if (body == null || body.isEmpty()) {
            return items;
        }

        for (int i = 0; i < body.size(); i++) {
            items.add(body.get(i));
        }
        return items;
    }

    /** 当前常量表快照（按 ECID 升序）。 */
    List<EquipmentConstantDef> constants() {
        List<EquipmentConstantDef> list;
        synchronized (this.constants) {
            list = new ArrayList<>(this.constants.values());
        }
        list.sort(java.util.Comparator.comparingInt(EquipmentConstantDef::id));
        return list;
    }

    /** 常量落盘回调：把当前常量表交给上层写回配置文件。 */
    interface ConstantSaver {

        /**
         * 保存常量。
         *
         * @param constants ECID → 常量
         * @throws java.io.IOException 写盘失败
         */
        void save(Map<Integer, EquipmentConstantDef> constants) throws java.io.IOException;
    }

    void constantSaver(ConstantSaver saver) {
        this.constantSaver = saver;
    }

    void errorSink(java.util.function.Consumer<Exception> sink) {
        this.errorSink = sink;
    }

    /** 设置回话出口。 */
    void replySink(ProtocolReply sink) {
        this.replySink = sink;
    }

    /** 设置设备模型来源。 */
    void modelSupplier(java.util.function.Supplier<com.shimizukenta.secssim.core.compile.ResolvedEquipment> supplier) {
        this.modelSupplier = supplier;
    }

    /** 设置控制状态来源。 */
    void controlStateSupplier(
            java.util.function.Supplier<com.shimizukenta.secssim.app.session.EquipmentControlState> supplier) {
        this.controlStateSupplier = supplier;
    }

    /** 设置会话 id（错误日志用）。 */
    void sessionId(String id) {
        this.sessionId = id;
    }

    /** 当前设备模型。 */
    private com.shimizukenta.secssim.core.compile.ResolvedEquipment model() {
        java.util.function.Supplier<com.shimizukenta.secssim.core.compile.ResolvedEquipment> supplier = this.modelSupplier;
        return supplier == null ? null : supplier.get();
    }

    /** 当前控制状态。 */
    private com.shimizukenta.secssim.app.session.EquipmentControlState controlState() {
        java.util.function.Supplier<com.shimizukenta.secssim.app.session.EquipmentControlState> supplier =
                this.controlStateSupplier;
        return supplier == null ? null : supplier.get();
    }

    /** 以主机期望的 stream/function 回话。 */
    private void reply(SecsMessage primary, int stream, int function, Secs2 body) {
        ProtocolReply sink = this.replySink;
        if (sink == null) {
            return;
        }
        sink.reply(primary, stream, function, body);
    }

    /** 记录一次处理错误。 */
    private void recordError(Exception e) {
        java.util.function.Consumer<Exception> sink = this.errorSink;
        if (sink != null) {
            sink.accept(e);
        }
    }


    /**
     * 校验 S2F31 的时间格式。
     *
     * <p>E5 的 TIME 有两种：A16 {@code yyyyMMddHHmmsscc}（16 位数字）与
     * A12 {@code yyMMddHHmmss}（12 位数字）。这里只做形状校验（位数 + 全数字），
     * 不校验日历合法性——设备侧不做对时，形状错了就没必要再往下走。</p>
     *
     * @param primary S2F31
     * @return 形状合法返回 {@code true}
     */
    boolean isValidTimeSet(SecsMessage primary) {
        try {
            Secs2 body = primary.secs2();

            /* 这里有坑：Secs2Ascii.size() 返回的是**字符数**，不是元素数。
               S2F31 的体通常就是一个 A16 项（size()==16），不能用 size()!=1 去判"体里几个元素"，
               那个条件对任何非空字符串恒成立、对空串恒假。 */
            if (body.secs2Item() == Secs2Item.LIST) {
                if (body.size() != 1) {
                    return false;
                }
                body = body.get(0);
            }

            String time = body.getAscii();

            if (time == null) {
                return false;
            }

            int len = time.length();

            if (len != 12 && len != 16) {
                return false;
            }

            for (int i = 0; i < len; i++) {
                if (! Character.isDigit(time.charAt(i))) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            recordError(e);
            return false;
        }
    }


    /**
     * S2F13：主机查询设备常量。回 S2F14。
     *
     * <p>E5 形状：{@code S2F13 L[n]{ECID}} → {@code S2F14 L[n]{ECV}}。
     * 应答必须<b>与请求一一对应</b>：主机按自己请求的顺序解释返回值，
     * 顺序错了会把超时当成温度。所以这里严格按请求顺序取值，不认识的回 0。</p>
     *
     * <p>请求空列表 = 全部常量（E5 允许这种"枚举全部"），与 S1F3 的处理一致。</p>
     */
    void replyS2F14(SecsMessage primary) throws Exception {
        List<EquipmentConstantDef> known = this.constants();
        List<Secs2> values = new ArrayList<>();

        List<Integer> requested = new ArrayList<>();
        for (Secs2 item : requestSvids(primary)) {
            requested.add(Secs2Read.asInt(item));
        }

        Secs2Builder b = Secs2.getBuilder();

        if (requested.isEmpty()) {
            for (EquipmentConstantDef def : known) {
                values.add(this.constantItem(def));
            }
        } else {
            Map<Integer, EquipmentConstantDef> byId = new HashMap<>();
            for (EquipmentConstantDef def : known) {
                byId.put(def.id(), def);
            }

            for (int ecid : requested) {
                EquipmentConstantDef def = byId.get(ecid);
                /* 未知 ECID 回 0：不回会让主机等 T3，而设备其实"听到了但没这个常量" */
                values.add(def == null ? b.uint4(0) : this.constantItem(def));
            }
        }

        reply(primary, 2, 14, b.list(values));
    }


    /**
     * 把一个常量按配置的类型写成 SECS-II 数据项。
     *
     * @param def 常量
     * @return 数据项
     * @throws Secs2Exception 构造失败
     */
    private Secs2 constantItem(EquipmentConstantDef def) throws Secs2Exception {
        return switch (def.type()) {
            case U1 -> Secs2.getBuilder().uint1((long) def.value());
            case U2 -> Secs2.getBuilder().uint2((long) def.value());
            case U4 -> Secs2.getBuilder().uint4((long) def.value());
            case U8 -> Secs2.getBuilder().uint8((long) def.value());
            case I1 -> Secs2.getBuilder().int1((long) def.value());
            case I2 -> Secs2.getBuilder().int2((long) def.value());
            case I4 -> Secs2.getBuilder().int4((long) def.value());
            case I8 -> Secs2.getBuilder().int8((long) def.value());
            case F4 -> Secs2.getBuilder().float4((float) def.value());
            case F8 -> Secs2.getBuilder().float8(def.value());
            /* 常量不该是这些类型；落到这里说明配置写错了，回 0 而不是抛异常让主机等 T3 */
            default -> Secs2.getBuilder().uint4(0);
        };
    }


    /**
     * S2F15：主机写设备常量。回 S2F16。
     *
     * <p>E5 形状：{@code S2F15 L[2]{DATAID, L[n]{L[2]{ECID, ECV}}}} → {@code S2F16 B EAC}。</p>
     *
     * <p>EAC 语义：</p>
     * <ul>
     *   <li>{@code 0} 接受，全部写入；</li>
     *   <li>{@code 1} 至少一个常量不存在（<b>整批不生效</b>，不做部分写入）；</li>
     *   <li>{@code 2} 值越界或类型不对；</li>
     *   <li>{@code 3} 当前不允许写（未 ONLINE）。</li>
     * </ul>
     *
     * <p><b>为什么整批不生效</b>：主机发一批常量时通常期待"要么都成，要么都不成"。
     * 部分写入会让设备停留在一个主机以为写成了、其实只写了一半的状态，
     * 比直接拒绝更难排查。</p>
     *
     * <p>写成功后如果接了 {@link ConstantSaver}，会立刻写回配置文件——
     * 这就是"重载/重启后保留"的实现点。</p>
     */
    void handleConstantSet(SecsMessage primary) throws Exception {
        int eac;

        try {
            eac = this.applyConstants(primary.secs2());
        } catch (Exception e) {
            recordError(e);
            eac = 2;
        }

        reply(primary, 2, 16, Secs2.getBuilder().binary((byte) eac));
    }


    /**
     * 校验并应用一批常量写入。
     *
     * @param body S2F15 报文体
     * @return EAC
     * @throws Secs2Exception 形状不可解析
     */
    private int applyConstants(Secs2 body) throws Secs2Exception {
        if (controlState() != EquipmentControlState.ONLINE) {
            /* E30：不在 ONLINE 时不允许改设备参数 */
            return 3;
        }

        /* E5 标准形状 L[2]{DATAID, L[n]{L[2]{ECID, ECV}}}：
           第 1 个元素是 DATAID 标量，第 2 个是常量对列表。
           省略 DATAID 的非标形状 L{L{...}} 也兼容——判定顺序与 S2F33 一致：
           先按标准形状（第 1 元素不是列表）认，认不出来再按非标形状。 */
        if (body.size() == 0) {
            return 2;
        }

        Secs2 pairs;
        if (! isList(body.get(0))) {
            if (body.size() < 2 || ! isList(body.get(1))) {
                return 2;
            }
            pairs = body.get(1);
        } else {
            pairs = body;
        }

        Map<Integer, EquipmentConstantDef> next = new LinkedHashMap<>();
        synchronized (this.constants) {
            next.putAll(this.constants);
        }

        for (int i = 0; i < pairs.size(); i++) {
            Secs2 pair;
            try {
                pair = pairs.get(i);
            } catch (Exception e) {
                recordError(e);
                return 2;
            }

            if (pair.size() != 2) {
                return 2;
            }

            int ecid;
            double value;
            try {
                ecid = Secs2Read.asInt(pair.get(0));
                value = pair.get(1).getDouble(0);
            } catch (Exception e) {
                recordError(e);
                return 2;
            }

            EquipmentConstantDef def = next.get(ecid);
            if (def == null) {
                /* 不认识的 ECID：整批拒绝，让主机知道这批没生效 */
                return 1;
            }
            if (! Double.isFinite(value)) {
                return 2;
            }
            if (! isInRange(def.type(), value)) {
                return 2;
            }

            next.put(ecid, def.withValue(value));
        }

        /* 先落盘再改内存：写盘失败时内存保持旧值，
           避免出现"设备显示新值、重启后变回旧值"这种最难解释的状态 */
        ConstantSaver saver = this.constantSaver;
        if (saver != null) {
            try {
                saver.save(java.util.Collections.unmodifiableMap(next));
            } catch (IOException e) {
                recordError(new IllegalStateException("常量落盘失败: " + e.getMessage(), e));
                Log.error("会话 " + this.sessionId + " 常量落盘失败", e);
                return 2;
            }
        }

        synchronized (this.constants) {
            this.constants.clear();
            this.constants.putAll(next);
        }
        return 0;
    }


    /** 整型常量必须是整数且落在类型范围内；浮点只要有限即可。 */
    private static boolean isInRange(ValueType type, double value) {
        return switch (type) {
            case U1 -> value >= 0 && value <= 255 && value == Math.rint(value);
            case U2 -> value >= 0 && value <= 65535 && value == Math.rint(value);
            case U4 -> value >= 0 && value <= 4294967295L && value == Math.rint(value);
            case U8 -> value >= 0 && value == Math.rint(value);
            case I1 -> value >= -128 && value <= 127 && value == Math.rint(value);
            case I2 -> value >= -32768 && value <= 32767 && value == Math.rint(value);
            case I4 -> value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE && value == Math.rint(value);
            case I8 -> value == Math.rint(value);
            case F4, F8 -> true;
            default -> false;
        };
    }


    /**
     * S2F41：主机下发远程命令。回 S2F42。
     *
     * <p>E30 的形状：</p>
     * <pre>
     * S2F41 W  &lt;L[2] &lt;A RCMD&gt; &lt;L[n] &lt;L[2] &lt;A CPNAME&gt; &lt;V CPVAL&gt;&gt;&gt;&gt;
     * S2F42    &lt;L[2] &lt;B HCACK&gt; &lt;L[n] &lt;L[2] &lt;A CPNAME&gt; &lt;V CPACK&gt;&gt;&gt;&gt;
     * </pre>
     *
     * <p>HCACK 语义按 E30 §执行条件：</p>
     * <ul>
     *   <li>{@code 0} 接受并已执行；</li>
     *   <li>{@code 1} 命令存在但参数非法（本实现的命令集不带参数，因此只在形状非法时出现）；</li>
     *   <li>{@code 2} 当前无法执行（不在 ONLINE / 控制器处于 LOCAL）；</li>
     *   <li>{@code 3} 命令不存在；</li>
     *   <li>{@code 4} 需要初始化（S1F13 未完成）。</li>
     * </ul>
     *
     * <p><b>能力边界</b>：模拟器不对 RCMD 做任何设备动作（没有真实的 START/STOP 语义），
     * 只回结构正确的 S2F42。这样主机上线流程里"探测命令支持"的一步能走通，
     * 而不是落 S9F5 —— 对主机来说"不支持命令"与"设备故障"是两回事，
     * 回 S9F5 会让它走异常分支。真实动作需要下游给出命令集与副作用定义后再接。</p>
     */
    void handleRemoteCommand(SecsMessage primary) throws Exception {
        int hcack;

        try {
            Secs2 body = primary.secs2();

            if (body.size() == 0) {
                /* 体为空：形状不合法，回 1。不能让 get(0) 抛下标越界后再混进同一个 catch，
                   否则日志里看不出"主机发了空报文"还是"RCMD 类型错"。 */
                throw new Secs2IrregalDataFormatException("S2F41 报文体为空");
            }

            String rcmd = body.get(0).getAscii();

            if (rcmd == null || rcmd.isBlank()) {
                hcack = 1;
            } else if (controlState() == EquipmentControlState.OFFLINE
                    || controlState() == EquipmentControlState.COMMUNICATING) {
                /* 通信尚未建立或已离线：E30 要求回 4（需初始化），而不是假装执行了 */
                hcack = 4;
            } else {
                /* ONLINE：模拟器没有真实设备动作，命令"被接受"但不产生副作用 */
                hcack = 0;
            }
        } catch (Exception e) {
            recordError(e);
            hcack = 1;
        }

        Secs2Builder b = Secs2.getBuilder();
        reply(primary, 2, 42, b.list(
                b.binary((byte) hcack),
                /* 本项目所有命令都不带参数，参数级确认列表为空 */
                b.list()));
    }


    /**
     * S2F33：主机定义报告（RPTID → VID 列表）。
     *
     * <p>空的报告列表表示"清空全部主机定义"，此时回落到本地配置（ADR 0006）。</p>
     */
    void handleDefineReport(SecsMessage primary) throws Exception {
        int code = 0;

        try {
            Secs2 reportList = reportListOf(primary.secs2());

            if (reportList.size() == 0) {
                this.hostReports.clearReports();
            } else {
                for (int i = 0; i < reportList.size(); i++) {
                    Secs2 item = reportList.get(i);
                    this.hostReports.defineReport(
                            Secs2Read.asInt(item.get(0)),
                            this.intList(item.get(1)));
                }
            }
        } catch (Exception e) {
            recordError(e);
            code = 1;
        }

        this.ackDynamicReport(primary, 34, code);
    }


    /**
     * 从 S2F33 报文体取出报告列表。
     *
     * <p>E5 的标准形状是 {@code L[2]{U4 DATAID, L[n]{L[2]{U4 RPTID, L[m]{U4 VID}}}}}
     * —— 报告列表在<b>第 2 个</b>元素。早期夹具和少数实现的省略写法是
     * {@code L{L{...}, L{}}}，报告列表在第 1 个元素。两种都要能吃下：
     * 只认一种的后果是标准主机发来时把 DATAID 标量当成列表，
     * 取元素抛 {@code Secs2IrregalDataFormatException} → 恒回 DRACK=1，动态报告彻底不可用。</p>
     *
     * <p><b>判定顺序必须先标准、后兼容</b>：标准形状的 DATAID 是 U4 标量，非标形状的第 1 个元素
     * 是列表，两者按类型就能分开。不能反过来“先看第 2 个元素是不是 L[0] 就当成兼容形状”——
     * 标准形状的“清空全部”（{@code L[2]{DATAID=0, L[0]}}）正是这个样子，
     * 抢判会把 DATAID 当成“报告 0”写进覆盖层，主机后面的事件就发不出来了。</p>
     *
     * <p>标准形状里 DATAID 不是 U4（例如主机把 DATAID 也写成列表）时，按标准解析失败，
     * 再回退到非标形状解析——这是唯一会被两种形状同时命中的情形。</p>
     *
     * @param body S2F33 报文体
     * @return 报告列表（可能是 {@code L[0]}）
     * @throws Secs2Exception 形状无法识别
     */
    private static Secs2 reportListOf(Secs2 body) throws Secs2Exception {
        if (body.size() == 0) {
            throw new Secs2IrregalDataFormatException("S2F33 报文体为空");
        }

        if (body.size() >= 2) {
            Secs2 dataId = body.get(0);
            Secs2 second = body.get(1);

            if (!isList(dataId) && isList(second)
                    && (second.size() == 0 || isReportEntry(second.get(0)))) {
                return second;
            }
        }

        Secs2 first = body.get(0);

        if (isList(first) && (first.size() == 0 || isReportEntry(first.get(0)))) {
            return first;
        }

        throw new Secs2IrregalDataFormatException("S2F33 报文体里没有报告列表");
    }


    /**
     * 从 S2F35 报文体取出「事件 → 报告」链接列表。
     *
     * <p>形状判断与 {@link #reportListOf(Secs2)} 同一套规则：标准形状
     * {@code L[2]{DATAID, L[n]{L[2]{CEID, L[m] RPTID}}}} 优先，省略 DATAID 的
     * {@code L{L{...}, L{}}} 兼容保留。</p>
     *
     * @param body S2F35 报文体
     * @return 链接列表（可能是 {@code L[0]}）
     * @throws Secs2Exception 形状无法识别
     */
    private static Secs2 linkListOf(Secs2 body) throws Secs2Exception {
        if (body.size() == 0) {
            throw new Secs2IrregalDataFormatException("S2F35 报文体为空");
        }

        if (body.size() >= 2) {
            Secs2 dataId = body.get(0);
            Secs2 second = body.get(1);

            if (!isList(dataId) && isList(second)
                    && (second.size() == 0 || isLinkEntry(second.get(0)))) {
                return second;
            }
        }

        Secs2 first = body.get(0);

        if (isList(first) && (first.size() == 0 || isLinkEntry(first.get(0)))) {
            return first;
        }

        throw new Secs2IrregalDataFormatException("S2F35 报文体里没有事件链接列表");
    }


    /** {@code L[2]{U4 CEID, L[m] RPTID}} */
    private static boolean isLinkEntry(Secs2 item) throws Secs2Exception {
        return isScalarAndList(item);
    }


    /** {@code L[2]{U4 RPTID, L[m] VID}} */
    private static boolean isReportEntry(Secs2 item) throws Secs2Exception {
        return isScalarAndList(item);
    }


    /** 是否为 {@code L[2]{U4 标量, L[m]}}：S2F33 / S2F35 的条目都长这样。 */
    private static boolean isScalarAndList(Secs2 item) throws Secs2Exception {
        return isList(item) && item.size() == 2 && !isList(item.get(0)) && isList(item.get(1));
    }


    private static boolean isList(Secs2 item) {
        return item.secs2Item() == Secs2Item.LIST;
    }


    /**
     * S2F35：主机建立"事件 → 报告"的链接。
     */
    void handleLinkEventReport(SecsMessage primary) throws Exception {
        int code = 0;

        try {
            Secs2 links = linkListOf(primary.secs2());

            if (links.size() == 0) {
                this.hostReports.clearLinks();
            } else {
                for (int i = 0; i < links.size(); i++) {
                    Secs2 item = links.get(i);
                    this.hostReports.link(
                            Secs2Read.asInt(item.get(0)),
                            this.intList(item.get(1)));
                }
            }
        } catch (Exception e) {
            recordError(e);
            code = 1;
        }

        this.ackDynamicReport(primary, 36, code);
    }


    /**
     * S2F37：主机启用/禁用事件上报。
     *
     * <p>CEID 列表为空表示"全部"。</p>
     */
    void handleEnableEventReport(SecsMessage primary) throws Exception {
        int code = 0;

        try {
            Secs2 body = primary.secs2();
            boolean enabled = readCeed(body.get(0));
            List<Integer> ceids = body.size() >= 2 ? this.intList(body.get(1)) : List.of();

            this.hostReports.setEnabled(enabled, ceids);
        } catch (Exception e) {
            recordError(e);
            code = 1;
        }

        this.ackDynamicReport(primary, 38, code);
    }


    /**
     * 读取 S2F37 的 CEED。
     *
     * <p>E30 定义为 BOOLEAN，但现场确实有主机按 {@code B[1]}（0/1）发。严格的
     * {@code getBoolean} 在 B 上会抛类型异常 → 恒回 ERACK=1，主机以为设备不支持
     * 事件开关。这里两种都收：BOOLEAN 直接取，B 按"非 0 即真"。</p>
     *
     * @param item CEED 数据项
     * @return 是否启用
     * @throws Secs2Exception 类型既不是 BOOLEAN 也不是 B
     */
    private static boolean readCeed(Secs2 item) throws Secs2Exception {
        if (item.secs2Item() == Secs2Item.BINARY) {
            return Secs2Read.asByte(item) != 0;
        }

        return item.getBoolean(0);
    }


    private List<Integer> intList(Secs2 list) throws Exception {
        List<Integer> values = new ArrayList<>(list.size());

        for (int i = 0; i < list.size(); i++) {
            values.add(Secs2Read.asInt(list.get(i)));
        }
        return values;
    }


    private void ackDynamicReport(SecsMessage primary, int function, int code) throws Exception {
        reply(primary, 2, function, Secs2.getBuilder().binary((byte) code));
    }


    /**
     * S1F11：主机查询状态变量的名字与单位。
     *
     * <p>很多主机在采集前会先枚举一遍（"你有哪些变量、叫什么、单位是什么"），
     * 拿到 S1F12 才决定采哪些 SVID。没有它，自动发现型主机就走不下去。
     * </p>
     *
     * <p>请求为空列表时返回全部变量——E5 允许这种"枚举全部"的用法，现场调试也常用。
     * </p>
     */
    void replyS1F12(SecsMessage primary) throws Exception {
        List<Integer> requested = new ArrayList<>();

        for (Secs2 item : requestSvids(primary)) {
            requested.add(Secs2Read.asInt(item));
        }

        if (requested.isEmpty()) {
            requested.addAll(model().svids().keySet().stream().sorted().toList());
        }

        Secs2Builder b = Secs2.getBuilder();
        List<Secs2> rows = new ArrayList<>(requested.size());

        for (int svid : requested) {
            ResolvedSvid definition = model().svids().get(svid);

            /* 未定义的 SVID 用 "UNKNOWN" 而不是空串：主机拿到空名字无法区分
               "这是个没命名的变量"和"设备在糊弄我"；而 S1F3/F4 对未知 SVID 一律回 U4 0
               （见 P2-1），类型也可能对不上，名字至少要让现场一眼看出是哪一档。 */
            rows.add(b.list(
                    b.uint4(svid),
                    b.ascii(definition == null ? UNKNOWN_SVID_NAME : definition.svName()),
                    b.ascii(definition == null ? "" : definition.unitSymbol())));
        }

        reply(primary, 1, 12, b.list(rows));
    }
}
