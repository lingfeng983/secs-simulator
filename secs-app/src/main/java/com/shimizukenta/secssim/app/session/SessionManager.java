package com.shimizukenta.secssim.app.session;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话注册表。
 *
 * <p>进程内所有会话都挂在这里，便于统一起停与状态查询；
 * 每个会话的数据、曲线、随机源互不共享，这是"多端口互不干扰"的落点。
 * </p>
 */
public final class SessionManager {

    private final ConcurrentMap<String, SessionRuntime> sessions = new ConcurrentHashMap<>();

    /**
     * 注册会话。
     *
     * @param runtime 会话
     * @throws IllegalArgumentException 标识重复
     */
    public void register(SessionRuntime runtime) {
        SessionRuntime previous = this.sessions.putIfAbsent(runtime.id(), runtime);
        if (previous != null) {
            throw new IllegalArgumentException("session already registered: " + runtime.id());
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
     */
    public void closeAll() {
        this.sessions.values().forEach(SessionRuntime::close);
        this.sessions.clear();
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
