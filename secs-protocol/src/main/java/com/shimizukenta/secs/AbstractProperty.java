package com.shimizukenta.secs;

import java.io.Serializable;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Value Getter, Setter, Value-Change-Observer<br />
 * 
 * @author kenta-shimizu
 *
 * @param <T>
 */
public abstract class AbstractProperty<T> implements Property<T>, Serializable {
	
	private static final long serialVersionUID = -7897962203686565003L;
	
	private final Object sync = new Object();
	
	private T present;
	
	public AbstractProperty(T initial) {
		this.present = initial;
	}
	
	@Override
	public T get() {
		synchronized ( sync ) {
			return this.present;
		}
	}
	
	@Override
	public void set(T v) {
		boolean changed;
		synchronized ( sync ) {
			changed = ! Objects.equals(v, this.present);
			if ( changed ) {
				this.present = v;
				sync.notifyAll();
			}
		}
		if ( changed ) {
			dispatch(v);
		}
	}
	
	private final Collection<PropertyChangeListener<? super T>> listeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addChangeListener(PropertyChangeListener<? super T> l) {
		final boolean f;
		final T current;
		synchronized ( sync ) {
			f = listeners.add(l);
			current = this.present;
		}
		/* 回调在锁外：监听器里再 get/set 同一属性不会与本锁互相等待 */
		if ( f ) {
			notifyOne(l, current);
		}
		return f;
	}
	
	@Override
	public boolean removeChangeListener(PropertyChangeListener<? super T> l) {
		return listeners.remove(l);
	}
	
	protected void notifyChanged() {
		final T current;
		synchronized ( sync ) {
			current = this.present;
			sync.notifyAll();
		}
		dispatch(current);
	}
	
	/** 逐个派发；单个监听器抛异常不能中断状态迁移链。 */
	private void dispatch(T value) {
		listeners.forEach(l -> notifyOne(l, value));
	}
	
	private static <T> void notifyOne(PropertyChangeListener<? super T> listener, T value) {
		try {
			listener.changed(value);
		}
		catch ( RuntimeException ignore ) {
			/* 监听器自己的错误由它负责；这里保住属性状态机 */
		}
	}
	
	@Override
	public void waitUntil(T v) throws InterruptedException {
		synchronized ( sync ) {
			for ( ;; ) {
				if ( Objects.equals(get(), v) ) {
					return;
				}
				sync.wait();
			}
		}
	}
	
	@Override
	public void waitUntilNot(T v) throws InterruptedException {
		synchronized ( sync ) {
			for ( ;; ) {
				if ( ! Objects.equals(get(), v)) {
					return;
				}
				sync.wait();
			}
		}
	}
	
	@Override
	public String toString() {
		return Objects.toString(get());
	}
	
	
}
