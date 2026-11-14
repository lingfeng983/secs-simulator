package com.shimizukenta.jsonhub;

import java.io.Serializable;
import java.util.Objects;

/**
 * This class is implements of name and value pair in JSON-OBJECT.
 * 
 * @author kenta-shimizu
 *
 */
public class JsonObjectPair implements Serializable {
	
	private static final long serialVersionUID = 5500254554321018084L;
	
	private final JsonString name;
	private final JsonHub v;
	
	protected JsonObjectPair(JsonString name, JsonHub v) {
		this.name = Objects.requireNonNull(name, "JsonObjectPair nonNull \"name\"");
		this.v = Objects.requireNonNull(v, "JsonObjectPair nonNull \"value\"");
	}
	
	/**
	 * Returns name of pair.
	 * 
	 * @return name
	 */
	public JsonString name() {
		return name;
	}
	
	/**
	 * Returns value of pair.
	 * 
	 * @return value
	 */
	public JsonHub value() {
		return v;
	}
	
	/**
	 * Returns {@code true} if {@code o} is a JsonObjectPair with the same
	 * {@link #name()} and {@link #value()}.
	 * 
	 * <p>
	 * Comparison is based on the getters, consistent with
	 * {@link ObjectJsonHub#equals(Object)}.<br />
	 * </p>
	 * 
	 */
	@Override
	public boolean equals(Object o) {
		if ((o != null) && (o instanceof JsonObjectPair)) {
			JsonObjectPair p = (JsonObjectPair) o;
			return name().equals(p.name()) && value().equals(p.value());
		} else {
			return false;
		}
	}
	
	@Override
	public int hashCode() {
		return Objects.hash(name(), value());
	}
	
	@Override
	public String toString() {
		return "(\"" + name() + "\": " + value() + ")";
	}
}
