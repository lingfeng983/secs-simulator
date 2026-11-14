package com.shimizukenta.secs.gem;

import com.shimizukenta.secs.AbstractSecsCommunicator;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * GEM 接口（SEMI-E30，部分实现）。
 *
 * <p>报文按能力域拆进了子接口，{@code Gem} 是它们的汇总：
 * {@link GemCommunication}（建链与上线）、
 * {@link GemClock}（设备时钟）、
 * {@link GemRemoteCommand}（远程命令）、
 * {@link GemEventReport}（动态事件报告）、
 * {@link GemError}（S9Fx 错误报文）、
 * {@link GemTerminal}（报警确认与终端显示）。
 * 用到某一类能力时依赖对应的子接口即可，不必拖上整个 GEM。</p>
 *
 * <p>要拿到实例，调用 {@link SecsCommunicator#gem()}。</p>
 *
 * @author kenta-shimizu
 *
 */
public interface Gem extends GemCommunication, GemClock, GemRemoteCommand,
        GemEventReport, GemError, GemTerminal {

	/**
	 * Returns new instance.
	 * 
	 * @param communicator
	 * @param config
	 * @return newInstance
	 */
	public static Gem newInstance(AbstractSecsCommunicator communicator, AbstractGemConfig config) {
		return new AbstractGem(communicator, config) {};
	}

	/**
	 * Returns auto number DATAID.
	 * 
	 * @return Auto-DATAID
	 */
	public Secs2 autoDataId();

	/**
	 * Returns DATAID.
	 * 
	 * @param id DATA-ID-Nubmer
	 * @return SECS-II DATAID
	 */
	public Secs2 dataId(long id);

	/**
	 * Returns DynamicEventReportConfig instance.
	 * 
	 * @return DynamicEventReportConfig new-Instance
	 */
	public DynamicEventReportConfig newDynamicEventReportConfig();
}
