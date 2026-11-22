package com.mpi.feeverify.support;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Types;
import java.util.Map;
import java.util.function.Function;

/**
 * 受限执行器：在只读事务内以固定资源限制（statement_timeout / lock_timeout /
 * work_mem / 并行度）运行语句，模拟影子执行与路由执行的受控环境。
 */
@Component
public class RestrictedExecutor {

    private final DataSource dataSource;
    private final PlatformTransactionManager txManager;

    public RestrictedExecutor(DataSource dataSource, PlatformTransactionManager txManager) {
        this.dataSource = dataSource;
        this.txManager = txManager;
    }

    /** 事务内会话：同一连接上的命名参数模板与后端 PID。 */
    public record TxSession(NamedParameterJdbcTemplate np, int backendPid) {
    }

    /**
     * 在只读事务中执行。SET LOCAL 仅作用于本事务，模拟受限资源配额。
     *
     * @param statementTimeoutMs 语句超时（毫秒）
     * @param lockTimeoutMs      锁等待超时（毫秒）
     * @param workMem            工作内存，如 16MB
     */
    public <T> T inRestrictedTx(int statementTimeoutMs, int lockTimeoutMs, String workMem,
                                Function<TxSession, T> body) {
        return inRestrictedTx(statementTimeoutMs, lockTimeoutMs, workMem, 2, body);
    }

    /**
     * 同上，但并行度由调用方指定（调度器按资源策略的 max_parallel_workers 设置）。
     * 所有 SET LOCAL 只作用于本事务，分区提交后即失效，不影响其他分区与会话。
     */
    public <T> T inRestrictedTx(int statementTimeoutMs, int lockTimeoutMs, String workMem,
                                int maxParallelWorkers, Function<TxSession, T> body) {
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        txTemplate.setReadOnly(true);
        return txTemplate.execute(status -> {
            NamedParameterJdbcTemplate np = new NamedParameterJdbcTemplate(dataSource);
            np.getJdbcTemplate().execute("SET LOCAL statement_timeout = " + statementTimeoutMs);
            np.getJdbcTemplate().execute("SET LOCAL lock_timeout = " + lockTimeoutMs);
            np.getJdbcTemplate().execute("SET LOCAL work_mem = '" + workMem + "'");
            np.getJdbcTemplate().execute("SET LOCAL max_parallel_workers_per_gather = "
                    + Math.max(0, maxParallelWorkers));
            Integer pid = np.getJdbcTemplate().queryForObject("SELECT pg_backend_pid()", Integer.class);
            return body.apply(new TxSession(np, pid == null ? -1 : pid));
        });
    }

    /** 类型化参数：为可空参数显式指定 SQL 类型，避免 PostgreSQL 无法推断参数类型。 */
    public static MapSqlParameterSource typedParams(Map<String, Object> p) {
        MapSqlParameterSource s = new MapSqlParameterSource();
        s.addValue("monthFrom", p.get("monthFrom"), Types.VARCHAR);
        s.addValue("monthTo", p.get("monthTo"), Types.VARCHAR);
        s.addValue("orgCode", p.get("orgCode"), Types.VARCHAR);
        s.addValue("settleStatus", p.get("settleStatus"), Types.VARCHAR);
        s.addValue("diagPrefix", p.get("diagPrefix"), Types.VARCHAR);
        s.addValue("pageSize", toInt(p.get("pageSize")), Types.INTEGER);
        s.addValue("pageOffset", toInt(p.get("pageOffset")), Types.INTEGER);
        return s;
    }

    private static Integer toInt(Object v) {
        return v == null ? null : Integer.parseInt(String.valueOf(v));
    }
}
