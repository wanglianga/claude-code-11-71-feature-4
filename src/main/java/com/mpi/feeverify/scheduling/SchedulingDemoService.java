package com.mpi.feeverify.scheduling;

import com.mpi.feeverify.demo.DemoScenarioService;
import com.mpi.feeverify.registration.RegistrationService;
import com.mpi.feeverify.snapshot.SnapshotService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度演示：在真实数据库上串通资源策略 → 请求指纹 → 队列调度 → 检查点 → 订阅 → 回收，
 * 覆盖四个必验样例：
 * <ol>
 *   <li>权限不同的同 SQL：参数相同、授权机构范围不同 → 指纹不同，不共享宽权限结果；</li>
 *   <li>并发取消：合并任务下逐订阅者取消，最后一个取消者才取消共享任务，未取消者结果正确；</li>
 *   <li>跨时段限额切换：高峰策略运行中生效 → 暂停未开始分区并保留检查点，切到夜间大配额后续跑完；</li>
 *   <li>大结果集查询：结果体积超载荷上限 → 独立执行 + 载荷裁剪 + 回收器释放资源。</li>
 * </ol>
 */
@Service
public class SchedulingDemoService {

    private final JdbcTemplate jdbc;
    private final SnapshotService snapshotService;
    private final RegistrationService registrationService;
    private final ResourcePolicyService policyService;
    private final FingerprintService fingerprintService;
    private final QueueSchedulerService scheduler;
    private final SubscriptionService subscriptionService;
    private final SchedulingService schedulingService;

