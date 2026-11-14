package com.shimizukenta.secs.gem;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import java.util.Optional;

/**
 * 远程命令：S2F22/S2F28/S2F40。
 *
 * <p>主机让设备做一次动作（重启、校准一类的），设备回 HCACK 说明收没收到。</p>
 */
public interface GemRemoteCommand {

	/**
	 * S2F22, Remote Command Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param cmda
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s2f22(SecsMessage primaryMsg, CMDA cmda)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S2F28, Initiate Processing Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param cmda
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s2f28(SecsMessage primaryMsg, CMDA cmda)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S2F40, Multi-block Grant.
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
	public Optional<SecsMessage> s2f40(SecsMessage primaryMsg, GRANT grant)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;
}
