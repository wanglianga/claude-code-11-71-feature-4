package com.mpi.feeverify.shadow;

import com.mpi.feeverify.candidate.CandidateService;
import com.mpi.feeverify.registration.RegistrationService;
import com.mpi.feeverify.snapshot.SnapshotService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.LockWaitSampler;
import com.mpi.feeverify.support.Params;
import com.mpi.feeverify.support.PlanParser;
import com.mpi.feeverify.support.RestrictedExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 影子执行器：在冻结快照（snap_* 表）上以固定资源限制运行原语句与候选语句，
 * 保存明细主键集合、总金额、机构级分组、实际计划与锁等待。
 * 验证结论仅代表当前快照；快照更新后旧裁判结论自动标记 STALE。
 */
@Service
public class ShadowExecutorService {

    private final JdbcTemplate jdbc;
    private final CandidateService candidateService;
    private final RegistrationService registrationService;
    private final SnapshotService snapshotService;
    private final RestrictedExecutor executor;
    private final LockWaitSampler sampler;

    @Value("${verify.shadow.statement-timeout-ms:15000}")
    private int statementTimeoutMs;
    @Value("${verify.shadow.lock-timeout-ms:5000}")
    private int lockTimeoutMs;
    @Value("${verify.shadow.work-mem:16MB}")
    private String workMem;

    public ShadowExecutorService(JdbcTemplate jdbc, CandidateService candidateService,
                                 RegistrationService registrationService, SnapshotService snapshotService,
                                 RestrictedExecutor executor, LockWaitSampler sampler) {
        this.jdbc = jdbc;
        this.candidateService = candidateService;
        this.registrationService = registrationService;
        this.snapshotService = snapshotService;
        this.executor = executor;
        this.sampler = sampler;
    }