    public SchedulingDemoService(JdbcTemplate jdbc, SnapshotService snapshotService,
                                 RegistrationService registrationService,
                                 ResourcePolicyService policyService,
                                 FingerprintService fingerprintService,
                                 QueueSchedulerService scheduler,
                                 SubscriptionService subscriptionService,
                                 SchedulingService schedulingService) {
        this.jdbc = jdbc;
        this.snapshotService = snapshotService;
        this.registrationService = registrationService;
        this.policyService = policyService;
        this.fingerprintService = fingerprintService;
        this.scheduler = scheduler;
        this.subscriptionService = subscriptionService;
        this.schedulingService = schedulingService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> run(String window) {
        String w = window == null || window.isBlank() ? "DAY" : window.toUpperCase();
        Map<String, Object> report = new LinkedHashMap<>();
        resetDemoState();

        // ---------- 0. 冻结快照 + 登记大范围查询（4 个月、全机构） ----------
        Map<String, Object> snap = snapshotService.freeze("sched-demo", "调度演示：冻结月结快照");
        long snapshotId = ((Number) snap.get("snapshotId")).longValue();
        Map<String, Object> reg = registrationService.register(
                DemoScenarioService.DEMO_SQL, "MONTHLY_AUDIT",
                Map.of("roles", List.of("AUDIT", "DRG", "BUDGET"), "orgCodes", List.of("*")),
                RegistrationService.defaultParamShape(), snapshotId, "sched-demo");
        long regId = ((Number) reg.get("id")).longValue();
        // 演示用宽参数：4 个月、全机构 —— 大结果集
        Map<String, Object> wideParams = new LinkedHashMap<>(Map.of(
                "monthFrom", "2025-11", "monthTo", "2026-02"));
        report.put("setup", Map.of("snapshotId", snapshotId, "registrationId", regId,
                "checksum", snap.get("checksum"), "window", w,
                "partitions", scheduler.partitionKeys(wideParams)));

        // ---------- 1. 同 SQL 不同授权范围 → 指纹必须不同 ----------
        Map<String, Object> scopeCompare = schedulingService.compareScopes(regId, wideParams,
                List.of("tok-audit01", "tok-audit02", "tok-drg01", "tok-budget01"));
        List<Map<String, Object>> scopeItems = (List<Map<String, Object>>) scopeCompare.get("items");
        long distinctScopes = scopeItems.stream().map(i -> i.get("scopeKey")).distinct().count();
        long auditDistinct = scopeItems.stream()
                .filter(i -> String.valueOf(i.get("username")).startsWith("audit"))
                .map(i -> i.get("fingerprint")).distinct().count();
        // AUDIT 两账号均为机构级：范围分别收敛到 ORG001 / ORG002，因此指纹不同；
        // DRG/BUDGET 省级账号范围同为全域，但角色不同 → 指纹仍不同。
        report.put("scenarioA_scopeDistinctFingerprint", Map.of(
                "allFingerprintsDistinct", scopeCompare.get("allFingerprintsDistinct"),
                "distinctScopeCount", distinctScopes,
                "auditAccountsDistinctFingerprints", auditDistinct,
                "auditScopesDiffer", auditDistinct == 2,
                "items", scopeItems,
                "principle", scopeCompare.get("principle")));

        // ---------- 1b. 权限不同的同 SQL：不共享同一任务/结果 ----------
        Map<String, Object> scopedParams = new LinkedHashMap<>(Map.of(
                "monthFrom", "2025-11", "monthTo", "2025-11"));
        Map<String, Object> scopeJob1 = schedulingService.submit(
                submitBody("tok-audit01", regId, scopedParams, "DAY", "AUDIT", null, 120000));
        Map<String, Object> scopeJob2 = schedulingService.submit(
                submitBody("tok-audit02", regId, scopedParams, "DAY", "AUDIT", null, 120000));
        report.put("scenarioA2_noCrossScopeSharing", mapOf(
                "sameParams", true,
                "scope1", scopeJob1.get("scopeBasis"),
                "scope2", scopeJob2.get("scopeBasis"),
                "fingerprint1", scopeJob1.get("fingerprintValue"),
                "fingerprint2", scopeJob2.get("fingerprintValue"),
                "fingerprintsDiffer", !String.valueOf(scopeJob1.get("fingerprintValue"))
                        .equals(String.valueOf(scopeJob2.get("fingerprintValue"))),
                "job1", scopeJob1.get("jobId"),
                "job2", scopeJob2.get("jobId"),
                "separateJobs", !String.valueOf(scopeJob1.get("jobId"))
                        .equals(String.valueOf(scopeJob2.get("jobId"))),
                "noMergeJoined", !"JOINED_MERGE".equals(scopeJob2.get("mode")),
                "note", "参数相同但授权机构范围不同：指纹不同、任务不同，宽权限账号无法把结果共享给窄权限请求"));

        // ---------- 2. 合并执行 + 并发取消 ----------
        // 2 个月 + 全机构：预估约 3960 行（未超载荷上限 5000），预估扫描约 3960ms 超过交互时限
        // → 首次独立排队，后续同指纹请求并入合并任务，只扫描一次
        Map<String, Object> mergeParams = new LinkedHashMap<>(Map.of(
                "monthFrom", "2025-11", "monthTo", "2025-12"));
        // 用夜间窗口：合并任务的结果载荷足以完整交付，便于核对未取消者结果正确
        String mergeWindow = "NIGHT";
        Map<String, Object> reqA = schedulingService.submit(submitBody("tok-drg01", regId,
                mergeParams, mergeWindow, "DRG", null, 120000));
        Map<String, Object> reqB = schedulingService.submit(submitBody("tok-drg02", regId,
                mergeParams, mergeWindow, "DRG", null, 120000));
        Map<String, Object> reqC = schedulingService.submit(submitBody("tok-drg03", regId,
                mergeParams, mergeWindow, "DRG", null, 120000));
        long mergeJobId = ((Number) reqB.get("jobId")).longValue();
        long sub2 = subscriptionIdOf(reqB);
        long sub3 = subscriptionIdOf(reqC);
        boolean joinedMerge = "JOINED_MERGE".equals(reqC.get("mode"))
                && ((Number) reqC.get("jobId")).longValue() == mergeJobId;

        // 第一个订阅者在其他人仍在等待时取消：合并任务继续，未取消者的正确结果不受影响
        Map<String, Object> cancel2 = subscriptionService.cancel(sub2, "病种专员改口径，撤回本次");
        Map<String, Object> jobAfterCancel2 = scheduler.loadJob(mergeJobId);
        Map<String, Object> finalRun = scheduler.run(mergeJobId, mergeWindow, null);
        Map<String, Object> deliverSurvivor = subscriptionService.deliver(sub3);
        // 最后一个取消者取消 → 共享任务/载荷按合并模型处置
        Map<String, Object> cancel3 = subscriptionService.cancel(sub3, "最后一位订阅者撤回");
        Map<String, Object> payloadAfterLastCancel = subscriptionService.payloadOf(mergeJobId);
        report.put("scenarioB_mergeAndConcurrentCancel", mapOf(
                "mergeJobId", mergeJobId,
                "firstRequestModel", reqA.get("executionModel"),
                "duplicateRequestModel", reqB.get("executionModel"),
                "thirdRequestMode", reqC.get("mode"),
                "thirdJoinedMerge", joinedMerge,
                "duplicateRate", ((Map<String, Object>) reqB.get("decision")).get("duplicateRate"),
                "jobStatusAfterFirstCancel", jobAfterCancel2.get("status"),
                "jobContinuedForSurvivors", !List.of("CANCELLED", "FAILED")
                        .contains(String.valueOf(jobAfterCancel2.get("status"))),
                "firstCancel", Map.of("lastCanceller", cancel2.get("lastCanceller"),
                        "jobCancelled", cancel2.get("jobCancelled")),
                "finalJobStatus", finalRun.get("status"),
                "finalResultRows", finalRun.get("result_rows"),
                "survivorDeliveredFullSet", deliverSurvivor.get("scopedRows"),
                "survivorDeliveredRows", deliverSurvivor.get("scopedRows"),
                "survivorRowsCorrect", ((Number) deliverSurvivor.get("scopedRows")).intValue() > 0,
                "lastCancel", Map.of("lastCanceller", cancel3.get("lastCanceller"),
                        "jobCancelled", cancel3.get("jobCancelled")),
                "payloadStatusAfterLastCancel", payloadAfterLastCancel == null
                        ? null : payloadAfterLastCancel.get("status")));

        // ---------- 3. 跨时段限额切换 + 运行中高峰策略 ----------
        // 先在白天策略下跑 2 个分区（白天配额 6000 行，2 个月各约 2000 行，可通过）
        Map<String, Object> keyJobBody = new LinkedHashMap<>(submitBody("tok-audit01", regId,
                wideParams, "DAY", "AUDIT", "PER_REQUEST", 120000));
        Map<String, Object> keySubmit = schedulingService.submit(keyJobBody);
        long keyJobId = ((Number) keySubmit.get("jobId")).longValue();
        Map<String, Object> keyRun1 = scheduler.run(keyJobId, "DAY", 2);
        int doneBeforePeak = jdbc.queryForObject(
                "SELECT COUNT(*) FROM query_checkpoint WHERE job_id = ? AND status = 'DONE'",
                Integer.class, keyJobId);
        // 高峰策略：运行中把白天配额收紧到 100 行。用最具体的 (purpose, role) 键，
        // 确保优先于种子里的 MONTHLY_AUDIT/* 兜底策略生效。
        Map<String, Object> peakPolicy = policyService.create("DAY", "MONTHLY_AUDIT", "AUDIT",
                Map.of("maxConcurrentTasks", 2, "maxTotalRows", 100, "resultRowCap", 200,
                        "statementTimeoutMs", 3000, "workMem", "8MB", "maxParallelWorkers", 1));
        policyService.activate(((Number) peakPolicy.get("id")).longValue());
        // 推进：分区开始前重解析策略，累计已扫描 > 100 行 → 不启动新分区，暂停并保留检查点
        Map<String, Object> keyRun2 = scheduler.run(keyJobId, "DAY", null);
        int doneAfterPeak = jdbc.queryForObject(
                "SELECT COUNT(*) FROM query_checkpoint WHERE job_id = ? AND status = 'DONE'",
                Integer.class, keyJobId);
        // 夜间更大配额：切到 NIGHT 继续跑完
        Map<String, Object> nightResume = scheduler.run(keyJobId, "NIGHT", null);
        int doneAfterNight = jdbc.queryForObject(
                "SELECT COUNT(*) FROM query_checkpoint WHERE job_id = ? AND status = 'DONE'",
                Integer.class, keyJobId);
        // 高峰策略退出，恢复白天目录（后续场景按正常白天配额运行）
        jdbc.update("UPDATE resource_policy SET status = 'RETIRED' WHERE id = ?", peakPolicy.get("id"));
        jdbc.update("UPDATE resource_policy SET status = 'ACTIVE' WHERE family = 'fee-scheduling'"
                + " AND window_name = 'DAY' AND purpose = 'MONTHLY_AUDIT' AND role_name = '*'"
                + " AND version = 1");
        report.put("scenarioC_windowLimitSwitch", mapOf(
                "jobId", keyJobId,
                "firstRunStatus", keyRun1.get("status"),
                "partitionsDoneBeforePeak", doneBeforePeak,
                "scannedBeforePeak", keyRun1.get("scanned_rows"),
                "peakPolicyId", peakPolicy.get("id"),
                "peakMaxTotalRows", 100,
                "afterPeakPolicyStatus", keyRun2.get("status"),
                "afterPeakReason", String.valueOf(keyRun2.get("paused_reason")),
                "partitionsDoneAfterPeakRun", doneAfterPeak,
                "peakDidNotStartNewPartition", doneAfterPeak == doneBeforePeak,
                "nightResumeStatus", nightResume.get("status"),
                "partitionsDoneAfterNight", doneAfterNight,
                "totalPartitions", ((Number) nightResume.get("total_partitions")).intValue(),
                "checkpointPreservedAcrossWindow", doneAfterPeak >= doneBeforePeak
                        && doneAfterNight > doneAfterPeak,
                "noTransactionKilled", "已开始分区完整提交，仅暂停未开始分区"));

        // ---------- 3b. 队列预算与等待：小机构查询在白天队列占满后保持 QUEUED ----------
        Map<String, Object> queueParamsA = new LinkedHashMap<>(Map.of(
                "monthFrom", "2025-11", "monthTo", "2025-11", "orgCode", "ORG003"));
        Map<String, Object> holder = schedulingService.submit(
                submitBody("tok-audit01", regId, queueParamsA, "DAY", "AUDIT", "PER_REQUEST", 120000));
        long holderJobId = ((Number) holder.get("jobId")).longValue();
        // maxPartitions=0：仅预算准入并占用一个 AUDIT 队列槽位
        Map<String, Object> holderAdmit = scheduler.run(holderJobId, "DAY", 0);
        // 白天 AUDIT 队列预算由 (DAY, MONTHLY_AUDIT, *) 决定（当前为 1）：槽位占满后其余任务排队等待
        Map<String, Object> second = schedulingService.submit(
                submitBody("tok-audit02", regId, new LinkedHashMap<>(wideParams), "DAY", "AUDIT",
                        "PER_REQUEST", 120000));
        long secondJobId = ((Number) second.get("jobId")).longValue();
        Map<String, Object> secondAdmit = scheduler.run(secondJobId, "DAY", 0);
        Map<String, Object> third = schedulingService.submit(
                submitBody("tok-audit01", regId, new LinkedHashMap<>(Map.of(
                        "monthFrom", "2025-12", "monthTo", "2025-12", "orgCode", "ORG003")),
                        "DAY", "AUDIT", "PER_REQUEST", 120000));
        long thirdJobId = ((Number) third.get("jobId")).longValue();
        Map<String, Object> thirdRun = scheduler.run(thirdJobId, "DAY", 0);
        List<Map<String, Object>> auditQueues = scheduler.queues().stream()
                .filter(q -> String.valueOf(q.get("queue")).endsWith(":AUDIT")).toList();
        report.put("scenarioC2_queueBudgetWaiting", mapOf(
                "queue", "DAY:MONTHLY_AUDIT:AUDIT",
                "concurrencyBudget", auditQueues.isEmpty() ? null : auditQueues.get(0).get("concurrencyBudget"),
                "firstAdmitted", holderAdmit.get("admitted"),
                "secondAdmitted", secondAdmit.get("admitted"),
                "secondWaitReason", String.valueOf(secondAdmit.get("reason")),
                "thirdAdmitted", thirdRun.get("admitted"),
                "thirdStatus", thirdRun.get("status"),
                "thirdWaitReason", String.valueOf(thirdRun.get("reason")),
                "waitingMs", thirdRun.get("waitingMs"),
                "budgetSaturated", Boolean.TRUE.equals(holderAdmit.get("admitted"))
                        && !Boolean.TRUE.equals(secondAdmit.get("admitted")),
                "auditQueueSnapshot", auditQueues));

        // ---------- 4. 大结果集：体积超上限 → 独立执行、裁剪载荷、回收 ----------
        Map<String, Object> bigParams = new LinkedHashMap<>(Map.of(
                "monthFrom", "2025-07", "monthTo", "2026-06"));
        Map<String, Object> bigSubmit = schedulingService.submit(
                submitBody("tok-budget01", regId, bigParams, "NIGHT", "BUDGET", null, 1500));
        // 第二个同指纹请求：重复率虽高，但结果体积超过载荷上限仍应独立执行（合并会把复制成本放大）
        Map<String, Object> bigSubmit2 = schedulingService.submit(
                submitBody("tok-budget01", regId, bigParams, "NIGHT", "BUDGET", null, 1500));
        long bigJobId = ((Number) bigSubmit.get("jobId")).longValue();
        Map<String, Object> bigRun = scheduler.run(bigJobId, "NIGHT", null);
        Map<String, Object> bigPayload = subscriptionService.payloadOf(bigJobId);
        long bigSub = subscriptionIdOf(bigSubmit);
        Map<String, Object> bigDeliver;
        String lifecycleCheck;
        try {
            bigDeliver = subscriptionService.deliver(bigSub);
            lifecycleCheck = "delivered";
        } catch (RuntimeException e) {
            bigDeliver = Map.of("error", e.getMessage());
            lifecycleCheck = "reclaimedBeforeDelivery";
        }
        // 让载荷保留期失效并回收
        jdbc.update("UPDATE query_subscription SET expires_at = now() - interval '1 second'"
                + " WHERE job_id = ?", bigJobId);
        Map<String, Object> reclaim = subscriptionService.reclaim();
        Map<String, Object> bigPayloadAfter = subscriptionService.payloadOf(bigJobId);
        report.put("scenarioD_largeResultSet", mapOf(
                "jobId", bigJobId,
                "decision", bigSubmit.get("decision"),
                "duplicateRequestDecision", bigSubmit2.get("decision"),
                "duplicateRequestModel", bigSubmit2.get("executionModel"),
                "duplicateRequestMode", bigSubmit2.get("mode"),
                "status", bigRun.get("status"),
                "resultRows", bigPayload == null ? null : bigPayload.get("row_count"),
                "payloadBytes", bigPayload == null ? null : bigPayload.get("bytes"),
                "truncated", bigRun.get("truncated"),
                "payloadStatusBeforeReclaim", bigPayload == null ? null : bigPayload.get("status"),
                "deliveryLifecycle", lifecycleCheck,
                "reclaimReclaimedJobs", reclaim.get("reclaimedJobs"),
                "reclaimReclaimedBytes", reclaim.get("reclaimedBytes"),
                "payloadStatusAfterReclaim", bigPayloadAfter == null ? null : bigPayloadAfter.get("status"),
                "reclaimReason", bigPayloadAfter == null ? null : bigPayloadAfter.get("reclaim_reason")));

        // ---------- 5. 治理视图 ----------
        report.put("governance", schedulingService.governanceReport(30));
        report.put("queues", scheduler.queues());
        report.put("summary", Map.of(
                "registrationId", regId,
                "snapshotId", snapshotId,
                "scenarios", List.of("scopeDistinctFingerprint", "mergeAndConcurrentCancel",
                        "windowLimitSwitch", "largeResultSet"),
                "queuesObserved", scheduler.queues().size()));
        return report;
    }

    private Map<String, Object> submitBody(String token, long regId, Map<String, Object> params,
                                           String window, String role, String model, long keepAliveMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("registrationId", regId);
        body.put("params", params);
        body.put("purpose", "MONTHLY_AUDIT");
        body.put("window", window);
        body.put("executionModel", model);
        body.put("keepAliveMs", keepAliveMs);
        return body;
    }

    @SuppressWarnings("unchecked")
    private long subscriptionIdOf(Map<String, Object> submitResponse) {
        Map<String, Object> sub = (Map<String, Object>) submitResponse.get("subscription");
        return ((Number) sub.get("id")).longValue();
    }

    /** 清空上一轮调度演示的任务类数据，保证演示可重复运行；策略目录与账号保留。 */
    private void resetDemoState() {
        jdbc.execute("TRUNCATE scheduling_event, query_subscription, result_payload,"
                + " query_checkpoint, schedule_job, merge_decision, query_fingerprint RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM resource_policy WHERE family = 'fee-scheduling' AND version > 1");
        jdbc.update("UPDATE resource_policy SET status = 'ACTIVE' WHERE family = 'fee-scheduling'");
        // 权限策略回到基线 v1：调度演示必须在确定的授权范围上运行（其他演示可能已收窄策略）
        jdbc.update("UPDATE permission_policy SET status = 'RETIRED'"
                + " WHERE family = 'fee-query' AND version <> 1");
        jdbc.update("UPDATE permission_policy SET status = 'ACTIVE'"
                + " WHERE family = 'fee-query' AND version = 1");
    }

    /** 可容纳任意数量键值的可变 Map（Map.of 上限 10 对）。 */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
