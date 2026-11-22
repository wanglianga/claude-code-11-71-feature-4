package com.mpi.feeverify.demo;

import com.mpi.feeverify.candidate.CandidateService;
import com.mpi.feeverify.diagnosis.DiagnosisService;
import com.mpi.feeverify.gate.GateService;
import com.mpi.feeverify.judge.JudgeService;
import com.mpi.feeverify.plan.PlanCaptureService;
import com.mpi.feeverify.policy.PolicyService;
import com.mpi.feeverify.registration.RegistrationService;
import com.mpi.feeverify.router.RouterService;
import com.mpi.feeverify.shadow.ShadowExecutorService;
import com.mpi.feeverify.snapshot.SnapshotService;
import com.mpi.feeverify.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示场景编排：一次调用跑通参数倾斜、分组不一致、权限收窄、回退演练、
 * 数据刷新重验五个场景，返回结构化证据。所有对象真实落库，可逐条用 API 复查。
 */
@Service
public class DemoScenarioService {

    /** 平台登记的典型费用明细查询（含 to_char 不可索引化条件与冗余关联）。 */
    public static final String DEMO_SQL = """
            SELECT d.id, d.org_code, o.org_name, d.diagnosis_code, d.drug_code, d.settle_status, d.amount, d.settle_date
            FROM snap_fee_detail d
            JOIN snap_dim_org o ON o.org_code = d.org_code
            JOIN snap_dim_drug g ON g.drug_code = d.drug_code
            WHERE to_char(d.settle_date, 'YYYY-MM') BETWEEN :monthFrom AND :monthTo
              AND (:orgCode IS NULL OR d.org_code = :orgCode)
              AND (:settleStatus IS NULL OR d.settle_status = :settleStatus)
              AND (:diagPrefix IS NULL OR d.diagnosis_code LIKE :diagPrefix || '%')
            ORDER BY d.id""";

    /** 窄参数：单月 + 小机构（行数少 → 索引路径）。 */
    public static final Map<String, Object> NARROW_PARAMS =
            Map.of("monthFrom", "2025-08", "monthTo", "2025-08", "orgCode", "ORG003");
    /** 宽参数：跨政策切换期的 4 个月 + 全机构（→ 全表扫描路径）。 */
    public static final Map<String, Object> WIDE_PARAMS =
            Map.of("monthFrom", "2025-11", "monthTo", "2026-02");

    private final JdbcTemplate jdbc;
    private final SnapshotService snapshotService;
    private final RegistrationService registrationService;
    private final PlanCaptureService planCaptureService;
    private final DiagnosisService diagnosisService;
    private final CandidateService candidateService;
    private final ShadowExecutorService shadowExecutor;
    private final JudgeService judgeService;
    private final GateService gateService;
    private final PolicyService policyService;
    private final RouterService routerService;

