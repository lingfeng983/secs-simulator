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
 * 远程命令：主机让设备做一次动作，设备回 HCACK。
 *
 * <p>实现 {@link GemRemoteCommand}；共享状态（通信器、配置、ID 构造）在 {@link GemSupport}。</p>
 */
public abstract class AbstractGemRemoteCommand implements GemRemoteCommand {

	/** 共享的通信器与配置。 */
	protected final GemSupport support;

	protected AbstractGemRemoteCommand(GemSupport support) {
		this.support = support;
	}

	public Optional<SecsMessage> s2f22(SecsMessage primaryMsg, CMDA cmda)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 2, 22, false, cmda.secs2());
	}

	public Optional<SecsMessage> s2f28(SecsMessage primaryMsg, CMDA cmda)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 2, 28, false, cmda.secs2());
	}

	public Optional<SecsMessage> s2f40(SecsMessage primaryMsg, GRANT grant)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 2, 40, false, grant.secs2());
	}
}
