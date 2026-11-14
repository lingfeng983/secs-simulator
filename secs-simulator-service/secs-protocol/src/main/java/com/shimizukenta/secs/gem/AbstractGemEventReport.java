package com.shimizukenta.secs.gem;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.shimizukenta.secs.AbstractSecsCommunicator;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;
import com.shimizukenta.secs.secs2.Secs2Item;

/**
 * 动态事件报告：定义/链接/启停报告，以及 S3/S6/S7 家族里的报告相关报文。
 *
 * <p>实现 {@link GemEventReport}；共享状态（通信器、配置、ID 构造）在 {@link GemSupport}。</p>
 */
public abstract class AbstractGemEventReport implements GemEventReport {

	/** 共享的通信器与配置。 */
	protected final GemSupport support;

	protected AbstractGemEventReport(GemSupport support) {
		this.support = support;
	}

	public DRACK s2f33DeleteAll()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		return s2f33Inner(Collections.emptyList());
	}

	public DRACK s2f33Define(DynamicEventReportConfig config)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		return config.s2f33Define();
	}

	public DRACK s2f33Inner(List<? extends Secs2> reports)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		Secs2 ss = Secs2.list(
				this.support.autoDataId(),
				Secs2.list(reports));
		
		Secs2 r = support.comm.send(2, 33, true, ss)
				.filter(msg -> msg.getStream() == 2)
				.filter(msg -> msg.getFunction() == 34)
				.map(msg -> msg.secs2())
				.orElseThrow(() -> new Secs2Exception("s2f34 parse failed"));
		
		return DRACK.get(r);
	}

	public Optional<SecsMessage> s2f34(SecsMessage primaryMsg, DRACK drack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 2, 34, false, drack.secs2());
	}

	public LRACK s2f35(DynamicEventReportConfig config)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		return config.s2f35();
	}

	public LRACK s2f35Inner(List<? extends Secs2> links)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		Secs2 ss = Secs2.list(
				this.support.autoDataId(),
				Secs2.list(links));
		
		Secs2 r = support.comm.send(2, 35, true, ss)
				.filter(msg -> msg.getStream() == 2)
				.filter(msg -> msg.getFunction() == 36)
				.map(msg -> msg.secs2())
				.orElseThrow(() -> new Secs2Exception("s2f36 parse failed"));
		
		return LRACK.get(r);
	}

	public Optional<SecsMessage> s2f36(SecsMessage primaryMsg, LRACK lrack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 2, 36, false, lrack.secs2());
	}

	public ERACK s2f37DisableAll()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		return s2f37Inner(CEED.DISABLE, Collections.emptyList());
	}

	public ERACK s2f37EnableAll()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		return s2f37Inner(CEED.ENABLE, Collections.emptyList());
	}

	public ERACK s2f37Enable(DynamicEventReportConfig config)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		return config.s2f37Enable();
	}

	public ERACK s2f37Inner(CEED ceed, List<? extends Secs2> ces)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		Secs2 ss = Secs2.list(
				ceed.secs2(),
				Secs2.list(ces));
		
		Secs2 r = support.comm.send(2, 37, true, ss)
				.filter(msg -> msg.getStream() == 2)
				.filter(msg -> msg.getFunction() == 38)
				.map(msg -> msg.secs2())
				.orElseThrow(() -> new Secs2Exception("s2f38 parse failed"));
		
		return ERACK.get(r);
	}

	public Optional<SecsMessage> s2f38(SecsMessage primaryMsg, ERACK erack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 2, 38, false, erack.secs2());
	}

	public Optional<SecsMessage> s3f16(SecsMessage primaryMsg, GRANT grant)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 3, 16, false, grant.secs2());
	}

	public Optional<SecsMessage> s6f2(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s6fx(primaryMsg, 2, ackc6);
	}

	public Optional<SecsMessage> s6f4(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s6fx(primaryMsg, 4, ackc6);
	}

	public Optional<SecsMessage> s6f6(SecsMessage primaryMsg, GRANT6 grant6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 6, 6, false, grant6.secs2());
	}

	public Optional<SecsMessage> s6f10(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s6fx(primaryMsg, 10, ackc6);
	}

	public Optional<SecsMessage> s6f12(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s6fx(primaryMsg, 12, ackc6);
	}

	public Optional<SecsMessage> s6f14(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s6fx(primaryMsg, 14, ackc6);
	}

	public Optional<SecsMessage> s6f15(DynamicCollectionEvent ce)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return support.comm.send(6, 15, true, ce.collectionEventId());
	}

	public Optional<SecsMessage> s6f17(DynamicCollectionEvent ce)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return support.comm.send(6, 17, true, ce.collectionEventId());
	}

	public Optional<SecsMessage> s6f19(DynamicReport report)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return support.comm.send(6, 19, true, report.reportId());
	}

	public Optional<SecsMessage> s6f21(DynamicReport report)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return support.comm.send(6, 21, true, report.reportId());
	}

	public Optional<SecsMessage> s6f26(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s6fx(primaryMsg, 26, ackc6);
	}

	private Optional<SecsMessage> s6fx(SecsMessage primaryMsg, int func, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 6, func, false, ackc6.secs2());
	}

	public Optional<SecsMessage> s7f4(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 4, ackc7);
	}

	public Optional<SecsMessage> s7f12(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 12, ackc7);
	}

	public Optional<SecsMessage> s7f14(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 14, ackc7);
	}

	public Optional<SecsMessage> s7f16(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 16, ackc7);
	}

	public Optional<SecsMessage> s7f18(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 18, ackc7);
	}

	public Optional<SecsMessage> s7f24(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 24, ackc7);
	}

	public Optional<SecsMessage> s7f32(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 32, ackc7);
	}

	public Optional<SecsMessage> s7f38(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 38, ackc7);
	}

	public Optional<SecsMessage> s7f40(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 40, ackc7);
	}

	public Optional<SecsMessage> s7f42(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 42, ackc7);
	}

	public Optional<SecsMessage> s7f44(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s7fx(primaryMsg, 44, ackc7);
	}

	private Optional<SecsMessage> s7fx(SecsMessage primaryMsg, int func, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 7, func, false, ackc7.secs2());
	}
}
