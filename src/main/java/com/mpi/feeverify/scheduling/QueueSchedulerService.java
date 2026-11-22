package com.mpi.feeverify.scheduling;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.Params;
import com.mpi.feeverify.support.RestrictedExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 队列调度器：记录各队列的预算、等待与开始状态，按“结算月份”分区执行查询。
 *
 * <ul>
 *   <li>预算准入：队列内 RUNNING 任务数达到策略 maxConcurrentTasks 时保持 QUEUED 等待；</li>
 *   <li>扫描配额：累计扫描行数达到 maxTotalRows 时在分区边界暂停（BUDGET_PAUSED），保留检查点；</li>
 *   <li>运行中策略变更：每个分区开始前重新解析资源策略，新上限更紧时暂停未开始分区，
 *       但绝不强杀已提交事务——已开始分区必定执行完并写入检查点；</li>
 *   <li>跨时段：恢复时可切换 DAY/NIGHT 窗口，夜间使用更大扫描配额继续未完成分区。</li>
 * </ul>
 */
@Service
public class QueueSchedulerService {

    private final JdbcTemplate jdbc;
    private final RestrictedExecutor executor;
    private final ResourcePolicyService policyService;
    private final SubscriptionService subscriptionService;

    @Value("${verify.scheduling.default-keep-alive-ms:60000}")
    private long defaultKeepAliveMs;

    public QueueSchedulerService(JdbcTemplate jdbc, RestrictedExecutor executor,
                                 ResourcePolicyService policyService,
                                 SubscriptionService subscriptionService) {
        this.jdbc = jdbc;
        this.executor = executor;
        this.policyService = policyService;
        this.subscriptionService = subscriptionService;
    }

