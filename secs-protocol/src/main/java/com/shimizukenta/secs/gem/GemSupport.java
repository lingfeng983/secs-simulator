package com.shimizukenta.secs.gem;

import java.util.concurrent.atomic.AtomicLong;

import com.shimizukenta.secs.AbstractSecsCommunicator;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Item;

/**
 * GEM 各能力实现共用的支撑对象：通信器、配置与 ID 构造。
 *
 * <p>原来这些都堆在 {@code AbstractGem} 里，六十几种报文混在一个类中。
 * Java 只能有一个父类，所以这里不做成基类，而是做成一个被各能力实现
 * <b>持有</b>的对象：六个能力类各自独立，共享同一份状态。</p>
 */
public final class GemSupport {

	/** 通信器。 */
	public final SecsCommunicator comm;

	/** GEM 配置。 */
	public final AbstractGemConfig config;

	/** 自动分配的 DATAID 计数器。 */
	private final AtomicLong autoDataId = new AtomicLong();

	/** 自动分配的 REPORTID 计数器。 */
	private final AtomicLong autoReportSeq = new AtomicLong();

	public GemSupport(AbstractSecsCommunicator communicator, AbstractGemConfig config) {
		this.comm = communicator;
		this.config = config;
	}

	protected Secs2 createSecs2Number(Secs2Item item, long... values) {
		switch ( item ) {
		case INT1: {
			return Secs2.int1(values);
			/* break */
		}
		case INT2: {
			return Secs2.int2(values);
			/* break */
		}
		case INT4: {
			return Secs2.int4(values);
			/* break */
		}
		case INT8: {
			return Secs2.int8(values);
			/* break */
		}
		case UINT1: {
			return Secs2.uint1(values);
			/* break */
		}
		case UINT2: {
			return Secs2.uint2(values);
			/* break */
		}
		case UINT4: {
			return Secs2.uint4(values);
			/* break */
		}
		case UINT8: {
			return Secs2.uint8(values);
			/* break */
		}
		default: {
			throw new IllegalArgumentException("Not support " + item.toString());
		}
		}
	}

	public Secs2 autoDataId() {
		return dataId(autoDataId.incrementAndGet());
	}

	public Secs2 dataId(long id) {
		return createSecs2Number(config.dataIdSecs2Item().get(), id);
	}

	public Secs2 autoReportId() {
		return reportId(this.autoReportSeq.incrementAndGet());
	}

	public Secs2 reportId(long id) {
		return createSecs2Number(config.reportIdSecs2Item().get(), id);
	}

	public Secs2 vId(long id) {
		return createSecs2Number(config.vIdSecs2Item().get(), id);
	}

	public Secs2 collectionEventId(long id) {
		return createSecs2Number(config.collectionEventIdSecs2Item().get(), id);
	}
}
