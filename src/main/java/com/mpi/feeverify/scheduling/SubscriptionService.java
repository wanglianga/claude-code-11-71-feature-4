package com.mpi.feeverify.scheduling;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订阅与回收器：管理结果订阅、取消语义与资源释放。
 *
 * <p>取消语义按执行模型区分：
 * <ul>
 *   <li>PER_REQUEST（独立任务）：取消只作用于本人任务，立即释放本人资源；</li>
 *   <li>MERGE（合并任务）：单个订阅者取消只退出订阅，不影响其他等待者；
 *       仅当最后一个订阅者也取消时，才取消共享任务并释放资源。
 *       “最后一个取消者”的选择记录在取消原因中，便于审计。</li>
 * </ul>
 *
 * <p>回收器：结果载荷超出订阅保留时长或绝对上限时置 RECLAIMED 并记录释放字节；
 * 长期无人订阅的合并任务在全部订阅过期后一并释放。
 */
@Service
public class SubscriptionService {

    private final JdbcTemplate jdbc;

    @Value("${verify.scheduling.default-keep-alive-ms:60000}")
    private long defaultKeepAliveMs;
    @Value("${verify.scheduling.max-payload-age-ms:3600000}")
    private long maxPayloadAgeMs;

    public SubscriptionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增订阅（同一任务同一用户唯一）。返回订阅摘要。 */
    public Map<String, Object> subscribe(long jobId, String username, String role, String scopeKey,
                                         int priority, Long keepAliveMs) {
        long keep = keepAliveMs == null || keepAliveMs <= 0 ? defaultKeepAliveMs : keepAliveMs;
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT * FROM query_subscription WHERE job_id = ? AND username = ?", jobId, username);
        if (!existing.isEmpty()) {
            String status = String.valueOf(existing.get(0).get("status"));
            if ("CANCELLED".equals(status)) {
                jdbc.update("UPDATE query_subscription SET status = 'WAITING', submitted_at = now(),"
                                + " cancelled_at = NULL, cancel_reason = NULL, cancel_role = NULL,"
                                + " ready_at = NULL, delivered_at = NULL, expires_at = NULL"
                                + " WHERE id = ?", existing.get(0).get("id"));
                return get(((Number) existing.get(0).get("id")).longValue());
            }
            return Jsons.normalizeRow(existing.get(0));
        }
        Long id = jdbc.queryForObject(
                "INSERT INTO query_subscription (job_id, username, role_name, scope_key, priority,"
                        + " keep_alive_ms, status) VALUES (?,?,?,?,?,?,'WAITING') RETURNING id",
                Long.class, jobId, username, role, scopeKey, priority, keep);
        event(jobId, id, "SUBSCRIBED", Map.of(
                "username", username, "priority", priority, "keepAliveMs", keep));
        return get(id);
    }

