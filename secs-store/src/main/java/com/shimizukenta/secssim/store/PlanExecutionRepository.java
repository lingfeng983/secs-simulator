package com.shimizukenta.secssim.store;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 执行台账仓储（runtime.db 的 plan_execution）。
 *
 * <p><b>绝不写在发送线程上</b>（设计 §2.7.2）：计划周期最小可配到 1ms，
 * 同步 INSERT 会抢单写者锁拖慢所有设备。append() 只进内存队列，
 * 专用写线程每 50ms 或每 64 条批量落库；进程崩溃最多丢最后一个 flush 窗口的
 * 台账，不影响已发出的报文与已施加的效果。幂等键 UNIQUE 兜底去重，
 * 重复键的行按冲突忽略。</p>
 *
 * <p>保留策略：每设备最近 {@link #RETENTION_ROWS} 条或 7 天（先到先裁），
 * 由启动对账与每小时清理任务调用 {@link #trim}。</p>
 */
public final class PlanExecutionRepository implements Closeable {

    /** 每设备保留条数。 */
    public static final int RETENTION_ROWS = 5_000;
    /** 保留时长（毫秒）：7 天。 */
    public static final long RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

    /** 一条执行台账（设计 §2.2.2）。 */
    public record Execution(String deviceId, String planId, long ceid, String idempotencyKey,
                            boolean dryRun, long scheduledAt, long firedAt, String status,
                            String messageJson, String effectJson, String error) {
    }

    /** 台账查询行。 */
    public record ExecutionRow(long id, String deviceId, String planId, long ceid,
                               boolean dryRun, long scheduledAt, long firedAt, String status,
                               String messageJson, String effectJson, String error) {
    }

    /**
     * 一次计划触发的计数（fire_count / skipped_count / last_error）。
     *
     * <p>与台账同走异步批量写队列：发送线程只入内存队列，计数在写线程里
     * 按 {@code (deviceId, planId)} 聚合后一次 upsert，避免每次 fire 抢写锁。</p>
     *
     * @param deviceId  设备 id
     * @param planId    计划 id
     * @param succeeded 是否真的发出去了
     * @param error     错误描述；无则空串
     */
    public record FireRecord(String deviceId, String planId, boolean succeeded, String error) {
    }

    private static final int FLUSH_THRESHOLD = 64;
    private static final long FLUSH_INTERVAL_MS = 50;
    private static final int QUEUE_CAPACITY = 4_096;

    private final Db db;
    /** 台账行与触发计数共用一条队列，保证「台账 + 计数」落在同一批事务里。 */
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread writer;
    private volatile boolean closed;

    /** @param db 双库门面 */
    public PlanExecutionRepository(Db db) {
        this.db = db;
        this.writer = Thread.ofPlatform().name("plan-execution-writer").daemon(true).start(this::drainLoop);
    }

    /**
     * 入队一条台账（非阻塞；队列满时丢弃最旧并在日志记一次——
     * 台账是证据不是业务数据，丢行不报错）。
     *
     * @param execution 台账
     */
    public void append(Execution execution) {
        enqueue(Objects.requireNonNull(execution, "execution"));
    }

    /**
     * 入队一次触发计数（非阻塞；与 {@link #append} 同队列、同批落库）。
     *
     * @param fire 触发计数
     */
    public void appendFire(FireRecord fire) {
        enqueue(Objects.requireNonNull(fire, "fire"));
    }

    private void enqueue(Object item) {
        while (!this.queue.offer(item)) {
            /* 队列满时丢最旧：台账是证据不是业务数据，绝不能让发送线程阻塞 */
            this.queue.poll();
        }
    }

    private void drainLoop() {
        while (!this.closed) {
            try {
                Object first = this.queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<Object> batch = new ArrayList<>(FLUSH_THRESHOLD);
                batch.add(first);
                this.queue.drainTo(batch, FLUSH_THRESHOLD - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                /* 单批失败不终止写线程；台账丢弃但进程继续 */
            }
        }
        /* 关闭前把剩余的冲完 */
        List<Object> remaining = new ArrayList<>();
        this.queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            try {
                flush(remaining);
            } catch (RuntimeException e) {
                /* 停机路径，尽力而为 */
            }
        }
    }

    /** 台账行 + 触发计数落在同一个写事务里（按计划聚合计数，一次 upsert）。 */
    private void flush(List<Object> batch) {
        List<Execution> executions = new ArrayList<>(batch.size());
        List<FireRecord> fires = new ArrayList<>(batch.size());
        for (Object item : batch) {
            if (item instanceof Execution execution) {
                executions.add(execution);
            } else if (item instanceof FireRecord fire) {
                fires.add(fire);
            }
        }
        if (executions.isEmpty() && fires.isEmpty()) {
            return;
        }
        this.db.writeRuntime(connection -> {
            if (!executions.isEmpty()) {
                insertBatch(connection, executions);
            }
            if (!fires.isEmpty()) {
                upsertFires(connection, fires);
            }
            return null;
        });
    }

    private static void insertBatch(Connection connection, List<Execution> batch) {
        try (PreparedStatement st = connection.prepareStatement(
                "INSERT INTO plan_execution(device_id, plan_id, ceid, idempotency_key, dry_run, "
                        + "scheduled_at, fired_at, status, message_json, effect_json, error) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(idempotency_key) DO NOTHING")) {
            for (Execution e : batch) {
                st.setString(1, e.deviceId());
                st.setString(2, e.planId());
                st.setLong(3, e.ceid());
                st.setString(4, e.idempotencyKey());
                st.setInt(5, e.dryRun() ? 1 : 0);
                st.setLong(6, e.scheduledAt());
                st.setLong(7, e.firedAt());
                st.setString(8, e.status());
                st.setString(9, e.messageJson());
                st.setString(10, e.effectJson());
                st.setString(11, e.error());
                st.addBatch();
            }
            st.executeBatch();
        } catch (SQLException err) {
            throw new StoreException("台账批量落库失败: " + err.getMessage(), err);
        }
    }

    /** 按 (deviceId, planId) 聚合：fire_count += 条数，skipped_count += 失败条数。 */
    private static void upsertFires(Connection connection, List<FireRecord> fires) {
        Map<String, Aggregated> aggregated = new LinkedHashMap<>();
        for (FireRecord fire : fires) {
            if (fire.deviceId() == null || fire.planId() == null) {
                continue;
            }
            String key = fire.deviceId() + '\u0000' + fire.planId();
            Aggregated agg = aggregated.computeIfAbsent(key,
                    ignored -> new Aggregated(fire.deviceId(), fire.planId()));
            agg.count++;
            if (!fire.succeeded()) {
                agg.skipped++;
            }
            agg.lastError = fire.error() == null ? "" : fire.error();
        }
        if (aggregated.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement st = connection.prepareStatement(
                "INSERT INTO runtime_plan_state(device_id, plan_id, enabled, start_at, last_fire_at, "
                        + "fire_count, skipped_count, last_error) VALUES(?,?,1,?,?,?,?,?) "
                        + "ON CONFLICT(device_id, plan_id) DO UPDATE SET "
                        + "last_fire_at=excluded.last_fire_at, "
                        + "fire_count=fire_count+excluded.fire_count, "
                        + "skipped_count=skipped_count+excluded.skipped_count, "
                        + "last_error=excluded.last_error")) {
            for (Aggregated agg : aggregated.values()) {
                st.setString(1, agg.deviceId);
                st.setString(2, agg.planId);
                st.setLong(3, now);
                st.setLong(4, now);
                st.setInt(5, agg.count);
                st.setInt(6, agg.skipped);
                st.setString(7, agg.lastError);
                st.addBatch();
            }
            st.executeBatch();
        } catch (SQLException err) {
            throw new StoreException("计划触发计数落库失败: " + err.getMessage(), err);
        }
    }

    /** 批内聚合累加器。 */
    private static final class Aggregated {
        private final String deviceId;
        private final String planId;
        private int count;
        private int skipped;
        private String lastError = "";

        private Aggregated(String deviceId, String planId) {
            this.deviceId = deviceId;
            this.planId = planId;
        }
    }

    /**
     * 查询台账（新→旧）。
     *
     * @param deviceId 设备；null = 全部
     * @param planId   计划；null = 全部
     * @param status   状态过滤；null = 全部
     * @param includeDryRun false = 只看真实触发（dry_run=0）
     * @param limit    条数上限
     * @return 台账列表
     */
    public List<ExecutionRow> query(String deviceId, String planId, String status,
                                    boolean includeDryRun, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, device_id, plan_id, ceid, dry_run, scheduled_at, fired_at, status, "
                        + "message_json, effect_json, error FROM plan_execution WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (deviceId != null && !deviceId.isBlank()) {
            sql.append(" AND device_id=?");
            params.add(deviceId);
        }
        if (planId != null && !planId.isBlank()) {
            sql.append(" AND plan_id=?");
            params.add(planId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            params.add(status);
        }
        if (!includeDryRun) {
            sql.append(" AND dry_run=0");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(Math.max(1, limit));
        try (Connection connection = this.db.readRuntime();
             PreparedStatement st = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                st.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = st.executeQuery()) {
                List<ExecutionRow> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new ExecutionRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getInt(5) == 1, rs.getLong(6), rs.getLong(7),
                            rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new StoreException("查台账失败: " + e.getMessage(), e);
        }
    }

    /** 保留策略裁剪（每设备最近 N 条或 7 天，先到先裁）。 */
    public void trim() {
        this.db.writeRuntime(connection -> {
            try (var st = connection.createStatement()) {
                st.executeUpdate("DELETE FROM plan_execution WHERE id <= ("
                        + "SELECT id FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY device_id "
                        + "ORDER BY id DESC) AS rn FROM plan_execution) WHERE rn > " + RETENTION_ROWS + ")");
                st.executeUpdate("DELETE FROM plan_execution WHERE fired_at < "
                        + (System.currentTimeMillis() - RETENTION_MS));
            } catch (SQLException e) {
                throw new StoreException("台账裁剪失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.writer.interrupt();
        try {
            this.writer.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
