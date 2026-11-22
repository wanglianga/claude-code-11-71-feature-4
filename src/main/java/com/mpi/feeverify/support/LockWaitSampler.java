package com.mpi.feeverify.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 锁等待采样器：在语句执行期间以固定间隔轮询 pg_stat_activity，
 * 累计目标后端进程处于 Lock 等待的事件时长（毫秒，按采样间隔近似）。
 */
@Component
public class LockWaitSampler {

    private static final long INTERVAL_MS = 25;

    private final JdbcTemplate jdbc;

    public LockWaitSampler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param backendPid 执行语句的后端进程 PID（需与采样查询使用不同连接）
     * @param work       实际执行语句的动作
     * @return 执行结果与采样到的锁等待毫秒数
     */
    public <T> Sampled<T> run(int backendPid, Supplier<T> work) {
        AtomicBoolean stop = new AtomicBoolean(false);
        long[] lockWait = {0};
        Thread sampler = new Thread(() -> {
            while (!stop.get()) {
                try {
                    List<String> events = jdbc.queryForList(
                            "SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?",
                            String.class, backendPid);
                    if (events.stream().anyMatch("Lock"::equals)) {
                        lockWait[0] += INTERVAL_MS;
                    }
                    //noinspection BusyWait
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    // 采样失败不影响主执行
                    return;
                }
            }
        }, "lock-wait-sampler");
        sampler.setDaemon(true);
        sampler.start();
        try {
            T result = work.get();
            return new Sampled<>(result, lockWait[0]);
        } finally {
            stop.set(true);
            sampler.interrupt();
        }
    }

    public record Sampled<T>(T result, long lockWaitMs) {
    }
}
