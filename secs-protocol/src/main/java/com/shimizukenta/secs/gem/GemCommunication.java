package com.shimizukenta.secs.gem;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secs.secs2.Secs2Exception;
import java.util.Optional;

/**
 * 通信建立与状态：S1F1/S1F13/S1F14/S1F15/S1F16/S1F17/S1F18。
 *
 * <p>主机侧的"连接与上线"一组：建立通信、请求通信、在线/离线通知。
 * 这组报文决定设备能不能主动上报，是 GEM 的地基。</p>
 */
public interface GemCommunication {

	/**
	 * S1F1, Are You Online?.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @return Reply-Message
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s1f1()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S1F2, On Line Data.
	 * 
	 * <p>
	 * MDLN, SOFTREV is reference AbstractGemConfig value.<br />
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s1f2(SecsMessage primaryMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S1F13, Establish Communications Request.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @return COMMACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public COMMACK s1f13()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S1F14, Establish Communications Request Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param commack
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s1f14(SecsMessage primaryMsg, COMMACK commack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S1F15, Request OFF-LINE.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @return OFLACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public OFLACK s1f15()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S1F16, OFF-LINE Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s1f16(SecsMessage primaryMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S1F17, Request ON-LINE.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @return ONLACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public ONLACK s1f17()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S1F18, ON-LINE Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param onlack
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s1f18(SecsMessage primaryMsg, ONLACK onlack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;
}
