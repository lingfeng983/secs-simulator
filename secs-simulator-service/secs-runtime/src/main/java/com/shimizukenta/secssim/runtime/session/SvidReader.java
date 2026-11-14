package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.value.ValueSource;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;

/**
 * SVID 取值与 S1F4 应答。
 *
 * <p>S1F3 请求一组 SVID，S1F4 必须按<b>同样的顺序与个数</b>返回。
 * E5 允许空列表表示"全部 SVID"，自动发现型主机常用它一次拿全，
 * 这里与 S1F11 的空列表语义保持一致。</p>
 */
final class SvidReader {

    /** S1F12 里未定义 SVID 的名字占位。 */
    static final String UNKNOWN_SVID_NAME = "UNKNOWN";

    /** 设备模型。 */
    private final java.util.function.Supplier<ResolvedEquipment> model;

    /** 值来源。 */
    private final java.util.function.Supplier<ValueSource> values;

    /** 通信器。 */
    private final java.util.function.Supplier<SecsCommunicator> communicator;

    /** 主机侧报文处理（取 S1F3 请求的 SVID）。 */
    private final HostCommandHandler hostSide;

    /** 当前时钟（毫秒）。 */
    private final java.util.function.LongSupplier clock;

    SvidReader(java.util.function.Supplier<ResolvedEquipment> model,
               java.util.function.Supplier<ValueSource> values,
               java.util.function.Supplier<SecsCommunicator> communicator,
               HostCommandHandler hostSide,
               java.util.function.LongSupplier clock) {
        this.model = model;
        this.values = values;
        this.communicator = communicator;
        this.hostSide = hostSide;
        this.clock = clock;
    }

    /**
     * 回 S1F4：按请求的顺序与个数返回 SVID 的当前值。
     *
     * @param primary 主机发来的 S1F3
     * @throws Exception 发送失败
     */
    void replyS1F4(SecsMessage primary) throws Exception {
        long nowMs = this.clock.getAsLong();
        List<Secs2> requested = this.hostSide.requestSvids(primary);
        List<Secs2> values = new ArrayList<>();

        if (requested.isEmpty()) {
            for (int svid : this.model.get().svids().keySet().stream().sorted().toList()) {
                values.add(this.valueOf(svid, nowMs));
            }
        } else {
            for (Secs2 item : requested) {
                values.add(this.valueOf(item, nowMs));
            }
        }

        this.communicator.get().send(primary, 1, 4, false, Secs2.getBuilder().list(values));
    }

    /** 取单个 SVID 项的值。 */
    Secs2 valueOf(Secs2 svidItem, long nowMs) throws Secs2Exception {
        return this.valueOf(Secs2Read.asInt(svidItem), nowMs);
    }

    /** 取单个 SVID 的值；未定义的 SVID 返回 0。 */
    Secs2 valueOf(int svid, long nowMs) {
        ResolvedSvid definition = this.model.get().svids().get(svid);

        if (definition == null) {
            /* 未知 SVID：主机按自己的表解析，返回 0 比断开连接更友好 */
            return Secs2.getBuilder().uint4(0L);
        }

        OptionalDouble value = this.values.get().valueAt(svid, nowMs);
        return Secs2Mapper.of(definition.type(), value.orElse(0.0));
    }
}
