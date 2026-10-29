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
 * 设备时钟：主机会问设备几点，也会把自己的时间推给设备。
 *
 * <p>实现 {@link GemClock}；共享状态（通信器、配置、ID 构造）在 {@link GemSupport}。</p>
 */
public abstract class AbstractGemClock implements GemClock {

	/** 共享的通信器与配置。 */
	protected final GemSupport support;

	protected AbstractGemClock(GemSupport support) {
		this.support = support;
	}

	public Clock s2f17()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		Secs2 ss = support.comm.send(2, 17, true)
				.filter(msg -> msg.getStream() == 2)
				.filter(msg -> msg.getFunction() == 18)
				.map(msg -> msg.secs2())
				.orElseThrow(() -> new Secs2Exception("s2f18 parse failed"));
		
		return Clock.from(ss);
	}

	public Optional<SecsMessage> s2f18(SecsMessage primaryMsg, Clock c)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		return s2f18(primaryMsg, getClockSecs2(c));
	}

	public Optional<SecsMessage> s2f18Now(SecsMessage primaryMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		return s2f18(primaryMsg, Clock.now());
	}

	public TIACK s2f31(Clock c)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		Secs2 ss = support.comm.send(2, 31, true, getClockSecs2(c))
				.filter(msg -> msg.getStream() == 2)
				.filter(msg -> msg.getFunction() == 32)
				.map(msg -> msg.secs2())
				.orElseThrow(() -> new Secs2Exception("s2f32 parse failed"));
		
		return TIACK.get(ss);
	}

	public TIACK s2f31Now()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		return s2f31(Clock.now());
	}

	public Optional<SecsMessage> s2f32(SecsMessage primaryMsg, TIACK tiack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 2, 32, false, tiack.secs2());
	}
	private Secs2 getClockSecs2(Clock c) {
		
		switch ( support.config.clockType().get() ) {
		case A12: {
			return c.toAscii12();
			/* break; */
		}
		case A16: {
			return c.toAscii16();
			/* break; */
		}
		default: {
			return Secs2.empty();
		}
		}
	}
	private Optional<SecsMessage> s2f18(SecsMessage primaryMsg, Secs2 ss)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 2, 18, false, ss);
	}
}
