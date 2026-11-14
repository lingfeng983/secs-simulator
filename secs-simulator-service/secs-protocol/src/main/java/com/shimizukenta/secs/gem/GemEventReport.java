package com.shimizukenta.secs.gem;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secs.secs2.Secs2Exception;
import java.util.Optional;

/**
 * 动态事件报告：S2F33–S2F38 的定义/链接/启停，以及 S3/S6/S7 家族里的报告相关报文。
 *
 * <p>主机在线定义报告（S2F33）、把事件链到报告（S2F35）、按 CEED 启停事件（S2F37），
 * 设备随后用 S6F11 把事件报上来。S6F15/S6F17 是设备主动发起的那一侧。</p>
 */
public interface GemEventReport {

	/**
	 * S2F33, Delete All Define-Report.
	 * 
	 * <p>
	 * DATA-ID is AutoNumber.<br />
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @return DRACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public DRACK s2f33DeleteAll()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F33, Define Report.
	 * 
	 * <p>
	 * DATA-ID is AutoNumber.<br />
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param config DynamicEventReportConfig
	 * @return DRACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public DRACK s2f33Define(DynamicEventReportConfig config)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F34, Define Report Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param drack
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s2f34(SecsMessage primaryMsg, DRACK drack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S2F35, Link Collection Event Report.
	 * 
	 * <p>
	 * DATA-ID is AutoNumber.<br />
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param config DynamicEventReportConfig
	 * @return LRACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public LRACK s2f35(DynamicEventReportConfig config)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F36, Link Collection Event Report Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param lrack
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s2f36(SecsMessage primaryMsg, LRACK lrack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S2F37, Disable All Collection-Event-Report.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @return ERACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public ERACK s2f37DisableAll()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F37, Enable All Collection-Event-Report.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @return ERACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public ERACK s2f37EnableAll()
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F37, Enable Collection-Event-Report.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param DynamicEventReportConfig
	 * @return ERACK
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws Secs2Exception
	 * @throws InterruptedException
	 */
	public ERACK s2f37Enable(DynamicEventReportConfig config)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException;

	/**
	 * S2F38, Enable/Disable CollectionEvent Report Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param erack
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s2f38(SecsMessage primaryMsg, ERACK erack)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S3F16, Matls Multi-block Grant.
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
	public Optional<SecsMessage> s3f16(SecsMessage primaryMsg, GRANT grant)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F2, Trace Data Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc6
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f2(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F4, Discrete Variable Data Send Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc6
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f4(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F6, Multi-block Grant.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param grant6
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f6(SecsMessage primaryMsg, GRANT6 grant6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F10, Formatted Variable Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc6
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f10(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F12, CollectionEvent Report Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc6
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f12(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F14, Annotated CollectionEvent Report Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc6
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f14(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F15, Event Report Request.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param ce DynamicCollectionEvent
	 * @return reply-message
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f15(DynamicCollectionEvent ce)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F17, Annotated Event Report Request.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param ce DynamicCollectionEvent
	 * @return reply-message
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f17(DynamicCollectionEvent ce)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F19, Individual Report Request.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param report DynamicReport
	 * @return reply-message
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f19(DynamicReport report)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F21, Annotated Individual Report Request.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param report DynamicReport
	 * @return reply-message
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f21(DynamicReport report)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S6F26, Notification Report Send Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc6
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s6f26(SecsMessage primaryMsg, ACKC6 ackc6)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F4, Process Program Send Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f4(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F12, Matl/Process Matrix Update Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f12(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F14, Delete Matl/Process Matrix Entry Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f14(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F16, Matrix Mode Select Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f16(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F18, Delete Process Program Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f18(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F24, Formatted Process Program Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f24(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F32, Verification Request Acknowledge.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f32(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F38, Large PP Send Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f38(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F40, Large Formatted PP Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f40(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F42, Large PP Req Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f42(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;

	/**
	 * S7F44, Large Formatted PP Req Ack.
	 * 
	 * <p>
	 * blocking-method.<br />
	 * </p>
	 * 
	 * @param primaryMsg
	 * @param ackc7
	 * @return {@code Optional.empty()}
	 * @throws SecsSendMessageException if send failed
	 * @throws SecsWaitReplyMessageException if receive message failed, e.g. Timeout-T3
	 * @throws SecsException
	 * @throws InterruptedException
	 */
	public Optional<SecsMessage> s7f44(SecsMessage primaryMsg, ACKC7 ackc7)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, InterruptedException;
}
