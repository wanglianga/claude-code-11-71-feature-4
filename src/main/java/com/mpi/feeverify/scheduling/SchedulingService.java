package com.mpi.feeverify.scheduling;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.Params;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度编排：把资源策略、请求指纹、队列任务、检查点与订阅串成一条链路。
 *
 * <p>提交请求时：
 * <ol>
 *   <li>鉴权并解析有效授权机构范围（登记授权 ∩ 生效权限策略 ∩ 用户机构）；</li>
 *   <li>按四要素生成指纹：SQL 参数相同而授权范围不同必然不同指纹；</li>
 *   <li>按重复率/结果体积/权限重叠/交互时限决定 PER_REQUEST 或 MERGE；</li>
 *   <li>落库任务与分区检查点，并建立提交者订阅。</li>
 * </ol>
 * 相同指纹的请求：MERGE 时复用同一在途合并任务的订阅；PER_REQUEST 时分别建队，
 * 各自隔离取消与超时，代价是同分区可能被重复扫描。
 */
@Service
public class SchedulingService {

    private final JdbcTemplate jdbc;
    private final ResourcePolicyService policyService;
    private final FingerprintService fingerprintService;
    private final QueueSchedulerService scheduler;
    private final SubscriptionService subscriptionService;

    public SchedulingService(JdbcTemplate jdbc, ResourcePolicyService policyService,
                             FingerprintService fingerprintService,
                             QueueSchedulerService scheduler,
                             SubscriptionService subscriptionService) {
        this.jdbc = jdbc;
        this.policyService = policyService;
        this.fingerprintService = fingerprintService;
        this.scheduler = scheduler;
        this.subscriptionService = subscriptionService;
    }

