package com.shimizukenta.secs;

import java.io.Serializable;
import java.util.Objects;

import com.shimizukenta.secs.gem.AbstractGemConfig;

/**
 * This abstract class is implementation of communicate config.
 * 
 * <p>
 * To set device-id, {@link #deviceId(int)}<br />
 * To set is-equip, {@link #isEquip(boolean)}<br />
 * To set maximum receive-message bytes, {@link #maxMessageBytes(long)}<br />
 * To set timeouts, {@link #timeout()}<br />
 * To set gem config, {@link #gem()}<br />
 * To set log-subject-header, {@link #logSubjectHeader(CharSequence)}
 * </p>
 * 
 * @author kenta-shimizu
 *
 */
public abstract class AbstractSecsCommunicatorConfig implements Serializable {
	
	private static final long serialVersionUID = -8456991094606676409L;
	
	private static final long DEFAULT_MAX_MESSAGE_BYTES = 8L * 1024L * 1024L;
	
	private final SecsTimeout timeout = new SecsTimeout();
	private final NumberProperty deviceId = NumberProperty.newInstance(10);
	private final NumberProperty maxMessageBytes = NumberProperty.newInstance(DEFAULT_MAX_MESSAGE_BYTES);
	private final BooleanProperty isEquip = BooleanProperty.newInstance(false);
	
	private final AbstractGemConfig gem = new AbstractGemConfig() {
		private static final long serialVersionUID = -3386783271396322749L;
	};
	
	private final StringProperty logSubjectHeader = StringProperty.newInstance("");
	
	public AbstractSecsCommunicatorConfig() {
		/* Nothing */
	}
	
	/**
	 * Device-ID setter.
	 * 
	 * @param id Device-ID. value is {@code 0} to {@code 65535}
	 */
	public void deviceId(int id) {
		if ( (id < 0) || (id > 0xFFFF) ) {
			throw new IllegalArgumentException("deviceId is 0 to 65535");
		}
		this.deviceId.set(id);
	}
	
	/**
	 * Device-ID getter
	 * 
	 * @return device-id
	 */
	public ReadOnlyNumberProperty deviceId() {
		return deviceId;
	}
	
	/**
	 * Maximum receive-message bytes setter.
	 * 
	 * <p>
	 * The receiver refuses a message whose declared length exceeds this value,
	 * and then the connection is terminated.<br />
	 * Big data collection, such as GetDigitizedData (S2F31/S2F32),
	 * may need to raise this value.
	 * </p>
	 * 
	 * @param v maximum receive-message bytes. value is {@code > 0}
	 */
	public void maxMessageBytes(long v) {
		if ( v <= 0L ) {
			throw new IllegalArgumentException("maxMessageBytes is > 0");
		}
		this.maxMessageBytes.set(v);
	}
	
	/**
	 * Maximum receive-message bytes getter.
	 * 
	 * @return maximum receive-message bytes
	 */
	public ReadOnlyNumberProperty maxMessageBytes() {
		return maxMessageBytes;
	}
	
	/**
	 * Set true if equipment.
	 * 
	 * @param f set {@code true} if equipment
	 */
	public void isEquip(boolean f) {
		this.isEquip.set(f);
	}
	
	/**
	 * getter
	 * 
	 * @return {@code true} if equipment
	 */
	public ReadOnlyBooleanProperty isEquip() {
		return isEquip;
	}
	
	/**
	 * SecsTimeout getter
	 * 
	 * @return SecsTimeout
	 */
	public SecsTimeout timeout() {
		return timeout;
	}
	
	/**
	 * AbstractGemConfig getter.
	 * 
	 * @return AbstractGemConfig
	 */
	public AbstractGemConfig gem() {
		return gem;
	}
	
	/**
	 * if setted, insert header to subject of SecsLog
	 * 
	 * @param header insert header of SecsLog
	 */
	public void logSubjectHeader(CharSequence header) {
		this.logSubjectHeader.set(Objects.requireNonNull(header).toString());
	}
	
	/**
	 * Log-Subject-Header getter
	 * 
	 * @return log-subject-header
	 */
	public ReadOnlyProperty<String> logSubjectHeader() {
		return logSubjectHeader;
	}
	
}
