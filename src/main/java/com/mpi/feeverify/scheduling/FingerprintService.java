package com.mpi.feeverify.scheduling;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.Params;
import com.mpi.feeverify.support.SqlRewrite;
import com.mpi.feeverify.support.RestrictedExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 请求指纹器：由 SQL 模板、参数、快照、授权范围四要素生成请求标识。
 *
 * <p>四要素缺一不可：SQL 参数相同但授权机构范围不同 → 指纹不同，绝不共享
 * 宽权限结果。指纹相同的请求才允许合并执行。
 *
 * <p>合并边界由重复率、结果体积、权限重叠度与交互时限共同决定：
 * 重复率高且为非交互长查询时合并（省数据库扫描）；交互短查询或结果体积
 * 超过载荷上限时按请求隔离（省的收益不足以覆盖可见性与生命周期复杂度）。
 */
@Service
public class FingerprintService {

    private final JdbcTemplate jdbc;
    private final RestrictedExecutor executor;

    @Value("${verify.scheduling.interactive-budget-ms:1500}")
    private int interactiveBudgetMs;
    @Value("${verify.scheduling.duplicate-window-ms:300000}")
    private long duplicateWindowMs;
    @Value("${verify.scheduling.planning-rows-per-ms:1.0}")
    private double planningRowsPerMs;

    public FingerprintService(JdbcTemplate jdbc, RestrictedExecutor executor) {
        this.jdbc = jdbc;
        this.executor = executor;
    }

    /**
     * 计算或复用指纹。四要素摘要全部写入 components，便于审计“为什么不同”。
     */
    public Fingerprint createOrGet(long registrationId, long snapshotId, String snapshotChecksum,
                                   Map<String, Object> params, ResourcePolicyService.Scope scope,
                                   String purpose, String role, String policyVersion,
                                   String sqlText) {
        String templateDigest = Params.sha256(sqlText.replaceAll("\\s+", " ").trim());
        String paramsDigest = Params.sha256(Jsons.write(params));
        // 授权范围要素含角色与机构集合：机构范围不同或角色不同 → 摘要不同
        String scopeDigest = Params.sha256(role + "@" + scope.scopeKey());
        String fingerprint = Params.sha256(String.join("|",
                "tpl:" + templateDigest, "par:" + paramsDigest,
                "snap:" + snapshotChecksum, "scope:" + scopeDigest));

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("templateDigest", templateDigest);
        components.put("paramsDigest", paramsDigest);
        components.put("snapshotChecksum", snapshotChecksum);
        components.put("scopeKey", scope.scopeKey());
        components.put("role", role);
        components.put("scopeDigest", scopeDigest);
        components.put("scopeBasis", scope.basis());
        components.put("note", "SQL 参数相同而授权机构范围不同 → 指纹不同，宽权限结果不得共享");

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT * FROM query_fingerprint WHERE fingerprint = ?", fingerprint);
        Long id;
        if (existing.isEmpty()) {
            id = jdbc.queryForObject(
                    "INSERT INTO query_fingerprint (fingerprint, registration_id, snapshot_id, params,"
                            + " template_digest, scope_orgs, scope_key, purpose, role_name,"
                            + " policy_version, components)"
                            + " VALUES (?,?,?,?::jsonb,?,?::jsonb,?,?,?,?,?::jsonb)"
                            + " ON CONFLICT (fingerprint) DO UPDATE SET fingerprint = EXCLUDED.fingerprint"
                            + " RETURNING id",
                    Long.class, fingerprint, registrationId, snapshotId, Jsons.write(params),
                    templateDigest, Jsons.write(scope.orgs()), scope.scopeKey(), purpose, role,
                    policyVersion, Jsons.write(components));
        } else {
            id = ((Number) existing.get(0).get("id")).longValue();
        }
        return new Fingerprint(id, fingerprint, registrationId, snapshotId, params,
                scope.orgs(), scope.scopeKey(), purpose, role, components);
    }

    /**
     * 合并决策：返回选择模型、四项度量、预估与实际边界说明。
     * 强制模型（forceModel）仅用于演示/治理演练，仍记录度量与理由。
     */
    public Decision decide(Fingerprint fp, String forceModel, String windowName) {
        return decide(fp, forceModel, windowName, "FULL");
    }