    /** 对候选与原语句各执行一次影子运行。 */
    public Map<String, Object> execute(long candidateId, Map<String, Object> rawParams) {
        Map<String, Object> candidate = candidateService.get(candidateId);
        long registrationId = ((Number) candidate.get("registration_id")).longValue();
        String originalSql = registrationService.sqlOf(registrationId);
        String candidateSql = String.valueOf(candidate.get("rewritten_sql"));

        Map<String, Object> params = Params.normalize(rawParams);
        Params.validate(params);
        if ("PAGE_WINDOW".equals(candidate.get("compare_mode"))) {
            if (params.get("pageSize") == null) {
                params.put("pageSize", 500);
            }
            if (params.get("pageOffset") == null) {
                params.put("pageOffset", 0);
            }
        }
        @SuppressWarnings("unchecked")
        List<String> requiredNull = Jsons.asList(candidate.get("required_null_params"))
                .stream().map(String::valueOf).toList();
        for (String key : requiredNull) {
            if (params.get(key) != null) {
                throw ApiException.unprocessable("候选 " + candidateId + "（" + candidate.get("rewrite_type")
                        + "）仅在参数 " + key + " 为空时保证等价，当前值=" + params.get(key));
            }
        }

        Map<String, Object> snapshot = snapshotService.active();
        long snapshotId = ((Number) snapshot.get("id")).longValue();

        Map<String, Object> original = runSide(candidateId, snapshotId, "ORIGINAL", originalSql, params);
        Map<String, Object> cand = runSide(candidateId, snapshotId, "CANDIDATE", candidateSql, params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateId", candidateId);
        result.put("snapshotId", snapshotId);
        result.put("snapshotChecksum", snapshot.get("checksum"));
        result.put("original", original);
        result.put("candidate", cand);
        return result;
    }

    private Map<String, Object> runSide(long candidateId, long snapshotId, String side,
                                        String sql, Map<String, Object> params) {
        Map<String, Object> limits = Map.of(
                "statementTimeoutMs", statementTimeoutMs,
                "lockTimeoutMs", lockTimeoutMs,
                "workMem", workMem);
        // 只读事务内执行语句并收集工件；执行记录 INSERT 在事务外进行
        record RunOutcome(String planJson, PlanParser.PlanInfo plan, long lockWaitMs,
                          long elapsedMs, Artifacts artifacts) {
        }
        try {
            RunOutcome outcome = executor.inRestrictedTx(statementTimeoutMs, lockTimeoutMs, workMem,
                    session -> {
                        LockWaitSampler.Sampled<String> planSample = sampler.run(session.backendPid(),
                                () -> session.np().queryForObject(
                                        "EXPLAIN (ANALYZE, TIMING OFF, FORMAT JSON) " + sql,
                                        RestrictedExecutor.typedParams(params), String.class));
                        PlanParser.PlanInfo plan = PlanParser.parse(planSample.result());

                        long start = System.currentTimeMillis();
                        LockWaitSampler.Sampled<List<Map<String, Object>>> rowsSample = sampler.run(
                                session.backendPid(),
                                () -> session.np().queryForList(sql, RestrictedExecutor.typedParams(params)));
                        long elapsed = System.currentTimeMillis() - start;

                        return new RunOutcome(planSample.result(), plan,
                                planSample.lockWaitMs() + rowsSample.lockWaitMs(), elapsed,
                                Artifacts.of(rowsSample.result()));
                    });

            Artifacts artifacts = outcome.artifacts();
            Long id = jdbc.queryForObject(
                    "INSERT INTO shadow_execution (candidate_id, snapshot_id, side, params,"
                            + " resource_limits, status, row_count, total_amount, pk_list, org_group,"
                            + " lock_wait_ms, elapsed_ms, actual_plan)"
                            + " VALUES (?,?,?,?::jsonb,?::jsonb,?,?,?,?::jsonb,?::jsonb,?,?,?::jsonb)"
                            + " RETURNING id",
                    Long.class, candidateId, snapshotId, side, Jsons.write(params),
                    Jsons.write(limits), "SUCCESS", artifacts.rowCount(), artifacts.totalAmount(),
                    Jsons.write(artifacts.pkList()), Jsons.write(artifacts.orgGroup()),
                    outcome.lockWaitMs(), outcome.elapsedMs(), outcome.planJson());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("executionId", id);
            out.put("side", side);
            out.put("status", "SUCCESS");
            out.put("rowCount", artifacts.rowCount());
            out.put("totalAmount", artifacts.totalAmount());
            out.put("orgGroup", artifacts.orgGroup());
            out.put("lockWaitMs", outcome.lockWaitMs());
            out.put("elapsedMs", outcome.elapsedMs());
            out.put("actualRows", outcome.plan().actualRows());
            out.put("planSummary", outcome.plan().summary());
            return out;
        } catch (DataAccessException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            String status = msg != null && msg.contains("statement timeout") ? "TIMEOUT" : "FAILED";
            Long id = jdbc.queryForObject(
                    "INSERT INTO shadow_execution (candidate_id, snapshot_id, side, params,"
                            + " resource_limits, status, error) VALUES (?,?,?,?::jsonb,?::jsonb,?,?)"
                            + " RETURNING id",
                    Long.class, candidateId, snapshotId, side, Jsons.write(params),
                    Jsons.write(limits), status, msg);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("executionId", id);
            out.put("side", side);
            out.put("status", status);
            out.put("error", msg);
            return out;
        }
    }

    /** 供路由器复用：在受限只读事务中执行语句并返回全部行。 */
    public List<Map<String, Object>> fetchRows(String sql, Map<String, Object> params) {
        return executor.inRestrictedTx(statementTimeoutMs, lockTimeoutMs, workMem,
                session -> session.np().queryForList(sql, RestrictedExecutor.typedParams(params)));
    }

    public List<Map<String, Object>> listExecutions(long candidateId) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, side, status, row_count, total_amount, lock_wait_ms, elapsed_ms, created_at"
                        + " FROM shadow_execution WHERE candidate_id = ? ORDER BY id", candidateId));
    }

    /** 执行结果工件：主键集合、总金额、机构级分组。 */
    public record Artifacts(long rowCount, BigDecimal totalAmount, List<Object> pkList,
                            Map<String, BigDecimal> orgGroup) {

        static Artifacts of(List<Map<String, Object>> rows) {
            List<Object> pks = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            Map<String, BigDecimal> orgGroup = new TreeMap<>();
            boolean hasId = !rows.isEmpty() && rows.get(0).containsKey("id");
            for (Map<String, Object> row : rows) {
                if (hasId) {
                    pks.add(row.get("id"));
                }
                BigDecimal amount = row.get("amount") instanceof BigDecimal b ? b : BigDecimal.ZERO;
                total = total.add(amount);
                Object org = row.get("org_code");
                if (org != null) {
                    orgGroup.merge(String.valueOf(org), amount, BigDecimal::add);
                }
            }
            return new Artifacts(rows.size(), total, pks, orgGroup);
        }
    }
}
