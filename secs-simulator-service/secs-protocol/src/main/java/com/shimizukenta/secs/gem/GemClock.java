package com.shimizukenta.secs.gem;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secs.secs2.Secs2Exception;
import java.util.Optional;

/**
 * 设备时钟：S2F17/S2F18/S2F31/S2F32。
 *
 * <p>主机会问设备几点（S2F17），也会把自己的时间推给设备（S2F31/S2F32）。
 * 时间格式不合法时设备必须回 TIACK=1，不能假装同步成功。</p>
 */
public interface GemClock {

	/**
	 * S2F17, Date and Time Request.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @return Clock
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public Clock s2f17()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F18, Date and Time Response.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param clock
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s2f18(SecsMessage primaryMsg, Clock clock)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F18, Now Date and Time Response.
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
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s2f18Now(SecsMessage primaryMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F31, Date and Time Set Request.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param clock
	 * @return TIACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public TIACK s2f31(Clock clock)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F31, Now Date and Time Set Request.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @return TIACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public TIACK s2f31Now()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F32, Date and Time Set Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param tiack
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s2f32(SecsMessage primaryMsg, TIACK tiack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;
}