    /** @param resultShape FULL（明细）/ PAGE_WINDOW / AGGREGATE，决定交付行数口径。 */
    public Decision decide(Fingerprint fp, String forceModel, String windowName, String resultShape) {
        long windowAgo = duplicateWindowMs / 1000;
        String paramsJson = Jsons.write(fp.params());
        List<Map<String, Object>> siblings = jdbc.queryForList(
                "SELECT f.fingerprint, f.scope_orgs, j.status, j.execution_model FROM schedule_job j"
                        + " JOIN query_fingerprint f ON f.id = j.fingerprint_id"
                        + " WHERE f.template_digest = ? AND f.snapshot_id = ?"
                        + " AND f.params = CAST(? AS jsonb)"
                        + " AND j.queued_at > now() - (? || ' seconds')::interval",
                fp.components().get("templateDigest"), fp.snapshotId(),
                paramsJson, String.valueOf(windowAgo));
        long sameFingerprint = siblings.stream()
                .filter(s -> fp.fingerprint().equals(s.get("fingerprint"))
                        && !"COMPLETED".equals(String.valueOf(s.get("status")))
                        && !"CANCELLED".equals(String.valueOf(s.get("status")))
                        && !"FAILED".equals(String.valueOf(s.get("status"))))
                .count();
        long totalSiblings = siblings.size();

        double duplicateRate = totalSiblings == 0 ? 0.0
                : round(sameFingerprint / (double) totalSiblings);
        double permissionOverlap = permOverlap(fp.scopeOrgs(), siblings);

        long estRows = estimateRows(fp);
        int estScanMs = (int) Math.max(1, Math.round(estRows / planningRowsPerMs));
        boolean interactive = estScanMs <= interactiveBudgetMs;
        // 结果体积：单请求可交付上限；超大结果合并会把逐订阅者复制与生命周期成本放大。
        // 按本次请求实际交付行数（受聚合/分页参数裁剪）与上限比较。
        int payloadCap = 5000;
        long deliveredRows = Math.min(estRows, payloadCap);

        List<String> reasons = new ArrayList<>();
        boolean duplicatesExist = sameFingerprint >= 1;
        boolean longRunning = !interactive;
        boolean volumeOk = estRows <= payloadCap;
        boolean permissionsAligned = permissionOverlap >= 0.8;

        String chosen;
        if ("PER_REQUEST".equals(forceModel) || "MERGE".equals(forceModel)) {
            chosen = forceModel;
            reasons.add("治理方强制指定执行模型（演练），度量仍完整记录");
        } else if (duplicatesExist && longRunning && volumeOk && permissionsAligned) {
            chosen = "MERGE";
            reasons.add("存在同指纹在途请求，重复率=" + duplicateRate + "，可合并一次执行");
            reasons.add("预估扫描耗时 " + estScanMs + "ms 超过交互时限 " + interactiveBudgetMs
                    + "ms，属非交互长查询，合并节省扫描的收益可覆盖调度等待");
            reasons.add("预估结果 " + estRows + " 行未超过载荷上限 " + payloadCap + "，复制与生命周期成本可控");
            reasons.add("权限重叠度=" + permissionOverlap + "，订阅者机构范围一致，可共享同一结果");
        } else {
            chosen = "PER_REQUEST";
            if (!duplicatesExist) {
                reasons.add("暂无同指纹在途请求（重复率=" + duplicateRate + "），合并无收益");
            }
            if (interactive) {
                reasons.add("预估扫描耗时 " + estScanMs + "ms 在交互时限 " + interactiveBudgetMs
                        + "ms 内，隔离执行可避免订阅者等待共享任务，保证可见性");
            }
            if (!volumeOk) {
                reasons.add("预估结果 " + estRows + " 行超过载荷上限 " + payloadCap
                        + "（本次最多交付 " + deliveredRows + " 行）"
                        + "，逐订阅者复制与生命周期管理成本高于单次扫描收益");
            }
            if (!permissionsAligned) {
                reasons.add("权限重叠度 " + permissionOverlap + " 偏低，合并边界应更保守");
            }
            if (reasons.isEmpty()) {
                reasons.add("未满足全部合并条件，按请求隔离执行");
            }
        }

        Map<String, Object> benefits = new LinkedHashMap<>();
        long expectedScansPerRequest = sameFingerprint + 1;
        long scansWithMerge = expectedScansPerRequest > 1 ? 1 : expectedScansPerRequest;
        benefits.put("expectedRequests", expectedScansPerRequest);
        benefits.put("dbScansWithoutMerge", expectedScansPerRequest);
        benefits.put("dbScansWithMerge", scansWithMerge);
        benefits.put("dbScansSavedIfMerged", expectedScansPerRequest - scansWithMerge);
        benefits.put("rowsReadSavedIfMerged", Math.max(0, expectedScansPerRequest - scansWithMerge) * estRows);
        benefits.put("resultRowsPerExecution", estRows);
        benefits.put("estimatedScanMs", estScanMs);
        benefits.put("interactiveBudgetMs", interactiveBudgetMs);
        benefits.put("estimatedPayloadBytesPerSubscriber",
                deliveredRows * 220);
        benefits.put("mergeVisibleLatencyPenaltyMs", chosen.equals("MERGE") ? estScanMs : 0);
        benefits.put("partitionRepeatScanRisk", chosen.equals("MERGE")
                ? "无（同指纹只扫描一次）" : "同分区可能被多位申请人的独立任务重复扫描");

        Decision decision = new Decision(-1L, chosen, duplicateRate, estRows, permissionOverlap,
                interactiveBudgetMs, estScanMs, reasons, benefits);
        Long decisionId = jdbc.queryForObject(
                "INSERT INTO merge_decision (fingerprint_id, chosen_model, duplicate_rate,"
                        + " estimated_result_rows, permission_overlap, interactive_budget_ms,"
                        + " estimated_scan_ms, benefits, reasons)"
                        + " VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb) RETURNING id",
                Long.class, fp.id(), chosen, duplicateRate, estRows, permissionOverlap,
                interactiveBudgetMs, estScanMs, Jsons.write(benefits), Jsons.write(reasons));
        return decision.withId(decisionId);
    }

