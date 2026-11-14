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
 * 主机侧的"连接与上线"一组：建立通信、请求通信、在线/离线通知。
 *
 * <p>实现 {@link GemCommunication}；共享状态（通信器、配置、ID 构造）在 {@link GemSupport}。</p>
 */
public abstract class AbstractGemCommunication implements GemCommunication {

	/** 共享的通信器与配置。 */
	protected final GemSupport support;

	protected AbstractGemCommunication(GemSupport support) {
		this.support = support;
	}

	public Optional<SecsMessage> s1f1()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		return support.comm.send(1, 1, true);
	}

	public Optional<SecsMessage> s1f2(SecsMessage primaryMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		Secs2 ss;
		
		if ( support.comm.isEquip() ) {
			
			ss = Secs2.list(
					Secs2.ascii(support.config.mdln().get())
					, Secs2.ascii(support.config.softrev().get())
					);
			
		} else {
			
			ss = Secs2.list();
		}
		
		return support.comm.send(primaryMsg, 1, 2, false, ss);
	}

	public COMMACK s1f13()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		Secs2 ss;
		
		if ( support.comm.isEquip() ) {
			
			ss = Secs2.list(
					Secs2.ascii(support.config.mdln().get())
					, Secs2.ascii(support.config.softrev().get())
					);
			
		} else {
			
			ss = Secs2.list();
		}
		
		Secs2 r = support.comm.send(1, 13, true, ss)
				.filter(msg -> msg.getStream() == 1)
				.filter(msg -> msg.getFunction() == 14)
				.map(msg -> msg.secs2())
				.orElseThrow(() -> new Secs2Exception("S1F14 parse failed"));
		
		return COMMACK.get(r.get(0));
	}

	public Optional<SecsMessage> s1f14(SecsMessage primaryMsg, COMMACK commack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		Secs2 ss;
		
		if ( support.comm.isEquip() ) {
			
			ss = Secs2.list(
					Secs2.ascii(support.config.mdln().get())
					, Secs2.ascii(support.config.softrev().get())
					);
			
		} else {
			
			ss = Secs2.list();
		}
		
		return support.comm.send(primaryMsg, 1, 14, false, Secs2.list(commack.secs2(), ss));
	}

	public OFLACK s1f15()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		Secs2 ss = support.comm.send(1, 15, true)
				.filter(msg -> msg.getStream() == 1)
				.filter(msg -> msg.getFunction() == 16)
				.map(msg -> msg.secs2())
				.orElseThrow(() -> new Secs2Exception("S1F16 parse failed"));
		
		return OFLACK.get(ss);
	}

	public Optional<SecsMessage> s1f16(SecsMessage primaryMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 1, 16, false, OFLACK.OK.secs2());
	}

	public ONLACK s1f17()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		
		Secs2 ss = support.comm.send(1, 17, true)
				.filter(msg -> msg.getStream() == 1)
				.filter(msg -> msg.getFunction() == 18)
				.map(msg -> msg.secs2())
				.orElseThrow(() -> new Secs2Exception("S1F18 parse failed"));
		
		return ONLACK.get(ss);
	}

	public Optional<SecsMessage> s1f18(SecsMessage primaryMsg, ONLACK onlack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException {
		
		if ( ! primaryMsg.wbit() ) {
			return Optional.empty();
		}
		
		return support.comm.send(primaryMsg, 1, 18, false, onlack.secs2());
	}
}
