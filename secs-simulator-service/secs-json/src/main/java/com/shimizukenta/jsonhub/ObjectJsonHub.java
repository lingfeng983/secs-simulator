package com.shimizukenta.jsonhub;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class is implements of JSON value OBJECT.
 * 
 * <p>
 * Instances of this class are immutable.
 * </p>
 * <p>
 * Pairs with duplicate names are all preserved in insertion order;
 * {@link #get(CharSequence)} and {@link #getOrDefault(CharSequence)}
 * return the value of the first matching pair.<br />
 * </p>
 * 
 * @author kenta-shimizu
 *
 */
public class ObjectJsonHub extends AbstractJsonHub {
	
	private static final long serialVersionUID = 6707336078321312442L;
	
	/**
	 * 元素容器。运行时实际是 ArrayList（可序列化）；字段声明为 Collection 接口，
	 * javac 只能看到接口类型，故在此抑制 serial 告警。
	 */
	@SuppressWarnings("serial")
	private final Collection<JsonObjectPair> v;
	private String toJsonCache;
	private String toJsonExcludedNullValueInObjectCache;
	
	/** 名 → 首个匹配值的索引；重复键保持 first-wins 语义。 */
	private transient volatile Map<String, JsonHub> index;
	
	protected ObjectJsonHub(Collection<? extends JsonObjectPair> v) {
		super();
		
		this.v = new ArrayList<>(Objects.requireNonNull(v));
		this.toJsonCache = null;
		this.toJsonExcludedNullValueInObjectCache = null;
		this.index = null;
	}
	
	/**
	 * 惰性建一次名字索引。
	 *
	 * <p>{@code get}/{@code containsKey} 以前每次全表扫描并重新 unescape 键名：
	 * 解析一份几百字段的配置就是 O(n²)。索引只在第一次查找时建，线程安全靠
	 * volatile 发布不可变 Map（重复构建无害，先到先得）。</p>
	 */
	private Map<String, JsonHub> index() {
		Map<String, JsonHub> local = this.index;
		if ( local != null ) {
			return local;
		}
		Map<String, JsonHub> built = new java.util.HashMap<>();
		for ( JsonObjectPair pair : v ) {
			built.putIfAbsent(pair.name().unescaped(), pair.value());
		}
		local = Map.copyOf(built);
		this.index = local;
		return local;
	}
	
	@Override
	public Iterator<JsonHub> iterator() {
		return stream().iterator();
	}
	
	@Override
	public Spliterator<JsonHub> spliterator() {
		return stream().spliterator();
	}
	
	@Override
	public void forEach(Consumer<? super JsonHub> action) {
		stream().forEach(action);
	}
	
	@Override
	public void forEach(BiConsumer<? super JsonString, ? super JsonHub> action) {
		v.forEach(x -> {
			action.accept(x.name(), x.value());
		});
	}
	
	
	@Override
	public JsonHubType type() {
		return JsonHubType.OBJECT;
	}
	
	@Override
	public Stream<JsonHub> stream() {
		return v.stream().map(x -> x.value());
	}
	
	@Override
	public Set<JsonString> keySet() {
		return v.stream().map(x -> x.name()).collect(Collectors.toSet());
	}
	
	protected Collection<JsonObjectPair> objectPairs() {
		return Collections.unmodifiableCollection(v);
	}
	
	@Override
	public List<JsonHub> values() {
		return stream().collect(Collectors.toList());
	}
	
	@Override
	public boolean containsKey(CharSequence name) {
		return index().containsKey(name.toString());
	}
	
	@Override
	public JsonHub get(CharSequence name) {
		return getOrDefault(name, null);
	}
	
	@Override
	public JsonHub get(String... names) {
		return get(new LinkedList<>(Arrays.asList(names)));
	}
	
	private JsonHub get(LinkedList<String> ll) {
		
		if ( ll.isEmpty() ) {
			
			return this;
			
		} else {
			
			String s = ll.removeFirst();
			
			JsonHub x = get(s);
			
			if ( x == null ) {
				return null;
			}
			
			if ( x instanceof ObjectJsonHub ) {
				
				return ((ObjectJsonHub)x).get(ll);
				
			} else {
				
				/* leaf value: return it if last, otherwise path not found */
				return ll.isEmpty() ? x : null;
			}
		}
	}
	
	@Override
	public JsonHub getOrDefault(CharSequence name) {
		return getOrDefault(name, JsonHub.getBuilder().emptyObject());
	}
	
	@Override
	public JsonHub getOrDefault(CharSequence name, JsonHub defaultValue) {
		JsonHub found = index().get(name.toString());
		return found == null ? defaultValue : found;
	}
	
	@Override
	public int length() {
		return v.size();
	}
	
	@Override
	public boolean isEmpty() {
		return v.isEmpty();
	}
	
	@Override
	public String toJson() {
		return toJsonCache();
	}
	
	@Override
	public void toJson(Writer writer) throws IOException {
		writer.write(toJsonCache());
	}
	
	@Override
	public String toJsonExcludedNullValueInObject() {
		return toJsonExcludedNullValueInObjectCache();
	}
	
	@Override
	public void toJsonExcludedNullValueInObject(Writer writer) throws IOException {
		writer.write(toJsonExcludedNullValueInObjectCache());
	}
	
	private String toJsonCache() {
		synchronized ( this ) {
			if ( toJsonCache == null ) {
				toJsonCache = super.toJson();
			}
			
			return toJsonCache;
		}
	}
	
	private String toJsonExcludedNullValueInObjectCache() {
		synchronized ( this ) {
			if ( toJsonExcludedNullValueInObjectCache == null ) {
				toJsonExcludedNullValueInObjectCache = super.toJsonExcludedNullValueInObject();
			}
			
			return toJsonExcludedNullValueInObjectCache;
		}
	}
	
	@Override
	public String toString() {
		return toJson();
	}
	
	@Override
	public boolean equals(Object o) {
		if ((o != null) && (o instanceof ObjectJsonHub)) {
			return ((ObjectJsonHub) o).toJson().equals(toJson());
		} else {
			return false;
		}
	}
	
	@Override
	public int hashCode() {
		return toJson().hashCode();
	}
	
}