    /**
     * 预估结果行数：对模板查询按条件下推后的等价语句做 COUNT。
     * 不能用原语句 EXPLAIN 的估算——to_char 包装会让规划器严重低估（演示数据估计 120 行、实际 7920 行），
     * 从而把长查询误判为交互查询。COUNT 走半开区间日期条件，24k 行快照上代价可忽略。
     */
    private long estimateRows(Fingerprint fp) {
        String sql = String.valueOf(jdbc.queryForObject(
                "SELECT sql_text FROM query_registration WHERE id = ?", String.class, fp.registrationId()));
        String scopeFilter = "";
        if (!fp.scopeOrgs().contains("*")) {
            String list = String.join(",", fp.scopeOrgs().stream()
                    .map(o -> "'" + o.replace("'", "''") + "'").toList());
            scopeFilter = " WHERE q.org_code IN (" + list + ")";
        }
        try {
            String pushed = SqlRewrite.pushdown(sql);
            String countSql = "SELECT COUNT(*) FROM (" + pushed + ") q" + scopeFilter;
            Long count = executor.inRestrictedTx(5000, 2000, "8MB",
                    s -> s.np().queryForObject(countSql,
                            RestrictedExecutor.typedParams(fp.params()), Long.class));
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            // 估计失败不阻断调度：回退为保守大值，促使保守选择
            return 1_000_000;
        }
    }

    /**
     * 权限重叠度：同模板同参数同快照的近期请求中，与本请求机构范围一致的占比；
     * 无兄弟请求时视为完全对齐（合并不受权限限制）。
     * '*' 代表全域，与任何具体范围都是包含关系（重叠=1）。
     */
    private double permOverlap(List<String> mine, List<Map<String, Object>> siblings) {
        if (siblings.isEmpty()) {
            return 1.0;
        }
        Set<String> my = expand(mine);
        double sum = 0;
        for (Map<String, Object> s : siblings) {
            Set<String> other = expand(Jsons.asList(s.get("scope_orgs")));
            sum += jaccard(my, other);
        }
        return round(sum / siblings.size());
    }

    private Set<String> expand(List<?> orgs) {
        Set<String> set = new LinkedHashSet<>();
        if (orgs == null || orgs.isEmpty()) {
            set.add("*");
        } else {
            orgs.forEach(o -> set.add(String.valueOf(o)));
        }
        return set;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.contains("*") || b.contains("*")) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return round(inter.size() / (double) union.size());
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    public Map<String, Object> describe(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM query_fingerprint WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("指纹不存在: " + id);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> listRecent(int limit) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, fingerprint, registration_id, snapshot_id, scope_key, purpose, role_name,"
                        + " policy_version, created_at FROM query_fingerprint ORDER BY id DESC LIMIT ?",
                Math.min(Math.max(limit, 1), 200)));
    }

    public record Fingerprint(long id, String fingerprint, long registrationId, long snapshotId,
                              Map<String, Object> params, List<String> scopeOrgs, String scopeKey,
                              String purpose, String role, Map<String, Object> components) {
    }

    public record Decision(long id, String chosenModel, double duplicateRate, long estimatedResultRows,
                           double permissionOverlap, int interactiveBudgetMs, int estimatedScanMs,
                           List<String> reasons, Map<String, Object> benefits) {
        Decision withId(long newId) {
            return new Decision(newId, chosenModel, duplicateRate, estimatedResultRows,
                    permissionOverlap, interactiveBudgetMs, estimatedScanMs, reasons, benefits);
        }
    }
}