    /** 执行一轮：推进目标任务的未完成分区，返回本次执行报告。 */
    public Map<String, Object> run(long jobId, String windowOverride, Integer maxPartitions) {
        Map<String, Object> job = loadJob(jobId);
        String status = String.valueOf(job.get("status"));
        if (List.of("COMPLETED", "CANCELLED", "FAILED").contains(status)) {
            throw ApiException.conflict("任务 " + jobId + " 已处于终态 " + status);
        }
        if (Boolean.TRUE.equals(job.get("cancel_requested"))) {
            return cancelJob(jobId, "取消请求在安全点被处理");
        }

        String window = windowOverride == null ? String.valueOf(job.get("window_name"))
                : windowOverride.toUpperCase();
        if (!List.of("DAY", "NIGHT").contains(window)) {
            throw ApiException.badRequest("window 仅支持 DAY / NIGHT");
        }
        if (!window.equals(job.get("window_name"))) {
            jdbc.update("UPDATE schedule_job SET window_name = ? WHERE id = ?", window, jobId);
            event(jobId, null, "WINDOW_SWITCH", Map.of(
                    "from", job.get("window_name"), "to", window,
                    "note", "跨时段恢复：夜间使用更大扫描配额"));
        }

        // ---- 预算准入：队列并发槽位 ----
        Map<String, Object> policy = policyService.resolve(window,
                String.valueOf(job.get("purpose")), String.valueOf(job.get("role_name")));
        int maxConcurrent = ((Number) policy.get("max_concurrent_tasks")).intValue();
        int runningOthers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_job WHERE queue_name = ? AND status = 'RUNNING' AND id <> ?",
                Integer.class, job.get("queue_name"), jobId);
        if (runningOthers >= maxConcurrent) {
            jdbc.update("UPDATE schedule_job SET status = 'QUEUED' WHERE id = ?", jobId);
            event(jobId, null, "QUEUE_WAIT", Map.of(
                    "queue", job.get("queue_name"), "runningOthers", runningOthers,
                    "budget", maxConcurrent, "waitingMs", waitingMs(job)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("jobId", jobId);
            out.put("queue", job.get("queue_name"));
            out.put("status", "QUEUED");
            out.put("admitted", false);
            out.put("reason", "队列预算已满（运行中 " + runningOthers + " / 预算 " + maxConcurrent + "），保持等待");
            out.put("waitingMs", waitingMs(job));
            return out;
        }

        Limits limits = Limits.of(policy);
        updateLimitSnapshot(jobId, policy);
        jdbc.update("UPDATE schedule_job SET status = 'RUNNING', started_at = COALESCE(started_at, now()),"
                + " last_heartbeat_at = now(), paused_reason = NULL WHERE id = ?", jobId);
        event(jobId, null, "JOB_STARTED", Map.of(
                "window", window, "queue", job.get("queue_name"),
                "policyId", policy.get("id"), "limits", limits.toMap()));

        // maxPartitions=0：只做预算准入、占用槽位而不执行分区（用于演示队列预算与等待）
        if (maxPartitions != null && maxPartitions == 0) {
            event(jobId, null, "JOB_HOLD_RUNNING", Map.of(
                    "note", "已准入并占用队列槽位，等待后续调度推进分区"));
            Map<String, Object> held = jobDetail(jobId);
            held.put("admitted", true);
            held.put("heldRunning", true);
            return held;
        }

        List<String> partitions = partitionKeys(Jsons.asMap(job.get("params")));
        int budget = maxPartitions == null ? Integer.MAX_VALUE : maxPartitions;
        List<Map<String, Object>> executed = new ArrayList<>();
        String terminalReason = null;
        String finalStatus = "RUNNING";

        try {
            for (String partition : partitions) {
                if (budget-- <= 0) {
                    finalStatus = "BUDGET_PAUSED";
                    terminalReason = "本轮分区上限已到，保留检查点等待下次调度";
                    break;
                }
                // 已提交事务的分区不再重跑（检查点已完成）
                String cpStatus = jdbc.query(
                        "SELECT status FROM query_checkpoint WHERE job_id = ? AND partition_key = ?",
                        rs -> rs.next() ? rs.getString(1) : null, jobId, partition);
                if ("DONE".equals(cpStatus)) {
                    continue;
                }

                // 取消：仅在分区安全点生效
                Boolean cancelRequested = jdbc.queryForObject(
                        "SELECT cancel_requested FROM schedule_job WHERE id = ?", Boolean.class, jobId);
                if (Boolean.TRUE.equals(cancelRequested)) {
                    finalStatus = "CANCELLED";
                    terminalReason = "收到取消请求，在分区安全点停止；已完成分区检查点保留";
                    break;
                }

                // 运行中策略刷新：分区开始前重解析，绝不影响已开始分区
                Map<String, Object> refreshed = policyService.resolve(window,
                        String.valueOf(job.get("purpose")), String.valueOf(job.get("role_name")));
                if (!refreshed.get("id").equals(policy.get("id"))) {
                    policy = refreshed;
                    limits = Limits.of(policy);
                    updateLimitSnapshot(jobId, policy);
                    event(jobId, null, "POLICY_REFRESH", Map.of(
                            "policyId", policy.get("id"), "version", policy.get("version"),
                            "newLimits", limits.toMap(),
                            "note", "高峰策略在任务运行中生效：仅影响尚未开始的分区"));
                }

                long scanned = jdbc.queryForObject(
                        "SELECT scanned_rows FROM schedule_job WHERE id = ?", Long.class, jobId);
                long remaining = limits.maxTotalRows - scanned;
                if (remaining <= 0) {
                    finalStatus = "BUDGET_PAUSED";
                    terminalReason = "扫描配额 " + limits.maxTotalRows + " 行已用尽（已扫描 " + scanned
                            + " 行），暂停未开始分区并保留检查点";
                    event(jobId, null, "BUDGET_EXHAUSTED", Map.of(
                            "maxTotalRows", limits.maxTotalRows, "scannedRows", scanned,
                            "checkpointsPreserved", true));
                    break;
                }

                Map<String, Object> result = runPartition(job, partition, limits);
                executed.add(result);
                if ("FAILED".equals(result.get("status"))) {
                    finalStatus = "FAILED";
                    terminalReason = "分区 " + partition + " 执行失败：" + result.get("error");
                    break;
                }
                long rows = ((Number) result.get("scannedRows")).longValue();
                long newScanned = scanned + rows;
                jdbc.update("UPDATE schedule_job SET scanned_rows = ?,"
                                + " completed_partitions = completed_partitions + 1,"
                                + " last_heartbeat_at = now() WHERE id = ?",
                        newScanned, jobId);
                if (newScanned > limits.maxTotalRows) {
                    finalStatus = "BUDGET_PAUSED";
                    terminalReason = "分区 " + partition + " 执行后累计扫描 " + newScanned
                            + " 行超过配额 " + limits.maxTotalRows + "；暂停后续分区";
                    event(jobId, null, "BUDGET_EXHAUSTED", Map.of(
                            "maxTotalRows", limits.maxTotalRows, "scannedRows", newScanned,
                            "completedPartition", partition,
                            "note", "已开始的分区完整执行并写入检查点，未强杀事务"));
                    break;
                }
            }

            int done = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM query_checkpoint WHERE job_id = ? AND status = 'DONE'",
                    Integer.class, jobId);
            int total = ((Number) job.get("total_partitions")).intValue();
            if (finalStatus.equals("RUNNING")) {
                finalStatus = done >= total ? "COMPLETED" : "BUDGET_PAUSED";
                if (done < total && terminalReason == null) {
                    terminalReason = "本轮未覆盖全部分区";
                }
            }
            // 配额在最后一个分区后刚好用尽：所有分区已完成，直接收尾而不是挂起
            if (finalStatus.equals("BUDGET_PAUSED") && done >= total) {
                finalStatus = "COMPLETED";
            }
            if (finalStatus.equals("COMPLETED")) {
                return finalizeCompleted(jobId, limits);
            }
            if (finalStatus.equals("CANCELLED")) {
                return cancelJob(jobId, terminalReason);
            }
            if (finalStatus.equals("FAILED")) {
                jdbc.update("UPDATE schedule_job SET status = 'FAILED', error = ?, finished_at = now()"
                        + " WHERE id = ?", terminalReason, jobId);
                subscriptionService.markJobTerminal(jobId, "FAILED", terminalReason);
                event(jobId, null, "JOB_FAILED", Map.of("reason", terminalReason));
                return jobDetail(jobId);
            }
            jdbc.update("UPDATE schedule_job SET status = 'BUDGET_PAUSED', paused_reason = ?,"
                    + " last_heartbeat_at = now() WHERE id = ?", terminalReason, jobId);
            event(jobId, null, "JOB_PAUSED", Map.of(
                    "reason", terminalReason, "window", window,
                    "scannedRows", jdbc.queryForObject(
                            "SELECT scanned_rows FROM schedule_job WHERE id = ?", Long.class, jobId)));
            subscriptionService.markJobPaused(jobId, terminalReason);
        } catch (RuntimeException e) {
            jdbc.update("UPDATE schedule_job SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ?",
                    String.valueOf(e.getMessage()), jobId);
            event(jobId, null, "JOB_FAILED", Map.of("error", String.valueOf(e.getMessage())));
            throw e;
        }

        Map<String, Object> out = jobDetail(jobId);
        out.put("executedPartitions", executed);
        return out;
    }

