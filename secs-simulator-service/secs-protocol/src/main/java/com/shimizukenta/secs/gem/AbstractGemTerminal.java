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
 * 报警确认与终端显示。
 *
 * <p>实现 {@link GemTerminal}；共享状态（通信器、配置、ID 构造）在 {@link GemSupport}。</p>
 */
public abstract class AbstractGemTerminal implements GemTerminal {

	/** 共享的通信器与配置。 */
	protected final GemSupport support;

	protected AbstractGemTerminal(GemSupport support) {
		this.support = support;
	}

	public Optional<SecsMessage> s5f2(SecsMessage primaryMsg, ACKC5 ackc5)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 5, 2, false, ackc5.secs2());
	}

	public Optional<SecsMessage> s5f4(SecsMessage primaryMsg, ACKC5 ackc5)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 5, 4, false, ackc5.secs2());
	}

	public Optional<SecsMessage> s10f2(SecsMessage primaryMsg, ACKC10 ackc10)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s10fx(primaryMsg, 2, ackc10);
	}

	public Optional<SecsMessage> s10f4(SecsMessage primaryMsg, ACKC10 ackc10)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s10fx(primaryMsg, 4, ackc10);
	}

	public Optional<SecsMessage> s10f6(SecsMessage primaryMsg, ACKC10 ackc10)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s10fx(primaryMsg, 6, ackc10);
	}

	public Optional<SecsMessage> s10f10(SecsMessage primaryMsg, ACKC10 ackc10)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s10fx(primaryMsg, 10, ackc10);
	}

	private Optional<SecsMessage> s10fx(SecsMessage primaryMsg, int func, ACKC10 ackc10)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		/* 应答必须复用主报文的 SystemBytes：用 send(10, func, ...) 会生成新的，
		   主机挂起等的 T3 永远等不到自己那笔事务 */
		return support.comm.send(primaryMsg, 10, func, false, ackc10.secs2());
	}

	public Optional<SecsMessage> s13f12(SecsMessage primaryMsg, GRANT grant)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 13, 12, false, grant.secs2());
	}
}