    public DemoScenarioService(JdbcTemplate jdbc, SnapshotService snapshotService,
                               RegistrationService registrationService,
                               PlanCaptureService planCaptureService, DiagnosisService diagnosisService,
                               CandidateService candidateService, ShadowExecutorService shadowExecutor,
                               JudgeService judgeService, GateService gateService,
                               PolicyService policyService, RouterService routerService) {
        this.jdbc = jdbc;
        this.snapshotService = snapshotService;
        this.registrationService = registrationService;
        this.planCaptureService = planCaptureService;
        this.diagnosisService = diagnosisService;
        this.candidateService = candidateService;
        this.shadowExecutor = shadowExecutor;
        this.judgeService = judgeService;
        this.gateService = gateService;
        this.policyService = policyService;
        this.routerService = routerService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> run() {
        Map<String, Object> report = new LinkedHashMap<>();

        // ---------- 0. 冻结快照 + 登记 ----------
        Map<String, Object> snapshot = snapshotService.freeze("demo-snapshot", "演示：月结前冻结");
        long snapshotId = ((Number) snapshot.get("snapshotId")).longValue();
        Map<String, Object> registration = registrationService.register(
                DEMO_SQL, "月结费用明细稽核查询（稽核/病种付费/预算共用）",
                Map.of("roles", List.of("AUDIT", "DRG", "BUDGET"), "orgCodes", List.of("*")),
                RegistrationService.defaultParamShape(), snapshotId, "demo");
        long regId = ((Number) registration.get("id")).longValue();
        report.put("setup", Map.of(
                "snapshotId", snapshotId, "checksum", snapshot.get("checksum"),
                "registrationId", regId, "rowCount", snapshot.get("rowCount")));

        // ---------- 1. 参数倾斜：相同 SQL 不同参数范围 → 不同计划 → 分别建档 ----------
        Map<String, Object> narrowCapture = planCaptureService.capture(regId, NARROW_PARAMS);
        Map<String, Object> wideCapture = planCaptureService.capture(regId, WIDE_PARAMS);
        List<Map<String, Object>> diagnoses = diagnosisService.diagnose(regId);
        boolean plansDiffer = !narrowCapture.get("planHash").equals(wideCapture.get("planHash"));
        report.put("scenario1_paramSkew", Map.of(
                "narrow", Map.of("paramClass", narrowCapture.get("paramClass"),
                        "planHash", narrowCapture.get("planHash"),
                        "planSummary", narrowCapture.get("planSummary"),
                        "estRows", narrowCapture.get("estRows")),
                "wide", Map.of("paramClass", wideCapture.get("paramClass"),
                        "planHash", wideCapture.get("planHash"),
                        "planSummary", wideCapture.get("planSummary"),
                        "estRows", wideCapture.get("estRows")),
                "plansDiffer", plansDiffer,
                "diagnosisFiledSeparately", diagnoses.size() == 2,
                "diagnosisCount", diagnoses.size(),
                "diagnoses", diagnoses.stream().map(d -> Map.of(
                        "diagnosisId", d.get("diagnosisId"),
                        "paramClass", d.get("paramClass"),
                        "findingTypes", ((List<Map<String, Object>>) d.get("findings")).stream()
                                .map(f -> f.get("type") + "/" + f.get("severity")).toList())).toList()));

        // ---------- 2. 候选生成 + 影子执行 + 裁判（含分组不一致阻断） ----------
        List<Map<String, Object>> candidates = candidateService.generate(regId,
                List.of("PUSHDOWN", "PAGINATION", "JOIN_REORDER", "PRE_AGGREGATE"),
                List.of("current", "stale"));
        List<Map<String, Object>> shadowResults = new ArrayList<>();
        Map<String, Object> staleBlockEvidence = null;
        Long pushdownCandidateId = null;
        Long joinReorderCandidateId = null;
        Long staleCandidateId = null;
        for (Map<String, Object> cand : candidates) {
            long candId = ((Number) cand.get("id")).longValue();
            String type = String.valueOf(cand.get("rewrite_type"));
            String variant = String.valueOf(cand.get("variant"));
            shadowExecutor.execute(candId, WIDE_PARAMS);
            Map<String, Object> judgement = judgeService.judge(candId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", candId);
            item.put("type", type);
            item.put("variant", variant);
            item.put("verdict", judgement.get("verdict"));
            item.put("amountEqual", judgement.get("amount_equal"));
            item.put("orgGroupEqual", judgement.get("org_group_equal"));
            item.put("pkSetEqual", judgement.get("pk_set_equal"));
            shadowResults.add(item);
            if ("PUSHDOWN".equals(type)) {
                pushdownCandidateId = candId;
            }
            if ("JOIN_REORDER".equals(type)) {
                joinReorderCandidateId = candId;
            }
            if ("PRE_AGGREGATE".equals(type) && "stale".equals(variant)) {
                staleCandidateId = candId;
                staleBlockEvidence = Map.of(
                        "candidateId", candId,
                        "verdict", judgement.get("verdict"),
                        "totalAmountEqual", judgement.get("amount_equal"),
                        "orgGroupEqual", judgement.get("org_group_equal"),
                        "orgGroupDiff", judgement.get("org_group_diff"),
                        "detail", judgement.get("detail"));
            }
        }
        report.put("scenario2_shadowAndJudge", Map.of("results", shadowResults));
        report.put("scenario2_groupMismatchBlocked", staleBlockEvidence);

        // ---------- 3. 发布门禁：绑定参数范围与权限策略；BLOCK 候选不得进门禁 ----------
        long activePolicyId = jdbc.queryForObject(
                "SELECT id FROM permission_policy WHERE family = 'fee-query' AND status = 'ACTIVE'"
                        + " ORDER BY version DESC LIMIT 1", Long.class);
        Map<String, Object> gate = gateService.create(pushdownCandidateId,
                new LinkedHashMap<>(Map.of("monthFrom", "2025-11", "monthTo", "2026-02",
                        "orgCode", "ANY", "settleStatus", "ANY", "diagPrefix", "ANY")),
                activePolicyId);
        long gateId = ((Number) gate.get("id")).longValue();
        boolean blockedCandidateRejected;
        try {
            gateService.create(staleCandidateId,
                    new LinkedHashMap<>(Map.of("monthFrom", "2025-11", "monthTo", "2026-02",
                            "orgCode", "ANY", "settleStatus", "NULL_ONLY", "diagPrefix", "NULL_ONLY")),
                    activePolicyId);
            blockedCandidateRejected = false;
        } catch (ApiException e) {
            blockedCandidateRejected = true;
        }
        boolean overGeneralizationRejected;
        try {
            gateService.create(joinReorderCandidateId,
                    new LinkedHashMap<>(Map.of("monthFrom", "2025-11", "monthTo", "2026-03",
                            "orgCode", "ANY", "settleStatus", "ANY", "diagPrefix", "ANY")),
                    activePolicyId);
            overGeneralizationRejected = false;
        } catch (ApiException e) {
            overGeneralizationRejected = true;
        }
        Map<String, Object> inRangeRoute = routerService.route("tok-audit01", gateId, WIDE_PARAMS, false);
        Map<String, Object> outOfRangeRoute = routerService.route("tok-audit01", gateId,
                Map.of("monthFrom", "2026-03", "monthTo", "2026-03"), false);
        report.put("scenario3_releaseGate", Map.of(
                "gateId", gateId,
                "gateStatus", gate.get("status"),
                "blockedCandidateRejected", blockedCandidateRejected,
                "overGeneralizationRejected", overGeneralizationRejected,
                "inRangeRoutedTo", inRangeRoute.get("routedTo"),
                "outOfRangeRoutedTo", outOfRangeRoute.get("routedTo"),
                "outOfRangeReason", String.valueOf(outOfRangeRoute.get("reason"))));

        // ---------- 4. 权限收窄：在途待复核，旧策略新请求拒绝 ----------
        Map<String, Object> inFlight = routerService.route("tok-audit01", gateId, WIDE_PARAMS, true);
        long inFlightId = ((Number) inFlight.get("routeRequestId")).longValue();
        Map<String, Object> policyV2 = policyService.create("fee-query", List.of(
                Map.of("role", "AUDIT", "allowedOrgs", List.of("ORG001")),
                Map.of("role", "DRG", "allowedOrgs", List.of("ORG001", "ORG002")),
                Map.of("role", "BUDGET", "allowedOrgs", "*")));
        Map<String, Object> activation = policyService.activate(
                ((Number) policyV2.get("id")).longValue());
        Map<String, Object> inFlightAfter = routerService.request(inFlightId);
        Map<String, Object> rejectedRoute = routerService.route("tok-audit01", gateId, WIDE_PARAMS, false);
        report.put("scenario4_policyNarrow", Map.of(
                "policyV2Id", policyV2.get("id"),
                "inFlightRequestId", inFlightId,
                "inFlightStatusAfterNarrow", inFlightAfter.get("status"),
                "newRequestRoutedTo", rejectedRoute.get("routedTo"),
                "newRequestReason", String.valueOf(rejectedRoute.get("reason")),
                "gateImpacts", activation.get("gateImpacts")));

        // ---------- 5. 重绑策略 + 复核 + 发布 + 回退演练 ----------
        gateService.rebind(gateId, ((Number) policyV2.get("id")).longValue());
        routerService.review(inFlightId, "COMPLETED");
        gateService.publish(gateId);
        routerService.route("tok-audit01", gateId, WIDE_PARAMS, false);
        routerService.route("tok-budget01", gateId, WIDE_PARAMS, false);
        Map<String, Object> rollback = gateService.rollback(gateId, "回退演练：验证路由恢复与账本");
        Map<String, Object> afterRollback = routerService.route("tok-audit01", gateId, WIDE_PARAMS, false);
        report.put("scenario5_rollback", Map.of(
                "gateStatus", rollback.get("status"),
                "affectedRequests", rollback.get("affectedRequests"),
                "ledgerId", rollback.get("ledgerId"),
                "routedToAfterRollback", afterRollback.get("routedTo"),
                "reasonAfterRollback", String.valueOf(afterRollback.get("reason"))));

        // ---------- 6. 数据刷新：旧结论置 STALE，门禁阻断 ----------
        Map<String, Object> gate2 = gateService.create(joinReorderCandidateId,
                new LinkedHashMap<>(Map.of("monthFrom", "2025-11", "monthTo", "2026-02",
                        "orgCode", "ANY", "settleStatus", "ANY", "diagPrefix", "ANY")),
                ((Number) policyV2.get("id")).longValue());
        long gate2Id = ((Number) gate2.get("id")).longValue();
        gateService.publish(gate2Id);
        Map<String, Object> refresh = snapshotService.freeze("demo-snapshot-refresh", "演示：数据更新后重验");
        Map<String, Object> gate2After = gateService.get(gate2Id);
        Map<String, Object> staleRoute = routerService.route("tok-audit01", gate2Id, WIDE_PARAMS, false);
        report.put("scenario6_dataRefresh", Map.of(
                "newSnapshotId", refresh.get("snapshotId"),
                "judgementsMarkedStale", refresh.get("judgementsMarkedStale"),
                "gate2StatusAfterRefresh", gate2After.get("status"),
                "routedToAfterRefresh", staleRoute.get("routedTo"),
                "reason", String.valueOf(staleRoute.get("reason"))));

        report.put("summary", Map.of(
                "registrationId", regId,
                "snapshotId", snapshotId,
                "gateId", gateId,
                "allScenariosCovered", List.of("paramSkew", "groupMismatch", "policyNarrow",
                        "rollback", "dataRefresh")));
        return report;
    }
}