    /** 执行单个分区：独立只读事务（一个事务 = 一个分区），写入检查点与中间摘要。 */
    private Map<String, Object> runPartition(Map<String, Object> job, String partition, Limits limits) {
        long jobId = ((Number) job.get("id")).longValue();
        Map<String, Object> params = new LinkedHashMap<>(Jsons.asMap(job.get("params")));
        params.put("monthFrom", partition);
        params.put("monthTo", partition);
        String sql = String.valueOf(jdbc.queryForObject(
                "SELECT sql_text FROM query_registration WHERE id = ?",
                String.class, job.get("registration_id")));
        List<String> scopeOrgs = Jsons.asList(job.get("scope_orgs")).stream().map(String::valueOf).toList();
        String wrapped = wrapWithScope(sql, scopeOrgs);

        jdbc.update("UPDATE query_checkpoint SET status = 'RUNNING', started_at = now()"
                + " WHERE job_id = ? AND partition_key = ?", jobId, partition);
        long start = System.currentTimeMillis();
        try {
            List<Map<String, Object>> rows = executor.inRestrictedTx(
                    limits.statementTimeoutMs, Math.min(limits.statementTimeoutMs, 5000),
                    limits.workMem, limits.maxParallelWorkers,
                    s -> s.np().queryForList(wrapped, RestrictedExecutor.typedParams(params)));
            long elapsed = System.currentTimeMillis() - start;
            List<Map<String, Object>> normalized = normalizeRows(rows);
            BigDecimal total = BigDecimal.ZERO;
            Map<String, BigDecimal> orgGroup = new TreeMap<>();
            for (Map<String, Object> row : normalized) {
                BigDecimal amount = row.get("amount") instanceof BigDecimal b ? b : BigDecimal.ZERO;
                total = total.add(amount);
                Object org = row.get("org_code");
                if (org != null) {
                    orgGroup.merge(String.valueOf(org), amount, BigDecimal::add);
                }
            }
            jdbc.update("UPDATE query_checkpoint SET status = 'DONE', scanned_rows = ?, result_rows = ?,"
                            + " total_amount = ?, org_group = ?::jsonb, rows = ?::jsonb,"
                            + " elapsed_ms = ?, finished_at = now()"
                            + " WHERE job_id = ? AND partition_key = ?",
                    rows.size(), rows.size(), total, Jsons.write(orgGroup), Jsons.write(normalized),
                    elapsed, jobId, partition);
            event(jobId, null, "PARTITION_CHECKPOINT", Map.of(
                    "partition", partition, "scannedRows", rows.size(),
                    "elapsedMs", elapsed, "limit", limits.statementTimeoutMs));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("partition", partition);
            out.put("status", "DONE");
            out.put("scannedRows", rows.size());
            out.put("elapsedMs", elapsed);
            return out;
        } catch (RuntimeException e) {
            String msg = String.valueOf(e.getMessage());
            boolean timeout = msg != null && msg.contains("statement timeout");
            jdbc.update("UPDATE query_checkpoint SET status = 'FAILED' WHERE job_id = ? AND partition_key = ?",
                    jobId, partition);
            event(jobId, null, timeout ? "PARTITION_TIMEOUT" : "PARTITION_FAILED", Map.of(
                    "partition", partition, "error", msg));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("partition", partition);
            out.put("status", "FAILED");
            out.put("error", msg);
            return out;
        }
    }

