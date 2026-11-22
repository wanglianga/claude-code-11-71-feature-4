package com.mpi.feeverify.plan;

import com.mpi.feeverify.registration.RegistrationService;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.LockWaitSampler;
import com.mpi.feeverify.support.Params;
import com.mpi.feeverify.support.PlanParser;
import com.mpi.feeverify.support.RestrictedExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划采集器：对登记查询按参数签名分别执行 EXPLAIN，保存执行计划、
 * 估计扫描量、锁等待与代价。相同 SQL 不同参数范围 → 不同参数签名 → 分别采集。
 */
@Service
public class PlanCaptureService {

    private final JdbcTemplate jdbc;
    private final RegistrationService registrationService;
    private final RestrictedExecutor executor;
    private final LockWaitSampler sampler;

    @Value("${verify.shadow.statement-timeout-ms:15000}")
    private int statementTimeoutMs;
    @Value("${verify.shadow.lock-timeout-ms:5000}")
    private int lockTimeoutMs;
    @Value("${verify.shadow.work-mem:16MB}")
    private String workMem;

    public PlanCaptureService(JdbcTemplate jdbc, RegistrationService registrationService,
                              RestrictedExecutor executor, LockWaitSampler sampler) {
        this.jdbc = jdbc;
        this.registrationService = registrationService;
        this.executor = executor;
        this.sampler = sampler;
    }

    public Map<String, Object> capture(long registrationId, Map<String, Object> rawParams) {
        String sql = registrationService.sqlOf(registrationId);
        Map<String, Object> params = Params.normalize(rawParams);
        Params.validate(params);
        String signature = Params.signature(params);
        String paramClass = Params.classify(params);

        LockWaitSampler.Sampled<String> sampled = executor.inRestrictedTx(
                statementTimeoutMs, lockTimeoutMs, workMem,
                session -> sampler.run(session.backendPid(),
                        () -> session.np().queryForObject(
                                "EXPLAIN (COSTS, FORMAT JSON) " + sql,
                                RestrictedExecutor.typedParams(params), String.class)));

        PlanParser.PlanInfo info = PlanParser.parse(sampled.result());

        boolean seenBefore = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM plan_capture WHERE registration_id = ? AND plan_hash = ?)",
                Boolean.class, registrationId, info.planHash()));

        Long id = jdbc.queryForObject(
                "INSERT INTO plan_capture (registration_id, param_signature, param_class, params,"
                        + " plan_hash, plan_json, est_rows, total_cost, lock_wait_ms)"
                        + " VALUES (?,?,?,?::jsonb,?,?::jsonb,?,?,?) RETURNING id",
                Long.class, registrationId, signature, paramClass, Jsons.write(params),
                info.planHash(), sampled.result(), info.estRows(), info.totalCost(),
                sampled.lockWaitMs());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("captureId", id);
        result.put("registrationId", registrationId);
        result.put("paramSignature", signature);
        result.put("paramClass", paramClass);
        result.put("planHash", info.planHash());
        result.put("planSummary", info.summary());
        result.put("estRows", info.estRows());
        result.put("totalCost", info.totalCost());
        result.put("lockWaitMs", sampled.lockWaitMs());
        result.put("newPlanShapeForRegistration", !seenBefore);
        return result;
    }

    public List<Map<String, Object>> listForRegistration(long registrationId) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, param_signature, param_class, params, plan_hash, est_rows, total_cost,"
                        + " lock_wait_ms, captured_at FROM plan_capture"
                        + " WHERE registration_id = ? ORDER BY id", registrationId));
    }
}