    @Transactional
    public Map<String, Object> submit(Map<String, Object> body) {
        String token = str(body.get("token"));
        Map<String, Object> user = authenticate(token);
        long registrationId = num(body.get("registrationId"), "registrationId");
        String purpose = str(body.getOrDefault("purpose", "MONTHLY_AUDIT"));
        String role = String.valueOf(user.get("role"));
        String userOrg = user.get("org_code") == null ? null : String.valueOf(user.get("org_code"));
        String window = body.get("window") == null ? policyService.currentWindow()
                : String.valueOf(body.get("window")).toUpperCase();
        String forceModel = body.get("executionModel") == null ? null
                : String.valueOf(body.get("executionModel")).toUpperCase();
        Long keepAlive = body.get("keepAliveMs") == null ? null
                : Long.parseLong(String.valueOf(body.get("keepAliveMs")));
        int priority = body.get("priority") == null ? 100
                : Integer.parseInt(String.valueOf(body.get("priority")));

        Map<String, Object> registration = jdbc.queryForMap(
                "SELECT * FROM query_registration WHERE id = ?", registrationId);
        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT * FROM data_snapshot WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1");
        Map<String, Object> policy = policyService.resolve(window, purpose, role);
        Map<String, Object> permission = jdbc.queryForMap(
                "SELECT * FROM permission_policy WHERE family = 'fee-query' AND status = 'ACTIVE'"
                        + " ORDER BY version DESC LIMIT 1");
        ResourcePolicyService.Scope scope = policyService.resolveScope(role, userOrg,
                Jsons.asMap(registration.get("auth_scope")), permission);

        Map<String, Object> rawParams = body.get("params") instanceof Map<?, ?> m
                ? castMap(m) : Map.of();
        Map<String, Object> params = Params.normalize(rawParams);
        Params.validate(params);

        FingerprintService.Fingerprint fp = fingerprintService.createOrGet(
                registrationId, ((Number) snapshot.get("id")).longValue(),
                String.valueOf(snapshot.get("checksum")), params, scope, purpose, role,
                String.valueOf(permission.get("version")), String.valueOf(registration.get("sql_text")));
        FingerprintService.Decision decision = fingerprintService.decide(fp, forceModel, window);

        // 合并执行：若已有同指纹在途 MERGE 任务，加入订阅而不是新建任务
        if ("MERGE".equals(decision.chosenModel())) {
            List<Map<String, Object>> running = jdbc.queryForList(
                    "SELECT * FROM schedule_job WHERE fingerprint_id = ? AND execution_model = 'MERGE'"
                            + " AND status IN ('QUEUED','RUNNING','BUDGET_PAUSED','PARTIAL')"
                            + " ORDER BY id LIMIT 1", fp.id());
            if (!running.isEmpty()) {
                Map<String, Object> job = running.get(0);
                long jobId = ((Number) job.get("id")).longValue();
                Map<String, Object> sub = subscriptionService.subscribe(
                        jobId, String.valueOf(user.get("username")), role, scope.scopeKey(), priority, keepAlive);
                // 任务已有部分/完整结果载荷时，新订阅直接就绪，避免停在 WAITING 既领不到结果又阻碍回收
                if ("PARTIAL".equals(job.get("status")) || "COMPLETED".equals(job.get("status"))) {
                    subscriptionService.markJobReady(jobId, "PARTIAL".equals(job.get("status")));
                    sub = subscriptionService.get(((Number) sub.get("id")).longValue());
                }
                schedulerEvent(jobId, "SUBSCRIBER_JOINED_MERGE", Map.of(
                        "username", user.get("username"),
                        "fingerprint", fp.fingerprint(), "scopeKey", scope.scopeKey(),
                        "note", "相同指纹并入在途合并任务，仅增加订阅"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("mode", "JOINED_MERGE");
                out.put("jobId", jobId);
                out.put("subscription", sub);
                out.put("fingerprint", fp.components());
                out.put("fingerprintValue", fp.fingerprint());
                out.put("fingerprintReused", true);
                out.put("decision", describeDecision(decision));
                out.put("scopeBasis", scope.basis());
                return out;
            }
        }

        String queueName = window + ":" + purpose + ":" + role;
        List<String> partitions = scheduler.partitionKeys(params);
        Long jobId = jdbc.queryForObject(
                "INSERT INTO schedule_job (fingerprint_id, execution_model, queue_name, window_name,"
                        + " purpose, role_name, resource_policy_id, status, limit_snapshot, total_partitions,"
                        + " merge_decision_id) VALUES (?,?,?,?,?,?,?,'QUEUED',?::jsonb,?,?) RETURNING id",
                Long.class, fp.id(), decision.chosenModel(), queueName, window, purpose, role,
                policy.get("id"), Jsons.write(QueueSchedulerService.Limits.of(policy).toMap()),
                partitions.size(), decision.id());
        jdbc.update("UPDATE schedule_job SET merge_decision_id = ? WHERE id = ?", decision.id(), jobId);
        for (String partition : partitions) {
            jdbc.update("INSERT INTO query_checkpoint (job_id, partition_key) VALUES (?,?)",
                    jobId, partition);
        }
        Map<String, Object> sub = subscriptionService.subscribe(jobId,
                String.valueOf(user.get("username")), role, scope.scopeKey(), priority, keepAlive);
        schedulerEvent(jobId, "JOB_QUEUED", Map.of(
                "fingerprint", fp.fingerprint(), "model", decision.chosenModel(),
                "partitions", partitions, "policyId", policy.get("id"), "window", window,
                "scopeKey", scope.scopeKey()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "QUEUED");
        out.put("jobId", jobId);
        out.put("executionModel", decision.chosenModel());
        out.put("queue", queueName);
        out.put("partitions", partitions);
        out.put("subscription", sub);
        out.put("fingerprint", fp.components());
        out.put("fingerprintValue", fp.fingerprint());
        out.put("scopeBasis", scope.basis());
        out.put("resourcePolicy", summaryPolicy(policy));
        out.put("decision", describeDecision(decision));
        return out;
    }

    /** 同 SQL 不同授权范围指纹对比：证明范围不同必然指纹不同。 */
    public Map<String, Object> compareScopes(long registrationId, Map<String, Object> params,
                                             List<String> tokens) {
        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT * FROM data_snapshot WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1");
        Map<String, Object> registration = jdbc.queryForMap(
                "SELECT * FROM query_registration WHERE id = ?", registrationId);
        Map<String, Object> permission = jdbc.queryForMap(
                "SELECT * FROM permission_policy WHERE family = 'fee-query' AND status = 'ACTIVE'"
                        + " ORDER BY version DESC LIMIT 1");
        List<Map<String, Object>> items = new ArrayList<>();
        for (String token : tokens) {
            Map<String, Object> user = authenticate(token);
            String role = String.valueOf(user.get("role"));
            String userOrg = user.get("org_code") == null ? null : String.valueOf(user.get("org_code"));
            ResourcePolicyService.Scope scope = policyService.resolveScope(role, userOrg,
                    Jsons.asMap(registration.get("auth_scope")), permission);
            FingerprintService.Fingerprint fp = fingerprintService.createOrGet(registrationId,
                    ((Number) snapshot.get("id")).longValue(),
                    String.valueOf(snapshot.get("checksum")), params, scope, "SCOPE_COMPARE", role,
                    String.valueOf(permission.get("version")), String.valueOf(registration.get("sql_text")));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("token", token);
            item.put("username", user.get("username"));
            item.put("role", role);
            item.put("userOrg", userOrg);
            item.put("scopeKey", scope.scopeKey());
            item.put("scopeBasis", scope.basis());
            item.put("fingerprint", fp.fingerprint());
            items.add(item);
        }
        boolean allDistinct = items.stream().map(i -> i.get("fingerprint")).distinct().count()
                == items.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("params", params);
        out.put("items", items);
        out.put("allFingerprintsDistinct", allDistinct);
        out.put("principle", "SQL 参数相同而授权机构范围/角色不同 → 指纹不同，不得共享宽权限结果");
        return out;
    }

    /**
     * 治理视图：合并边界与实际资源收益。
     * 对比 MERGE 任务与同指纹 PER_REQUEST 任务的实际扫描量、结果体积与载荷。
     */
    public Map<String, Object> governanceReport(int limit) {
        List<Map<String, Object>> decisions = Jsons.normalizeRows(jdbc.queryForList(
                "SELECT d.*, f.fingerprint, f.scope_key, f.purpose, f.role_name,"
                        + " (SELECT COUNT(*) FROM schedule_job j WHERE j.merge_decision_id = d.id) AS jobs"
                        + " FROM merge_decision d JOIN query_fingerprint f ON f.id = d.fingerprint_id"
                        + " ORDER BY d.id DESC LIMIT ?", Math.min(Math.max(limit, 1), 100)));

        Map<String, List<Map<String, Object>>> mergeByFingerprint = new LinkedHashMap<>();
        for (Map<String, Object> j : jdbc.queryForList(
                "SELECT j.id, j.fingerprint_id, j.status, j.scanned_rows, j.result_rows,"
                        + " j.completed_partitions, j.total_partitions,"
                        + " (SELECT COUNT(*) FROM query_subscription s WHERE s.job_id = j.id) AS subs,"
                        + " (SELECT COALESCE(bytes,0) FROM result_payload p WHERE p.job_id = j.id) AS bytes"
                        + " FROM schedule_job j WHERE j.execution_model = 'MERGE' ORDER BY j.id DESC")) {
            mergeByFingerprint.computeIfAbsent(
                    String.valueOf(j.get("fingerprint_id")), k -> new ArrayList<>()).add(j);
        }

        List<Map<String, Object>> perRequest = jdbc.queryForList(
                "SELECT j.fingerprint_id, COUNT(*) AS jobs, COALESCE(SUM(j.scanned_rows),0) AS scanned,"
                        + " COALESCE(SUM(j.result_rows),0) AS result_rows"
                        + " FROM schedule_job j WHERE j.execution_model = 'PER_REQUEST'"
                        + " GROUP BY j.fingerprint_id");

        List<Map<String, Object>> rows = new ArrayList<>();
        long totalSavedRows = 0;
        for (Map<String, Object> d : decisions) {
            String fpId = String.valueOf(d.get("fingerprint_id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> benefits = d.get("benefits") instanceof Map<?, ?> bm
                    ? castMap(bm) : Map.of();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("decisionId", d.get("id"));
            row.put("fingerprint", d.get("fingerprint"));
            row.put("scopeKey", d.get("scope_key"));
            row.put("chosenModel", d.get("chosen_model"));
            row.put("duplicateRate", d.get("duplicate_rate"));
            row.put("estimatedResultRows", d.get("estimated_result_rows"));
            row.put("permissionOverlap", d.get("permission_overlap"));
            row.put("interactiveBudgetMs", d.get("interactive_budget_ms"));
            row.put("estimatedScanMs", d.get("estimated_scan_ms"));
            row.put("reasons", d.get("reasons"));
            row.put("estimatedBenefits", benefits);
            Map<String, Object> actual = new LinkedHashMap<>();
            List<Map<String, Object>> mergeJobs = mergeByFingerprint.get(fpId);
            if (mergeJobs != null) {
                long scanned = mergeJobs.stream().mapToLong(m -> ((Number) m.get("scanned_rows")).longValue()).sum();
                long subs = mergeJobs.stream().mapToLong(m -> ((Number) m.get("subs")).longValue()).sum();
                long bytes = mergeJobs.stream().mapToLong(m -> ((Number) m.get("bytes")).longValue()).sum();
                actual.put("mergeExecutions", mergeJobs.size());
                actual.put("actualScannedRows", scanned);
                actual.put("actualSubscribers", subs);
                actual.put("payloadBytes", bytes);
                if (subs > 1) {
                    long saved = scanned * (subs - 1);
                    actual.put("dbRowsSavedByMerge", saved);
                    totalSavedRows += saved;
                }
            }
            Map<String, Object> pr = perRequest.stream()
                    .filter(p -> fpId.equals(String.valueOf(p.get("fingerprint_id"))))
                    .findFirst().orElse(null);
            if (pr != null) {
                actual.put("perRequestJobs", pr.get("jobs"));
                actual.put("perRequestScannedRows", pr.get("scanned"));
                actual.put("perRequestRepeatScan", "同分区被各申请人独立扫描，隔离取消/超时但可能重复扫描");
            }
            row.put("actual", actual);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decisions", rows);
        out.put("totalEstimatedRowsSavedByMerge", totalSavedRows);
        out.put("boundaryPolicy", "重复率高 + 非交互长查询 + 结果体积可控 + 权限高度重叠 → 合并；"
                + "否则按请求隔离，以保证可见性、生命周期简单与取消隔离");
        out.put("queues", scheduler.queues());
        return out;
    }

    private Map<String, Object> describeDecision(FingerprintService.Decision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisionId", d.id());
        m.put("chosenModel", d.chosenModel());
        m.put("duplicateRate", d.duplicateRate());
        m.put("estimatedResultRows", d.estimatedResultRows());
        m.put("permissionOverlap", d.permissionOverlap());
        m.put("interactiveBudgetMs", d.interactiveBudgetMs());
        m.put("estimatedScanMs", d.estimatedScanMs());
        m.put("reasons", d.reasons());
        m.put("benefits", d.benefits());
        return m;
    }

    private Map<String, Object> summaryPolicy(Map<String, Object> p) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String key : List.of("id", "window_name", "purpose", "role_name", "version",
                "max_concurrent_tasks", "max_total_rows", "result_row_cap", "statement_timeout_ms",
                "work_mem", "max_parallel_workers")) {
            m.put(key, p.get(key));
        }
        m.put("matchedExactPurpose", p.get("matchedExactPurpose"));
        m.put("matchedExactRole", p.get("matchedExactRole"));
        return m;
    }

    private Map<String, Object> authenticate(String token) {
        if (token == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "缺少 token");
        }
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT * FROM app_user WHERE token = ?", token);
        if (users.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "token 无效");
        }
        return users.get(0);
    }

    private void schedulerEvent(long jobId, String type, Map<String, Object> detail) {
        jdbc.update("INSERT INTO scheduling_event (job_id, event_type, detail) VALUES (?,?,?::jsonb)",
                jobId, type, Jsons.write(detail));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static long num(Object v, String field) {
        if (!(v instanceof Number n)) {
            throw ApiException.badRequest(field + " 必填且为数字");
        }
        return n.longValue();
    }
}
