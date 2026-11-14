package com.shimizukenta.secs.gem;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import java.util.Optional;

/**
 * 终端与报警确认：S5F2/S5F4（报警确认）与 S10F2/S10F4/S10F6/S10F10、S13F12（终端显示）。
 *
 * <p>报警本身走 S5F1，这里是对应的确认与终端显示报文。</p>
 */
public interface GemTerminal {

	/**
	 * S5F2, Alarm Report Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc5
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s5f2(SecsMessage primaryMsg, ACKC5 ackc5)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S5F4, Enable/Disable Alarm Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc5
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s5f4(SecsMessage primaryMsg, ACKC5 ackc5)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S10F2, Terminal Request Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc10
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s10f2(SecsMessage primaryMsg, ACKC10 ackc10)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S10F4, Terminal Display, Single Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc10
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s10f4(SecsMessage primaryMsg, ACKC10 ackc10)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S10F6, Terminal Display, Multi-Block Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc10
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s10f6(SecsMessage primaryMsg, ACKC10 ackc10)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S10F10, Broadcast Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc10
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s10f10(SecsMessage primaryMsg, ACKC10 ackc10)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S13F12, Data Set Obj Multi-Block Grant.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param grant
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s13f12(SecsMessage primaryMsg, GRANT grant)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;
}