    /**
     * 取消订阅。返回语义说明：是否是最后一个取消者、是否取消了共享任务、释放情况。
     */
    @Transactional
    public Map<String, Object> cancel(long subscriptionId, String reason) {
        Map<String, Object> sub = get(subscriptionId);
        String status = String.valueOf(sub.get("status"));
        if (!List.of("WAITING", "PARTIAL_READY", "READY", "DELIVERED").contains(status)) {
            throw ApiException.conflict("订阅 " + subscriptionId + " 当前状态 " + status
                    + " 不可取消");
        }
        long jobId = ((Number) sub.get("job_id")).longValue();
        Map<String, Object> job = jdbc.queryForMap("SELECT * FROM schedule_job WHERE id = ?", jobId);
        String model = String.valueOf(job.get("execution_model"));

        int remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM query_subscription WHERE job_id = ? AND id <> ?"
                        + " AND status IN ('WAITING','PARTIAL_READY','READY','DELIVERED')",
                Integer.class, jobId, subscriptionId);
        boolean lastCanceller = remaining == 0;
        jdbc.update("UPDATE query_subscription SET status = 'CANCELLED', cancelled_at = now(),"
                        + " cancel_reason = ?, cancel_role = ? WHERE id = ?",
                reason, lastCanceller ? "LAST_CANCEL" : "REQUESTER", subscriptionId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscriptionId", subscriptionId);
        out.put("jobId", jobId);
        out.put("executionModel", model);
        out.put("lastCanceller", lastCanceller);
        out.put("remainingSubscribers", remaining);

        String jobStatus = String.valueOf(job.get("status"));
        boolean jobActive = !List.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED").contains(jobStatus);

        if ("PER_REQUEST".equals(model)) {
            if (jobActive) {
                jdbc.update("UPDATE schedule_job SET cancel_requested = true, status = CASE"
                        + " WHEN status = 'QUEUED' THEN 'CANCELLED' ELSE status END,"
                        + " paused_reason = ? WHERE id = ?", "申请人取消独立任务：" + reason, jobId);
                if ("QUEUED".equals(jobStatus)) {
                    jdbc.update("UPDATE schedule_job SET finished_at = now() WHERE id = ?", jobId);
                }
            }
            out.put("jobCancelled", true);
            out.put("note", "独立执行模型：取消仅影响本人任务，" + (jobActive
                    ? "在分区安全点停止且保留已完成检查点" : "任务已结束，仅登记取消"));
            event(jobId, subscriptionId, "CANCEL_ISOLATED", Map.of(
                    "reason", reason, "note", "独立任务取消不影响其他申请人的任务与结果"));
        } else {
            if (lastCanceller && jobActive) {
                jdbc.update("UPDATE schedule_job SET status = 'CANCELLED', finished_at = now(),"
                        + " paused_reason = ? WHERE id = ?", "最后一个订阅者取消：" + reason, jobId);
                int reclaimed = reclaimPayload(jobId, "最后一个订阅者取消，释放共享结果载荷");
                out.put("jobCancelled", true);
                out.put("note", "合并执行模型：最后一个订阅者取消，共享任务取消并释放资源（载荷回收 "
                        + reclaimed + " 项）；若仍有其他人等待则任务继续");
            } else if (jobActive) {
                out.put("jobCancelled", false);
                out.put("note", "合并执行模型：仅本人退出订阅，任务继续执行并保留给剩余 " + remaining
                        + " 位等待者，其正确结果不受影响");
            } else {
                // 任务已结束：只有最后一个订阅者取消（无其他有效订阅）时才回收载荷，
                // 否则仍可能有其他人尚未领取，回收会破坏其正确结果。
                int reclaimed = lastCanceller
                        ? reclaimPayload(jobId, "最后一个订阅者取消后释放共享结果载荷") : 0;
                out.put("jobCancelled", false);
                out.put("note", "任务已结束后取消订阅：" + (lastCanceller
                        ? "释放结果载荷（无其他有效订阅者）"
                        : "保留载荷供剩余 " + remaining + " 位订阅者领取"));
            }
            event(jobId, subscriptionId, lastCanceller ? "CANCEL_LAST" : "CANCEL_SHARED_SUBSCRIBER",
                    Map.of("reason", reason, "remainingSubscribers", remaining));
        }
        return out;
    }

