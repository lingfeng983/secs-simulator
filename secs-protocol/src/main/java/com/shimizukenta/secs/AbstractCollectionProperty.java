package com.shimizukenta.secs;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AbstractCollectionProperty<T>
		implements CollectionProperty<T>, Serializable {
	
	private static final long serialVersionUID = -4935312347859335201L;
	
	private final Object sync = new Object();
	
	private Collection<T> vv;
	
	public AbstractCollectionProperty(Collection<T> initial) {
		this.vv = Objects.requireNonNull(initial);
	}

	@Override
	public Collection<T> get() {
		synchronized ( sync ) {
			return vv.stream().map(v -> (T)v).collect(Collectors.toList());
		}
	}
	
	@Override
	public void set(Collection<? extends T> v) {
		throw new UnsupportedOperationException("Unsupport AbstractCollectionProperty#set");
	}
	
	private final Collection<PropertyChangeListener<? super Collection<T>>> listeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addChangeListener(PropertyChangeListener<? super Collection<T>> listener) {
		boolean f;
		Collection<T> current;
		synchronized ( sync ) {
			f = listeners.add(listener);
			current = new ArrayList<>(this.vv);
		}
		/* 回调在锁外：监听器里再读属性/改属性不会和这里的锁互相等 */
		if ( f ) {
			notifyOne(listener, current);
		}
		return f;
	}

	@Override
	public boolean removeChangeListener(PropertyChangeListener<? super Collection<T>> listener) {
		return listeners.remove(listener);
	}
	
	protected void notifyChanged() {
		Collection<T> snapshot;
		synchronized ( sync ) {
			snapshot = new ArrayList<>(this.vv);
			sync.notifyAll();
		}
		listeners.forEach(l -> notifyOne(l, snapshot));
	}
	
	/** 单个监听器的回调；异常只影响它自己，不能中断状态迁移链。 */
	private static <T> void notifyOne(PropertyChangeListener<? super Collection<T>> listener, Collection<T> value) {
		try {
			listener.changed(value);
		}
		catch ( RuntimeException ignore ) {
			/* 监听器自己的错误由它负责；这里保住属性状态机 */
		}
	}
	
	private void notifyChanged(boolean f) {
		if ( f ) {
			notifyChanged();
		}
	}

	@Override
	public void waitUntil(Collection<T> v) throws InterruptedException {
		synchronized ( sync ) {
			for ( ;; ) {
				if ( Objects.equals(get(), v)) {
					return;
				}
				sync.wait();
			}
		}
	}

	@Override
	public void waitUntilNot(Collection<T> v) throws InterruptedException {
		synchronized ( sync ) {
			for ( ;; ) {
				if ( ! Objects.equals(get(), v) ) {
					return;
				}
				sync.wait();
			}
		}
	}
	
	
	
	@Override
	public int size() {
		synchronized ( sync ) {
			return get().size();
		}
	}

	@Override
	public boolean isEmpty() {
		synchronized ( sync ) {
			return get().isEmpty();
		}
	}

	@Override
	public boolean contains(Object o) {
		synchronized ( sync ) {
			return get().contains(o);
		}
	}
	
	@Override
	public boolean containsAll(Collection<?> c) {
		synchronized ( sync ) {
			return get().containsAll(c);
		}
	}
	
	@Override
	public Iterator<T> iterator() {
		synchronized ( sync ) {
			return get().stream().map(x -> (T)x).iterator();
		}
	}

	@Override
	public Object[] toArray() {
		synchronized ( sync ) {
			return get().toArray();
		}
	}

	@Override
	public <U> U[] toArray(U[] a) {
		synchronized ( sync ) {
			return get().toArray(a);
		}
	}
	
	@Override
	public boolean add(T v) {
		boolean f;
		synchronized ( sync ) {
			f = vv.add(v);
		}
		notifyChanged(f);
		return f;
	}

	@Override
	public boolean remove(Object o) {
		boolean f;
		synchronized ( sync ) {
			f = vv.remove(o);
		}
		notifyChanged(f);
		return f;
	}

	@Override
	public boolean addAll(Collection<? extends T> c) {
		boolean f;
		synchronized ( sync ) {
			f = vv.addAll(c);
		}
		notifyChanged(f);
		return f;
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		boolean f;
		synchronized ( sync ) {
			f = vv.removeAll(c);
		}
		notifyChanged(f);
		return f;
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		boolean f;
		synchronized ( sync ) {
			f = vv.retainAll(c);
		}
		notifyChanged(f);
		return f;
	}
	
	@Override
	public void clear() {
		boolean changed;
		synchronized ( sync ) {
			changed = ! vv.isEmpty();
			vv.clear();
		}
		if ( changed ) {
			notifyChanged();
		}
	}
	
	@Override
	public boolean removeIf(Predicate<? super T> filter) {
		boolean f;
		synchronized ( sync ) {
			f = vv.removeIf(filter);
		}
		notifyChanged(f);
		return f;
	}
	
	
	
	@Override
	public Spliterator<T> spliterator() {
		synchronized ( sync ) {
			return get().stream().map(x -> (T)x).spliterator();
		}
	}
	
	@Override
	public Stream<T> stream() {
		synchronized ( sync ) {
			return get().stream().map(x -> (T)x);
		}
	}
	
	@Override
	public Stream<T> parallelStream() {
		synchronized ( sync ) {
			return get().parallelStream().map(x -> (T)x);
		}
	}
	
	@Override
	public void forEach(Consumer<? super T> action) {
		synchronized ( sync ) {
			get().forEach(action);
		}
	}

	
}