    /** 把授权机构范围强制施加在登记语句外层：宽权限帐号也不得越权读取。 */
    private String wrapWithScope(String sql, List<String> orgs) {
        if (orgs == null || orgs.isEmpty() || orgs.contains("*")) {
            return "SELECT * FROM (\n" + sql + "\n) q";
        }
        String list = String.join(",", orgs.stream().map(o -> "'" + o.replace("'", "''") + "'").toList());
        return "SELECT * FROM (\n" + sql + "\n) q WHERE q.org_code IN (" + list + ")";
    }

    /** 完成收尾：汇总检查点中间摘要，裁剪载荷，分发订阅。 */
    private Map<String, Object> finalizeCompleted(long jobId, Limits limits) {
        Map<String, Object> job = loadJob(jobId);
        List<Map<String, Object>> checkpoints = jdbc.queryForList(
                "SELECT partition_key, result_rows, total_amount, org_group, rows FROM query_checkpoint"
                        + " WHERE job_id = ? AND status = 'DONE' ORDER BY partition_key", jobId);
        long totalRows = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<String, BigDecimal> orgGroup = new TreeMap<>();
        List<Map<String, Object>> payloadRows = new ArrayList<>();
        for (Map<String, Object> cp : checkpoints) {
            totalRows += ((Number) cp.get("result_rows")).longValue();
            totalAmount = totalAmount.add(new BigDecimal(String.valueOf(cp.get("total_amount"))));
            Jsons.asMap(cp.get("org_group")).forEach((k, v) ->
                    orgGroup.merge(k, new BigDecimal(String.valueOf(v)), BigDecimal::add));
            if (payloadRows.size() < limits.resultRowCap) {
                for (Map<String, Object> row : Jsons.asListOfMaps(cp.get("rows"))) {
                    if (payloadRows.size() >= limits.resultRowCap) {
                        break;
                    }
                    payloadRows.add(row);
                }
            }
        }
        boolean truncated = totalRows > limits.resultRowCap;
        String payload = Jsons.write(payloadRows);
        long bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String checksum = String.valueOf(jdbc.queryForObject(
                "SELECT s.checksum FROM data_snapshot s JOIN query_fingerprint f ON f.snapshot_id = s.id"
                        + " JOIN schedule_job j ON j.fingerprint_id = f.id WHERE j.id = ?",
                String.class, jobId));

        jdbc.update("DELETE FROM result_payload WHERE job_id = ?", jobId);
        jdbc.update("INSERT INTO result_payload (job_id, snapshot_checksum, row_count, total_amount,"
                        + " org_group, rows, bytes, status) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,'AVAILABLE')",
                jobId, checksum, totalRows, totalAmount, Jsons.write(orgGroup), payload, bytes);
        jdbc.update("UPDATE schedule_job SET status = ?, result_rows = ?, truncated = ?,"
                        + " finished_at = now(), last_heartbeat_at = now(), paused_reason = NULL WHERE id = ?",
                truncated ? "PARTIAL" : "COMPLETED", totalRows, truncated, jobId);

        int delivered = subscriptionService.markJobReady(jobId, truncated);
        event(jobId, null, "JOB_COMPLETED", Map.of(
                "resultRows", totalRows, "payloadRows", payloadRows.size(),
                "payloadBytes", bytes, "truncated", truncated,
                "subscribersNotified", delivered,
                "lifecycle", "结果载荷在订阅 keepAlive 到期或被取消后由回收器释放"));
        Map<String, Object> out = jobDetail(jobId);
        out.put("payloadBytes", bytes);
        out.put("payloadRows", payloadRows.size());
        out.put("subscribersNotified", delivered);
        return out;
    }

