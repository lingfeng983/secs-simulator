package com.shimizukenta.secs.gem;

import java.util.List;
import java.util.Optional;

import com.shimizukenta.secs.AbstractSecsCommunicator;
import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;

/**
 * GEM 的完整实现：把六个能力域的实现合到一起。
 *
 * <p>报文按能力域拆进了 {@link GemCommunication}、{@link GemClock}、
 * {@link GemRemoteCommand}、{@link GemEventReport}、{@link GemError}、
 * {@link GemTerminal} 六个子接口；每个域有自己的实现类，共用
 * {@link GemSupport} 里的通信器与配置。这里只做转发。</p>
 */
public abstract class AbstractGem implements Gem {

	/** 通信与在线。 */
	protected final GemCommunication gemCommunication;

	/** 设备时钟。 */
	protected final GemClock gemClock;

	/** 远程命令。 */
	protected final GemRemoteCommand gemRemoteCommand;

	/** 动态事件报告（含 inner 方法，供报告配置用）。 */
	protected final AbstractGemEventReport gemEventReport;

	/** 错误报文。 */
	protected final GemError gemError;

	/** 报警确认与终端显示。 */
	protected final GemTerminal gemTerminal;

	/** 共享的通信器与配置。 */
	protected final GemSupport support;

	public AbstractGem(AbstractSecsCommunicator communicator, AbstractGemConfig config) {
		this.support = new GemSupport(communicator, config);
		this.gemCommunication = new AbstractGemCommunication(this.support) {};
		this.gemClock = new AbstractGemClock(this.support) {};
		this.gemRemoteCommand = new AbstractGemRemoteCommand(this.support) {};
		this.gemEventReport = new AbstractGemEventReport(this.support) {};
		this.gemError = new AbstractGemError(this.support) {};
		this.gemTerminal = new AbstractGemTerminal(this.support) {};
	}

