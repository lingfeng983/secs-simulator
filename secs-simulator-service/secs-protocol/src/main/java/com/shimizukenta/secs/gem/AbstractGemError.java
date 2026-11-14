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
 * 错误报文：设备不认识主机发来的报文时回一条 S9Fx。
 *
 * <p>实现 {@link GemError}；共享状态（通信器、配置、ID 构造）在 {@link GemSupport}。</p>
 */
public abstract class AbstractGemError implements GemError {

	/** 共享的通信器与配置。 */
	protected final GemSupport support;

	protected AbstractGemError(GemSupport support) {
		this.support = support;
	}

	public Optional<SecsMessage> s9f1(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s9fx(refMsg, 1);
	}

	public Optional<SecsMessage> s9f3(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s9fx(refMsg, 3);
	}

	public Optional<SecsMessage> s9f5(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s9fx(refMsg, 5);
	}

	public Optional<SecsMessage> s9f7(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s9fx(refMsg, 7);
	}

	public Optional<SecsMessage> s9f9(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s9fx(refMsg, 9);
	}

	public Optional<SecsMessage> s9f11(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return s9fx(refMsg, 11);
	}

	private Optional<SecsMessage> s9fx(SecsMessage refMsg, int func)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return support.comm.send(9, func, false, Secs2.binary(refMsg.header10Bytes()));
	}
}
