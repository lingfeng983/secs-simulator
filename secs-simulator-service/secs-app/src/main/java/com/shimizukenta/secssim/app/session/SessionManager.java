package com.shimizukenta.secssim.app.session;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.shimizukenta.secssim.app.log.Log;

/**
 * 会话注册表。
 *
 * <p>进程内所有会话都挂在这里，便于统一起停与状态查询；
 * 每个会话的数据、曲线、随机源互不共享，这是"多端口互不干扰"的落点。
 * </p>
 */
public final class SessionManager {

    private final ConcurrentMap<String, SessionRuntime> sessions = new ConcurrentHashMap<>();

    /** 注册观察者：启动装配用它给每个新会话挂执行器（3.0 M4）。 */
    private final CopyOnWriteArrayList<Consumer<SessionRuntime>> registerObservers =
            new CopyOnWriteArrayList<>();

    /** 关闭后不再接受注册（见 {@link #closeAll()}）。 */
    private volatile boolean closed;

    /**
     * 加一个注册观察者。
     *
     * <p>只监听<b>新注册</b>的会话；已存在的会话请直接遍历 {@link #list()}。
     * 观察者异常不会回滚注册——会话已经在表里，回滚会造成"表里有会话、
     * 创建方以为失败"的端口泄漏，只记日志。</p>
     *
     * @param observer 观察者
     */
    public void onRegister(Consumer<SessionRuntime> observer) {
        this.registerObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    /**
     * 注册会话。
     *
     * @param runtime 会话
     * @throws IllegalArgumentException 标识重复
     */
    public void register(SessionRuntime runtime) {
        synchronized ( this ) {
            if ( this.closed ) {
                throw new IllegalStateException("session manager is already closed: " + runtime.id());
            }
            SessionRuntime previous = this.sessions.putIfAbsent(runtime.id(), runtime);
            if (previous != null) {
                throw new IllegalArgumentException("session already registered: " + runtime.id());
            }
        }
        for (Consumer<SessionRuntime> observer : this.registerObservers) {
            try {
                observer.accept(runtime);
            } catch (RuntimeException e) {
                Log.error("会话注册观察者失败（会话已注册）: " + runtime.id(), e);
            }
        }
    }

    /**
     * 按标识取会话。
     *
     * @param id 标识
     * @return 会话
     */
    public Optional<SessionRuntime> find(String id) {
        return Optional.ofNullable(this.sessions.get(id));
    }

    /**
     * 全部会话（按标识排序，便于稳定输出）。
     *
     * @return 会话列表
     */
    public List<SessionRuntime> list() {
        return this.sessions.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
    }

    /**
     * 关闭并移除全部会话。
     *
     * <p>先原子换出整张表再逐个关闭：原来的"遍历后 clear"在并发 register 时
     * 会把新会话从表中抹掉却不关闭它——端口与线程泄漏，且再也找不到这个会话。
     * 关闭后 {@link #register} 会直接拒绝，避免"关闭中的管理器又长出新会话"。</p>
     */
    public void closeAll() {
        final List<SessionRuntime> snapshot;
        synchronized ( this ) {
            this.closed = true;
            snapshot = List.copyOf(this.sessions.values());
            this.sessions.clear();
        }
        snapshot.forEach(SessionRuntime::close);
    }

    /**
     * 关闭并移除一个会话。
     *
     * <p><b>先摘出注册表再关闭</b>：关闭会解绑端口、停线程，耗时不可控；
     * 反过来（先关后摘）的话，并发的 {@code find} 会在会话已经在关闭时
     * 仍然把它当成可用会话返回。
     * </p>
     *
     * @param id 标识
     * @return 存在并已关闭返回 {@code true}
     */
    public boolean remove(String id) {
        SessionRuntime removed = this.sessions.remove(id);
        if (removed == null) {
            return false;
        }
        removed.close();
        return true;
    }
}