	@Override
	public Optional<SecsMessage> s1f1() throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemCommunication.s1f1();
	}

	@Override
	public Optional<SecsMessage> s1f2(SecsMessage primaryMsg) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemCommunication.s1f2(primaryMsg);
	}

	@Override
	public COMMACK s1f13() throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemCommunication.s1f13();
	}

	@Override
	public Optional<SecsMessage> s1f14(SecsMessage primaryMsg, COMMACK commack) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemCommunication.s1f14(primaryMsg, commack);
	}

	@Override
	public OFLACK s1f15() throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemCommunication.s1f15();
	}

	@Override
	public Optional<SecsMessage> s1f16(SecsMessage primaryMsg) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemCommunication.s1f16(primaryMsg);
	}

	@Override
	public ONLACK s1f17() throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemCommunication.s1f17();
	}

	@Override
	public Optional<SecsMessage> s1f18(SecsMessage primaryMsg, ONLACK onlack) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemCommunication.s1f18(primaryMsg, onlack);
	}

	@Override
	public Clock s2f17() throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemClock.s2f17();
	}

	@Override
	public Optional<SecsMessage> s2f18(SecsMessage primaryMsg, Clock c) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemClock.s2f18(primaryMsg, c);
	}

	@Override
	public Optional<SecsMessage> s2f18Now(SecsMessage primaryMsg) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemClock.s2f18Now(primaryMsg);
	}

	@Override
	public TIACK s2f31(Clock c) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemClock.s2f31(c);
	}

	@Override
	public TIACK s2f31Now() throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemClock.s2f31Now();
	}

	@Override
	public Optional<SecsMessage> s2f32(SecsMessage primaryMsg, TIACK tiack) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemClock.s2f32(primaryMsg, tiack);
	}

	@Override
	public Optional<SecsMessage> s2f22(SecsMessage primaryMsg, CMDA cmda) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemRemoteCommand.s2f22(primaryMsg, cmda);
	}

	@Override
	public Optional<SecsMessage> s2f28(SecsMessage primaryMsg, CMDA cmda) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemRemoteCommand.s2f28(primaryMsg, cmda);
	}

	@Override
	public Optional<SecsMessage> s2f40(SecsMessage primaryMsg, GRANT grant) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemRemoteCommand.s2f40(primaryMsg, grant);
	}

	@Override
	public DRACK s2f33DeleteAll() throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemEventReport.s2f33DeleteAll();
	}

	@Override
	public DRACK s2f33Define(DynamicEventReportConfig config) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemEventReport.s2f33Define(config);
	}

	@Override
	public Optional<SecsMessage> s2f34(SecsMessage primaryMsg, DRACK drack) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s2f34(primaryMsg, drack);
	}

	@Override
	public LRACK s2f35(DynamicEventReportConfig config) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemEventReport.s2f35(config);
	}

	@Override
	public Optional<SecsMessage> s2f36(SecsMessage primaryMsg, LRACK lrack) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s2f36(primaryMsg, lrack);
	}

	@Override
	public ERACK s2f37DisableAll() throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemEventReport.s2f37DisableAll();
	}

	@Override
	public ERACK s2f37EnableAll() throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemEventReport.s2f37EnableAll();
	}

	@Override
	public ERACK s2f37Enable(DynamicEventReportConfig config) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , Secs2Exception , InterruptedException {
		return this.gemEventReport.s2f37Enable(config);
	}

	@Override
	public Optional<SecsMessage> s2f38(SecsMessage primaryMsg, ERACK erack) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s2f38(primaryMsg, erack);
	}

	@Override
	public Optional<SecsMessage> s3f16(SecsMessage primaryMsg, GRANT grant) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s3f16(primaryMsg, grant);
	}

	@Override
	public Optional<SecsMessage> s6f2(SecsMessage primaryMsg, ACKC6 ackc6) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f2(primaryMsg, ackc6);
	}

	@Override
	public Optional<SecsMessage> s6f4(SecsMessage primaryMsg, ACKC6 ackc6) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f4(primaryMsg, ackc6);
	}

	@Override
	public Optional<SecsMessage> s6f6(SecsMessage primaryMsg, GRANT6 grant6) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f6(primaryMsg, grant6);
	}

	@Override
	public Optional<SecsMessage> s6f10(SecsMessage primaryMsg, ACKC6 ackc6) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f10(primaryMsg, ackc6);
	}

	@Override
	public Optional<SecsMessage> s6f12(SecsMessage primaryMsg, ACKC6 ackc6) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f12(primaryMsg, ackc6);
	}

	@Override
	public Optional<SecsMessage> s6f14(SecsMessage primaryMsg, ACKC6 ackc6) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f14(primaryMsg, ackc6);
	}

	@Override
	public Optional<SecsMessage> s6f15(DynamicCollectionEvent ce) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f15(ce);
	}

	@Override
	public Optional<SecsMessage> s6f17(DynamicCollectionEvent ce) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f17(ce);
	}

	@Override
	public Optional<SecsMessage> s6f19(DynamicReport report) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f19(report);
	}

	@Override
	public Optional<SecsMessage> s6f21(DynamicReport report) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f21(report);
	}

	@Override
	public Optional<SecsMessage> s6f26(SecsMessage primaryMsg, ACKC6 ackc6) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s6f26(primaryMsg, ackc6);
	}

	@Override
	public Optional<SecsMessage> s7f4(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f4(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s7f12(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f12(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s7f14(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f14(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s7f16(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f16(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s7f18(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f18(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s7f24(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f24(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s7f32(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f32(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s7f38(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f38(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s7f40(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f40(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s7f42(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f42(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s7f44(SecsMessage primaryMsg, ACKC7 ackc7) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemEventReport.s7f44(primaryMsg, ackc7);
	}

	@Override
	public Optional<SecsMessage> s9f1(SecsMessage refMsg) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemError.s9f1(refMsg);
	}

	@Override
	public Optional<SecsMessage> s9f3(SecsMessage refMsg) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemError.s9f3(refMsg);
	}

	@Override
	public Optional<SecsMessage> s9f5(SecsMessage refMsg) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemError.s9f5(refMsg);
	}

	@Override
	public Optional<SecsMessage> s9f7(SecsMessage refMsg) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemError.s9f7(refMsg);
	}

	@Override
	public Optional<SecsMessage> s9f9(SecsMessage refMsg) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemError.s9f9(refMsg);
	}

	@Override
	public Optional<SecsMessage> s9f11(SecsMessage refMsg) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemError.s9f11(refMsg);
	}

	@Override
	public Optional<SecsMessage> s5f2(SecsMessage primaryMsg, ACKC5 ackc5) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemTerminal.s5f2(primaryMsg, ackc5);
	}

	@Override
	public Optional<SecsMessage> s5f4(SecsMessage primaryMsg, ACKC5 ackc5) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemTerminal.s5f4(primaryMsg, ackc5);
	}

	@Override
	public Optional<SecsMessage> s10f2(SecsMessage primaryMsg, ACKC10 ackc10) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemTerminal.s10f2(primaryMsg, ackc10);
	}

	@Override
	public Optional<SecsMessage> s10f4(SecsMessage primaryMsg, ACKC10 ackc10) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemTerminal.s10f4(primaryMsg, ackc10);
	}

	@Override
	public Optional<SecsMessage> s10f6(SecsMessage primaryMsg, ACKC10 ackc10) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemTerminal.s10f6(primaryMsg, ackc10);
	}

	@Override
	public Optional<SecsMessage> s10f10(SecsMessage primaryMsg, ACKC10 ackc10) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemTerminal.s10f10(primaryMsg, ackc10);
	}

	@Override
	public Optional<SecsMessage> s13f12(SecsMessage primaryMsg, GRANT grant) throws SecsSendMessageException , SecsWaitReplyMessageException , SecsException , InterruptedException {
		return this.gemTerminal.s13f12(primaryMsg, grant);
	}

	/** 自动分配的 DATAID。 */
	public Secs2 autoDataId() {
		return this.support.autoDataId();
	}

	/** 指定 DATAID。 */
	public Secs2 dataId(long id) {
		return this.support.dataId(id);
	}

	/** 新的动态事件报告配置。 */
	public DynamicEventReportConfig newDynamicEventReportConfig() {
		return DynamicEventReportConfig.newInstance(this);
	}

	protected Secs2 autoReportId() {
		return this.support.autoReportId();
	}

	protected Secs2 reportId(long id) {
		return this.support.reportId(id);
	}

	protected Secs2 vId(long id) {
		return this.support.vId(id);
	}

	protected Secs2 collectionEventId(long id) {
		return this.support.collectionEventId(id);
	}

	protected DRACK s2f33Inner(List<? extends Secs2> reports)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		return this.gemEventReport.s2f33Inner(reports);
	}

	protected LRACK s2f35Inner(List<? extends Secs2> links)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		return this.gemEventReport.s2f35Inner(links);
	}

	protected ERACK s2f37Inner(CEED ceed, List<? extends Secs2> ces)
			throws SecsSendMessageException
			, SecsWaitReplyMessageException
			, SecsException
			, Secs2Exception
			, InterruptedException {
		return this.gemEventReport.s2f37Inner(ceed, ces);
	}
}
