package com.shimizukenta.secs.gem;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import java.util.Optional;

/**
 * 错误报文：S9F1/S9F3/S9F5/S9F7/S9F9/S9F11。
 *
 * <p>设备不认识主机发来的报文时必须回一条 S9Fx 告诉主机"这条我不认"，
 * 否则主机只会看到 T3 超时，分不清是"设备不支持"还是"设备坏了"。</p>
 */
public interface GemError {

	/**
	 * S9F1, Unknown Device ID.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param refMsg
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s9f1(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S9F3, Unknown Stream.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param refMsg
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s9f3(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S9F5, Unknown Function.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param refMsg
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s9f5(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S9F7, Illegal Data.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param refMsg
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s9f7(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S9F9, Transaction Timeout.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param refMsg
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s9f9(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S9F11, Data Too Long.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param refMsg
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s9f11(SecsMessage refMsg)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;
}