    /** 订阅领取：任务就绪后按订阅者授权范围裁剪返回，并标记 DELIVERED + 到期时间。 */
    public Map<String, Object> deliver(long subscriptionId) {
        Map<String, Object> sub = get(subscriptionId);
        String status = String.valueOf(sub.get("status"));
        if (!List.of("READY", "PARTIAL_READY", "DELIVERED").contains(status)) {
            throw ApiException.conflict("订阅 " + subscriptionId + " 当前状态 " + status
                    + " 暂不可领取（任务可能仍在运行或被取消）");
        }
        long jobId = ((Number) sub.get("job_id")).longValue();
        Map<String, Object> payload = payloadOf(jobId);
        if (payload == null || !"AVAILABLE".equals(payload.get("status"))) {
            throw ApiException.conflict("任务 " + jobId + " 结果载荷已被回收（生命周期结束），"
                    + "请重新提交或延长保留时长");
        }
        List<Map<String, Object>> rows = Jsons.asListOfMaps(payload.get("rows"));
        List<String> orgs = new ArrayList<>();
        for (String s : String.valueOf(sub.get("scope_key")).split(",")) {
            if (!s.isBlank()) {
                orgs.add(s);
            }
        }
        boolean fullScope = orgs.contains("*");
        List<Map<String, Object>> visible = rows.stream()
                .filter(r -> fullScope || orgs.contains(String.valueOf(r.get("org_code"))))
                .toList();
        jdbc.update("UPDATE query_subscription SET status = 'DELIVERED', delivered_at = now(),"
                + " expires_at = now() + (? || ' milliseconds')::interval WHERE id = ?",
                String.valueOf(sub.get("keep_alive_ms")), subscriptionId);
        event(jobId, subscriptionId, "DELIVERED", Map.of(
                "rows", visible.size(), "scope", String.valueOf(sub.get("scope_key"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscriptionId", subscriptionId);
        out.put("jobId", jobId);
        out.put("snapshotChecksum", payload.get("snapshot_checksum"));
        out.put("totalResultRows", payload.get("row_count"));
        out.put("totalAmount", payload.get("total_amount"));
        out.put("scopedRows", visible.size());
        out.put("scope", String.valueOf(sub.get("scope_key")));
        out.put("rows", visible);
        out.put("expiresAt", jdbc.queryForObject(
                "SELECT expires_at FROM query_subscription WHERE id = ?", String.class, subscriptionId));
        return out;
    }

    /** 任务就绪：所有等待订阅置 READY/PARTIAL_READY，并计算到期时间。 */
    public int markJobReady(long jobId, boolean truncated) {
        return jdbc.update("UPDATE query_subscription SET status = ?, ready_at = now(),"
                        + " expires_at = now() + (keep_alive_ms || ' milliseconds')::interval"
                        + " WHERE job_id = ? AND status = 'WAITING'",
                truncated ? "PARTIAL_READY" : "READY", jobId);
    }

    public void markJobPaused(long jobId, String reason) {
        jdbc.update("UPDATE query_subscription SET status = 'WAITING' WHERE job_id = ?"
                + " AND status = 'WAITING'", jobId);
        event(jobId, null, "SUBSCRIBERS_UPDATED", Map.of("state", "WAITING", "reason", reason));
    }

    public void markJobTerminal(long jobId, String terminalStatus, String reason) {
        String target = "CANCELLED".equals(terminalStatus) ? "CANCELLED" : "POLICY_RETIRED";
        int n = jdbc.update("UPDATE query_subscription SET status = ?, cancel_reason = ?,"
                        + " cancelled_at = now(), cancel_role = 'GOVERNANCE'"
                        + " WHERE job_id = ? AND status = 'WAITING'",
                "CANCELLED".equals(terminalStatus) ? "CANCELLED" : target, reason, jobId);
        if (n > 0) {
            event(jobId, null, "SUBSCRIBERS_TERMINATED", Map.of(
                    "status", target, "count", n, "reason", reason));
        }
    }

    /**
     * 回收一轮：释放超过订阅保留时长或绝对上限的载荷。
     * 返回释放条目、字节数与释放后的资源收益视图。
     */
    @Transactional
    public Map<String, Object> reclaim() {
        List<Map<String, Object>> expired = jdbc.queryForList(
                "SELECT p.job_id, p.bytes, p.row_count, p.created_at,"
                        + " (SELECT COUNT(*) FROM query_subscription s WHERE s.job_id = p.job_id"
                        + "   AND s.status <> 'CANCELLED') AS active_subs,"
                        + " (SELECT MIN(s.expires_at) FROM query_subscription s WHERE s.job_id = p.job_id"
                        + "   AND s.status IN ('READY','PARTIAL_READY','DELIVERED')"
                        + "   AND s.expires_at IS NOT NULL) AS min_expires,"
                        + " (SELECT COUNT(*) FROM query_subscription s WHERE s.job_id = p.job_id"
                        + "   AND s.status = 'CANCELLED') AS cancelled_subs"
                        + " FROM result_payload p WHERE p.status = 'AVAILABLE'");
        List<Map<String, Object>> reclaimed = new ArrayList<>();
        long bytes = 0;
        for (Map<String, Object> p : expired) {
            long jobId = ((Number) p.get("job_id")).longValue();
            Object minExpires = p.get("min_expires");
            boolean noneLeft = ((Number) p.get("active_subs")).intValue() == 0;
            boolean allExpired = minExpires != null
                    && Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT ?::timestamptz < now()", Boolean.class, String.valueOf(minExpires)));
            boolean agedOut = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT ?::timestamptz < now() - (? || ' milliseconds')::interval",
                    Boolean.class, String.valueOf(p.get("created_at")), String.valueOf(maxPayloadAgeMs)));
            if (!noneLeft && !allExpired && !agedOut) {
                continue;
            }
            String reason = noneLeft ? "无有效订阅（全部取消）"
                    : allExpired ? "订阅保留时长到期" : "超过结果载荷绝对保留上限";
            reclaimPayload(jobId, reason);
            long b = ((Number) p.get("bytes")).longValue();
            bytes += b;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("jobId", jobId);
            item.put("bytes", b);
            item.put("rows", p.get("row_count"));
            item.put("reason", reason);
            reclaimed.add(item);
            event(jobId, null, "PAYLOAD_RECLAIMED", Map.of(
                    "bytes", b, "rows", p.get("row_count"), "reason", reason));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reclaimed", reclaimed);
        out.put("reclaimedBytes", bytes);
        out.put("reclaimedJobs", reclaimed.size());
        out.put("activePayloads", jdbc.queryForObject(
                "SELECT COUNT(*) FROM result_payload WHERE status = 'AVAILABLE'", Integer.class));
        out.put("retainedBytes", jdbc.queryForObject(
                "SELECT COALESCE(SUM(bytes), 0) FROM result_payload WHERE status = 'AVAILABLE'", Long.class));
        return out;
    }

    private int reclaimPayload(long jobId, String reason) {
        int n = jdbc.update("UPDATE result_payload SET status = 'RECLAIMED', reclaimed_at = now(),"
                + " reclaim_reason = ?, rows = '[]'::jsonb WHERE job_id = ? AND status = 'AVAILABLE'",
                reason, jobId);
        if (n > 0) {
            event(jobId, null, "RESOURCE_RELEASED", Map.of("reason", reason));
        }
        return n;
    }

    public Map<String, Object> payloadOf(long jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM result_payload WHERE job_id = ?", jobId);
        return rows.isEmpty() ? null : Jsons.normalizeRow(rows.get(0));
    }

    public Map<String, Object> get(long subscriptionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM query_subscription WHERE id = ?", subscriptionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("订阅不存在: " + subscriptionId);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> subscriptionsOfJob(long jobId) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, username, role_name, scope_key, status, priority, keep_alive_ms,"
                        + " submitted_at, ready_at, delivered_at, cancelled_at, expires_at,"
                        + " cancel_reason, cancel_role FROM query_subscription WHERE job_id = ? ORDER BY id",
                jobId));
    }

    public List<Map<String, Object>> list(String status, int limit) {
        String sql = "SELECT id, job_id, username, status, scope_key, priority, keep_alive_ms,"
                + " submitted_at, expires_at, cancel_role FROM query_subscription";
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql += " WHERE status = ?";
            args.add(status);
        }
        sql += " ORDER BY id DESC LIMIT ?";
        args.add(Math.min(Math.max(limit, 1), 200));
        return Jsons.normalizeRows(jdbc.queryForList(sql, args.toArray()));
    }

    void event(Long jobId, Long subscriptionId, String type, Map<String, Object> detail) {
        jdbc.update("INSERT INTO scheduling_event (job_id, subscription_id, event_type, detail)"
                        + " VALUES (?,?,?,?::jsonb)",
                jobId, subscriptionId, type, Jsons.write(detail));
    }
}