    private Map<String, Object> cancelJob(long jobId, String reason) {
        jdbc.update("UPDATE schedule_job SET status = 'CANCELLED', finished_at = now(),"
                + " paused_reason = ? WHERE id = ? AND status <> 'CANCELLED'", reason, jobId);
        subscriptionService.markJobTerminal(jobId, "CANCELLED", reason);
        event(jobId, null, "JOB_CANCELLED", Map.of("reason", reason,
                "checkpointsPreserved", true, "note", "已开始的分区不中断，已完成检查点保留"));
        return jobDetail(jobId);
    }

    private void updateLimitSnapshot(long jobId, Map<String, Object> policy) {
        jdbc.update("UPDATE schedule_job SET resource_policy_id = ?, limit_snapshot = ?::jsonb WHERE id = ?",
                policy.get("id"), Jsons.write(Limits.of(policy).toMap()), jobId);
    }

    public List<String> partitionKeys(Map<String, Object> params) {
        YearMonth from = Params.parseMonth(String.valueOf(params.get("monthFrom")));
        YearMonth to = Params.parseMonth(String.valueOf(params.get("monthTo")));
        TreeSet<String> keys = new TreeSet<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            keys.add(m.format(fmt));
        }
        return new ArrayList<>(keys);
    }

    /** 各队列的预算、等待与开始状态。 */
    public List<Map<String, Object>> queues() {
        List<String> names = jdbc.queryForList(
                "SELECT DISTINCT queue_name FROM schedule_job ORDER BY queue_name", String.class);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : names) {
            String[] parts = name.split(":", 3);
            Map<String, Object> policy = policyService.resolve(parts[0],
                    parts.length > 1 ? parts[1] : "*", parts.length > 2 ? parts[2] : "*");
            Map<String, Object> stats = jdbc.queryForMap(
                    "SELECT COUNT(*) FILTER (WHERE status = 'RUNNING') AS running,"
                            + " COUNT(*) FILTER (WHERE status = 'QUEUED') AS queued,"
                            + " COUNT(*) FILTER (WHERE status = 'BUDGET_PAUSED') AS paused,"
                            + " COUNT(*) FILTER (WHERE status IN ('COMPLETED','PARTIAL')) AS completed,"
                            + " COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,"
                            + " COALESCE(MAX(EXTRACT(EPOCH FROM (now() - queued_at)) * 1000)"
                            + "   FILTER (WHERE status IN ('QUEUED','BUDGET_PAUSED')), 0)::bigint AS oldest_wait_ms"
                            + " FROM schedule_job WHERE queue_name = ?", name);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("queue", name);
            q.put("window", parts[0]);
            q.put("purpose", parts.length > 1 ? parts[1] : "*");
            q.put("role", parts.length > 2 ? parts[2] : "*");
            q.put("concurrencyBudget", policy.get("max_concurrent_tasks"));
            q.put("rowQuota", policy.get("max_total_rows"));
            q.put("resultRowCap", policy.get("result_row_cap"));
            q.put("policyId", policy.get("id"));
            q.put("policyVersion", policy.get("version"));
            q.put("running", stats.get("running"));
            q.put("queued", stats.get("queued"));
            q.put("paused", stats.get("paused"));
            q.put("completed", stats.get("completed"));
            q.put("cancelled", stats.get("cancelled"));
            q.put("oldestWaitMs", stats.get("oldest_wait_ms"));
            q.put("admissionState",
                    ((Number) stats.get("running")).intValue()
                            >= ((Number) policy.get("max_concurrent_tasks")).intValue()
                            ? "SATURATED" : "OPEN");
            out.add(q);
        }
        return out;
    }

    /** 任务总览：任务、检查点、订阅、策略与合并决策。 */
    public Map<String, Object> jobDetail(long jobId) {
        Map<String, Object> job = loadJob(jobId);
        List<Map<String, Object>> checkpoints = Jsons.normalizeRows(jdbc.queryForList(
                "SELECT partition_key, status, scanned_rows, result_rows, total_amount, org_group,"
                        + " elapsed_ms, started_at, finished_at FROM query_checkpoint"
                        + " WHERE job_id = ? ORDER BY partition_key", jobId));
        Map<String, Object> out = new LinkedHashMap<>(job);
        out.put("checkpoints", checkpoints);
        out.put("subscriptions", subscriptionService.subscriptionsOfJob(jobId));
        out.put("policy", policyService.get(((Number) job.get("resource_policy_id")).longValue()));
        List<Map<String, Object>> payload = jdbc.queryForList(
                "SELECT id, row_count, total_amount, org_group, bytes, status, created_at, reclaimed_at,"
                        + " reclaim_reason FROM result_payload WHERE job_id = ?", jobId);
        out.put("payload", payload.isEmpty() ? null : Jsons.normalizeRow(payload.get(0)));
        return out;
    }

    public Map<String, Object> loadJob(long jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT j.*, f.fingerprint, f.scope_orgs, f.scope_key, f.registration_id, f.snapshot_id,"
                        + " f.params FROM schedule_job j JOIN query_fingerprint f ON f.id = j.fingerprint_id"
                        + " WHERE j.id = ?", jobId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("调度任务不存在: " + jobId);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> listJobs(String status, int limit) {
        String sql = "SELECT j.id, j.queue_name, j.execution_model, j.status, j.window_name,"
                + " j.total_partitions, j.completed_partitions, j.scanned_rows, j.result_rows,"
                + " j.truncated, j.queued_at, j.started_at, j.finished_at, f.fingerprint, f.scope_key"
                + " FROM schedule_job j JOIN query_fingerprint f ON f.id = j.fingerprint_id";
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql += " WHERE j.status = ?";
            args.add(status);
        }
        sql += " ORDER BY j.id DESC LIMIT ?";
        args.add(Math.min(Math.max(limit, 1), 200));
        return Jsons.normalizeRows(jdbc.queryForList(sql, args.toArray()));
    }

    public List<Map<String, Object>> events(long jobId) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT * FROM scheduling_event WHERE job_id = ? ORDER BY id", jobId));
    }

    void event(Long jobId, Long subscriptionId, String type, Map<String, Object> detail) {
        jdbc.update("INSERT INTO scheduling_event (job_id, subscription_id, event_type, detail)"
                        + " VALUES (?,?,?,?::jsonb)",
                jobId, subscriptionId, type, Jsons.write(detail));
    }

    private long waitingMs(Map<String, Object> job) {
        Object queuedAt = job.get("queued_at");
        if (queuedAt == null) {
            return 0;
        }
        return jdbc.queryForObject(
                "SELECT (EXTRACT(EPOCH FROM (now() - ?::timestamptz)) * 1000)::bigint",
                Long.class, String.valueOf(queuedAt));
    }

    /** 把执行结果中的 Date/Timestamp 等转为可稳定序列化的字符串。 */
    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> {
                if (v instanceof java.sql.Date d) {
                    m.put(k, d.toLocalDate().toString());
                } else if (v instanceof java.sql.Timestamp ts) {
                    m.put(k, ts.toLocalDateTime().toString());
                } else if (v instanceof org.postgresql.util.PGobject pg) {
                    m.put(k, pg.getValue());
                } else {
                    m.put(k, v);
                }
            });
            out.add(m);
        }
        return out;
    }

    /** 生效上限快照。 */
    public record Limits(int maxConcurrentTasks, long maxTotalRows, int resultRowCap,
                         int statementTimeoutMs, String workMem, int maxParallelWorkers) {
        static Limits of(Map<String, Object> policy) {
            return new Limits(
                    ((Number) policy.get("max_concurrent_tasks")).intValue(),
                    ((Number) policy.get("max_total_rows")).longValue(),
                    ((Number) policy.get("result_row_cap")).intValue(),
                    ((Number) policy.get("statement_timeout_ms")).intValue(),
                    String.valueOf(policy.get("work_mem")),
                    ((Number) policy.get("max_parallel_workers")).intValue());
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("maxConcurrentTasks", maxConcurrentTasks);
            m.put("maxTotalRows", maxTotalRows);
            m.put("resultRowCap", resultRowCap);
            m.put("statementTimeoutMs", statementTimeoutMs);
            m.put("workMem", workMem);
            m.put("maxParallelWorkers", maxParallelWorkers);
            return m;
        }
    }
}
